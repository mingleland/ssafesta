"""archive 용 project.bundle: 실제 브랜치·태그 + MR head(refs/merge-requests/<iid>/head). 임시 PR 브랜치는 넣지 않는다."""
import json
import os
import subprocess
import sys

root, out = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
src = os.path.join(root, "gei", "bundle-src.git")
if not os.path.exists(src):
    subprocess.run(["git", "clone", "-q", "--mirror", os.path.join(root, "work", "staging.git"), src], check=True)
refs = subprocess.run(["git", "-C", src, "for-each-ref", "--format=%(refname)"], capture_output=True, text=True).stdout.split()
lines = [f"delete {r}" for r in refs if r.startswith(("refs/heads/gl-mr/", "refs/heads/gh-pr/", "refs/remotes/"))]
for iid, sha in json.load(open(os.path.join(root, "gei", "archive-build-map.json"), encoding="utf-8"))["mr_heads"].items():
    if sha:  # 커밋이 없는 MR 은 head 가 없다
        lines.append(f"update refs/merge-requests/{iid}/head {sha}")
subprocess.run(["git", "-C", src, "update-ref", "--stdin"], input=("\n".join(lines) + "\n").encode(), check=True)
subprocess.run(["git", "-C", src, "bundle", "create", out, "--all"], check=True, capture_output=True)
print(subprocess.run(["git", "-C", src, "for-each-ref", "--format=%(refname)"], capture_output=True, text=True).stdout.count("\n"), os.path.getsize(out))

