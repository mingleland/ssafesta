#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

site="${repo_root}/infra/environments/nginx/sites/api.conf.template"
demo_site="${repo_root}/infra/environments/nginx/sites/demo.conf.template"

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
# Game Studio 초안 저장은 2,000,000 bytes 까지 허용한다 — nginx 기본 1m 이면 앱에 닿기도 전에 413 이다 (GitLab #207).
assert_contains "${site}" 'client_max_body_size 4m;' 'API must accept Game Studio drafts above the 1m nginx default'
# Demo Front 는 PUBLIC_API_BASE_URL=https://api.<root> 로 나간다 — demo vhost 에 API proxy 를 또 두면
# 한도·헤더가 두 곳으로 갈라진다. 여기서 없음을 계약으로 고정한다.
assert_not_contains "${demo_site}" 'location /api/' 'Demo API must stay on the api vhost'
assert_not_contains "${site}" '^[[:space:]]*http2 on;' 'API must remain compatible with the deployed Nginx version'

pass 'demo API is exposed only through its TLS virtual host'
