#!/usr/bin/env bash
# 검증된 Production candidate의 cutover를 준비하고 public route를 maintenance로 격리한다.
set -euo pipefail
set +x

[[ $# -eq 1 ]] || { echo 'Usage: prepare-production-cutover.sh <receipt.json>' >&2; exit 64; }
receipt="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
webgl_root="${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}"
receipt_id="$(python3 - "${receipt}" <<'PY'
import json,pathlib,sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))['receiptId'])
PY
)"
cutover_dir="${state_root}/production/cutovers"
evidence="${cutover_dir}/${receipt_id}.prepare.json"
current="${state_root}/production/current.json"
known_good="${state_root}/production/known-good.json"
previous="${state_root}/production/previous.json"
template="${PRODUCTION_MAINTENANCE_TEMPLATE:-${repo_root}/infra/environments/nginx/sites/prod-maintenance.conf.template}"
active_config="${PRODUCTION_NGINX_CONFIG_PATH:-/etc/nginx/sites-enabled/prod.conf}"
docker_bin="${DOCKER_BIN:-docker}"

ROOT_DOMAIN="${ROOT_DOMAIN:-ssafesta.world}"
NGINX_ORIGIN_CERTIFICATE_FILE="${NGINX_ORIGIN_CERTIFICATE_FILE:-/etc/nginx/tls/world-dev-origin.pem}"
NGINX_ORIGIN_PRIVATE_KEY_FILE="${NGINX_ORIGIN_PRIVATE_KEY_FILE:-/etc/nginx/tls/world-dev-origin.key}"
for path in "${receipt}" "${template}"; do
  [[ -f "${path}" ]] || { echo "missing cutover input: ${path}" >&2; exit 66; }
done

python3 - "${receipt}" "${current}" "${known_good}" "${previous}" <<'PY'
import json,pathlib,sys
receipt,current,known_good,previous=map(pathlib.Path,sys.argv[1:])
r=json.loads(receipt.read_text(encoding='utf-8'))
if r.get('state')!='APPROVED_FOR_PRODUCTION':
    raise SystemExit('cutover requires an approved Production receipt')
if current.exists() != known_good.exists():
    raise SystemExit('Production current/known-good must both exist or both be absent')
if current.exists():
    c=json.loads(current.read_text(encoding='utf-8'))
    k=json.loads(known_good.read_text(encoding='utf-8'))
    if c.get('state')!='CURRENT' or c.get('receiptId')!=k.get('receiptId') or k.get('state')!='KNOWN_GOOD':
        raise SystemExit('Production cutover requires the active canonical release to be known-good')
    for key in ('applications','webgl','world'):
        if c.get(key)!=k.get(key): raise SystemExit(f'Production current/known-good artifact mismatch: {key}')
PY

mkdir -p "${cutover_dir}"
rendered="${cutover_dir}/.${receipt_id}.maintenance.conf"
ROOT_DOMAIN="${ROOT_DOMAIN}" NGINX_ORIGIN_CERTIFICATE_FILE="${NGINX_ORIGIN_CERTIFICATE_FILE}" NGINX_ORIGIN_PRIVATE_KEY_FILE="${NGINX_ORIGIN_PRIVATE_KEY_FILE}" \
python3 - "${template}" "${rendered}" <<'PY'
import os,pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
for key in ('ROOT_DOMAIN','NGINX_ORIGIN_CERTIFICATE_FILE','NGINX_ORIGIN_PRIVATE_KEY_FILE'):
    text=text.replace('${'+key+'}',os.environ[key])
if '${' in text: raise SystemExit('unresolved maintenance Nginx template variable')
pathlib.Path(sys.argv[2]).write_text(text,encoding='utf-8')
PY

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
backup="${cutover_dir}/.${receipt_id}.active-before-maintenance.conf"
had_active=0
if privileged test -f "${active_config}"; then
  privileged_read_file "${active_config}" "${backup}"
  had_active=1
fi
restore_active(){
  if [[ "${had_active}" == 1 ]]; then
    privileged_write_file "${backup}" "${active_config}"
  else
    privileged rm -f "${active_config}"
  fi
}
privileged_write_file "${rendered}" "${active_config}"
if ! privileged "${NGINX_BIN:-nginx}" -t; then restore_active; exit 65; fi
if ! privileged "${NGINX_RELOAD_BIN:-systemctl}" reload nginx; then
  restore_active
  privileged "${NGINX_BIN:-nginx}" -t >/dev/null
  privileged "${NGINX_RELOAD_BIN:-systemctl}" reload nginx
  exit 69
fi

python3 - "${current}" "${previous}" <<'PY'
import json,pathlib,sys
current,previous=map(pathlib.Path,sys.argv[1:])
if current.exists():
    document=json.loads(current.read_text(encoding='utf-8'))
    previous.parent.mkdir(parents=True,exist_ok=True)
    tmp=previous.with_suffix('.tmp')
    tmp.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
    tmp.replace(previous)
elif previous.exists():
    previous.unlink()
PY

legacy_audit="$({
  for name in festa-prod-front-1 festa-prod-back-1 festa-prod-ai-1 festa-prod-world-1; do
    "${docker_bin}" inspect --format '{{.Name}}\t{{.Config.Image}}\t{{.State.Status}}' "${name}" 2>/dev/null || true
  done
} | sed 's#^/##')"
webgl_current=''
[[ -L "${webgl_root}/prod/current" ]] && webgl_current="$(readlink "${webgl_root}/prod/current")"
LEGACY_AUDIT="${legacy_audit}" WEBGL_CURRENT="${webgl_current}" RECEIPT="${receipt}" EVIDENCE="${evidence}" PREVIOUS="${previous}" RENDERED="${rendered}" python3 - <<'PY'
import datetime,hashlib,json,os,pathlib
r=json.loads(pathlib.Path(os.environ['RECEIPT']).read_text(encoding='utf-8'))
previous=pathlib.Path(os.environ['PREVIOUS'])
legacy=[]
for line in os.environ.get('LEGACY_AUDIT','').splitlines():
    parts=line.split('\t')
    if len(parts)==3: legacy.append({'name':parts[0],'imageRef':parts[1],'status':parts[2]})
rendered=pathlib.Path(os.environ['RENDERED'])
d={'schemaVersion':'1.0.0','state':'MAINTENANCE_ACTIVE','receiptId':r['receiptId'],
   'previous':json.loads(previous.read_text(encoding='utf-8')) if previous.exists() else None,
   'legacyAudit':legacy,'legacyRollbackAllowed':False,
   'webglCurrentAudit':os.environ.get('WEBGL_CURRENT') or None,
   'maintenanceConfigSha256':hashlib.sha256(rendered.read_bytes()).hexdigest(),
   'preparedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
p=pathlib.Path(os.environ['EVIDENCE']); tmp=p.with_suffix('.tmp')
tmp.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); tmp.replace(p)
PY
rm -f "${rendered}" "${backup}"
printf 'PRODUCTION_CUTOVER_PREPARED=%s\n' "${evidence}"
