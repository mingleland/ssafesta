#!/usr/bin/env bash
# 공개 WSS 설정과 외부 검증기가 443 공개·7777 차단 계약을 함께 지키는지 검사한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

nginx_template="${unity_server_dir}/nginx/world.conf.template"
verifier="${unity_server_dir}/scripts/verify-public-wss.sh"

assert_file "${nginx_template}"
assert_file "${verifier}"
assert_contains "${nginx_template}" 'listen[[:space:]]+443[[:space:]]+ssl' 'world ingress must listen on TLS 443'
assert_contains "${nginx_template}" 'proxy_set_header[[:space:]]+Upgrade' 'world ingress must preserve WebSocket Upgrade'
assert_not_contains "${nginx_template}" 'listen[[:space:]]+7777' 'world ingress must not publish game port 7777'
assert_contains "${verifier}" '7777' 'external verifier must check that public 7777 is blocked'

bash "${verifier}" --config-only >/dev/null
pass 'public WSS ingress and verification boundary'
