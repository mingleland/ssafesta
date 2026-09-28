"""프로젝트 이벤트(push_data)에 나온 커밋 중 저장소에 없는 것을 찾는다 — 삭제·강제 push 된 브랜치의 커밋 후보."""
import glob
import json
import os
import subprocess
import sys

api, repo = sys.argv[1], sys.argv[2]
shas = {}
for f in glob.glob(os.path.join(api, "project", "events", "page-*.json")):
    for e in json.load(open(f, encoding="utf-8")):
        pd = e.get("push_data") or {}
        for k in ("commit_from", "commit_to"):
            if pd.get(k):
                shas.setdefault(pd[k], f"event {e['id']} {pd.get('ref')} {pd.get('action')} {k}")
out = subprocess.run(["git", "-C", repo, "cat-file", "--batch-check"], input="\n".join(shas).encode(), capture_output=True).stdout.decode()
missing = [l.split()[0] for l in out.splitlines() if l.endswith("missing")]
json.dump({"total": len(shas), "missing": {s: shas[s] for s in missing}}, sys.stdout, indent=1, ensure_ascii=False)

