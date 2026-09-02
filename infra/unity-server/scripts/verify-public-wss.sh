#!/usr/bin/env bash
# DNS부터 승인된 Unity 입장까지 공개 WSS 계층을 검사하고 민감정보 없는 결과만 기록한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

if [[ "${1:-}" == '--config-only' ]]; then
  assert_file "${unity_server_dir}/nginx/world.conf.template"
  exit 0
fi

if [[ $# -ne 4 || "$1" != '--approval-evidence' || "$3" != '--output' ]]; then
  fail "usage: $0 --approval-evidence <PASS file> --output <evidence file>"
fi

approval_evidence="$2"
output="$4"
: "${ROOT_DOMAIN:?ROOT_DOMAIN is required}"
[[ "${ROOT_DOMAIN}" =~ ^[A-Za-z0-9.-]+$ ]] || fail 'ROOT_DOMAIN contains unsupported characters'
world_host="world.${ROOT_DOMAIN}"

assert_file "${approval_evidence}"
[[ "$(tr -d '[:space:]' <"${approval_evidence}")" == 'PASS' ]] || fail 'approved Unity admission evidence is not PASS'
command -v openssl >/dev/null 2>&1 || fail 'openssl is required'
command -v curl >/dev/null 2>&1 || fail 'curl is required'
command -v timeout >/dev/null 2>&1 || fail 'timeout is required'

if command -v getent >/dev/null 2>&1; then
  getent hosts "${world_host}" >/dev/null || fail 'DNS resolution failed'
else
  nslookup "${world_host}" >/dev/null || fail 'DNS resolution failed'
fi

openssl s_client -connect "${world_host}:443" -servername "${world_host}" \
  -verify_hostname "${world_host}" -verify_return_error </dev/null >/dev/null 2>&1 \
  || fail 'TLS chain, expiry, or hostname verification failed'

wss_status="$({ curl --silent --show-error --http1.1 --max-time 5 --output /dev/null \
  --write-out '%{http_code}' \
  --header 'Connection: Upgrade' \
  --header 'Upgrade: websocket' \
  --header 'Sec-WebSocket-Version: 13' \
  --header 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "https://${world_host}/" || true; } | tail -c 3)"
[[ "${wss_status}" == '101' ]] || fail "WebSocket Upgrade failed with HTTP ${wss_status:-none}"

if timeout 3 bash -c "</dev/tcp/${world_host}/7777" >/dev/null 2>&1; then
  fail 'public game port 7777 is reachable'
fi

mkdir -p -- "$(dirname "${output}")"
cat >"${output}" <<EOF
checkedAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
host=${world_host}
dns=PASS
tls=PASS
websocketUpgrade=PASS
public7777=BLOCKED
approvedAdmission=PASS
EOF
pass "public WSS verified; evidence=${output}"
