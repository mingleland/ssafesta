#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

nginx_template="${unity_server_dir}/nginx/world.conf.template"
assert_file "${nginx_template}"

assert_contains "${nginx_template}" 'proxy_http_version[[:space:]]+1\.1;' 'WebSocket proxy must use HTTP/1.1'
assert_contains "${nginx_template}" 'proxy_set_header[[:space:]]+Upgrade[[:space:]]+\$http_upgrade;' 'Upgrade header is missing'
assert_contains "${nginx_template}" 'proxy_set_header[[:space:]]+Connection[[:space:]]+\$festa_connection_upgrade;' 'Connection upgrade mapping is missing'
assert_contains "${nginx_template}" 'proxy_read_timeout[[:space:]]+180s;' 'initial read timeout must be 180 seconds'
assert_contains "${nginx_template}" 'proxy_send_timeout[[:space:]]+180s;' 'initial send timeout must be 180 seconds'
assert_contains "${nginx_template}" 'proxy_buffering[[:space:]]+off;' 'WebSocket buffering must be disabled'
assert_contains "${nginx_template}" 'proxy_cache[[:space:]]+off;' 'WebSocket caching must be disabled'
assert_contains "${nginx_template}" 'Cache-Control[[:space:]]+"no-store"' 'WebSocket response must be no-store'
assert_contains "${nginx_template}" 'proxy_pass[[:space:]]+http://\$\{GAME_UPSTREAM_HOST\}:\$\{GAME_UPSTREAM_PORT\};' 'upstream must be injected, not hardcoded to a public endpoint'
assert_not_contains "${nginx_template}" 'proxy_ssl_verify[[:space:]]+off' 'TLS verification bypass is forbidden'
pass 'Nginx WebSocket Upgrade, timeout and cache boundary'
