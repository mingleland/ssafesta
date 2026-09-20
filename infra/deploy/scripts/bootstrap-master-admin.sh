#!/usr/bin/env bash
# 최초 마스터 관리자 1명을 지정한다. 일회성이다.
#
# V36 의 초기 시드는 닉네임으로 찾는데, 그 계정이 아직 가입 전이면 0행 no-op 으로 끝나고 Flyway
# 는 '적용됨' 으로 기록한다 — Production 이 그 경우였다. 권한 승격은 관리자 API 로 할 수 없다
# (관리자가 있어야 부를 수 있다). 그래서 이것 하나만 DB 를 직접 만진다.
#
# 일상적인 운영 데이터 주입(provision-operational-data.sh)이나 bootstrap-production-data.sh 에
# 섞지 않는다. 권한 상승은 매번 도는 절차에 들어가면 안 된다.
#
# 기본은 dry-run 이다. --apply 를 붙여야 바꾼다.
set -euo pipefail

usage() {
  echo "usage: $0 --env <prod|demo> --provider <GOOGLE|KAKAO|SSAFY> --subject <provider subject> [--apply]" >&2
}

environment=''
provider=''
subject=''
apply=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) environment="${2:-}"; shift 2 ;;
    --provider) provider="${2:-}"; shift 2 ;;
    --subject) subject="${2:-}"; shift 2 ;;
    --apply) apply=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 64 ;;
  esac
done
[[ -n "${environment}" && -n "${provider}" && -n "${subject}" ]] || { usage; exit 64; }
case "${environment}" in
  prod) database='festa_prod_business' ;;
  demo) database='festa_demo_business' ;;
  *) usage; exit 64 ;;
esac

docker_bin="${DOCKER_BIN:-docker}"
postgres_container="${POSTGRES_CONTAINER:-}"
if [[ -z "${postgres_container}" ]]; then
  postgres_container="$(
    "${docker_bin}" ps \
      --filter 'label=com.docker.compose.project=festa-data' \
      --filter 'label=com.docker.compose.service=postgres' \
      --format '{{.ID}}'
  )"
fi
[[ "$(wc -w <<<"${postgres_container}")" -eq 1 ]] || { echo 'expected exactly one festa-data/postgres container' >&2; exit 66; }

psql() {
  "${docker_bin}" exec -i "${postgres_container}" psql -v ON_ERROR_STOP=1 -Atq -U festa_admin -d "${database}" "$@"
}

# provider/subject 는 인자에서 온다 — 문자열 보간 대신 psql 변수로 넘겨 인용을 psql 에 맡긴다.
lookup="$(psql -v provider="${provider}" -v subject="${subject}" <<'SQL'
SELECT u.id, u.is_master
  FROM users u
  JOIN oauth_identities oi ON oi.user_id = u.id
 WHERE oi.provider = :'provider' AND oi.provider_subject = :'subject';
SQL
)"

if [[ -z "${lookup}" ]]; then
  echo "MASTER_ADMIN_USER_ABSENT: ${provider} 계정이 ${environment} 에 아직 없다 — 먼저 로그인해야 한다" >&2
  exit 66
fi
user_id="${lookup%%|*}"
is_master="${lookup##*|}"

if [[ "${is_master}" == t ]]; then
  echo "MASTER_ADMIN_ALREADY: userId=${user_id}"
  exit 0
fi
if [[ "${apply}" -ne 1 ]]; then
  echo "DRY_RUN: userId=${user_id} is_master=false — --apply 를 붙이면 승격한다"
  exit 0
fi

# 승격과 감사 기록은 한 트랜잭션이다. actor 0 = 시스템(V36 과 같은 관례, admin_actions 에 FK 없음).
psql -v user_id="${user_id}" -v env="${environment}" <<'SQL' >/dev/null
BEGIN;
UPDATE users
   SET account_type = 'ADMIN', is_master = TRUE, updated_at = CURRENT_TIMESTAMP
 WHERE id = :'user_id'::bigint AND NOT is_master;
INSERT INTO admin_actions (actor_user_id, action, target_type, target_id, detail)
SELECT 0, 'ADMIN_GRANT', 'USER', id, 'bootstrap-master-admin ' || :'env'
  FROM users WHERE id = :'user_id'::bigint AND is_master;
COMMIT;
SQL

after="$(psql -v user_id="${user_id}" <<'SQL'
SELECT is_master FROM users WHERE id = :'user_id'::bigint;
SQL
)"
[[ "${after}" == t ]] || { echo 'MASTER_ADMIN_GRANT_FAILED' >&2; exit 65; }

summary="{\"environment\":\"${environment}\",\"userId\":${user_id},\"beforeIsMaster\":false,\"afterIsMaster\":true}"
if [[ -n "${MASTER_ADMIN_EVIDENCE_PATH:-}" ]]; then
  mkdir -p "$(dirname "${MASTER_ADMIN_EVIDENCE_PATH}")"
  printf '%s\n' "${summary}" >"${MASTER_ADMIN_EVIDENCE_PATH}"
fi
printf '%s\n' "${summary}"
