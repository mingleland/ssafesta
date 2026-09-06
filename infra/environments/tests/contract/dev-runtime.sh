#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

base="${repo_root}/infra/environments/compose/dev/base.yaml"
ingress="${repo_root}/infra/environments/nginx/sites/dev.conf"
world_ingress="${repo_root}/infra/environments/nginx/sites/world-dev.conf.template"
game_compose="${repo_root}/infra/deploy/compose/dev/game.compose.yaml"
back_compose="${repo_root}/infra/deploy/compose/dev/back.compose.yaml"

assert_file "${base}"
assert_file "${ingress}"
assert_file "${world_ingress}"
assert_file "${game_compose}"
assert_file "${back_compose}"
assert_contains "${base}" '^name: festa-dev$' 'dev must use the festa-dev Compose project'
assert_contains "${base}" 'name: festa-dev-private' 'dev private network name is required'
assert_contains "${base}" 'internal: true' 'dev network must be internal'

for route in front api ai; do
  assert_contains "${ingress}" "location /__dev/${route}/" "missing dev ${route} route"
done
assert_not_contains "${ingress}" '/__dev/world/' 'UnityTransport cannot connect through a URL path'
assert_contains "${ingress}" 'dev-allowlist/\*\.conf' 'dev routes must use the approved-IP allowlist'
assert_contains "${ingress}" 'deny all;' 'dev routes must deny unapproved sources'
assert_contains "${ingress}" '127\.0\.0\.1:3001' 'front must proxy through loopback'
assert_contains "${ingress}" '127\.0\.0\.1:8081' 'api must proxy through loopback'
assert_contains "${ingress}" '127\.0\.0\.1:8000' 'ai must proxy through loopback'
assert_contains "${world_ingress}" 'server_name world-dev\.\$\{ROOT_DOMAIN\};' 'world must use the dedicated dev host'
assert_contains "${world_ingress}" 'dev-allowlist/\*\.conf' 'dev world must use the approved-IP allowlist'
assert_contains "${world_ingress}" 'proxy_pass http://127\.0\.0\.1:7777;' 'world must proxy through loopback'
assert_contains "${world_ingress}" 'proxy_set_header Upgrade \$http_upgrade;' 'world must preserve WebSocket upgrade'

for key in FESTA_ENVIRONMENT WORLD_TRANSPORT WORLD_LISTEN_PORT WORLD_ID WORLD_CHANNEL_ID \
  WORLD_MAX_PLAYERS WORLD_ENTRY_TOKEN_ISSUER WORLD_ENTRY_TOKEN_AUDIENCE \
  CONNECTION_TOKEN_SECRET_FILE WORLD_LEDGER_PATH; do
  assert_contains "${game_compose}" "${key}:" "dev game Compose is missing ${key}"
done
assert_contains "${game_compose}" 'world-replay:/var/lib/festa-world' 'game replay ledger must use a persistent volume'
assert_contains "${game_compose}" 'file: \$\{CONNECTION_TOKEN_SECRET_FILE:' 'game token Secret must come from a file reference'
assert_contains "${back_compose}" 'WORLD_SCHEME:[[:space:]]+wss' 'backend must advertise a secure world endpoint'
assert_contains "${back_compose}" 'WORLD_HOST:[[:space:]]+world-dev\.\$\{ROOT_DOMAIN' 'backend must advertise the dedicated dev world host'
assert_contains "${back_compose}" 'WORLD_PORT:[[:space:]]+"443"' 'backend must advertise world port 443'
pass 'dev runtime keeps component inputs private and IP-gated ingress loopback-only'
