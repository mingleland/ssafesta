"""최초 GitHub 저장소 원본 덤프: kanghyunsoon/ssafesta 의 PR·이슈·댓글·리뷰·타임라인·첨부를 읽기 전용으로 보존한다.

사용법: python github_dump.py <owner/repo> <출력 디렉터리>
토큰은 gh 키링에서 읽고 저장하지 않는다. 본문 HTML(full+json)의 서명 URL 로 비공개 첨부 이미지 실물도 받는다.
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
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

REPO, OUT = sys.argv[1], os.path.abspath(sys.argv[2])
API = os.path.join(OUT, "api")
os.makedirs(API, exist_ok=True)
TOKEN = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True).stdout.strip()
LOCK = threading.Lock()
MANIFEST = open(os.path.join(OUT, "requests.jsonl"), "a", encoding="utf-8")
ERRORS = []
FULL = "application/vnd.github.full+json"


def now():
    return datetime.now(timezone.utc).isoformat()


def http(url, accept="application/vnd.github+json", method="GET", body=None, auth=True):
    if not url.startswith("http"):
        url = "https://api.github.com" + url
    last = (0, b"", {})
    for attempt in range(8):
        h = {"Accept": accept, "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "mingleland-history-dump"}
        if auth:
            h["Authorization"] = f"Bearer {TOKEN}"
        req = urllib.request.Request(url, headers=h, method=method, data=json.dumps(body).encode() if body else None)
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            payload = e.read()
            last = (e.code, payload, dict(e.headers))
            if e.code in (429, 500, 502, 503, 504) or (e.code == 403 and b"rate limit" in payload.lower()):
                time.sleep(min(float(e.headers.get("Retry-After") or 2 ** attempt), 120))
                continue
            return last
        except Exception as e:
            last = (0, repr(e).encode(), {})
            time.sleep(min(2 ** attempt, 60))
    return last


def save(rel, url, status, payload):
    if status != 200:
        rel = f"{rel}.http{status}"
        with LOCK:
            ERRORS.append({"url": url, "status": status})
    path = os.path.join(API, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(payload)
    with LOCK:
        MANIFEST.write(json.dumps({"t": now(), "url": url, "status": status, "file": "api/" + rel, "bytes": len(payload),
                                   "sha256": hashlib.sha256(payload).hexdigest()}, ensure_ascii=False) + "\n")
        MANIFEST.flush()


def fetch(rel, url, accept="application/vnd.github+json"):
    st, payload, _ = http(url, accept)
    save(rel, url, st, payload)
    return json.loads(payload) if st == 200 and payload else None


def paged(rel_dir, url, accept="application/vnd.github+json"):
    items, i = [], 1
    url = url + ("&" if "?" in url else "?") + "per_page=100"
    while url:
        st, payload, headers = http(url, accept)
        save(f"{rel_dir}/page-{i:04d}.json", url, st, payload)
        if st != 200:
            break
        data = json.loads(payload)
        items += data if isinstance(data, list) else [data]
        m = re.search(r'<([^>]+)>;\s*rel="next"', headers.get("Link") or headers.get("link") or "")
        url, i = (m.group(1) if m else None), i + 1
    return items


R = f"/repos/{REPO}"
THREADS_Q = """query($o:String!,$n:String!,$pr:Int!,$c:String){repository(owner:$o,name:$n){pullRequest(number:$pr){
 reviewThreads(first:50,after:$c){pageInfo{hasNextPage endCursor} nodes{id isResolved isOutdated isCollapsed path line originalLine
  startLine diffSide resolvedBy{login} comments(first:100){nodes{databaseId author{login} body createdAt url}}}}}}}"""


def dump_pr(n):
    d = f"pulls/{n}"
    fetch(f"{d}/pull.json", f"{R}/pulls/{n}", FULL)
    paged(f"{d}/commits", f"{R}/pulls/{n}/commits")
    paged(f"{d}/files", f"{R}/pulls/{n}/files")
    paged(f"{d}/reviews", f"{R}/pulls/{n}/reviews", FULL)
    paged(f"{d}/review-comments", f"{R}/pulls/{n}/comments", FULL)
    fetch(f"{d}/requested_reviewers.json", f"{R}/pulls/{n}/requested_reviewers")
    owner, name = REPO.split("/")
    cursor, i = None, 1
    while True:
        st, payload, _ = http("https://api.github.com/graphql", method="POST",
                              body={"query": THREADS_Q, "variables": {"o": owner, "n": name, "pr": n, "c": cursor}})
        save(f"{d}/review-threads-graphql/page-{i:04d}.json", "graphql reviewThreads", st, payload)
        page = (((json.loads(payload).get("data") or {}).get("repository") or {}).get("pullRequest") or {}).get("reviewThreads") if st == 200 else None
        if not page or not page["pageInfo"]["hasNextPage"]:
            break
        cursor, i = page["pageInfo"]["endCursor"], i + 1


def dump_issue(n):
    d = f"issues/{n}"
    fetch(f"{d}/issue.json", f"{R}/issues/{n}", FULL)
    paged(f"{d}/comments", f"{R}/issues/{n}/comments", FULL)
    paged(f"{d}/timeline", f"{R}/issues/{n}/timeline", FULL)
    paged(f"{d}/events", f"{R}/issues/{n}/events")
    paged(f"{d}/reactions", f"{R}/issues/{n}/reactions")


def download_attachments():
    """body_html 의 서명 URL 은 몇 분 뒤 만료되므로, 방금 받은 파일에서 뽑아 바로 받는다."""
    urls = {}
    for root, _, files in os.walk(API):
        for fn in files:
            if not fn.endswith(".json"):
                continue
            text = open(os.path.join(root, fn), "rb").read().decode("utf-8", "replace")
            for m in re.finditer(r'https://private-user-images\.githubusercontent\.com/[^"\s\\<>]+', text):
                u = m.group(0).replace("\\u0026", "&").replace("&amp;", "&")
                key = u.split("?")[0]
                urls.setdefault(key, u)
            for m in re.finditer(r'https://(?:github\.com/user-attachments/(?:assets|files)/[^"\s\\<>)]+|user-images\.githubusercontent\.com/[^"\s\\<>)]+|github\.com/[\w.-]+/[\w.-]+/(?:assets|files)/[^"\s\\<>)]+)', text):
                urls.setdefault(m.group(0), m.group(0))
    res = []
    for key, u in sorted(urls.items()):
        signed = "private-user-images" in u
        st, payload, headers = http(u, accept="*/*", auth=not signed)
        rec = {"source_url": key, "status": st, "content_type": headers.get("Content-Type")}
        if st == 200:
            name = re.sub(r"[^\w.\-]", "_", key.split("/")[-1])[:120]
            path = os.path.join(OUT, "attachments", hashlib.sha256(key.encode()).hexdigest()[:16] + "-" + name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            open(path, "wb").write(payload)
            rec.update(file=os.path.relpath(path, OUT).replace("\\", "/"), bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest())
        res.append(rec)
    json.dump(res, open(os.path.join(OUT, "attachments-manifest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return res


def main():
    started = now()
    fetch("repo.json", R)
    for rel, url in [("labels", f"{R}/labels"), ("milestones", f"{R}/milestones?state=all"), ("branches", f"{R}/branches"),
                     ("tags", f"{R}/tags"), ("releases", f"{R}/releases"), ("collaborators", f"{R}/collaborators"),
                     ("contributors", f"{R}/contributors?anon=1"), ("issue-comments-all", f"{R}/issues/comments?sort=created&direction=asc"),
                     ("issue-events-all", f"{R}/issues/events"), ("pull-review-comments-all", f"{R}/pulls/comments?sort=created&direction=asc"),
                     ("commit-comments-all", f"{R}/comments"), ("workflows", f"{R}/actions/workflows"), ("workflow-runs", f"{R}/actions/runs"),
                     ("deployments", f"{R}/deployments"), ("forks", f"{R}/forks")]:
        paged(f"repo/{rel}", url)
    fetch("repo/languages.json", f"{R}/languages")
    issues = paged("repo/issues-and-pulls", f"{R}/issues?state=all&sort=created&direction=asc", FULL)
    pulls = paged("repo/pulls", f"{R}/pulls?state=all&sort=created&direction=asc", FULL)
    with ThreadPoolExecutor(4) as ex:
        list(ex.map(dump_issue, [i["number"] for i in issues]))
        list(ex.map(dump_pr, [p["number"] for p in pulls]))
    atts = download_attachments()
    summary = {"started": started, "finished": now(), "repo": REPO, "issues_and_pulls": len(issues), "pulls": len(pulls),
               "attachments": len(atts), "attachments_ok": sum(1 for a in atts if a["status"] == 200), "http_errors": ERRORS}
    json.dump(summary, open(os.path.join(OUT, "dump-summary.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in summary.items() if k != "http_errors"}), "errors", ERRORS, flush=True)


if __name__ == "__main__":
    main()

