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
assert_contains "${nginx_template}" 'proxy_pass http://127\.0\.0\.1:\$\{DEMO_GAME_HOST_PORT\};' 'world ingress must target the demo loopback port'
assert_contains "${nginx_template}" 'proxy_set_header[[:space:]]+Upgrade' 'world ingress must preserve WebSocket Upgrade'
assert_not_contains "${nginx_template}" 'listen[[:space:]]+7777' 'world ingress must not publish game port 7777'
assert_not_contains "${nginx_template}" '^[[:space:]]*http2 on;' 'world ingress must remain compatible with the deployed Nginx version'
assert_contains "${verifier}" '7777' 'external verifier must check that public 7777 is blocked'

bash "${verifier}" --config-only >/dev/null
# Demo/Production World 격리 (Batch 1): template 은 Demo 전용 host 만 선언하고, verifier 는 host 를 추론하지 않는다.
assert_contains "${nginx_template}" 'server_name[[:space:]]+\$\{DEMO_WORLD_HOST\};' 'world ingress must use the dedicated Demo World host'
assert_not_contains "${nginx_template}" 'world\.\$\{ROOT_DOMAIN\}' 'world ingress must not claim the Production World host'
assert_contains "${verifier}" 'WORLD_PUBLIC_HOST' 'external verifier must take the World host explicitly'
assert_not_contains "${verifier}" 'world\.\$\{ROOT_DOMAIN\}' 'external verifier must not infer the World host from ROOT_DOMAIN'
if env -u WORLD_PUBLIC_HOST ROOT_DOMAIN=example.invalid bash "${verifier}" --output /tmp/public-wss-no-host.txt >/dev/null 2>&1; then
  fail 'verifier must fail fast without WORLD_PUBLIC_HOST'
fi
rendered="$(DEMO_WORLD_HOST=world-demo.example.invalid DEMO_GAME_HOST_PORT=17777 NGINX_ORIGIN_CERTIFICATE_FILE=/x.pem NGINX_ORIGIN_PRIVATE_KEY_FILE=/x.key \
  envsubst '${DEMO_WORLD_HOST} ${DEMO_GAME_HOST_PORT} ${NGINX_ORIGIN_CERTIFICATE_FILE} ${NGINX_ORIGIN_PRIVATE_KEY_FILE}' < "${nginx_template}")"
grep -Fq 'server_name world-demo.example.invalid;' <<<"${rendered}" || fail 'rendered Demo world vhost must carry world-demo host'
if grep -Fq 'server_name world.example.invalid;' <<<"${rendered}"; then fail 'rendered Demo world vhost must not collide with Production world host'; fi
pass 'public WSS ingress and verification boundary'
