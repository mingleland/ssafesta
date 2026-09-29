"""GEI archive 의 본문·댓글 텍스트에서 공개 전 민감 패턴을 센다 (커밋 작성자 필드 제외)."""
import collections
import glob
import json
import re
import sys

texts = []
for f in glob.glob(sys.argv[1] + "/*.ndjson"):
    for line in open(f, encoding="utf-8"):
        o = json.loads(line)
        texts.append(o.get("description") or "")
        texts += [n.get("note") or "" for n in o.get("notes", [])]
t = "\n".join(texts)
emails = [m for m in re.findall(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", t) if "noreply" not in m]
print("texts", len(texts), "emails", len(emails), collections.Counter(emails).most_common(12))
print("webhook_with_path", len(re.findall(r"automation/webhooks/jira/a/[0-9a-f-]{20,}", t)))
print("tokens", len(re.findall(r"(ghp_|gho_|github_pat_|glpat-|ATATT|xox[bp]-|AKIA[0-9A-Z]{12})", t)))
print("private_key", len(re.findall(r"BEGIN [A-Z ]*PRIVATE KEY", t)), "jira_accountid", len(re.findall(r"\b\d{6}:[0-9a-f-]{36}\b", t)))

