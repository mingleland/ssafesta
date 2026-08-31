#!/usr/bin/env bash
# 공통 설정의 불변식과 민감정보 근거 기록 거부 동작을 로컬에서 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
install_cleanup_trap

common="${repo_root}/infra/environments/compose/common.yaml"
data="${repo_root}/infra/environments/compose/data/compose.yaml"
postgres="${repo_root}/infra/environments/postgres/init/00-databases-and-roles.sql"
redis_config="${repo_root}/infra/environments/redis/redis.conf"
redis_acl="${repo_root}/infra/environments/redis/users.acl.example"

assert_not_contains "${common}" '^[[:space:]]*ports:' 'common Compose publishes a port'
assert_not_contains "${data}" '^[[:space:]]*ports:' 'data Compose publishes a port'
assert_contains "${data}" 'pgvector/pgvector:[0-9]' 'PostgreSQL image is not pinned'
assert_contains "${data}" 'redis:[0-9]' 'Redis image is not pinned'
assert_contains "${postgres}" 'REVOKE CONNECT .* FROM PUBLIC' 'PUBLIC database CONNECT is not revoked'
assert_contains "${postgres}" 'CREATE EXTENSION IF NOT EXISTS vector' 'AI pgvector bootstrap is missing'
assert_contains "${postgres}" "pg_read_file\(item.file_path\)" 'PostgreSQL app credentials are not read from Secret files'
assert_contains "${redis_config}" '^maxmemory-policy noeviction$' 'Redis noeviction policy is missing'
assert_contains "${redis_acl}" '^user default off$' 'Redis default user is enabled'

temp_dir="$(mktemp -d)"
register_cleanup "${temp_dir}"
writer="${repo_root}/infra/environments/scripts/write-evidence.sh"
bash "${writer}" --output "${temp_dir}/safe.json" --scenario contract --environment dev \
  --release-id test-release --result PASS --command 'bash infra/environments/tests/contract/run.sh' >/dev/null
assert_file "${temp_dir}/safe.json"
if bash "${writer}" --output "${temp_dir}/unsafe.json" --scenario contract --environment dev \
  --release-id test-release --result PASS --command 'Authorization: Bearer [TEST-ONLY]' >/dev/null 2>&1; then
  fail 'evidence writer accepted a credential'
fi

pass 'shared foundation keeps data private and evidence sanitized'
