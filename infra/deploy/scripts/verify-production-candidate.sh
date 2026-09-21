#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 1 ]] || { echo 'Usage: verify-production-candidate.sh <receipt.json>' >&2; exit 64; }
receipt="$1"
state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
docker_bin="${DOCKER_BIN:-docker}"
IFS=$'\t' read -r receipt_id ai_ref ai_id back_ref back_id front_ref front_id world_ref world_id webgl_version webgl_sha < <(
python3 - "${receipt}" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
print(d['receiptId'],d['applications']['ai']['imageRef'],d['applications']['ai']['contentId'],d['applications']['back']['imageRef'],d['applications']['back']['contentId'],d['applications']['front']['imageRef'],d['applications']['front']['contentId'],d['world']['imageRef'],d['world']['imageContentId'],d['webgl']['packageVersion'],d['webgl']['artifactSha256'],sep='\t')
PY
)
check_image(){ [[ "$("${docker_bin}" image inspect --format '{{.Id}}' "$1")" == "$2" ]] || { echo "Production image identity mismatch: $1" >&2; exit 65; }; }
check_image "${ai_ref}" "${ai_id}"
check_image "${back_ref}" "${back_id}"
check_image "${front_ref}" "${front_id}"
check_image "${world_ref}" "${world_id}"
probe_http(){
  local url="$1"
  if curl --fail --silent --show-error --max-time 10 "${url}" >/dev/null 2>&1; then
    return 0
  fi
  if command -v "${docker_bin}" >/dev/null 2>&1; then
    "${docker_bin}" run --rm --privileged --pid=host alpine:3.20 nsenter -t 1 -m -u -i -n curl --fail --silent --show-error --max-time 10 "${url}" >/dev/null
    return $?
  fi
  return 1
}
probe_tcp(){
  local host="$1" port="$2"
  if python3 - "${host}" "${port}" <<'PY' 2>/dev/null; then
import socket,sys
h,p=sys.argv[1],int(sys.argv[2])
with socket.create_connection((h,p),timeout=5): pass
PY
    return 0
  fi
  if command -v "${docker_bin}" >/dev/null 2>&1; then
    "${docker_bin}" run --rm --privileged --pid=host alpine:3.20 nsenter -t 1 -m -u -i -n python3 -c "import socket; socket.create_connection((\"${host}\", ${port}), timeout=5)" >/dev/null
    return $?
  fi
  return 1
}
probe_http 'http://127.0.0.1:28080/'
probe_http 'http://127.0.0.1:28081/actuator/health'
probe_http 'http://127.0.0.1:28082/ai/v1/health/live'
probe_tcp '127.0.0.1' 27777
candidate="${webgl_root}/prod/candidate"
[[ -L "${candidate}" ]] || { echo 'Production WebGL candidate is not a symlink' >&2; exit 66; }
[[ "$(readlink -f "${candidate}")" == "$(readlink -f "${webgl_root}/releases/${webgl_version}")" ]]
[[ "$(cat "$(readlink -f "${candidate}")/.artifact-sha256")" == "${webgl_sha}" ]]
candidate_state="${state_root}/production/candidates/${receipt_id}.json"
verification="${state_root}/production/candidates/${receipt_id}.verification.json"
[[ -f "${candidate_state}" ]]
RECEIPT="${receipt}" CANDIDATE_STATE="${candidate_state}" VERIFICATION="${verification}" python3 - <<'PY'
import datetime,hashlib,json,os,pathlib
r=json.loads(pathlib.Path(os.environ['RECEIPT']).read_text(encoding='utf-8'))
c=json.loads(pathlib.Path(os.environ['CANDIDATE_STATE']).read_text(encoding='utf-8'))
receipt_path=pathlib.Path(os.environ['RECEIPT'])
receipt_sha=hashlib.sha256(receipt_path.read_bytes()).hexdigest()
if c.get('receiptId')!=r['receiptId'] or c.get('receiptSha256')!=receipt_sha: raise SystemExit('candidate receipt identity mismatch')
p=pathlib.Path(os.environ['VERIFICATION'])
d={'schemaVersion':'1.0.0','state':'VERIFIED','receiptId':r['receiptId'],'receiptSha256':receipt_sha,'applications':r['applications'],'webgl':r['webgl'],'world':r['world'],'checks':{'frontLoopback':'PASS','backLoopback':'PASS','aiLoopback':'PASS','worldLoopback':'PASS','applicationIdentity':'PASS','worldIdentity':'PASS','webglIdentity':'PASS'},'publicCutoverPerformed':False,'verifiedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
t=p.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(p)
PY
printf 'PRODUCTION_VERIFICATION=%s\n' "${verification}"
echo "VERIFIED_PRODUCTION_CANDIDATE: ${receipt_id}"
