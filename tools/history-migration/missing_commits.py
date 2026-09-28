"""API 덤프에 나오는 커밋 SHA(MR commits·diff version·merge/squash 커밋) 중 미러에 없는 것을 찾는다."""
import glob
import json
import os
import subprocess
import sys

api, repo = sys.argv[1], sys.argv[2]
shas = {}


def add(sha, src):
    if sha and len(sha) == 40:
        shas.setdefault(sha, src)


for f in glob.glob(os.path.join(api, "merge_requests", "*", "merge_request.json")):
    m = json.load(open(f, encoding="utf-8"))
    iid = m["iid"]
    for k in ("sha", "merge_commit_sha", "squash_commit_sha"):
        add(m.get(k), f"!{iid}.{k}")
    for k, v in (m.get("diff_refs") or {}).items():
        add(v, f"!{iid}.diff_refs.{k}")
for f in glob.glob(os.path.join(api, "merge_requests", "*", "commits", "page-*.json")):
    for c in json.load(open(f, encoding="utf-8")):
        add(c["id"], "mr-commit " + f.split(os.sep)[-3])
for f in glob.glob(os.path.join(api, "merge_requests", "*", "versions", "page-*.json")):
    for v in json.load(open(f, encoding="utf-8")):
        for k in ("head_commit_sha", "base_commit_sha", "start_commit_sha"):
            add(v.get(k), f"!{f.split(os.sep)[-3]} version {k}")
out = subprocess.run(["git", "-C", repo, "cat-file", "--batch-check"], input="\n".join(shas).encode(), capture_output=True).stdout.decode()
missing = [l.split()[0] for l in out.splitlines() if l.endswith("missing")]
json.dump({"total": len(shas), "missing": {s: shas[s] for s in missing}}, sys.stdout, indent=1)

