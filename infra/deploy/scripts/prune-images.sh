#!/usr/bin/env bash
set -euo pipefail

docker_bin="${DOCKER_BIN:-docker}"
python_bin="${PYTHON_BIN:-python3}"
: "${STATE_ROOT:?}"
keep="${IMAGE_RETENTION_PER_COMPONENT:-5}"
[[ "${keep}" =~ ^[1-9][0-9]*$ ]] || { echo 'IMAGE_RETENTION_PER_COMPONENT must be positive' >&2; exit 64; }
native_state="${STATE_ROOT}"; if command -v cygpath >/dev/null 2>&1; then native_state="$(cygpath -w "${STATE_ROOT}")"; fi
protected_file="$(mktemp)"; candidates_file="$(mktemp)"; trap 'rm -f "${protected_file}" "${candidates_file}"' EXIT
"${python_bin}" - "${native_state}" >"${protected_file}" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]); protected=set()
try:
    import subprocess
    for img in subprocess.check_output(['docker', 'ps', '--format', '{{.Image}}']).decode().splitlines():
        if img.strip(): protected.add(img.strip())
except Exception: pass
if root.exists():
 for p in root.rglob('*.json'):
  try:d=json.loads(p.read_text(encoding='utf-8'))
  except Exception:continue
  def walk(x):
   if isinstance(x,dict):
    if isinstance(x.get('contentId'),str): protected.add(x['contentId'])
    if isinstance(x.get('imageRef'),str): protected.add(x['imageRef'])
    for v in x.values(): walk(v)
   elif isinstance(x,list):
    for v in x:walk(v)
  walk(d)
print('\n'.join(sorted(protected)))
PY

"${docker_bin}" image ls --filter label=org.ssafy-festa.managed=true --format '{{.Repository}}:{{.Tag}}|{{.ID}}|{{.CreatedAt}}' >"${candidates_file}"
"${python_bin}" - "${protected_file}" "${candidates_file}" "${keep}" "${PRUNE_APPROVED:-NO}" <<'PY'
import subprocess, sys
protected_file, candidates_file, keep_str, approved = sys.argv[1:5]
keep = int(keep_str)
protected = set(open(protected_file, encoding='utf-8').read().splitlines())

lines = [line.strip() for line in open(candidates_file, encoding='utf-8') if line.strip()]
if not lines:
    sys.exit(0)

refs = [l.split('|')[0] for l in lines]
raw_ids = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}'] + refs).decode().splitlines()

seen = {}
for ref, full_id in zip(refs, raw_ids):
    component = ref.split(':')[0]
    seen[component] = seen.get(component, 0) + 1
    if ref in protected or full_id in protected or seen[component] <= keep:
        print(f"PROTECTED {ref} {full_id}")
        continue
    if approved == "YES":
        subprocess.run(['docker', 'image', 'rm', full_id], check=False)
        print(f"REMOVED {ref} {full_id}")
    else:
        print(f"DRY_RUN_REMOVE {ref} {full_id}")
PY
