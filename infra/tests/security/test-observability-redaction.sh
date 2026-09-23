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
 r'(?i)(?:authorization\s*[:=]\s*(?:bearer|basic)\s+)([^\s,;]+)',
 r'(?i)(?:(?:password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|token)\s*[:=]\s*)([^\s,;]+)',
 r'(?i)(?:(?:cookie|set-cookie)\s*[:=]\s*)([^\r\n]+)',
 r'(https?://[^\s/]+/(?:hooks|webhooks)/[^\s]+)',
 r'([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})',
 r'((?:\+?82[- ]?)?0?1[016789][- ]?[0-9]{3,4}[- ]?[0-9]{4})']
def replace_capture(match):
 start,end=match.span(1); offset=match.start()
 return match.group(0)[:start-offset]+'[REDACTED]'+match.group(0)[end-offset:]
for expression in patterns:
 assert re.compile(expression).groups==1,expression
 text=re.sub(expression,replace_capture,text)
expected=pathlib.Path(sys.argv[2]).read_text(encoding='utf-8')
assert text==expected,(text,expected)
PY
echo 'PASS: observability redaction covers credential and PII families before storage'
