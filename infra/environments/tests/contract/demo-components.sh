#!/usr/bin/env bash
# demo overlay 가 develop 자동 배포 대상(demo.ssafesta.world)의 포트·주입 계약을 지키는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
compose_dir="${repo_root}/infra/environments/compose/demo"
manifest="${repo_root}/infra/environments/config/manifests/demo.json"
deploy="${repo_root}/infra/environments/scripts/deploy-environment.sh"

assert_file "${manifest}"
assert_contains "${compose_dir}/base.yaml" 'name: festa-demo' 'demo stack must own its Compose project'

for component in ai back front; do
  overlay="${compose_dir}/${component}.yaml"
  assert_file "${overlay}"
  assert_contains "${overlay}" "profiles: \[${component}\]" "${component} must have its own profile"
  assert_contains "${overlay}" 'COMPONENT_IMAGE_REF' "${component} image must be supplied by the release"
  assert_contains "${overlay}" 'com\.ssafy-festa\.environment: demo' "${component} must be labelled demo"
  assert_contains "${overlay}" 'com\.ssafy-festa\.release-id' "${component} must expose release provenance"
  assert_not_contains "${overlay}" '0\.0\.0\.0:' "${component} must not bind a public host port"
done

# Nginx 가 프록시하는 루프백 포트와 한 글자라도 어긋나면 배포가 성공해도 사이트는 옛 컨테이너를 본다.
assert_contains "${compose_dir}/front.yaml" '127\.0\.0\.1:\$\{DEMO_FRONT_HOST_PORT:-18080\}:80' 'front must serve the Nginx loopback port'
assert_contains "${compose_dir}/back.yaml" '127\.0\.0\.1:\$\{DEMO_BACK_HOST_PORT:-18081\}:8080' 'back must serve the api.* loopback port'
assert_contains "${compose_dir}/ai.yaml" '127\.0\.0\.1:\$\{DEMO_AI_HOST_PORT:-18082\}:8000' 'ai must serve the /ai/v1 loopback port'

# 빈 PUBLIC_API_BASE_URL 로 뜬 프론트는 화면이 멀쩡한데 구글 로그인만 404 다 (2026-09-17 실측).
# 기본값을 주지 말고 여기서 기동을 막는다.
assert_contains "${compose_dir}/front.yaml" 'PUBLIC_API_BASE_URL: \$\{PUBLIC_API_BASE_URL:?' 'front API endpoint must fail closed when unset'
assert_contains "${compose_dir}/front.yaml" 'PUBLIC_UNITY_BUILD_BASE: \$\{PUBLIC_UNITY_BUILD_BASE:?' 'front Unity base must fail closed when unset'
assert_not_contains "${compose_dir}/front.yaml" 'ssafesta\.world' 'front must not bake an environment hostname'

# demo 는 DB·Redis 값을 env_file 하나에서만 읽는다. compose 에도 적으면 두 곳이 언젠가 갈린다.
for component in ai back; do
  overlay="${compose_dir}/${component}.yaml"
  assert_contains "${overlay}" 'COMPONENT_ENV_FILE' "${component} must load its environment file"
  assert_not_contains "${overlay}" 'POSTGRES_DB:' "${component} must not hardcode a database name"
  assert_not_contains "${overlay}" 'REDIS_USERNAME:' "${component} must not hardcode a Redis user"
done
assert_contains "${compose_dir}/back.yaml" 'INTERNAL_INFRA_TO_SPRING_TOKENS:' 'backend must receive its Infra-to-Spring token'
assert_contains "${compose_dir}/ai.yaml" 'name: festa-demo-ai-data' 'AI data volume must be environment-scoped'

# Dedicated Server 는 infra-003 의 festa-demo-world 가 소유한다 — 여기서 또 올리면 월드가 두 번 뜬다.
[[ ! -e "${compose_dir}/game.yaml" ]] || fail 'demo game overlay belongs to infra-003, not infra-002'
assert_contains "${deploy}" 'environment must be dev or demo' 'deploy must accept both environments'
assert_contains "${deploy}" 'demo game deployment belongs to infra-003' 'deploy must refuse demo game'

pass 'demo overlays bind the Nginx loopback ports and fail closed on missing public endpoints'
