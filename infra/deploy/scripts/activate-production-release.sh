#!/usr/bin/env bash
# 검증된 canonical candidate를 public CURRENT로 원자적으로 전환한다.
set -euo pipefail
set +x
[[ $# -eq 1 ]] || { echo 'Usage: activate-production-release.sh <receipt.json>' >&2; exit 64; }
receipt="$1"; repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"; webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
receipt_id="$(python3 - "${receipt}" <<'PY'
import json,pathlib,sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))['receiptId'])
PY
)"
verification="${state_root}/production/candidates/${receipt_id}.verification.json"
prepare="${state_root}/production/cutovers/${receipt_id}.prepare.json"
current="${state_root}/production/current.json"; previous="${state_root}/production/previous.json"
activation="${state_root}/production/receipts/${receipt_id}.activation.json"
candidate="${webgl_root}/prod/candidate"; prod_current="${webgl_root}/prod/current"; prod_previous="${webgl_root}/prod/previous"
prod_template="${PRODUCTION_NGINX_TEMPLATE:-${repo_root}/infra/environments/nginx/sites/prod.conf.template}"
world_template="${PRODUCTION_WORLD_NGINX_TEMPLATE:-${repo_root}/infra/environments/nginx/sites/world-prod.conf.template}"
prod_config="${PRODUCTION_NGINX_CONFIG_PATH:-/etc/nginx/sites-enabled/prod.conf}"
world_config="${PRODUCTION_WORLD_NGINX_CONFIG_PATH:-/etc/nginx/sites-enabled/world-prod.conf}"
ROOT_DOMAIN="${ROOT_DOMAIN:-ssafesta.world}"; : "${PRODUCTION_WORLD_HOST:?PRODUCTION_WORLD_HOST is required}"
NGINX_ORIGIN_CERTIFICATE_FILE="${NGINX_ORIGIN_CERTIFICATE_FILE:-/etc/nginx/tls/world-dev-origin.pem}"
NGINX_ORIGIN_PRIVATE_KEY_FILE="${NGINX_ORIGIN_PRIVATE_KEY_FILE:-/etc/nginx/tls/world-dev-origin.key}"
# 월드 WSS 인증서는 사이트 인증서와 따로 둔다 — 직결 호스트는 Cloudflare 원본 인증서를 브라우저가 거부한다 (T-295).
PRODUCTION_WORLD_CERTIFICATE_FILE="${PRODUCTION_WORLD_CERTIFICATE_FILE:-${NGINX_ORIGIN_CERTIFICATE_FILE}}"
PRODUCTION_WORLD_PRIVATE_KEY_FILE="${PRODUCTION_WORLD_PRIVATE_KEY_FILE:-${NGINX_ORIGIN_PRIVATE_KEY_FILE}}"
docker_bin="${DOCKER_BIN:-docker}"
for path in "${receipt}" "${verification}" "${prepare}" "${prod_template}" "${world_template}"; do [[ -f "${path}" ]] || { echo "missing activation input: ${path}" >&2; exit 66; }; done
[[ -L "${candidate}" ]] || { echo 'Production WebGL candidate is missing' >&2; exit 66; }
python3 - "${receipt}" "${verification}" "${prepare}" <<'PY'
import hashlib,json,pathlib,sys
r,v,p=[json.loads(pathlib.Path(x).read_text(encoding='utf-8')) for x in sys.argv[1:]]
if v.get('state')!='VERIFIED' or p.get('state')!='MAINTENANCE_ACTIVE': raise SystemExit('activation requires verified candidate and maintenance evidence')
if len({r.get('receiptId'),v.get('receiptId'),p.get('receiptId')})!=1: raise SystemExit('activation evidence identity mismatch')
if v.get('receiptSha256')!=hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest(): raise SystemExit('activation receipt checksum mismatch')
PY
mkdir -p "$(dirname "${activation}")" "$(dirname "${prod_current}")"
render(){ ROOT_DOMAIN="${ROOT_DOMAIN}" PRODUCTION_WORLD_HOST="${PRODUCTION_WORLD_HOST}" NGINX_ORIGIN_CERTIFICATE_FILE="${NGINX_ORIGIN_CERTIFICATE_FILE}" NGINX_ORIGIN_PRIVATE_KEY_FILE="${NGINX_ORIGIN_PRIVATE_KEY_FILE}" PRODUCTION_WORLD_CERTIFICATE_FILE="${PRODUCTION_WORLD_CERTIFICATE_FILE}" PRODUCTION_WORLD_PRIVATE_KEY_FILE="${PRODUCTION_WORLD_PRIVATE_KEY_FILE}" python3 - "$1" "$2" <<'PY'
import os,pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
for key in ('ROOT_DOMAIN','PRODUCTION_WORLD_HOST','NGINX_ORIGIN_CERTIFICATE_FILE','NGINX_ORIGIN_PRIVATE_KEY_FILE','PRODUCTION_WORLD_CERTIFICATE_FILE','PRODUCTION_WORLD_PRIVATE_KEY_FILE'): text=text.replace('${'+key+'}',os.environ[key])
if '${' in text: raise SystemExit('unresolved Production Nginx template variable')
pathlib.Path(sys.argv[2]).write_text(text,encoding='utf-8')
PY
}
prod_render="${activation}.prod.conf"; world_render="${activation}.world.conf"; render "${prod_template}" "${prod_render}"; render "${world_template}" "${world_render}"
privileged(){
  if [[ "${PRODUCTION_USE_SUDO:-1}" == 1 ]]; then
    if ! command -v "${NGINX_BIN:-nginx}" >/dev/null 2>&1 && command -v "${docker_bin}" >/dev/null 2>&1; then
      "${docker_bin}" run --rm --privileged --pid=host alpine:3.20 nsenter -t 1 -m -u -i -n "$@"
    else
      "${SUDO_BIN:-sudo}" -n "$@"
    fi
  else
    "$@"
  fi
}
privileged_write_file(){
  local src="$1" dest="$2"
  if [[ "${PRODUCTION_USE_SUDO:-1}" == 1 ]]; then
    if ! command -v "${NGINX_BIN:-nginx}" >/dev/null 2>&1 && command -v "${docker_bin}" >/dev/null 2>&1; then
      "${docker_bin}" run --rm -i --privileged --pid=host alpine:3.20 nsenter -t 1 -m -u -i -n sh -c 'cat > "'"${dest}"'"' < "${src}"
    else
      "${SUDO_BIN:-sudo}" -n install -m 0644 "${src}" "${dest}"
    fi
  else
    install -m 0644 "${src}" "${dest}"
  fi
}
privileged_read_file(){
  local src="$1" dest="$2"
  if [[ "${PRODUCTION_USE_SUDO:-1}" == 1 ]]; then
    if ! command -v "${NGINX_BIN:-nginx}" >/dev/null 2>&1 && command -v "${docker_bin}" >/dev/null 2>&1; then
      "${docker_bin}" run --rm --privileged --pid=host alpine:3.20 nsenter -t 1 -m -u -i -n cat "${src}" > "${dest}"
    else
      "${SUDO_BIN:-sudo}" -n cat "${src}" > "${dest}"
    fi
  else
    cat "${src}" > "${dest}"
  fi
}
maintenance_backup="${activation}.maintenance.conf"; privileged_read_file "${prod_config}" "${maintenance_backup}"
old_current=''; [[ -L "${prod_current}" ]] && old_current="$(readlink "${prod_current}")"
rollback_activation(){
  privileged_write_file "${maintenance_backup}" "${prod_config}" || true
  privileged rm -f "${world_config}" || true
  if [[ -n "${old_current}" ]]; then tmp="${webgl_root}/prod/.restore.$$"; ln -s "${old_current}" "${tmp}"; mv -Tf "${tmp}" "${prod_current}"; else rm -f "${prod_current}"; fi
  privileged "${NGINX_BIN:-nginx}" -t >/dev/null 2>&1 && privileged "${NGINX_RELOAD_BIN:-systemctl}" reload nginx >/dev/null 2>&1 || true
}
on_activation_failure(){
  local rc=$?
  trap - ERR INT TERM
  rollback_activation
  (( rc == 0 )) && rc=1
  exit "${rc}"
}
trap on_activation_failure ERR INT TERM
if [[ -n "${old_current}" ]]; then tmp="${webgl_root}/prod/.previous.$$"; ln -s "${old_current}" "${tmp}"; mv -Tf "${tmp}" "${prod_previous}"; fi
tmp="${webgl_root}/prod/.current.$$"; ln -s "$(readlink "${candidate}")" "${tmp}"; mv -Tf "${tmp}" "${prod_current}"
privileged_write_file "${prod_render}" "${prod_config}"; privileged_write_file "${world_render}" "${world_config}"
privileged "${NGINX_BIN:-nginx}" -t; privileged "${NGINX_RELOAD_BIN:-systemctl}" reload nginx
RECEIPT="${receipt}" CURRENT="${current}" ACTIVATION="${activation}" VERIFICATION="${verification}" PREVIOUS="${previous}" python3 - <<'PY'
import datetime,hashlib,json,os,pathlib
r=json.loads(pathlib.Path(os.environ['RECEIPT']).read_text(encoding='utf-8')); v=pathlib.Path(os.environ['VERIFICATION'])
now=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
d={'schemaVersion':'1.0.0','state':'CURRENT','receiptId':r['receiptId'],'demoReleaseId':r['demoReleaseId'],'applications':r['applications'],'webgl':r['webgl'],'world':r['world'],'publicActivatedAt':now,'verificationEvidenceSha256':hashlib.sha256(v.read_bytes()).hexdigest()}
p=pathlib.Path(os.environ['CURRENT']); p.parent.mkdir(parents=True,exist_ok=True); t=p.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(p)
a={'schemaVersion':'1.0.0','state':'PUBLIC_ACTIVE','receiptId':r['receiptId'],'previous':json.loads(pathlib.Path(os.environ['PREVIOUS']).read_text(encoding='utf-8')) if pathlib.Path(os.environ['PREVIOUS']).exists() else None,'current':d,'activatedAt':now}
p=pathlib.Path(os.environ['ACTIVATION']); t=p.with_suffix('.tmp'); t.write_text(json.dumps(a,indent=2)+'\n',encoding='utf-8'); t.replace(p)
PY
trap - ERR INT TERM
rm -f "${prod_render}" "${world_render}" "${maintenance_backup}"
printf 'PRODUCTION_CURRENT=%s\n' "${current}"
