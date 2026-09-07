#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

site="${repo_root}/infra/environments/nginx/sites/api.conf.template"

assert_file "${site}"
assert_contains "${site}" 'server_name api\.\$\{ROOT_DOMAIN\};' 'API must use its public host'
assert_contains "${site}" 'proxy_pass http://127\.0\.0\.1:18081;' 'API must proxy to the loopback-only demo backend'
assert_contains "${site}" 'proxy_set_header X-Forwarded-Proto https;' 'API must preserve its public HTTPS scheme'
assert_not_contains "${site}" '^[[:space:]]*http2 on;' 'API must remain compatible with the deployed Nginx version'

pass 'demo API is exposed only through its TLS virtual host'
