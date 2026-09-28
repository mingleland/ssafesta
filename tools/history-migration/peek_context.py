"""패턴 주변 문맥을 비밀 부분을 가린 채 보여준다 (검토용)."""
import re
import sys

path, rx = sys.argv[1], sys.argv[2]
t = open(path, encoding="utf-8").read()
for m in list(re.finditer(rx, t))[:10]:
    s = t[max(0, m.start() - 100):m.end() + 60]
    s = re.sub(r"(://[^:/\s]+:)[^@\s]*@", r"\1***@", s)
    s = re.sub(r"(?i)((?:password|passwd|pwd)\s*[:=]\s*)\S+", r"\1***", s)
    print(s.replace("\n", " ")[:320])
    print("---")

