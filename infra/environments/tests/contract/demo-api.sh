#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

site="${repo_root}/infra/environments/nginx/sites/api.conf.template"

assert_file "${site}"
assert_contains "${site}" 'server_name api\.\$\{ROOT_DOMAIN\};' 'API must use its public host'
assert_contains "${site}" 'proxy_pass http://127\.0\.0\.1:18081;' 'API must proxy to the loopback-only demo backend'
assert_contains "${site}" 'location \^~ /ws \{' 'API must expose the Spring WebSocket route'
assert_contains "${site}" 'proxy_http_version 1\.1;' 'API WebSocket proxy must use HTTP/1.1'
assert_contains "${site}" 'proxy_set_header Upgrade \$http_upgrade;' 'API WebSocket proxy must forward Upgrade'
assert_contains "${site}" 'proxy_set_header Connection "upgrade";' 'API WebSocket proxy must forward Connection upgrade'
assert_contains "${site}" 'proxy_read_timeout 180s;' 'API WebSocket proxy must retain the initial read timeout'
assert_contains "${site}" 'proxy_send_timeout 180s;' 'API WebSocket proxy must retain the initial send timeout'
assert_contains "${site}" 'proxy_buffering off;' 'API WebSocket proxy must not buffer frames'
assert_contains "${site}" 'proxy_set_header X-Forwarded-Proto https;' 'API must preserve its public HTTPS scheme'
assert_not_contains "${site}" '^[[:space:]]*http2 on;' 'API must remain compatible with the deployed Nginx version'

pass 'demo API is exposed only through its TLS virtual host'
