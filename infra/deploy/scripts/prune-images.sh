#!/usr/bin/env bash
set -euo pipefail

docker_bin="${DOCKER_BIN:-docker}"
: "${STATE_ROOT:?}"
keep="${IMAGE_RETENTION_PER_COMPONENT:-5}"
[[ "${keep}" =~ ^[1-9][0-9]*$ ]] || { echo 'IMAGE_RETENTION_PER_COMPONENT must be positive' >&2; exit 64; }
native_state="${STATE_ROOT}"; if command -v cygpath >/dev/null 2>&1; then native_state="$(cygpath -w "${STATE_ROOT}")"; fi
protected_file="$(mktemp)"; candidates_file="$(mktemp)"; trap 'rm -f "${protected_file}" "${candidates_file}"' EXIT
python - "${native_state}" >"${protected_file}" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1]); protected=set()
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
declare -A seen=()
while IFS='|' read -r ref short_id created; do
  [[ -n "${ref}" ]] || continue
  full_id="$(${docker_bin} image inspect --format '{{.Id}}' "${ref}")"
  component="${ref%%:*}"; seen["${component}"]=$(( ${seen["${component}"]:-0} + 1 ))
  if grep -Fxq "${ref}" "${protected_file}" || grep -Fxq "${full_id}" "${protected_file}" || (( seen["${component}"] <= keep )); then
    echo "PROTECTED ${ref} ${full_id}"; continue
  fi
  if [[ "${PRUNE_APPROVED:-NO}" == YES ]]; then "${docker_bin}" image rm "${full_id}"; else echo "DRY_RUN_REMOVE ${ref} ${full_id}"; fi
done <"${candidates_file}"
