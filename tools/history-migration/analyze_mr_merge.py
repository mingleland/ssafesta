"""GitLab MR 별로 병합 방식(merge commit/squash/ff)과 head 가 실제 병합 커밋의 조상인지 판정한다 — 새 PR 을 실제 커밋만으로 Merged 표시할 수 있는지 가르는 근거."""
import collections
import glob
import json
import os
import subprocess
import sys

api, repo, out = sys.argv[1], sys.argv[2], sys.argv[3]
rows = []
for f in glob.glob(os.path.join(api, "merge_requests", "*", "merge_request.json")):
    m = json.load(open(f, encoding="utf-8"))
    dr = m.get("diff_refs") or {}
    head, base = dr.get("head_sha") or m.get("sha"), dr.get("base_sha")
    mc, sq = m.get("merge_commit_sha"), m.get("squash_commit_sha")
    row = {"iid": m["iid"], "state": m["state"], "head": head, "base": base, "start": dr.get("start_sha"), "merge_commit_sha": mc,
           "squash_commit_sha": sq, "squash": m.get("squash"), "squash_on_merge": m.get("squash_on_merge"),
           "source_branch": m["source_branch"], "target_branch": m["target_branch"]}
    def anc(a, b):
        return bool(a and b) and subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", a, b]).returncode == 0
    target = mc or sq
    row["head_in_merge_commit"] = anc(head, target) if m["state"] == "merged" else None
    row["base_eq_head"] = head == base
    row["head_ancestor_of_base"] = anc(head, base)
    rows.append(row)
rows.sort(key=lambda r: r["iid"])
json.dump(rows, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
c = collections.Counter()
for r in rows:
    if r["state"] == "merged":
        kind = "merge_commit" if r["merge_commit_sha"] and not r["squash_commit_sha"] else ("squash+merge" if r["merge_commit_sha"] else ("squash_ff" if r["squash_commit_sha"] else "ff_or_unknown"))
        c[(kind, r["head_in_merge_commit"])] += 1
    c[("empty_or_ancestor", r["base_eq_head"] or r["head_ancestor_of_base"])] += 1
    c[("no_base", r["base"] is None)] += 1
for k, v in sorted(c.items(), key=str):
    print(k, v)

