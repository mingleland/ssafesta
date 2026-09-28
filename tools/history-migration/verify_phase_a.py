"""Phase A 검증: 원본 덤프의 수량을 원본 서버가 알려준 수량(X-Total·total·필드 카운터)과 대조하고 결과를 JSON 으로 남긴다.

사용법: python verify_phase_a.py <migration 루트> <출력 json>
"""
import collections
import glob
import json
import os
import re
import subprocess
import sys

ROOT, OUT = sys.argv[1], sys.argv[2]
GL = os.path.join(ROOT, "raw", "gitlab", "api-20260928")
JI = os.path.join(ROOT, "raw", "jira", "dump-20260928")
GH = os.path.join(ROOT, "raw", "github-original", "api-20260928")
res = {}


def jl(path):
    return json.load(open(path, encoding="utf-8"))


def pages(d):
    items = []
    for f in sorted(glob.glob(os.path.join(d, "page-*.json"))):
        v = jl(f)
        items += v if isinstance(v, list) else [v]
    return items


def manifests(root, pattern):
    recs = []
    for f in glob.glob(os.path.join(root, pattern)):
        recs += [json.loads(l) for l in open(f, encoding="utf-8")]
    return recs


# ---------- GitLab: 모든 페이지 목록을 X-Total 과 대조 ----------
gl_recs = manifests(GL, "requests-*.jsonl")
gl_status = collections.Counter(r["status"] for r in gl_recs)
first_pages = {r["file"][:-len("/page-0001.json")]: r for r in gl_recs if r["file"].endswith("/page-0001.json")}
mismatch = []
checked = 0
for d, rec in first_pages.items():
    total = rec["headers"].get("X-Total") or rec["headers"].get("x-total")
    if total is None:
        continue
    n = len(pages(os.path.join(GL, d)))
    checked += 1
    if n != int(total):
        mismatch.append({"dir": d, "x_total": int(total), "dumped": n})
mrs = pages(os.path.join(GL, "api", "project", "merge_requests"))
iss = pages(os.path.join(GL, "api", "project", "issues"))
proj = jl(os.path.join(GL, "api", "project", "project.json"))


def notes_of(kind, iid):
    return pages(os.path.join(GL, "api", kind, str(iid), "notes"))


mr_notes = {m["iid"]: notes_of("merge_requests", m["iid"]) for m in mrs}
is_notes = {i["iid"]: notes_of("issues", i["iid"]) for i in iss}
user_notes_mismatch = [("MR", m["iid"], m["user_notes_count"], sum(1 for n in mr_notes[m["iid"]] if not n["system"])) for m in mrs
                       if m["user_notes_count"] != sum(1 for n in mr_notes[m["iid"]] if not n["system"])]
user_notes_mismatch += [("Issue", i["iid"], i["user_notes_count"], sum(1 for n in is_notes[i["iid"]] if not n["system"])) for i in iss
                        if i["user_notes_count"] != sum(1 for n in is_notes[i["iid"]] if not n["system"])]
mr_disc = sum(len(pages(os.path.join(GL, "api", "merge_requests", str(m["iid"]), "discussions"))) for m in mrs)
is_disc = sum(len(pages(os.path.join(GL, "api", "issues", str(i["iid"]), "discussions"))) for i in iss)
diff_notes = sum(1 for v in mr_notes.values() for n in v if n.get("type") == "DiffNote")
mr_detail_ok = sum(1 for m in mrs if os.path.exists(os.path.join(GL, "api", "merge_requests", str(m["iid"]), "merge_request.json")))
versions = sum(len(pages(os.path.join(GL, "api", "merge_requests", str(m["iid"]), "versions"))) for m in mrs)
version_files = len(glob.glob(os.path.join(GL, "api", "merge_requests", "*", "versions", "[0-9]*.json")))
up = jl(os.path.join(GL, "uploads-manifest.json"))
jt = jl(os.path.join(GL, "job-traces-manifest.json"))
pk = jl(os.path.join(GL, "packages-manifest.json")) if os.path.exists(os.path.join(GL, "packages-manifest.json")) else []
cn = jl(os.path.join(GL, "commit-notes-summary.json"))
mc = jl(os.path.join(ROOT, "logs", "missing-commits.json"))
repo = os.path.join(ROOT, "raw", "gitlab", "S15P21A604-complete.git")
still = subprocess.run(["git", "-C", repo, "cat-file", "--batch-check"], input="\n".join(mc["missing"]).encode(), capture_output=True).stdout.decode()
ev_missing = jl(os.path.join(ROOT, "logs", "event-missing-commits-after.json"))["missing"]
mirror = os.path.join(ROOT, "raw", "gitlab", "S15P21A604.git")
heads = subprocess.run(["git", "-C", mirror, "for-each-ref", "--format=%(refname:strip=2) %(objectname)", "refs/heads"], capture_output=True, text=True).stdout.split("\n")
heads = dict(l.split(" ") for l in heads if l)
api_branches = {b["name"]: b["commit"]["id"] for b in pages(os.path.join(GL, "api", "project", "branches"))}
res["gitlab"] = {
    "project_statistics": proj["statistics"], "http_status_counts": dict(gl_status),
    "paged_endpoints_checked_against_x_total": checked, "x_total_mismatches": mismatch,
    "merge_requests": {"x_total": 1363, "listed": len(mrs), "detail_dumped": mr_detail_ok,
                       "states": dict(collections.Counter(m["state"] for m in mrs)),
                       "notes": sum(len(v) for v in mr_notes.values()), "user_notes": sum(1 for v in mr_notes.values() for n in v if not n["system"]),
                       "system_notes": sum(1 for v in mr_notes.values() for n in v if n["system"]), "diff_notes": diff_notes,
                       "discussions": mr_disc, "diff_versions": versions, "diff_version_details_dumped": version_files},
    "issues": {"x_total": 264, "listed": len(iss), "states": dict(collections.Counter(i["state"] for i in iss)),
               "notes": sum(len(v) for v in is_notes.values()), "user_notes": sum(1 for v in is_notes.values() for n in v if not n["system"]),
               "discussions": is_disc},
    "user_notes_count_mismatches": user_notes_mismatch,
    "events": len(pages(os.path.join(GL, "api", "project", "events"))),
    "labels": len(pages(os.path.join(GL, "api", "project", "labels"))), "milestones": len(pages(os.path.join(GL, "api", "project", "milestones"))),
    "releases": len(pages(os.path.join(GL, "api", "project", "releases"))), "members_all": len(pages(os.path.join(GL, "api", "project", "members-all"))),
    "branches": {"api": len(api_branches), "mirror": len(heads), "sha_mismatch": [b for b in api_branches if heads.get(b) != api_branches[b]]},
    "uploads": {"listed": len(up["by_id"]), "downloaded_size_match": sum(1 for u in up["by_id"] if u.get("size_match") or u.get("status") == "cached"),
                "bytes": sum(u.get("bytes") or 0 for u in up["by_id"]), "markdown_references": len(up["by_reference"]),
                "references_resolved_to_upload": sum(1 for u in up["by_reference"] if u.get("upload_id"))},
    "pipelines": len(pages(os.path.join(GL, "api", "project", "pipelines"))), "jobs": len(jt),
    "job_traces_ok": sum(1 for j in jt if j["status"] in (200, "cached")),
    "packages": {"files": len(pk), "ok": sum(1 for p in pk if p.get("status") in (200, "cached") and p.get("sha256_match", True)),
                 "bytes": sum(p.get("bytes") or 0 for p in pk)},
    "commit_notes": {"commits_checked": cn["commits_checked"], "commits_with_discussions": len(cn["commits_with_discussions"])},
    "mr_referenced_commits": {"total": mc["total"], "unadvertised_recovered": len(mc["missing"]) - still.count("missing"),
                              "still_missing": [l.split()[0] for l in still.splitlines() if l.endswith("missing")]},
    "push_event_commits_unrecoverable": ev_missing,
}
# ---------- Jira ----------
ji_recs = manifests(JI, "requests.jsonl")
keys = [x["key"] for f in sorted(glob.glob(os.path.join(JI, "api", "search", "page-*.json"))) for x in jl(f)["issues"]]
cm_total = cm_dump = ch_total = ch_dump = wl_total = wl_dump = 0
att_meta = []
for k in keys:
    d = os.path.join(JI, "api", "issues", k)
    c0 = jl(os.path.join(d, "comments", "page-0000.json"))
    cm_total += c0["total"]
    cm_dump += len(pages_j := [c for f in sorted(glob.glob(os.path.join(d, "comments", "page-*.json"))) for c in jl(f)["comments"]])
    h0 = jl(os.path.join(d, "changelog", "page-0000.json"))
    ch_total += h0["total"]
    ch_dump += sum(len(jl(f)["values"]) for f in glob.glob(os.path.join(d, "changelog", "page-*.json")))
    w0 = jl(os.path.join(d, "worklog", "page-0000.json"))
    wl_total += w0["total"]
    wl_dump += sum(len(jl(f)["worklogs"]) for f in glob.glob(os.path.join(d, "worklog", "page-*.json")))
    att_meta += jl(os.path.join(d, "issue.v3.json"))["fields"].get("attachment") or []
att_rec = [r for r in ji_recs if r.get("kind") == "attachment"]
res["jira"] = {
    "approximate_count": jl(os.path.join(JI, "api", "search", "approximate-count.json"))["count"], "issues_listed": len(keys),
    "issues_dumped": sum(1 for k in keys if os.path.exists(os.path.join(JI, "api", "issues", k, "issue.v3.json"))),
    "comments": {"server_total": cm_total, "dumped": cm_dump}, "changelog_histories": {"server_total": ch_total, "dumped": ch_dump},
    "worklogs": {"server_total": wl_total, "dumped": wl_dump},
    "attachments": {"metadata": len(att_meta), "metadata_bytes": sum(a["size"] for a in att_meta), "downloaded_size_match": sum(1 for r in att_rec if r["size_match"]),
                    "downloaded_bytes": sum(r["bytes"] or 0 for r in att_rec)},
    "http_status_counts": dict(collections.Counter(r["status"] for r in ji_recs if "kind" not in r)),
    "http_errors": [r["file"] for r in ji_recs if "kind" not in r and r["status"] != 200],
    "boards": len(pages(os.path.join(JI, "api", "agile", "boards")) and jl(os.path.join(JI, "api", "agile", "boards", "page-0000.json"))["values"]),
    "sprints": len(glob.glob(os.path.join(JI, "api", "agile", "sprint-*"))), "users": len(glob.glob(os.path.join(JI, "api", "users", "*.json"))),
    "issue_types": dict(collections.Counter(jl(os.path.join(JI, "api", "issues", k, "issue.v3.json"))["fields"]["issuetype"]["name"] for k in keys)),
}
# ---------- GitHub 원본 ----------
gh_items = pages(os.path.join(GH, "api", "repo", "issues-and-pulls"))
prs = {p["number"]: jl(os.path.join(GH, "api", "pulls", str(p["number"]), "pull.json")) for p in pages(os.path.join(GH, "api", "repo", "pulls"))}
com_exp = sum(i["comments"] for i in gh_items)
com_got = sum(len(pages(os.path.join(GH, "api", "issues", str(i["number"]), "comments"))) for i in gh_items)
rc_exp = sum(p["review_comments"] for p in prs.values())
rc_got = sum(len(pages(os.path.join(GH, "api", "pulls", str(n), "review-comments"))) for n in prs)
cm_exp = sum(p["commits"] for p in prs.values())
cm_got = sum(len(pages(os.path.join(GH, "api", "pulls", str(n), "commits"))) for n in prs)
gh_recs = manifests(GH, "requests.jsonl")
res["github_original"] = {
    "items": len(gh_items), "pulls": len(prs), "issues": len(gh_items) - len(prs),
    "pull_states": dict(collections.Counter("merged" if p["merged_at"] else p["state"] for p in prs.values())),
    "issue_states": dict(collections.Counter(i["state"] for i in gh_items if "pull_request" not in i)),
    "issue_comments": {"expected": com_exp, "dumped": com_got}, "review_comments": {"expected": rc_exp, "dumped": rc_got},
    "reviews": sum(len(pages(os.path.join(GH, "api", "pulls", str(n), "reviews"))) for n in prs),
    "pr_commits": {"expected": cm_exp, "dumped": cm_got},
    "labels": len(pages(os.path.join(GH, "api", "repo", "labels"))), "http_status_counts": dict(collections.Counter(r["status"] for r in gh_recs)),
}
json.dump(res, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(res, ensure_ascii=False, indent=1)[:12000])

