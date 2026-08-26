#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
rules="${repo_root}/infra/observability/alloy/redaction.alloy"

for marker in authorization bearer password token cookie webhook email phone; do
  grep -qi "${marker}" "${rules}" || { echo "FAIL: redaction rule missing ${marker}" >&2; exit 1; }
done
grep -q '\[REDACTED\]' "${rules}"
! grep -Eq 'stage\.labels.*(email|phone|token|user)' "${rules}"
python - "${repo_root}/infra/tests/security/fixtures/observability/raw.log" "${repo_root}/infra/tests/security/fixtures/observability/expected.log" <<'PY'
import pathlib,re,sys
text=pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
patterns=[
 (r'(?i)(authorization\s*[:=]\s*(?:bearer|basic)\s+)[^\s,;]+',r'\1[REDACTED]'),
 (r'(?i)((?:password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|token)\s*[:=]\s*)[^\s,;]+',r'\1[REDACTED]'),
 (r'(?i)((?:cookie|set-cookie)\s*[:=]\s*)[^\r\n]+',r'\1[REDACTED]'),
 (r'https?://[^\s/]+/(?:hooks|webhooks)/[^\s]+','[REDACTED]'),
 (r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}','[REDACTED]'),
 (r'(?:\+?82[- ]?)?0?1[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4}','[REDACTED]')]
for expression,replacement in patterns:text=re.sub(expression,replacement,text)
expected=pathlib.Path(sys.argv[2]).read_text(encoding='utf-8')
assert text==expected,(text,expected)
PY
echo 'PASS: observability redaction covers credential and PII families before storage'
