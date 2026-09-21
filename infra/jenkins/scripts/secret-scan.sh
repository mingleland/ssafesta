#!/usr/bin/env bash
set -euo pipefail
paths=()
tracked_only=false
changed_since=''
while [[ $# -gt 0 ]]; do case "$1" in
  --path) paths+=("$2"); shift 2;;
  --tracked) tracked_only=true; shift;;
  --changed-since) changed_since="$2"; shift 2;;
  *) echo "unknown argument: $1" >&2; exit 64;;
esac; done
[[ ${#paths[@]} -gt 0 ]] || paths=(.)

# MR 검사는 그 MR 이 건드린 파일만 본다 — 나머지는 develop 의 Jenkins 전체 스캔(--tracked)이 덮는다.
# base 커밋을 못 찾으면(얕은 클론 등) 줄이지 않고 전체를 본다 — 조용히 덜 보는 것보다 느린 편이 낫다.
if [[ -n "${changed_since}" ]]; then
  if git rev-parse -q --verify "${changed_since}^{commit}" >/dev/null 2>&1; then
    mapfile -d '' changed_paths < <(git diff -z --name-only --diff-filter=ACMR "${changed_since}" HEAD -- "${paths[@]}")
    if [[ ${#changed_paths[@]} -eq 0 ]]; then
      echo 'SECRET_SCAN_OK files_under=0'
      exit 0
    fi
    paths=("${changed_paths[@]}")
  else
    echo "secret-scan: ${changed_since} is not in this clone; scanning every requested path" >&2
  fi
fi
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
# fixture를 명시적으로 검사했는지는 --tracked 확장 이전의 요청 path로 판정한다.
# git ls-files 이후에는 디렉터리가 개별 파일 roots로 바뀌므로 그 시점의
# root.is_dir()로 판정하면 explicit fixture가 사라진다.
explicit_fixture=false
for requested_path in "${paths[@]}"; do
  normalized_requested="${requested_path//\\//}"
  if [[ "${normalized_requested}" == *"infra/tests/security/fixtures"* ]]; then
    explicit_fixture=true
    break
  fi
done

if "${tracked_only}"; then
  git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo '--tracked requires a Git worktree.' >&2; exit 64; }
  mapfile -d '' python_paths < <(git ls-files -z -- "${paths[@]}")
else
  python_paths=("${paths[@]}")
fi
if command -v cygpath >/dev/null 2>&1; then
  windows_paths=()
  for path in "${python_paths[@]}"; do windows_paths+=("$(cygpath -w "${path}")"); done
  python_paths=("${windows_paths[@]}")
fi
"${python_bin}" - "${SECRET_CANARY:-}" "${explicit_fixture}" "${python_paths[@]}" <<'PY'
import pathlib,re,sys
canary=sys.argv[1]
explicit_fixture=sys.argv[2] == 'true'
roots=[pathlib.Path(p) for p in sys.argv[3:]]
excluded={'.git','Library','Temp','Logs','obj','Builds','node_modules','.venv','venv','jenkins_home'}
fixture_path='infra/tests/security/fixtures'
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
