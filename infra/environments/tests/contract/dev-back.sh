#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

overlay="${repo_root}/infra/environments/compose/dev/back.yaml"
data_compose="${repo_root}/infra/environments/compose/data/compose.yaml"

assert_file "${overlay}"
assert_contains "${overlay}" 'profiles: \[back\]' 'backend must be the back profile only'
assert_contains "${overlay}" '127\.0\.0\.1:\$\{BACK_HOST_PORT:-8081\}:8080' 'backend must expose only loopback ingress'
assert_contains "${overlay}" 'aliases: \[back\]' 'backend must use the internal back alias'
assert_contains "${overlay}" 'POSTGRES_HOST: postgres' 'backend must use the data-network PostgreSQL hostname'
assert_contains "${overlay}" 'POSTGRES_DB: festa_dev_business' 'backend must use the dev business database'
assert_contains "${overlay}" 'POSTGRES_USER: festa_dev_back_app' 'backend must use the dev database role'
assert_contains "${overlay}" 'REDIS_HOST: redis' 'backend must use the data-network Redis hostname'
assert_contains "${overlay}" 'REDIS_USERNAME: dev_back' 'backend must use the dev Redis ACL user'
assert_contains "${overlay}" 'FESTA_ENVIRONMENT: dev' 'backend must use the dev Redis namespace'
assert_contains "${overlay}" 'COMPONENT_ENV_FILE' 'backend runtime secrets must come from a credential file'
assert_contains "${overlay}" 'name: festa-data-private' 'backend must use the named private data network'
assert_contains "${data_compose}" 'name: festa-data-private' 'data project must create the named private data network'
assert_contains "${repo_root}/infra/environments/redis/users.acl.example" 'user dev_back .*~dev:auth:\* ~dev:wallet:\*' 'dev backend ACL must cover its namespaced Redis keys'
assert_not_contains "${overlay}" '^\s*POSTGRES_PASSWORD:' 'backend password must not be committed'
assert_not_contains "${overlay}" '^\s*REDIS_PASSWORD:' 'Redis password must not be committed'
pass 'dev backend uses scoped data credentials and loopback-only ingress'
