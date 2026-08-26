#!/usr/bin/env bash
set -euo pipefail
paths=()
while [[ $# -gt 0 ]]; do case "$1" in --path) paths+=("$2"); shift 2;; *) echo "unknown argument: $1" >&2; exit 64;; esac; done
[[ ${#paths[@]} -gt 0 ]] || paths=(.)
python_bin="${PYTHON_BIN:-python}"
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
patterns=[
 re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
 re.compile(r'(?i)authorization\s*[:=]\s*(?:bearer|basic)\s+(?!\[(?:REDACTED|PLACEHOLDER|TEST-CANARY|TEST-ONLY)\])\S+'),
 re.compile(r'(?i)(?:password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token)\s*["\']?\s*[:=]\s*["\']?(?!\[(?:REDACTED|PLACEHOLDER|TEST-CANARY|TEST-ONLY)\]|\s*$)[^\s,"\']{6,}'),
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
  try:
   if p.stat().st_size > 10_000_000: continue
   text=p.read_text(encoding='utf-8',errors='ignore')
  except OSError: continue
  for line_no,line in enumerate(text.splitlines(),1):
   if canary and canary in line: hits.append((p,line_no,'canary'))
   scan_line=re.sub(r'\$\{[^}]+\}|<[^>]+>', '[PLACEHOLDER]', line)
   if fixture_path in normalized or '/infra/tests/' in '/' + normalized: scan_line=re.sub(r'FESTA_[A-Z0-9_]+', '[TEST-CANARY]', scan_line)
   scan_line=scan_line.replace('foundation-only-value','[TEST-ONLY]')
   for pattern in patterns:
    if pattern.search(scan_line): hits.append((p,line_no,'sensitive-pattern'))
if hits:
 for p,line,kind in hits: print(f'SECRET_SCAN_HIT {p}:{line} {kind}',file=sys.stderr)
 raise SystemExit(1)
print(f'SECRET_SCAN_OK files_under={len(roots)}')
PY
