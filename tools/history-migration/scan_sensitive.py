"""공개 산출물의 민감 패턴을 값 노출 없이 집계한다: 자격증명 형식, URL 내 비밀번호, IP, 내부 호스트, 전화번호, 이메일."""
import collections
import os
import re
import sys

PAT = {
    "token_prefix": r"(ghp_|gho_|github_pat_|glpat-|ATATT|xox[abprs]-|sk-[A-Za-z0-9]{20}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30})",
    "private_key": r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    "url_credentials": r"[a-z][a-z0-9+.-]*://[^/\s:@]+:[^/\s@]{3,}@",
    "password_assign": r"(?i)(password|passwd|pwd|비밀번호)\s*[:=]\s*[\"']?[^\s\"'<>{}$]{4,}",
    "ipv4_public": r"\b(?!10\.|127\.|0\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)(?:\d{1,3}\.){3}\d{1,3}\b",
    "ipv4_private": r"\b(?:10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)(?:\d{1,3}\.){1}\d{1,3}\.\d{1,3}\b",
    "aws_internal_host": r"ip-\d+-\d+-\d+-\d+|compute\.internal|ec2-\d+-\d+",
    "email": r"\b[A-Za-z0-9._%+-]+@(?!users\.noreply)[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b",
    "phone": r"\b01[016789]-?\d{3,4}-?\d{4}\b",
    "webhook": r"hooks\.slack\.com|api-private\.atlassian\.com/automation|discord(?:app)?\.com/api/webhooks",
}
root = sys.argv[1]
hits = collections.defaultdict(collections.Counter)
samples = collections.defaultdict(set)
for d, _, fs in os.walk(root):
    for f in fs:
        p = os.path.join(d, f)
        if os.path.getsize(p) > 200_000_000 or f.lower().endswith((".png", ".jpg", ".jpeg", ".gif", ".webp", ".mp4", ".zip")):
            continue
        t = open(p, "rb").read().decode("utf-8", "replace")
        for name, rx in PAT.items():
            for m in re.finditer(rx, t):
                hits[name][os.path.relpath(p, root)] += 1
                v = m.group(0)
                samples[name].add(v[:6] + "…" + str(len(v)))
for name in PAT:
    tot = sum(hits[name].values())
    print(f"{name}: {tot}", dict(hits[name].most_common(4)), sorted(samples[name])[:8])

