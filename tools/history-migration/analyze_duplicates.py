"""최초 GitHub PR·이슈와 GitLab MR·이슈의 중복(이전 import 여부)을 제목·SHA·브랜치·본문·시각으로 대조한다 (Phase B)."""
import glob
import json
import os
import re
import sys

gh_api, gl_api, out = sys.argv[1], sys.argv[2], sys.argv[3]


def load_pages(pattern):
    items = []
    for f in sorted(glob.glob(pattern)):
        items += json.load(open(f, encoding="utf-8"))
    return items


def norm(t):
    return re.sub(r"\s+", " ", re.sub(r"[\[\]()#:!\x60'\"·—\-_/]", " ", (t or "").lower())).strip()


gh_items = load_pages(os.path.join(gh_api, "repo", "issues-and-pulls", "page-*.json"))
gh_pulls = {p["number"]: p for p in load_pages(os.path.join(gh_api, "repo", "pulls", "page-*.json"))}
gl_mrs = load_pages(os.path.join(gl_api, "project", "merge_requests", "page-*.json"))
gl_issues = load_pages(os.path.join(gl_api, "project", "issues", "page-*.json"))
gl_text = {}
for f in glob.glob(os.path.join(gl_api, "**", "*.json"), recursive=True):
    t = open(f, "rb").read().decode("utf-8", "replace")
    for m in re.finditer(r"github\.com/kanghyunsoon/ssafesta/(pull|issues)/(\d+)", t):
        gl_text.setdefault(f"{m.group(1)}/{m.group(2)}", set()).add(os.path.relpath(f, gl_api))

res = {"pulls": [], "issues": []}
for it in gh_items:
    n, is_pr = it["number"], "pull_request" in it
    p = gh_pulls.get(n, {})
    cands = gl_mrs if is_pr else gl_issues
    title = norm(it["title"])
    hits = []
    for c in cands:
        reasons = []
        if norm(c["title"]) == title:
            reasons.append("same_title")
        if is_pr:
            if p.get("head", {}).get("sha") and p["head"]["sha"] in (c.get("sha"), c.get("merge_commit_sha"), c.get("squash_commit_sha")):
                reasons.append("same_head_sha")
            if p.get("merge_commit_sha") and p["merge_commit_sha"] in (c.get("sha"), c.get("merge_commit_sha"), c.get("squash_commit_sha")):
                reasons.append("same_merge_sha")
            if p.get("head", {}).get("ref") and p["head"]["ref"] == c.get("source_branch"):
                reasons.append("same_source_branch")
        if (it.get("body") or "").strip() and (it.get("body") or "").strip()[:200] == (c.get("description") or "").strip()[:200]:
            reasons.append("same_body_prefix")
        if reasons:
            hits.append({"iid": c["iid"], "title": c["title"], "created_at": c["created_at"], "reasons": reasons})
    key = f"{'pull' if is_pr else 'issues'}/{n}"
    res["pulls" if is_pr else "issues"].append({
        "number": n, "title": it["title"], "state": it["state"], "created_at": it["created_at"], "closed_at": it.get("closed_at"),
        "merged_at": p.get("merged_at"), "user": it["user"]["login"], "head_ref": p.get("head", {}).get("ref"),
        "base_ref": p.get("base", {}).get("ref"), "head_sha": p.get("head", {}).get("sha"), "merge_commit_sha": p.get("merge_commit_sha"),
        "gitlab_candidates": hits, "gitlab_mentions_of_github_url": sorted(gl_text.get(key, []))[:10]})
res["summary"] = {
    "github_pulls": len(res["pulls"]), "github_issues": len(res["issues"]),
    "pulls_with_gitlab_candidate": sum(1 for x in res["pulls"] if x["gitlab_candidates"]),
    "issues_with_gitlab_candidate": sum(1 for x in res["issues"] if x["gitlab_candidates"]),
    "first_gitlab_mr": min(m["created_at"] for m in gl_mrs), "first_gitlab_issue": min(i["created_at"] for i in gl_issues),
    "last_github_item": max(i["created_at"] for i in gh_items)}
json.dump(res, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(res["summary"], ensure_ascii=False))
for x in res["pulls"] + res["issues"]:
    if x["gitlab_candidates"] or x["gitlab_mentions_of_github_url"]:
        print(x["number"], x["title"][:50], "->", [(c["iid"], c["reasons"]) for c in x["gitlab_candidates"]][:3], len(x["gitlab_mentions_of_github_url"]))

