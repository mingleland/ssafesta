"""gitleaks 결과를 규칙·파일별로 요약한다 (값은 이미 --redact 로 가려져 있다)."""
import collections
import json
import sys

for path in sys.argv[1:]:
    d = json.load(open(path, encoding="utf-8"))
    print("==", path, len(d))
    by = collections.Counter((x["RuleID"], x["File"].split("/")[-1] if "/" in x["File"] else x["File"].split("\\")[-1]) for x in d)
    for (rule, f), n in by.most_common(40):
        print(f"  {n:4d} {rule:28s} {f}")
    rules = collections.Counter(x["RuleID"] for x in d)
    print("  rules:", dict(rules))

