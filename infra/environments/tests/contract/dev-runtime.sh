#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

base="${repo_root}/infra/environments/compose/dev/base.yaml"
ingress="${repo_root}/infra/environments/nginx/sites/dev.conf"

assert_file "${base}"
assert_file "${ingress}"
assert_contains "${base}" '^name: festa-dev$' 'dev must use the festa-dev Compose project'
assert_contains "${base}" 'name: festa-dev-private' 'dev private network name is required'
assert_contains "${base}" 'internal: true' 'dev network must be internal'
assert_contains "${base}" 'DEV_COMPONENT_PROFILE' 'component profile input is required'
assert_contains "${base}" 'DEV_SERVICE_ALIAS' 'internal service alias input is required'
assert_contains "${base}" 'DEV_MOCK_COMPONENTS' 'mock selection input is required'

for route in front api ai world; do
  assert_contains "${ingress}" "location /__dev/${route}/" "missing dev ${route} route"
done
assert_contains "${ingress}" 'dev-allowlist/\*\.conf' 'dev routes must use the approved-IP allowlist'
assert_contains "${ingress}" 'deny all;' 'dev routes must deny unapproved sources'
assert_contains "${ingress}" '127\.0\.0\.1:3001' 'front must proxy through loopback'
assert_contains "${ingress}" '127\.0\.0\.1:8081' 'api must proxy through loopback'
assert_contains "${ingress}" '127\.0\.0\.1:8000' 'ai must proxy through loopback'
assert_contains "${ingress}" '127\.0\.0\.1:7777' 'world must proxy through loopback'
pass 'dev runtime keeps component inputs private and IP-gated ingress loopback-only'
