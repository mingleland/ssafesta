"""GitLab 프로젝트 원본 덤프: lab.ssafy.com 폐쇄 전에 S15P21A604 의 MR·이슈·토론·이벤트·파이프라인 메타데이터를 가공 없이 보존한다.

사용법: python gitlab_dump.py <출력 디렉터리> [phase ...]
  phase: meta issues mrs pipelines (기본: 전부)
토큰은 glab 키링에서 읽고 출력·저장하지 않는다. 응답 본문은 받은 바이트 그대로 api/ 아래에 저장하고
요청 목록(URL·상태·크기·sha256)은 requests.jsonl 에 남긴다. CI 변수 값만 예외로 저장하지 않는다.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

HOST = "https://lab.ssafy.com"
BASE = HOST + "/api/v4"
PID = int(os.environ.get("GITLAB_PROJECT_ID", "1443023"))
GID = int(os.environ.get("GITLAB_GROUP_ID", "1464148"))
OUT = os.path.abspath(sys.argv[1])
PHASES = sys.argv[2:] or ["meta", "issues", "mrs", "pipelines"]
API = os.path.join(OUT, "api")
os.makedirs(API, exist_ok=True)
LOCK = threading.Lock()
MANIFEST = open(os.path.join(OUT, f"requests-{'-'.join(PHASES)}.jsonl"), "a", encoding="utf-8")
ERRORS = []


def gitlab_token():
    out = subprocess.run(["glab", "auth", "status", "-t", "--hostname", "lab.ssafy.com"], capture_output=True,
                         text=True, encoding="utf-8", errors="replace")
    m = re.search(r"Token found[^:]*:\s*(\S+)", out.stdout + out.stderr)
    if not m:
        raise SystemExit("glab token not found")
    return m.group(1)


TOKEN = gitlab_token()


def now():
    return datetime.now(timezone.utc).isoformat()


def http(url):
    if not url.startswith("http"):
        url = BASE + url
    last = (0, b"", {})
    for attempt in range(10):
        req = urllib.request.Request(url, headers={"PRIVATE-TOKEN": TOKEN, "Accept": "application/json"})
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


def save(rel, url, status, payload, headers):
    if status != 200:
        rel = f"{rel}.http{status}"
        with LOCK:
            ERRORS.append({"url": url, "status": status})
    path = os.path.join(API, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(payload)
    keep = {k: v for k, v in headers.items() if k.lower().startswith("x-") and "token" not in k.lower()}
    rec = {"t": now(), "url": url if url.startswith("http") else BASE + url, "status": status, "file": "api/" + rel,
           "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest(), "headers": keep}
    with LOCK:
        MANIFEST.write(json.dumps(rec, ensure_ascii=False) + "\n")
        MANIFEST.flush()


def fetch(rel, url):
    status, payload, headers = http(url)
    save(rel, url, status, payload, headers)
    try:
        return json.loads(payload) if status == 200 and payload else None
    except ValueError:
        return None


def paged(rel_dir, path, per_page=100):
    """offset 페이지네이션. X-Next-Page 가 빌 때까지 page-NNNN.json 으로 저장하고 목록을 합쳐 돌려준다."""
    items, page = [], 1
    sep = "&" if "?" in path else "?"
    while True:
        url = f"{path}{sep}per_page={per_page}&page={page}"
        status, payload, headers = http(url)
        save(f"{rel_dir}/page-{page:04d}.json", url, status, payload, headers)
        if status != 200:
            break
        data = json.loads(payload) if payload else []
        if not isinstance(data, list):
            break
        items += data
        nxt = headers.get("X-Next-Page") or headers.get("x-next-page")
        if not nxt or not data:
            break
        page = int(nxt)
    return items


P = f"/projects/{PID}"


def phase_meta():
    fetch("project/project.json", f"{P}?statistics=true&license=true&with_custom_attributes=true")
    fetch("group/group.json", f"/groups/{GID}?with_projects=false")
    paged("group/members-all", f"/groups/{GID}/members/all")
    paged("group/labels", f"/groups/{GID}/labels?with_counts=true")
    paged("group/milestones", f"/groups/{GID}/milestones")
    for rel, path in [
        ("members", f"{P}/members"), ("members-all", f"{P}/members/all"),
        ("labels", f"{P}/labels?with_counts=true&include_ancestor_groups=true"),
        ("milestones", f"{P}/milestones?include_ancestors=true"), ("releases", f"{P}/releases"),
        ("tags", f"{P}/repository/tags"), ("branches", f"{P}/repository/branches"),
        ("protected_branches", f"{P}/protected_branches"), ("protected_tags", f"{P}/protected_tags"),
        ("contributors", f"{P}/repository/contributors?order_by=commits&sort=desc"),
        ("hooks", f"{P}/hooks"), ("badges", f"{P}/badges"), ("boards", f"{P}/boards"),
        ("pipeline_schedules", f"{P}/pipeline_schedules"), ("environments", f"{P}/environments"),
        ("deployments", f"{P}/deployments"), ("remote_mirrors", f"{P}/remote_mirrors"),
        ("access_requests", f"{P}/access_requests"), ("invitations", f"{P}/invitations"),
        ("uploads", f"{P}/uploads"), ("snippets", f"{P}/snippets"), ("wikis", f"{P}/wikis?with_content=1"),
        ("packages", f"{P}/packages"), ("deploy_keys", f"{P}/deploy_keys"), ("forks", f"{P}/forks"),
        ("events", f"{P}/events?sort=asc"),
    ]:
        items = paged(f"project/{rel}", path)
        if rel == "boards":
            for b in items:
                paged(f"project/boards/{b['id']}/lists", f"{P}/boards/{b['id']}/lists")
        if rel == "packages":
            for pk in items:
                fetch(f"project/packages/{pk['id']}/package.json", f"{P}/packages/{pk['id']}")
                paged(f"project/packages/{pk['id']}/package_files", f"{P}/packages/{pk['id']}/package_files")
    fetch("project/languages.json", f"{P}/languages")
    fetch("project/push_rule.json", f"{P}/push_rule")
    # CI 변수는 값이 비밀이라 키·속성만 남긴다 (의도적 예외)
    status, payload, headers = http(f"{P}/variables?per_page=100")
    if status == 200:
        redacted = [{k: v for k, v in x.items() if k != "value"} for x in json.loads(payload)]
        payload = json.dumps(redacted, ensure_ascii=False, indent=1).encode()
    save("project/variables.values-removed.json", f"{P}/variables", status, payload, {})


def dump_issue(iid):
    d, b = f"issues/{iid}", f"{P}/issues/{iid}"
    fetch(f"{d}/issue.json", b)
    paged(f"{d}/notes", f"{b}/notes?sort=asc&order_by=created_at")
    paged(f"{d}/discussions", f"{b}/discussions")
    for ev in ("resource_label_events", "resource_milestone_events", "resource_state_events"):
        paged(f"{d}/{ev}", f"{b}/{ev}")
    paged(f"{d}/award_emoji", f"{b}/award_emoji")
    for sub in ("links", "related_merge_requests", "closed_by", "participants", "time_stats"):
        fetch(f"{d}/{sub}.json", f"{b}/{sub}")


def dump_mr(iid):
    d, b = f"merge_requests/{iid}", f"{P}/merge_requests/{iid}"
    fetch(f"{d}/merge_request.json", f"{b}?include_diverged_commits_count=true&include_rebase_in_progress=true")
    paged(f"{d}/notes", f"{b}/notes?sort=asc&order_by=created_at")
    paged(f"{d}/discussions", f"{b}/discussions")
    paged(f"{d}/commits", f"{b}/commits")
    for ev in ("resource_label_events", "resource_milestone_events", "resource_state_events"):
        paged(f"{d}/{ev}", f"{b}/{ev}")
    paged(f"{d}/award_emoji", f"{b}/award_emoji")
    paged(f"{d}/closes_issues", f"{b}/closes_issues")
    paged(f"{d}/related_issues", f"{b}/related_issues")
    paged(f"{d}/pipelines", f"{b}/pipelines")
    for sub in ("approvals", "reviewers", "participants", "time_stats"):
        fetch(f"{d}/{sub}.json", f"{b}/{sub}")
    for v in paged(f"{d}/versions", f"{b}/versions"):
        fetch(f"{d}/versions/{v['id']}.json", f"{b}/versions/{v['id']}")


def phase_issues():
    issues = paged("project/issues", f"{P}/issues?scope=all&state=all&order_by=created_at&sort=asc&with_labels_details=true")
    print(f"issues: {len(issues)}", flush=True)
    with ThreadPoolExecutor(8) as ex:
        for n, _ in enumerate(ex.map(dump_issue, [i["iid"] for i in issues]), 1):
            if n % 50 == 0:
                print(f"issues {n}/{len(issues)}", flush=True)


def phase_mrs():
    mrs = paged("project/merge_requests", f"{P}/merge_requests?scope=all&state=all&order_by=created_at&sort=asc&with_labels_details=true")
    print(f"merge requests: {len(mrs)}", flush=True)
    with ThreadPoolExecutor(8) as ex:
        for n, _ in enumerate(ex.map(dump_mr, [m["iid"] for m in mrs]), 1):
            if n % 100 == 0:
                print(f"mrs {n}/{len(mrs)}", flush=True)


def dump_pipeline(pid_):
    d, b = f"pipelines/{pid_}", f"{P}/pipelines/{pid_}"
    fetch(f"{d}/pipeline.json", b)
    paged(f"{d}/jobs", f"{b}/jobs?include_retried=true")
    paged(f"{d}/bridges", f"{b}/bridges")
    fetch(f"{d}/test_report_summary.json", f"{b}/test_report_summary")


def phase_pipelines():
    pipes = paged("project/pipelines", f"{P}/pipelines?order_by=id&sort=asc")
    print(f"pipelines: {len(pipes)}", flush=True)
    with ThreadPoolExecutor(8) as ex:
        for n, _ in enumerate(ex.map(dump_pipeline, [p["id"] for p in pipes]), 1):
            if n % 200 == 0:
                print(f"pipelines {n}/{len(pipes)}", flush=True)


def main():
    started = now()
    for ph in PHASES:
        print(f"phase {ph} start {now()}", flush=True)
        globals()[f"phase_{ph}"]()
        print(f"phase {ph} done {now()}", flush=True)
    summary = {"started": started, "finished": now(), "phases": PHASES, "http_errors": ERRORS}
    with open(os.path.join(OUT, f"dump-summary-{'-'.join(PHASES)}.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    print("errors:", len(ERRORS), flush=True)


if __name__ == "__main__":
    main()

