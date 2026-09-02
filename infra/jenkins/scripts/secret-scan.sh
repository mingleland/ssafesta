#!/usr/bin/env bash
set -euo pipefail
paths=()
while [[ $# -gt 0 ]]; do case "$1" in --path) paths+=("$2"); shift 2;; *) echo "unknown argument: $1" >&2; exit 64;; esac; done
[[ ${#paths[@]} -gt 0 ]] || paths=(.)
python_bin="${PYTHON_BIN:-}"
if [[ -z "${python_bin}" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
    python_bin=python3
  elif command -v python >/dev/null 2>&1 && python -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
    python_bin=python
  else
    echo 'Python 3 is required.' >&2
    exit 69
  fi
fi
python_paths=("${paths[@]}")
if command -v cygpath >/dev/null 2>&1; then
  python_paths=()
  for path in "${paths[@]}"; do python_paths+=("$(cygpath -w "${path}")"); done
fi
"${python_bin}" - "${SECRET_CANARY:-}" "${python_paths[@]}" <<'PY'
import pathlib,re,sys
canary=sys.argv[1]; roots=[pathlib.Path(p) for p in sys.argv[2:]]
excluded={'.git','Library','Temp','Logs','obj','Builds','node_modules','.venv','venv','jenkins_home'}
fixture_path='infra/tests/security/fixtures'
explicit_fixture=any(fixture_path in root.as_posix() for root in roots)
safe_values={'[REDACTED]','[PLACEHOLDER]','[TEST-CANARY]','[TEST-ONLY]'}
def safe_literal(value): return value in safe_values or value.startswith('$') or '...' in value
sensitive_key=r'(?:[A-Za-z0-9_-]*(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token))'
quoted_assignment=re.compile(
 rf'''(?ix)(?<![A-Za-z0-9_-])(?:["']?{sensitive_key}["']?)\s*[:=]\s*(?P<quote>["'])(?P<value>[^"'\r\n]{{6,}})(?P=quote)''')
sensitive_env_key=r'[A-Z0-9_]*(?:PASSWORD|PASSWD|SECRET|API_KEY|ACCESS_TOKEN|REFRESH_TOKEN)'
env_assignment=re.compile(rf'^\s*(?:export\s+)?(?P<key>{sensitive_env_key})\s*=\s*(?P<value>[^\s\\]+)')
patterns=[
 re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
 re.compile(r'(?i)authorization\s*[:=]\s*(?:bearer|basic)\s+(?![$\{]|\[(?:REDACTED|PLACEHOLDER|TEST-CANARY|TEST-ONLY)\])\S+'),
 re.compile(r'https?://[^\s/]+/(?:hooks|webhooks)/[^\s"\']+'),
 re.compile(r'AKIA[0-9A-Z]{16}')]
hits=[]
for root in roots:
 files=[root] if root.is_file() else root.rglob('*') if root.exists() else []
 for p in files:
  relative_parts=(p.name,) if root.is_file() else p.relative_to(root).parts
  if not p.is_file() or any(part in excluded for part in relative_parts): continue
  normalized=p.as_posix()
  if fixture_path in normalized and not explicit_fixture: continue
  is_test_file=any(part.lower() in {'test','tests','__tests__'} for part in p.parts)
  try:
   if p.stat().st_size > 10_000_000: continue
   with p.open('rb') as source:
    head=source.read(8192)
    if b'\0' in head: continue
    text=(head+source.read()).decode('utf-8',errors='ignore')
  except OSError: continue
  for line_no,line in enumerate(text.splitlines(),1):
   if canary and canary in line: hits.append((p,line_no,'canary'))
   scan_line=re.sub(r'\$\{[^}]+\}|<[^>]+>', '[PLACEHOLDER]', line)
   if fixture_path in normalized or '/infra/tests/' in '/' + normalized: scan_line=re.sub(r'FESTA_[A-Z0-9_]+', '[TEST-CANARY]', scan_line)
   scan_line=scan_line.replace('foundation-only-value','[TEST-ONLY]')
   if not is_test_file or explicit_fixture:
    quoted=quoted_assignment.search(scan_line)
    literal_hit=bool(quoted and not safe_literal(quoted.group('value')))
    for env in env_assignment.finditer(scan_line):
     value=env.group('value').strip('"\'')
     literal_hit=literal_hit or (len(value) >= 6 and not safe_literal(value))
    if literal_hit: hits.append((p,line_no,'sensitive-literal'))
   for pattern in patterns:
    if pattern.search(scan_line): hits.append((p,line_no,'sensitive-pattern'))
if hits:
 for p,line,kind in hits: print(f'SECRET_SCAN_HIT {p}:{line} {kind}',file=sys.stderr)
 raise SystemExit(1)
print(f'SECRET_SCAN_OK files_under={len(roots)}')
PY
