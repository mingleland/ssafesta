"""Jira Cloud 프로젝트 원본 덤프: 사이트 폐쇄 전에 S15P21A604 의 이슈·댓글·변경이력·첨부 바이너리를 가공 없이 보존한다.

사용법: python jira_dump.py <출력 디렉터리>
환경 변수: JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN (값은 출력·저장하지 않는다)
모든 HTTP 응답 본문은 받은 바이트 그대로 api/ 아래에 저장하고, 요청 목록은 requests.jsonl 에 남긴다.
"""
import base64
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

BASE = os.environ["JIRA_BASE_URL"].rstrip("/")
AUTH = "Basic " + base64.b64encode(f'{os.environ["JIRA_EMAIL"]}:{os.environ["JIRA_API_TOKEN"]}'.encode()).decode()
PROJECT = os.environ.get("JIRA_PROJECT", "S15P21A604")
OUT = os.path.abspath(sys.argv[1])
API = os.path.join(OUT, "api")
ATT = os.path.join(OUT, "attachments")
os.makedirs(API, exist_ok=True)
LOCK = threading.Lock()
MANIFEST = open(os.path.join(OUT, "requests.jsonl"), "a", encoding="utf-8")
ERRORS = []


def now():
    return datetime.now(timezone.utc).isoformat()


def http(url, method="GET", body=None, accept="application/json"):
    if not url.startswith("http"):
        url = BASE + url
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": AUTH, "Accept": accept}
    if data is not None:
        headers["Content-Type"] = "application/json"
    last = (0, b"", {})
    for attempt in range(10):
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            payload = e.read()
            last = (e.code, payload, dict(e.headers))
            if e.code in (429, 500, 502, 503, 504):
                ra = e.headers.get("Retry-After")
                time.sleep(min(float(ra) if ra else 2 ** attempt, 120))
                continue
            return last
        except Exception as e:  # 네트워크 일시 오류는 재시도한다
            last = (0, repr(e).encode(), {})
            time.sleep(min(2 ** attempt, 60))
    return last


def record(rec):
    with LOCK:
        MANIFEST.write(json.dumps(rec, ensure_ascii=False) + "\n")
        MANIFEST.flush()


def fetch(rel, url, method="GET", body=None):
    status, payload, _ = http(url, method, body)
    if status != 200:
        rel = f"{rel}.http{status}"
        with LOCK:
            ERRORS.append({"url": url, "status": status})
    path = os.path.join(API, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(payload)
    record({"t": now(), "method": method, "url": url if url.startswith("http") else BASE + url, "body": body,
            "status": status, "file": "api/" + rel, "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()})
    try:
        return status, (json.loads(payload) if payload and status == 200 else None)
    except ValueError:
        return status, None


def paged(rel_dir, path, key, size, extra=""):
    """startAt 기반 페이지네이션. 각 페이지를 page-NNNN.json 으로 저장하고 값 목록을 합쳐 돌려준다."""
    values, start, i = [], 0, 0
    sep = "&" if "?" in path else "?"
    while True:
        st, d = fetch(f"{rel_dir}/page-{i:04d}.json", f"{path}{sep}startAt={start}&maxResults={size}{extra}")
        if st != 200 or d is None:
            break
        page = d.get(key, []) if isinstance(d, dict) else d
        values += page
        i += 1
        total = d.get("total") if isinstance(d, dict) else None
        start += len(page)
        if not page or (isinstance(d, dict) and d.get("isLast") is True) or (total is not None and start >= total):
            break
        if not isinstance(d, dict):
            break
    return values


def safe(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name[:150] or "_"


def dump_issue(key):
    d = f"issues/{key}"
    st, issue = fetch(f"{d}/issue.v3.json", f"/rest/api/3/issue/{key}?fields=*all&expand=renderedFields,names,schema,transitions,editmeta,changelog&properties=*all")
    fetch(f"{d}/issue.v2.json", f"/rest/api/2/issue/{key}?fields=*all&expand=renderedFields")
    paged(f"{d}/changelog", f"/rest/api/3/issue/{key}/changelog", "values", 100)
    paged(f"{d}/comments", f"/rest/api/3/issue/{key}/comment", "comments", 100, "&expand=renderedBody,properties")
    paged(f"{d}/worklog", f"/rest/api/3/issue/{key}/worklog", "worklogs", 1000)
    fetch(f"{d}/remotelinks.json", f"/rest/api/3/issue/{key}/remotelink")
    fetch(f"{d}/watchers.json", f"/rest/api/3/issue/{key}/watchers")
    fetch(f"{d}/votes.json", f"/rest/api/3/issue/{key}/votes")
    st, props = fetch(f"{d}/properties.json", f"/rest/api/3/issue/{key}/properties")
    for p in (props or {}).get("keys", []):
        fetch(f"{d}/properties/{safe(p['key'])}.json", f"/rest/api/3/issue/{key}/properties/{urllib.parse.quote(p['key'], safe='')}")
    if issue:
        iid = issue["id"]
        st, summ = fetch(f"{d}/devstatus/summary.json", f"/rest/dev-status/latest/issue/summary?issueId={iid}")
        for data_type, info in ((summ or {}).get("summary") or {}).items():
            if not info or not (info.get("overall") or {}).get("count"):
                continue
            for inst in (info.get("byInstanceType") or {}):
                fetch(f"{d}/devstatus/detail-{safe(inst)}-{safe(data_type)}.json",
                      f"/rest/dev-status/latest/issue/detail?issueId={iid}&applicationType={urllib.parse.quote(inst)}&dataType={urllib.parse.quote(data_type)}")
    return issue


def download_attachment(key, att):
    folder = os.path.join(ATT, key, str(att["id"]))
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, safe(att["filename"]))
    status, payload, headers = http(f"/rest/api/3/attachment/content/{att['id']}?redirect=false", accept="*/*")
    ok = status == 200 and len(payload) == int(att.get("size", -1))
    if status == 200:
        with open(path, "wb") as f:
            f.write(payload)
    rec = {"t": now(), "issue": key, "id": att["id"], "filename": att["filename"], "mimeType": att.get("mimeType"),
           "size_meta": att.get("size"), "bytes": len(payload) if status == 200 else None, "status": status,
           "size_match": ok, "sha256": hashlib.sha256(payload).hexdigest() if status == 200 else None,
           "file": os.path.relpath(path, OUT).replace("\\", "/") if status == 200 else None}
    record({"kind": "attachment", **rec})
    return rec


def main():
    started = now()
    # 1) 사이트·프로젝트 메타데이터
    for rel, url in [
        ("serverInfo.json", "/rest/api/3/serverInfo"),
        ("myself.json", "/rest/api/3/myself?expand=groups,applicationRoles"),
        ("project.json", f"/rest/api/3/project/{PROJECT}?expand=description,lead,issueTypes,url,projectKeys,permissions,insight"),
        ("project-components.json", f"/rest/api/3/project/{PROJECT}/components"),
        ("project-versions.json", f"/rest/api/3/project/{PROJECT}/versions?expand=operations"),
        ("project-statuses.json", f"/rest/api/3/project/{PROJECT}/statuses"),
        ("project-properties.json", f"/rest/api/3/project/{PROJECT}/properties"),
        ("project-roles.json", f"/rest/api/3/project/{PROJECT}/role"),
        ("project-features.json", f"/rest/api/3/project/{PROJECT}/features"),
        ("fields.json", "/rest/api/3/field"),
        ("priorities.json", "/rest/api/3/priority"),
        ("resolutions.json", "/rest/api/3/resolution"),
        ("issueLinkTypes.json", "/rest/api/3/issueLinkType"),
        ("statuses.json", "/rest/api/3/status"),
        ("issuetypes.json", "/rest/api/3/issuetype"),
        ("mypermissions.json", f"/rest/api/3/mypermissions?projectKey={PROJECT}&permissions=BROWSE_PROJECTS,ADMINISTER_PROJECTS,ADMINISTER,SYSTEM_ADMIN"),
        ("assignable-users.json", f"/rest/api/3/user/assignable/search?project={PROJECT}&maxResults=1000"),
        ("workflowscheme.json", f"/rest/api/3/project/{PROJECT}/workflowscheme"),
    ]:
        st, d = fetch(f"project/{rel}", url)
        if rel == "project.json" and d:
            pid = d["id"]
            fetch("project/hierarchy.json", f"/rest/api/3/project/{pid}/hierarchy")
            fetch("project/issuetypes-project.json", f"/rest/api/3/issuetype/project?projectId={pid}")
        if rel == "project-roles.json" and d:
            for name, url2 in d.items():
                fetch(f"project/roles/{safe(name)}.json", url2)
    # 2) 보드·스프린트
    boards = paged("agile/boards", f"/rest/agile/1.0/board?projectKeyOrId={PROJECT}", "values", 50)
    for b in boards:
        bid = b["id"]
        fetch(f"agile/board-{bid}/board.json", f"/rest/agile/1.0/board/{bid}")
        fetch(f"agile/board-{bid}/configuration.json", f"/rest/agile/1.0/board/{bid}/configuration")
        paged(f"agile/board-{bid}/epics", f"/rest/agile/1.0/board/{bid}/epic", "values", 50)
        paged(f"agile/board-{bid}/versions", f"/rest/agile/1.0/board/{bid}/version", "values", 50)
        paged(f"agile/board-{bid}/issues", f"/rest/agile/1.0/board/{bid}/issue", "issues", 100, "&fields=key,sprint,closedSprints,status")
        sprints = paged(f"agile/board-{bid}/sprints", f"/rest/agile/1.0/board/{bid}/sprint", "values", 50)
        for s in sprints:
            sid = s["id"]
            fetch(f"agile/sprint-{sid}/sprint.json", f"/rest/agile/1.0/sprint/{sid}")
            paged(f"agile/sprint-{sid}/issues", f"/rest/agile/1.0/sprint/{sid}/issue", "issues", 100, "&fields=key,status,assignee")
            fetch(f"agile/sprint-{sid}/sprintreport-board-{bid}.json", f"/rest/greenhopper/1.0/rapid/charts/sprintreport?rapidViewId={bid}&sprintId={sid}")
    # 3) 이슈 목록 (enhanced search, nextPageToken)
    keys, token, i = [], None, 0
    while True:
        q = {"jql": f"project = {PROJECT} ORDER BY created ASC", "fields": "key,created", "maxResults": 5000}
        if token:
            q["nextPageToken"] = token
        st, d = fetch(f"search/page-{i:04d}.json", "/rest/api/3/search/jql?" + urllib.parse.urlencode(q))
        if st != 200:
            raise SystemExit(f"search failed: {st}")
        keys += [x["key"] for x in d.get("issues", [])]
        token, i = d.get("nextPageToken"), i + 1
        if not token or d.get("isLast"):
            break
    st, cnt = fetch("search/approximate-count.json", "/rest/api/3/search/approximate-count", "POST", {"jql": f"project = {PROJECT}"})
    print(f"issues listed: {len(keys)} approximate-count: {(cnt or {}).get('count')}", flush=True)
    # 4) 이슈 상세
    issues = {}
    with ThreadPoolExecutor(6) as ex:
        for n, (k, issue) in enumerate(zip(keys, ex.map(dump_issue, keys)), 1):
            issues[k] = issue
            if n % 50 == 0:
                print(f"issues dumped {n}/{len(keys)}", flush=True)
    # 5) 첨부 바이너리
    atts = [(k, a) for k, iss in issues.items() if iss for a in (iss.get("fields", {}).get("attachment") or [])]
    print(f"attachments: {len(atts)}", flush=True)
    with ThreadPoolExecutor(4) as ex:
        att_results = list(ex.map(lambda ka: download_attachment(*ka), atts))
    # 6) 등장한 사용자 프로필
    ids = set()
    for root, _, files in os.walk(os.path.join(API, "issues")):
        for fn in files:
            if fn.endswith(".json"):
                with open(os.path.join(root, fn), "rb") as f:
                    ids.update(re.findall(rb'"accountId"\s*:\s*"([^"]+)"', f.read()))
    for acc in sorted(x.decode() for x in ids):
        fetch(f"users/{safe(acc)}.json", f"/rest/api/3/user?accountId={urllib.parse.quote(acc)}&expand=groups,applicationRoles")
    summary = {"started": started, "finished": now(), "project": PROJECT, "issues_listed": len(keys),
               "approximate_count": (cnt or {}).get("count"), "issues_fetched": sum(1 for v in issues.values() if v),
               "attachments": len(att_results), "attachments_ok": sum(1 for r in att_results if r["size_match"]),
               "attachment_bytes": sum(r["bytes"] or 0 for r in att_results), "users": len(ids), "http_errors": ERRORS}
    with open(os.path.join(OUT, "dump-summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in summary.items() if k != "http_errors"}, ensure_ascii=False), "errors:", len(ERRORS), flush=True)


if __name__ == "__main__":
    main()

