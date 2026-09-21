#!/usr/bin/env bash
set -euo pipefail
set +x
[[ $# -eq 1 ]] || { echo 'Usage: deploy-production-candidate.sh <receipt.json>' >&2; exit 64; }
receipt="$1"
state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
data_evidence="${PRODUCTION_DATA_EVIDENCE_PATH:-${state_root}/production/data/bootstrap.json}"
: "${BACK_ENV_FILE:?BACK_ENV_FILE is required}"
: "${AI_ENV_FILE:?AI_ENV_FILE is required}"
: "${CONNECTION_TOKEN_SECRET_FILE:?CONNECTION_TOKEN_SECRET_FILE is required}"
: "${INTERNAL_SPRING_TO_AI_TOKENS:?INTERNAL_SPRING_TO_AI_TOKENS is required}"
: "${INTERNAL_AI_TO_SPRING_TOKENS:?INTERNAL_AI_TO_SPRING_TOKENS is required}"
: "${INTERNAL_INFRA_TO_SPRING_TOKENS:?INTERNAL_INFRA_TO_SPRING_TOKENS is required}"
: "${PRODUCTION_WORLD_HOST:?PRODUCTION_WORLD_HOST is required}"
: "${ROOT_DOMAIN:?ROOT_DOMAIN is required}"
: "${GITLAB_DEPLOY_TOKEN:?GITLAB_DEPLOY_TOKEN is required}"
for required in "${receipt}" "${BACK_ENV_FILE}" "${AI_ENV_FILE}" "${CONNECTION_TOKEN_SECRET_FILE}" "${data_evidence}"; do
  [[ -f "${required}" ]] || { echo "missing required file: ${required}" >&2; exit 66; }
done
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
docker_bin="${DOCKER_BIN:-docker}"
PRODUCTION_PROMOTION_RECEIPT_PATH="${receipt}" ENVIRONMENT_STATE_DIR="${state_root}" WEBGL_RELEASE_ROOT="${webgl_root}" \
  bash "${repo_root}/infra/jenkins/scripts/validate-production-promotion.sh" >/dev/null
bash "${repo_root}/infra/deploy/scripts/validate-production-main-ancestry.sh" "${receipt}" HEAD >/dev/null
python3 - "${data_evidence}" <<'PY'
import json,pathlib,sys
doc=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
expected={'state':'READY','environment':'production','bootstrapMode':'fresh-isolated','demoDataCopied':False}
for k,v in expected.items():
    if doc.get(k)!=v: raise SystemExit(f'Production data evidence mismatch for {k}')
if doc.get('databases',{}).get('business',{}).get('name')!='festa_prod_business': raise SystemExit('Production business DB mismatch')
if doc.get('databases',{}).get('ai',{}).get('name')!='festa_prod_ai': raise SystemExit('Production AI DB mismatch')
r=doc.get('redis',{})
if doc.get('schemaVersion')!='1.1.0': raise SystemExit('Production data evidence schema mismatch')
if r.get('backKeyPattern')!='prod:*' or r.get('aiKeyPattern')!='prod:ai:*' or r.get('conversationKeyPattern')!='conversation:*' or r.get('persistence')!='host-acl-file-hashed': raise SystemExit('Production Redis evidence mismatch')
PY
IFS=$'\t' read -r receipt_id demo_release ai_ref ai_id back_ref back_id front_ref front_id world_ref world_id world_version world_sha world_url < <(
python3 - "${receipt}" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
print(d['receiptId'],d['demoReleaseId'],d['applications']['ai']['imageRef'],d['applications']['ai']['contentId'],d['applications']['back']['imageRef'],d['applications']['back']['contentId'],d['applications']['front']['imageRef'],d['applications']['front']['contentId'],d['world']['imageRef'],d['world']['imageContentId'],d['world']['packageVersion'],d['world']['archiveSha256'],d['world']['packageUrl'],sep='\t')
PY
)
cutover_evidence="${PRODUCTION_CUTOVER_EVIDENCE_PATH:-${state_root}/production/cutovers/${receipt_id}.prepare.json}"
[[ -f "${cutover_evidence}" ]] || { echo 'Production candidate deployment requires maintenance evidence' >&2; exit 66; }
python3 - "${cutover_evidence}" "${receipt_id}" <<'PY'
import json,pathlib,sys
doc=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
if doc.get('state')!='MAINTENANCE_ACTIVE' or doc.get('receiptId')!=sys.argv[2]:
    raise SystemExit('Production maintenance evidence mismatch')
if doc.get('legacyRollbackAllowed') is not False:
    raise SystemExit('Production maintenance evidence permits forbidden legacy rollback')
PY
verify_image() {
  local actual
  actual="$("${docker_bin}" image inspect --format '{{.Id}}' "$1")"
  [[ "${actual}" == "$2" ]] || { echo "image identity mismatch: $1" >&2; exit 65; }
}
verify_image "${ai_ref}" "${ai_id}"
verify_image "${back_ref}" "${back_id}"
verify_image "${front_ref}" "${front_id}"
bash "${repo_root}/infra/deploy/scripts/load-production-world.sh" --release-id "${world_version}" --sha256 "${world_sha}" --package-url "${world_url}" --image-ref "${world_ref}" --content-id "${world_id}" >/dev/null
verify_image "${world_ref}" "${world_id}"
WEBGL_RELEASE_ROOT="${webgl_root}" bash "${repo_root}/infra/deploy/scripts/stage-production-webgl.sh" "${receipt}" >/dev/null
export AI_IMAGE_REF="${ai_ref}" BACK_IMAGE_REF="${back_ref}" FRONT_IMAGE_REF="${front_ref}" WORLD_IMAGE_REF="${world_ref}" RELEASE_ID="${demo_release}"
compose=("${docker_bin}" compose --project-name festa-production --file "${repo_root}/infra/deploy/compose/production/compose.yaml")
"${compose[@]}" config --quiet
for legacy in festa-prod-front-1 festa-prod-back-1 festa-prod-ai-1 festa-prod-world-1; do
  if "${docker_bin}" container inspect "${legacy}" >/dev/null 2>&1; then
    "${docker_bin}" rm -f "${legacy}" >/dev/null
  fi
done
if ! "${compose[@]}" up -d --no-deps --wait ai back front world; then
  "${compose[@]}" stop ai back front world >/dev/null 2>&1 || true
  echo 'Production candidate failed readiness; candidate services stopped' >&2
  exit 69
fi
candidate_dir="${state_root}/production/candidates"
candidate_path="${candidate_dir}/${receipt_id}.json"
mkdir -p "${candidate_dir}"
RECEIPT="${receipt}" CANDIDATE_PATH="${candidate_path}" python3 - <<'PY'
import datetime,json,os,pathlib
r=json.loads(pathlib.Path(os.environ['RECEIPT']).read_text(encoding='utf-8'))
p=pathlib.Path(os.environ['CANDIDATE_PATH'])
d={'schemaVersion':'1.0.0','state':'RUNNING_UNVERIFIED','receiptId':r['receiptId'],'receiptSha256':__import__('hashlib').sha256(pathlib.Path(os.environ['RECEIPT']).read_bytes()).hexdigest(),'demoReleaseId':r['demoReleaseId'],'applications':r['applications'],'webgl':r['webgl'],'world':r['world'],'loopback':{'front':'127.0.0.1:28080','back':'127.0.0.1:28081','ai':'127.0.0.1:28082','world':'127.0.0.1:27777'},'publicCutoverPerformed':False,'startedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
t=p.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(p)
PY
printf 'PRODUCTION_CANDIDATE=%s\n' "${candidate_path}"
echo "DEPLOYED_PRODUCTION_CANDIDATE: ${receipt_id}"
