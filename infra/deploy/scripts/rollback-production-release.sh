#!/usr/bin/env bash
# 실패한 Production activation을 maintenance 또는 exact previous canonical release로 복구한다.
set -euo pipefail
set +x
[[ $# -eq 2 ]] || { echo 'Usage: rollback-production-release.sh <receipt.json> <reason>' >&2; exit 64; }
receipt="$1"; reason="$2"; repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"; webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
receipt_id="$(python3 - "${receipt}" <<'PY'
import json,pathlib,sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))['receiptId'])
PY
)"
previous="${state_root}/production/previous.json"; current="${state_root}/production/current.json"; output="${state_root}/production/receipts/${receipt_id}.rollback.json"
prod_config="${PRODUCTION_NGINX_CONFIG_PATH:-/etc/nginx/sites-enabled/prod.conf}"; world_config="${PRODUCTION_WORLD_NGINX_CONFIG_PATH:-/etc/nginx/sites-enabled/world-prod.conf}"
privileged(){ if [[ "${PRODUCTION_USE_SUDO:-1}" == 1 ]]; then "${SUDO_BIN:-sudo}" -n "$@"; else "$@"; fi; }
render(){ python3 - "$1" "$2" <<'PY'
import os,pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
for key in ('ROOT_DOMAIN','PRODUCTION_WORLD_HOST','NGINX_ORIGIN_CERTIFICATE_FILE','NGINX_ORIGIN_PRIVATE_KEY_FILE'): text=text.replace('${'+key+'}',os.environ.get(key,''))
if '${' in text: raise SystemExit('unresolved rollback Nginx template variable')
pathlib.Path(sys.argv[2]).write_text(text,encoding='utf-8')
PY
}
mkdir -p "$(dirname "${output}")"
if [[ ! -f "${previous}" ]]; then
  : "${ROOT_DOMAIN:?ROOT_DOMAIN is required}"; : "${NGINX_ORIGIN_CERTIFICATE_FILE:?NGINX_ORIGIN_CERTIFICATE_FILE is required}"; : "${NGINX_ORIGIN_PRIVATE_KEY_FILE:?NGINX_ORIGIN_PRIVATE_KEY_FILE is required}"
  maintenance="${output}.maintenance.conf"; render "${repo_root}/infra/environments/nginx/sites/prod-maintenance.conf.template" "${maintenance}"
  privileged install -m 0644 "${maintenance}" "${prod_config}"; privileged rm -f "${world_config}"
  privileged "${NGINX_BIN:-nginx}" -t; privileged "${NGINX_RELOAD_BIN:-systemctl}" reload nginx
  rm -f "${webgl_root}/prod/current"
  if [[ -f "${current}" ]] && python3 - "${current}" "${receipt_id}" <<'PY'
import json,pathlib,sys
raise SystemExit(0 if json.loads(pathlib.Path(sys.argv[1]).read_text()).get('receiptId')==sys.argv[2] else 1)
PY
  then rm -f "${current}"; fi
  rm -f "${maintenance}"
  RECEIPT_ID="${receipt_id}" REASON="${reason}" OUTPUT="${output}" python3 - <<'PY'
import datetime,json,os,pathlib
d={'schemaVersion':'1.0.0','receiptId':os.environ['RECEIPT_ID'],'previous':None,'result':'MAINTENANCE_REQUIRED','legacyRestored':False,'reason':os.environ['REASON'],'recordedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
p=pathlib.Path(os.environ['OUTPUT']); t=p.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(p)
PY
  printf 'PRODUCTION_ROLLBACK=%s\n' "${output}"
  exit 0
fi

: "${BACK_ENV_FILE:?BACK_ENV_FILE is required}"; : "${AI_ENV_FILE:?AI_ENV_FILE is required}"; : "${CONNECTION_TOKEN_SECRET_FILE:?CONNECTION_TOKEN_SECRET_FILE is required}"
: "${INTERNAL_SPRING_TO_AI_TOKENS:?INTERNAL_SPRING_TO_AI_TOKENS is required}"; : "${INTERNAL_AI_TO_SPRING_TOKENS:?INTERNAL_AI_TO_SPRING_TOKENS is required}"; : "${INTERNAL_INFRA_TO_SPRING_TOKENS:?INTERNAL_INFRA_TO_SPRING_TOKENS is required}"
: "${ROOT_DOMAIN:?ROOT_DOMAIN is required}"; : "${PRODUCTION_WORLD_HOST:?PRODUCTION_WORLD_HOST is required}"; : "${NGINX_ORIGIN_CERTIFICATE_FILE:?NGINX_ORIGIN_CERTIFICATE_FILE is required}"; : "${NGINX_ORIGIN_PRIVATE_KEY_FILE:?NGINX_ORIGIN_PRIVATE_KEY_FILE is required}"
IFS=$'\t' read -r AI_IMAGE_REF AI_CONTENT_ID BACK_IMAGE_REF BACK_CONTENT_ID FRONT_IMAGE_REF FRONT_CONTENT_ID WORLD_IMAGE_REF WORLD_CONTENT_ID RELEASE_ID WEBGL_VERSION WEBGL_SHA < <(python3 - "${previous}" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
if d.get('state')!='CURRENT': raise SystemExit('previous Production state is not canonical CURRENT')
print(d['applications']['ai']['imageRef'],d['applications']['ai']['contentId'],d['applications']['back']['imageRef'],d['applications']['back']['contentId'],d['applications']['front']['imageRef'],d['applications']['front']['contentId'],d['world']['imageRef'],d['world']['imageContentId'],d['demoReleaseId'],d['webgl']['packageVersion'],d['webgl']['artifactSha256'],sep='\t')
PY
)
export AI_IMAGE_REF BACK_IMAGE_REF FRONT_IMAGE_REF WORLD_IMAGE_REF RELEASE_ID
docker_bin="${DOCKER_BIN:-docker}"; compose=("${docker_bin}" compose --project-name festa-production --file "${repo_root}/infra/deploy/compose/production/compose.yaml")
verify_image(){ [[ "$("${docker_bin}" image inspect --format '{{.Id}}' "$1")" == "$2" ]] || { echo "previous canonical image identity mismatch: $1" >&2; exit 65; }; }
verify_image "${AI_IMAGE_REF}" "${AI_CONTENT_ID}"; verify_image "${BACK_IMAGE_REF}" "${BACK_CONTENT_ID}"; verify_image "${FRONT_IMAGE_REF}" "${FRONT_CONTENT_ID}"; verify_image "${WORLD_IMAGE_REF}" "${WORLD_CONTENT_ID}"
"${compose[@]}" up -d --no-deps --wait ai back front world
release="${webgl_root}/releases/${WEBGL_VERSION}"; [[ -d "${release}" && -f "${release}/.artifact-sha256" ]] || { echo 'previous canonical WebGL release is missing' >&2; exit 66; }
[[ "$(<"${release}/.artifact-sha256")" == "${WEBGL_SHA}" ]] || { echo 'previous canonical WebGL checksum mismatch' >&2; exit 65; }
mkdir -p "${webgl_root}/prod"; tmp="${webgl_root}/prod/.rollback.$$"; ln -s "../releases/${WEBGL_VERSION}" "${tmp}"; mv -Tf "${tmp}" "${webgl_root}/prod/current"
prod_render="${output}.prod.conf"; world_render="${output}.world.conf"; render "${repo_root}/infra/environments/nginx/sites/prod.conf.template" "${prod_render}"; render "${repo_root}/infra/environments/nginx/sites/world-prod.conf.template" "${world_render}"
privileged install -m 0644 "${prod_render}" "${prod_config}"; privileged install -m 0644 "${world_render}" "${world_config}"; privileged "${NGINX_BIN:-nginx}" -t; privileged "${NGINX_RELOAD_BIN:-systemctl}" reload nginx
PREVIOUS="${previous}" CURRENT="${current}" OUTPUT="${output}" RECEIPT_ID="${receipt_id}" REASON="${reason}" python3 - <<'PY'
import datetime,json,os,pathlib
p=pathlib.Path(os.environ['PREVIOUS']); d=json.loads(p.read_text(encoding='utf-8')); d['restoredAt']=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
c=pathlib.Path(os.environ['CURRENT']); t=c.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(c)
e={'schemaVersion':'1.0.0','receiptId':os.environ['RECEIPT_ID'],'previous':d,'result':'ROLLED_BACK','legacyRestored':False,'reason':os.environ['REASON'],'recordedAt':d['restoredAt']}
o=pathlib.Path(os.environ['OUTPUT']); t=o.with_suffix('.tmp'); t.write_text(json.dumps(e,indent=2)+'\n',encoding='utf-8'); t.replace(o)
PY
rm -f "${prod_render}" "${world_render}"
printf 'PRODUCTION_ROLLBACK=%s\n' "${output}"
