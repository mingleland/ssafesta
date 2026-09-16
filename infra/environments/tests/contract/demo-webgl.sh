#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

site="${repo_root}/infra/environments/nginx/sites/demo.conf.template"

assert_file "${site}"
assert_contains "${site}" 'server_name demo\.\$\{ROOT_DOMAIN\};' 'demo must use the public web host'
assert_not_contains "${site}" '^[[:space:]]*http2 on;' 'demo must remain compatible with the deployed Nginx version'
assert_contains "${site}" 'location = /unity/manifest\.json' 'manifest must bypass the frontend SPA fallback'
assert_contains "${site}" 'location /unity/Build/' 'hashed Unity build files need a dedicated location'
assert_contains "${site}" 'location /unity/' 'Unity static files need a same-origin location'
assert_contains "${site}" 'alias /srv/festa/webgl/current/' 'Unity must use the agreed current release directory'
assert_contains "${site}" 'default_type application/wasm;' 'wasm must use its streaming MIME type'
assert_contains "${site}" 'Content-Encoding br' 'Brotli Unity files must declare their encoding'
assert_contains "${site}" '\(br\|unityweb\)' 'Nginx must serve both native Brotli and Unity fallback-compressed suffixes'
assert_contains "${site}" 'max-age=31536000, immutable' 'hashed Unity build files need immutable caching'
assert_contains "${site}" 'Cache-Control "no-cache"' 'manifest and entry files must be revalidated'
assert_contains "${site}" 'proxy_pass http://127\.0\.0\.1:18080;' 'frontend must remain loopback-only behind Nginx'
assert_contains "${site}" 'location /ai/v1/' 'AI must answer same-origin before the frontend fallback'
assert_contains "${site}" 'proxy_pass http://127\.0\.0\.1:18082;' 'AI must remain loopback-only behind Nginx'
assert_contains "${site}" 'Cache-Control "no-store"' 'AI responses must never be cached'

pass 'demo serves same-origin Unity WebGL before the frontend fallback'
