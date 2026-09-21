#!/usr/bin/env bash
# public HTTPS/WSS가 활성 CURRENT의 exact artifact를 서비스하는지 검증한다.
set -euo pipefail
set +x
[[ $# -eq 1 ]] || { echo 'Usage: verify-production-public.sh <receipt.json>' >&2; exit 64; }
receipt="$1"; state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
: "${PRODUCTION_PUBLIC_BASE_URL:?PRODUCTION_PUBLIC_BASE_URL is required}"
: "${PRODUCTION_WORLD_PUBLIC_URL:?PRODUCTION_WORLD_PUBLIC_URL is required}"
receipt_id="$(python3 - "${receipt}" <<'PY'
import json,pathlib,sys
print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))['receiptId'])
PY
)"
current="${state_root}/production/current.json"; output="${state_root}/production/receipts/${receipt_id}.external-verification.json"
[[ -f "${current}" ]] || { echo 'Production CURRENT is missing' >&2; exit 66; }
python3 - "${receipt}" "${current}" <<'PY'
import json,pathlib,sys
r,c=[json.loads(pathlib.Path(x).read_text(encoding='utf-8')) for x in sys.argv[1:]]
if c.get('state')!='CURRENT' or c.get('receiptId')!=r.get('receiptId'): raise SystemExit('public verification requires matching CURRENT')
PY
curl_bin="${CURL_BIN:-curl}"; base="${PRODUCTION_PUBLIC_BASE_URL%/}"
check_http(){
  local path="$1" code
  code="$("${curl_bin}" --silent --show-error --output /dev/null --max-time "${PUBLIC_VERIFY_TIMEOUT_SECONDS:-20}" --write-out '%{http_code}' "${base}${path}")"
  [[ "${code}" =~ ^[234][0-9][0-9]$ ]] || { echo "public verification failed: ${path} status=${code}" >&2; exit 69; }
}
check_http '/'
check_http '/api/'
check_http '/oauth2/'
check_http '/login/oauth2/'
check_http '/ai/v1/health/live'
manifest="$(mktemp)"; trap 'rm -f "${manifest}"' EXIT
"${curl_bin}" --fail --silent --show-error --location --max-time "${PUBLIC_VERIFY_TIMEOUT_SECONDS:-20}" --output "${manifest}" "${base}/unity/manifest.json"
python3 - "${receipt}" "${manifest}" <<'PY'
import json,pathlib,sys
r=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')); m=json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
if m.get('sourceCommit')!=r['webgl']['sourceCommit']: raise SystemExit('public WebGL sourceCommit mismatch')
PY
WORLD_URL="${PRODUCTION_WORLD_PUBLIC_URL}" python3 - <<'PY'
import base64,os,socket,ssl,urllib.parse
url=urllib.parse.urlparse(os.environ['WORLD_URL'])
if url.scheme not in ('ws','wss') or not url.hostname: raise SystemExit('invalid Production World public URL')
port=url.port or (443 if url.scheme=='wss' else 80); raw=socket.create_connection((url.hostname,port),timeout=10)
sock=ssl.create_default_context().wrap_socket(raw,server_hostname=url.hostname) if url.scheme=='wss' else raw
key=base64.b64encode(os.urandom(16)).decode(); path=url.path or '/'
request=(f'GET {path} HTTP/1.1\r\nHost: {url.hostname}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode()
sock.sendall(request); response=sock.recv(1024); sock.close()
if not response.startswith(b'HTTP/1.1 101') and not response.startswith(b'HTTP/1.0 101'): raise SystemExit('Production World WSS upgrade did not return 101')
PY
mkdir -p "$(dirname "${output}")"
RECEIPT="${receipt}" CURRENT="${current}" OUTPUT="${output}" python3 - <<'PY'
import datetime,hashlib,json,os,pathlib
r=json.loads(pathlib.Path(os.environ['RECEIPT']).read_text(encoding='utf-8')); c=pathlib.Path(os.environ['CURRENT'])
d={'schemaVersion':'1.0.0','state':'EXTERNAL_VERIFIED','receiptId':r['receiptId'],'checks':{'frontHttps':'PASS','backHttps':'PASS','oauthRoutes':'PASS','aiHttps':'PASS','webglIdentity':'PASS','worldWss':'PASS'},'currentSha256':hashlib.sha256(c.read_bytes()).hexdigest(),'verifiedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
p=pathlib.Path(os.environ['OUTPUT']); t=p.with_suffix('.tmp'); t.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8'); t.replace(p)
PY
printf 'PRODUCTION_EXTERNAL_VERIFICATION=%s\n' "${output}"
