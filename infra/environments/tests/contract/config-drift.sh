#!/usr/bin/env bash
# 설정 정본이 두 곳으로 갈라지는 두 부류를 고정한다.
#  (1) env-file ↔ compose 이중 정의 — audit-env-overrides.sh 가 배포 때 드러내야 한다
#  (2) 쓰지도 않는 DB 설정 — festa-ai 는 DB 의존성이 없다(Demo/Prod 가 서로 다른 드라이버를
#      들고 있었지만 둘 다 죽은 값이었다). 코드가 다시 읽기 시작하면 이 검사가 먼저 깨진다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

audit="${repo_root}/infra/environments/scripts/audit-env-overrides.sh"
deploy="${repo_root}/infra/environments/scripts/deploy-environment.sh"
assert_file "${audit}"
assert_contains "${deploy}" 'audit-env-overrides\.sh' 'deploy must audit env-file/compose double definitions'

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

cat >"${work}/component.env" <<'ENV'
# 주석과 빈 줄은 키가 아니다
WORLD_HOST=world.example
OPENAI_API_KEY=unused
ONLY_IN_ENV_FILE=1
ENV
cat >"${work}/config.json" <<'JSON'
{"services":{"back":{"environment":{"WORLD_HOST":"demo.example","ONLY_IN_COMPOSE":"1"}}}}
JSON

output="$(bash "${audit}" --env-file "${work}/component.env" --compose-config "${work}/config.json" --service back 2>"${work}/err")"
[[ "${output}" == 'envOverrides: WORLD_HOST' ]] || fail "audit must report the overridden key only: ${output}"
grep -q 'compose wins over env-file for WORLD_HOST' "${work}/err" || fail 'audit must warn about runtime-destination overrides'

cat >"${work}/clean.env" <<'ENV'
ONLY_IN_ENV_FILE=1
ENV
output="$(bash "${audit}" --env-file "${work}/clean.env" --compose-config "${work}/config.json" --service back 2>/dev/null)"
[[ "${output}" == 'envOverrides: none' ]] || fail "audit must report none when nothing overlaps: ${output}"

bash "${audit}" --env-file "${work}/clean.env" >/dev/null 2>&1 && fail 'audit must reject missing arguments'

# festa-ai 에 DB 설정이 되살아나면 Demo/Prod 의 죽은 DATABASE_URL 도 같이 살아난다 —
# 그때는 이 검사를 지우는 대신 양쪽 드라이버부터 맞춰야 한다.
if rg -ni 'sqlalchemy|asyncpg|psycopg|DATABASE_URL' "${repo_root}/festa-ai" --glob '!*.lock' | grep -q .; then
  fail 'festa-ai must stay DB-free, or the Demo/Production DATABASE_URL drift must be resolved first'
fi

printf 'config-drift contract OK\n'
