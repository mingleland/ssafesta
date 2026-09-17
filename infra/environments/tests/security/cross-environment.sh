#!/usr/bin/env bash
# T063: Prevents dev and demo from sharing an application network or data credentials.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

dev_dir="${repo_root}/infra/deploy/compose/dev"
demo_compose="${repo_root}/infra/deploy/compose/integration/compose.yaml"
data_compose="${repo_root}/infra/environments/compose/data/compose.yaml"
postgres_init="${repo_root}/infra/environments/postgres/init/00-databases-and-roles.sql"
redis_acl="${repo_root}/infra/environments/redis/users.acl.example"

assert_file "${demo_compose}"
assert_file "${data_compose}"
assert_file "${postgres_init}"
assert_file "${redis_acl}"

for component in ai back; do
  assert_contains "${dev_dir}/${component}.compose.yaml" 'name: festa-dev-ai-back-private' "dev ${component} must use the dev-only application network"
  assert_contains "${dev_dir}/${component}.compose.yaml" 'COMPONENT_ENV_FILE:\?COMPONENT_ENV_FILE is required' "dev ${component} secrets must come from its env file"
  assert_not_contains "${dev_dir}/${component}.compose.yaml" 'festa-integration-private' "dev ${component} must not join the demo network"
done

assert_contains "${demo_compose}" 'name: festa-integration-private' 'demo must use its own application network'
assert_not_contains "${demo_compose}" 'festa-dev-ai-back-private' 'demo must not join the dev network'
assert_contains "${demo_compose}" 'AI_ENV_FILE:\?AI_ENV_FILE is required' 'demo AI secrets must come from its env file'
assert_contains "${demo_compose}" 'BACK_ENV_FILE:\?BACK_ENV_FILE is required' 'demo backend secrets must come from its env file'
assert_contains "${data_compose}" 'POSTGRES_.*PASSWORD_FILE' 'shared PostgreSQL credentials must be file references'
assert_contains "${data_compose}" 'REDIS_ACL_FILE' 'shared Redis ACL must be file-backed'

expected_grants=$'GRANT CONNECT ON DATABASE festa_dev_business TO festa_dev_back_app;\nGRANT CONNECT ON DATABASE festa_dev_ai TO festa_dev_ai_app;\nGRANT CONNECT ON DATABASE festa_demo_business TO festa_demo_back_app;\nGRANT CONNECT ON DATABASE festa_demo_ai TO festa_demo_ai_app;'
actual_grants="$(grep '^GRANT CONNECT ON DATABASE' "${postgres_init}")"
assert_equals "${expected_grants}" "${actual_grants}" 'PostgreSQL roles must have access only to their own environment database'

assert_contains "${redis_acl}" '^user dev_back .*~dev:\*' 'dev backend Redis ACL must stay in dev namespace'
assert_contains "${redis_acl}" '^user dev_ai .*~dev:ai:\*' 'dev AI Redis ACL must stay in dev namespace'
assert_contains "${redis_acl}" '^user demo_back .*~demo:\*' 'demo backend Redis ACL must stay in demo namespace'
assert_contains "${redis_acl}" '^user demo_ai .*~demo:ai:\*' 'demo AI Redis ACL must stay in demo namespace'
assert_not_contains "${redis_acl}" '^user dev_(back|ai) .*~demo:' 'dev Redis users must not access demo keys'
assert_not_contains "${redis_acl}" '^user demo_(back|ai) .*~dev:' 'demo Redis users must not access dev keys'

pass 'dev and demo application networks, data roles, Redis namespaces, and secret references are isolated'
