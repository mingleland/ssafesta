"""Jira ↔ 브랜치 ↔ 커밋 ↔ GitLab MR/이슈 ↔ 최초 GitHub PR 관계를 자동 추출한다 (Phase B).

근거는 커밋 메시지·브랜치 이름·MR 제목/본문/source branch·이슈 본문의 이슈 키, 그리고 Jira 개발 패널(dev-status)이다.
사용법: python analyze_jira_links.py <migration 루트> <출력 디렉터리>
"""
import collections
import glob
import json
import os
import re
import subprocess
import sys

ROOT, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
KEY = re.compile(r"S15P21A604-(\d+)", re.I)
CLOSES = re.compile(r"\b(?:closes?|closed|fix(?:es|ed)?|resolves?|resolved)\s*:?\s*S15P21A604-(\d+)", re.I)
GL = os.path.join(ROOT, "raw", "gitlab", "api-20260928", "api")
JI = os.path.join(ROOT, "raw", "jira", "dump-20260928", "api")
GH = os.path.join(ROOT, "raw", "github-original", "api-20260928", "api")
REPO = os.path.join(ROOT, "raw", "gitlab", "S15P21A604-complete.git")


def jl(p):
    return json.load(open(p, encoding="utf-8"))


def pages(d):
    out = []
    for f in sorted(glob.glob(os.path.join(d, "page-*.json"))):
        v = jl(f)
        out += v if isinstance(v, list) else [v]
    return out


def keys(text):
    return sorted({f"S15P21A604-{int(m)}" for m in KEY.findall(text or "")}, key=lambda k: int(k.split("-")[1]))


issues = {}
for d in sorted(glob.glob(os.path.join(JI, "issues", "*"))):
    i = jl(os.path.join(d, "issue.v3.json"))
    f = i["fields"]
    issues[i["key"]] = {"key": i["key"], "id": i["id"], "summary": f["summary"], "type": f["issuetype"]["name"],
                        "status": f["status"]["name"], "resolution": (f.get("resolution") or {}).get("name"),
                        "created": f["created"], "resolved": f.get("resolutiondate"),
                        "assignee": (f.get("assignee") or {}).get("displayName"), "reporter": (f.get("reporter") or {}).get("displayName"),
                        "parent": (f.get("parent") or {}).get("key"),
                        "commits": [], "closing_commits": [], "branches": set(), "gitlab_mrs": set(), "gitlab_issues": set(),
                        "github_original": set(), "devstatus": {"commits": set(), "branches": set(), "pull_requests": set()}}

# 커밋
raw = subprocess.run(["git", "-C", REPO, "log", "--all", "--format=%H%x1f%an%x1f%aI%x1f%B%x1e"], capture_output=True).stdout.decode("utf-8", "replace")
develop = set(subprocess.run(["git", "-C", REPO, "rev-list", "refs/heads/develop"], capture_output=True, text=True).stdout.split())
commit_keys = {}
for rec in raw.split("\x1e"):
    rec = rec.strip("\n")
    if not rec:
        continue
    sha, an, ad, msg = rec.split("\x1f", 3)
    ks = keys(msg)
    commit_keys[sha] = ks
    closing = {f"S15P21A604-{int(m)}" for m in CLOSES.findall(msg)}
    for k in ks:
        if k in issues:
            issues[k]["commits"].append({"sha": sha, "in_develop": sha in develop, "date": ad, "subject": msg.splitlines()[0][:200]})
            if k in closing:
                issues[k]["closing_commits"].append(sha)

# 브랜치 이름: 현재 + 이벤트 push 기록 + MR source branch
branch_names = set(b["name"] for b in pages(os.path.join(GL, "project", "branches")))
for e in pages(os.path.join(GL, "project", "events")):
    if (e.get("push_data") or {}).get("ref_type") == "branch":
        branch_names.add(e["push_data"]["ref"])
mrs = pages(os.path.join(GL, "project", "merge_requests"))
mr_keys = {}
for m in mrs:
    branch_names.add(m["source_branch"])
    ks = set(keys(m["title"]) + keys(m.get("description")) + keys(m["source_branch"]))
    for c in pages(os.path.join(GL, "merge_requests", str(m["iid"]), "commits")):
        ks |= set(commit_keys.get(c["id"], []))
    mr_keys[m["iid"]] = sorted(ks, key=lambda k: int(k.split("-")[1]))
    for k in ks:
        if k in issues:
            issues[k]["gitlab_mrs"].add(m["iid"])
for b in branch_names:
    for k in keys(b):
        if k in issues:
            issues[k]["branches"].add(b)
gl_issue_keys = {}
for i in pages(os.path.join(GL, "project", "issues")):
    ks = set(keys(i["title"]) + keys(i.get("description")))
    for n in pages(os.path.join(GL, "issues", str(i["iid"]), "notes")):
        ks |= set(keys(n.get("body")))
    gl_issue_keys[i["iid"]] = sorted(ks, key=lambda k: int(k.split("-")[1]))
    for k in ks:
        if k in issues:
            issues[k]["gitlab_issues"].add(i["iid"])
for it in pages(os.path.join(GH, "repo", "issues-and-pulls")):
    for k in keys((it.get("title") or "") + " " + (it.get("body") or "")):
        if k in issues:
            issues[k]["github_original"].add(it["number"])
# Jira 개발 패널
for k, v in issues.items():
    for f in glob.glob(os.path.join(JI, "issues", k, "devstatus", "detail-*.json")):
        for det in jl(f).get("detail", []):
            for r in det.get("repositories", []):
                v["devstatus"]["commits"] |= {c["id"] for c in r.get("commits", [])}
            v["devstatus"]["branches"] |= {b["name"] for b in det.get("branches", [])}
            for pr in det.get("pullRequests", []):
                m = re.search(r"!(\d+)$", pr.get("id", ""))
                if m:
                    v["devstatus"]["pull_requests"].add(int(m.group(1)))

def ser(v):
    v = dict(v)
    for f in ("branches", "gitlab_mrs", "gitlab_issues", "github_original"):
        v[f] = sorted(v[f])
    v["devstatus"] = {a: sorted(b) for a, b in v["devstatus"].items()}
    v["devstatus_mrs_not_found_by_text"] = sorted(set(v["devstatus"]["pull_requests"]) - set(v["gitlab_mrs"]))
    return v

out = [ser(v) for v in sorted(issues.values(), key=lambda x: int(x["key"].split("-")[1]))]
json.dump(out, open(os.path.join(OUT, "jira-links.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
json.dump({str(k): v for k, v in mr_keys.items()}, open(os.path.join(OUT, "gitlab-mr-jira-keys.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
json.dump({str(k): v for k, v in gl_issue_keys.items()}, open(os.path.join(OUT, "gitlab-issue-jira-keys.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
stats = {
    "jira_issues": len(out),
    "with_commit": sum(1 for v in out if v["commits"]), "with_develop_commit": sum(1 for v in out if any(c["in_develop"] for c in v["commits"])),
    "with_closing_commit": sum(1 for v in out if v["closing_commits"]), "with_branch": sum(1 for v in out if v["branches"]),
    "with_gitlab_mr": sum(1 for v in out if v["gitlab_mrs"]), "with_gitlab_issue": sum(1 for v in out if v["gitlab_issues"]),
    "with_devstatus_link": sum(1 for v in out if any(v["devstatus"].values())),
    "with_any_link": sum(1 for v in out if v["commits"] or v["gitlab_mrs"] or v["gitlab_issues"] or v["branches"] or any(v["devstatus"].values())),
    "without_any_link_by_type": dict(collections.Counter(v["type"] for v in out if not (v["commits"] or v["gitlab_mrs"] or v["gitlab_issues"] or v["branches"] or any(v["devstatus"].values())))),
    "gitlab_mrs_with_jira_key": sum(1 for v in mr_keys.values() if v), "gitlab_mrs": len(mr_keys),
    "devstatus_mrs_missed_by_text": sum(len(v["devstatus_mrs_not_found_by_text"]) for v in out),
    "unknown_keys_in_commits": sorted({k for ks in commit_keys.values() for k in ks if k not in issues}, key=lambda k: int(k.split("-")[1]))[:50],
}
json.dump(stats, open(os.path.join(OUT, "jira-links-summary.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(stats, ensure_ascii=False, indent=1))

