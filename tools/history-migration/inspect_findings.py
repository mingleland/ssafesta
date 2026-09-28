"""gitleaks git 결과의 실제 값을 출력하지 않고 성격만 판정한다: 길이, 앞 3글자, 테스트용 표식 여부."""
import json
import re
import subprocess
import sys

repo, report = sys.argv[1], sys.argv[2]
seen = set()
for x in json.load(open(report, encoding="utf-8")):
    key = (x["File"], x["StartLine"], x["Match"][:40])
    if key in seen:
        continue
    seen.add(key)
    blob = subprocess.run(["git", "-C", repo, "show", f"{x['Commit']}:{x['File']}"], capture_output=True).stdout.decode("utf-8", "replace").splitlines()
    line = blob[x["StartLine"] - 1] if len(blob) >= x["StartLine"] else ""
    m = re.search(r"[:=]\s*[\"']?([^\"'\s,}]+)", line[line.find(x["Match"].split("REDACTED")[0][:10]) if x["Match"].split("REDACTED")[0][:10] in line else 0:])
    val = m.group(1) if m else ""
    hints = [w for w in ("test", "dummy", "example", "fake", "local", "mock", "changeme", "sample", "placeholder", "S15P21A604", "leak", "xxx") if w.lower() in (line + x["File"]).lower()]
    print(f"{x['File']}:{x['StartLine']} len={len(val)} head={val[:3]!r} hints={hints} commits_with_file={x['Commit'][:8]}")

