#!/usr/bin/env bash
# dev component overlay와 배포 어댑터가 격리·주입 계약을 지키는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
compose_dir="${repo_root}/infra/environments/compose/dev"
deploy="${repo_root}/infra/environments/scripts/deploy-environment.sh"
verify="${repo_root}/infra/environments/scripts/verify-environment.sh"

for component in ai back front game; do
  overlay="${compose_dir}/${component}.yaml"
  assert_file "${overlay}"
  assert_contains "${overlay}" "profiles: \[${component}\]" "${component} must have its own profile"
  assert_contains "${overlay}" 'COMPONENT_IMAGE_REF' "${component} image must be supplied by the release"
  assert_contains "${overlay}" 'com\.ssafy-festa\.release-id' "${component} must expose release provenance"
  assert_not_contains "${overlay}" '0\.0\.0\.0:' "${component} must not bind a public host port"
done

assert_contains "${compose_dir}/ai.yaml" 'SPRING_INTERNAL_BASE_URL: http://back:8080' 'AI must use backend service DNS'
assert_contains "${compose_dir}/ai.yaml" 'name: festa-data-private' 'AI must join the data network'
assert_contains "${compose_dir}/ai.yaml" 'name: festa-dev-ai-data' 'AI data volume must be environment-scoped'
assert_contains "${compose_dir}/back.yaml" 'AI_INTERNAL_BASE_URL: http://ai:8000' 'backend must use AI service DNS'
assert_contains "${compose_dir}/back.yaml" 'INTERNAL_SPRING_TO_AI_TOKENS:' 'backend must receive its outbound token'
assert_contains "${compose_dir}/front.yaml" 'PUBLIC_API_BASE_URL: \$\{PUBLIC_API_BASE_URL:' 'front API endpoint must be runtime-injected'
assert_not_contains "${compose_dir}/front.yaml" 'ssafesta\.world' 'front must not bake an environment hostname'
assert_contains "${compose_dir}/game.yaml" '127\.0\.0\.1:\$\{GAME_HOST_PORT:-7777\}:7777' 'game must expose only loopback ingress'
assert_contains "${compose_dir}/game.yaml" 'world-replay:/var/lib/festa-world' 'game must persist its replay ledger'
assert_contains "${compose_dir}/game.yaml" 'CONNECTION_TOKEN_SECRET_FILE' 'game must receive its token from a file'

assert_file "${deploy}"
assert_file "${verify}"
assert_contains "${deploy}" 'freshness\.sh' 'deploy must reuse infra-001 freshness validation'
assert_contains "${deploy}" 'image content ID mismatch' 'deploy must verify immutable image content'
assert_contains "${deploy}" 'up -d --no-deps --wait' 'deploy must update only the selected service'
assert_not_contains "${deploy}" 'compose.*down' 'deploy must never stop the full environment'
assert_contains "${verify}" 'non-target container changed' 'verification must reject non-target changes'
assert_contains "${verify}" 'dataContinuous' 'verification must report shared data continuity'
assert_contains "${verify}" 'mockComponents' 'verification must disclose selected mocks'

pass 'dev overlays and deployment adapters keep endpoints, secrets, and updates scoped'
