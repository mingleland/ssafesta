"""광고되지 않는 커밋(MR head 가 정리된 squash MR 등)을 SHA 로 직접 받아 refs/archive/gitlab-unadvertised/<sha> 로 고정한다."""
import json
import subprocess
import sys

repo, missing_json, url = sys.argv[1], sys.argv[2], sys.argv[3]
shas = list(json.load(open(missing_json))["missing"])
for i in range(0, len(shas), 100):
    chunk = shas[i:i + 100]
    specs = [f"{s}:refs/archive/gitlab-unadvertised/{s}" for s in chunk]
    r = subprocess.run(["git", "-C", repo, "fetch", "--no-tags", "--no-write-fetch-head", url, *specs], capture_output=True, text=True)
    print(i + len(chunk), r.returncode, r.stderr.strip().splitlines()[-1:] if r.returncode else "", flush=True)
    if r.returncode:  # 하나씩 재시도해 실패 SHA 를 특정한다
        for s in chunk:
            r2 = subprocess.run(["git", "-C", repo, "fetch", "--no-tags", "--no-write-fetch-head", url, f"{s}:refs/archive/gitlab-unadvertised/{s}"], capture_output=True, text=True)
            if r2.returncode:
                print("FAILED", s, r2.stderr.strip().splitlines()[-1:], flush=True)

