#!/usr/bin/env bash
set -euo pipefail
set +x

: "${PROD_POSTGRES_BACK_PASSWORD:?PROD_POSTGRES_BACK_PASSWORD is required}"
: "${PROD_POSTGRES_AI_PASSWORD:?PROD_POSTGRES_AI_PASSWORD is required}"
: "${PROD_REDIS_BACK_PASSWORD:?PROD_REDIS_BACK_PASSWORD is required}"
: "${PROD_REDIS_AI_PASSWORD:?PROD_REDIS_AI_PASSWORD is required}"

for secret in \
  "${PROD_POSTGRES_BACK_PASSWORD}" \
  "${PROD_POSTGRES_AI_PASSWORD}" \
  "${PROD_REDIS_BACK_PASSWORD}" \
  "${PROD_REDIS_AI_PASSWORD}"
do
  [[ "${secret}" != *$'\n'* && "${secret}" != *$'\r'* ]] || {
    echo 'Production credential contains a newline' >&2
    exit 64
  }
done

docker_bin="${DOCKER_BIN:-docker}"
state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
evidence="${PRODUCTION_DATA_EVIDENCE_PATH:-${state_root}/production/data/bootstrap.json}"

find_one() {
  local service="$1" result
  result="$(
    "${docker_bin}" ps \
      --filter 'label=com.docker.compose.project=festa-data' \
      --filter "label=com.docker.compose.service=${service}" \
      --format '{{.ID}}'
  )"
  [[ "$(wc -w <<<"${result}")" -eq 1 ]] || {
    echo "expected exactly one festa-data/${service} container" >&2
    exit 66
  }
  printf '%s\n' "${result}"
}

postgres_container="$(find_one postgres)"
redis_container="$(find_one redis)"

PROD_POSTGRES_BACK_PASSWORD="${PROD_POSTGRES_BACK_PASSWORD}" \
PROD_POSTGRES_AI_PASSWORD="${PROD_POSTGRES_AI_PASSWORD}" \
python3 - <<'PY' |
import os

def literal(value):
    return "'" + value.replace("'", "''") + "'"

print(r'''\set ON_ERROR_STOP on
SELECT 'CREATE ROLE festa_prod_back_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='festa_prod_back_app')\gexec
SELECT 'CREATE ROLE festa_prod_ai_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='festa_prod_ai_app')\gexec
''')
print('ALTER ROLE festa_prod_back_app PASSWORD ' + literal(os.environ['PROD_POSTGRES_BACK_PASSWORD']) + ';')
print('ALTER ROLE festa_prod_ai_app PASSWORD ' + literal(os.environ['PROD_POSTGRES_AI_PASSWORD']) + ';')
print(r'''SELECT 'CREATE DATABASE festa_prod_business OWNER festa_prod_back_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='festa_prod_business')\gexec
SELECT 'CREATE DATABASE festa_prod_ai OWNER festa_prod_ai_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='festa_prod_ai')\gexec
REVOKE CONNECT ON DATABASE festa_prod_business, festa_prod_ai FROM PUBLIC;
GRANT CONNECT ON DATABASE festa_prod_business TO festa_prod_back_app;
GRANT CONNECT ON DATABASE festa_prod_ai TO festa_prod_ai_app;
\connect festa_prod_business
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_prod_back_app;
CREATE EXTENSION IF NOT EXISTS vector;
\connect festa_prod_ai
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_prod_ai_app;
CREATE EXTENSION IF NOT EXISTS vector;
''')
PY
"${docker_bin}" exec -i "${postgres_container}" psql -v ON_ERROR_STOP=1 -U festa_admin -d postgres >/dev/null

for database in festa_prod_business festa_prod_ai; do
  count="$("${docker_bin}" exec "${postgres_container}" psql -Atq -U festa_admin -d postgres -c "SELECT count(*) FROM pg_database WHERE datname='${database}'")"
  [[ "${count}" == 1 ]] || { echo "Production database missing: ${database}" >&2; exit 65; }
done
for role in festa_prod_back_app festa_prod_ai_app; do
  count="$("${docker_bin}" exec "${postgres_container}" psql -Atq -U festa_admin -d postgres -c "SELECT count(*) FROM pg_roles WHERE rolname='${role}'")"
  [[ "${count}" == 1 ]] || { echo "Production PostgreSQL role missing: ${role}" >&2; exit 65; }
done

echo 'PRODUCTION_POSTGRESQL=PASS'

redis_image="$("${docker_bin}" inspect --format '{{.Config.Image}}' "${redis_container}")"

acl_source="$(
  "${docker_bin}" inspect "${redis_container}" |
  python3 -c '
import json
import sys

doc = json.load(sys.stdin)[0]

items = [
    item
    for item in doc.get("Mounts", [])
    if item.get("Destination")
       == "/usr/local/etc/redis/users.acl"
]

if len(items) != 1:
    raise SystemExit(
        "Redis users.acl mount is not unique"
    )

item = items[0]

if item.get("Type") != "bind":
    raise SystemExit(
        "Redis users.acl is not a host bind mount"
    )

print(item["Source"])
'
)"

acl_dir="$(dirname "${acl_source}")"
acl_name="$(basename "${acl_source}")"

back_hash="$(
  printf '%s' "${PROD_REDIS_BACK_PASSWORD}" |
  sha256sum |
  awk '{print $1}'
)"

ai_hash="$(
  printf '%s' "${PROD_REDIS_AI_PASSWORD}" |
  sha256sum |
  awk '{print $1}'
)"

helper() {
  "${docker_bin}" run \
    --rm \
    -i \
    --network none \
    --mount "type=bind,src=${acl_dir},dst=/acl" \
    --entrypoint /bin/sh \
    "${redis_image}" \
    "$@"
}

restore_acl() {
  printf '%s\n' "${acl_name}" |
  helper -eu -c '
    IFS= read -r acl_name

    file="/acl/${acl_name}"
    backup="/acl/.${acl_name}.pre-production-bootstrap"

    if [ -f "${backup}" ]; then
      mv "${backup}" "${file}"
    fi
  '
}

cleanup_backup() {
  printf '%s\n' "${acl_name}" |
  helper -eu -c '
    IFS= read -r acl_name

    rm -f \
      "/acl/.${acl_name}.pre-production-bootstrap"
  '
}

wait_redis_healthy() {
  local deadline
  local state

  deadline=$((SECONDS + 90))

  while (( SECONDS < deadline )); do
    state="$(
      "${docker_bin}" inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "${redis_container}" \
        2>/dev/null || true
    )"

    case "${state}" in
      healthy)
        return 0
        ;;
      unhealthy|exited|dead)
        echo \
          "Redis failed after ACL reload: state=${state}" \
          >&2
        return 1
        ;;
    esac

    sleep 2
  done

  echo \
    'Redis did not become healthy within 90 seconds' \
    >&2

  return 1
}

restart_redis() {
  "${docker_bin}" restart "${redis_container}" >/dev/null
  wait_redis_healthy
}

cleanup_probe_keys() {
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_BACK_PASSWORD}" \
    "${redis_container}" redis-cli --user prod_back --no-auth-warning \
    DEL prod:backend:bootstrap:probe >/dev/null 2>&1 || true
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_AI_PASSWORD}" \
    "${redis_container}" redis-cli --user prod_ai --no-auth-warning \
    DEL prod:ai:bootstrap:probe conversation:bootstrap:probe >/dev/null 2>&1 || true
}

printf '%s\n%s\n%s\n' \
  "${acl_name}" \
  "${back_hash}" \
  "${ai_hash}" |
helper -eu -c '
  IFS= read -r acl_name
  IFS= read -r back_hash
  IFS= read -r ai_hash

  file="/acl/${acl_name}"
  backup="/acl/.${acl_name}.pre-production-bootstrap"
  temp="/acl/.${acl_name}.tmp.$$"
  content="${temp}.content"

  [ -f "${file}" ]

  [ ! -e "${backup}" ] || {
    echo \
      "stale Redis ACL bootstrap backup exists: ${backup}" \
      >&2
    exit 66
  }

  cp -p "${file}" "${backup}"

  awk \
    "!/^user prod_back / && !/^user prod_ai /" \
    "${file}" \
    >"${content}"

  printf "%s\n" \
    "user prod_back on #${back_hash} ~prod:* +@read +@write +@keyspace +@connection +@scripting -@dangerous +info" \
    >>"${content}"

  printf "%s\n" \
    "user prod_ai on #${ai_hash} ~prod:ai:* ~conversation:* +@read +@write +@scripting -@dangerous" \
    >>"${content}"

  cat "${content}" >"${temp}"

  rm -f "${content}"

  mv "${temp}" "${file}"
'

committed=0

on_error() {
  local rc=$?

  trap - ERR INT TERM

  if [[ "${committed}" != 1 ]]; then
    cleanup_probe_keys
    restore_acl >/dev/null 2>&1 || true
    restart_redis >/dev/null 2>&1 || true
  fi

  exit "${rc}"
}

on_signal() {
  trap - ERR INT TERM

  if [[ "${committed}" != 1 ]]; then
    cleanup_probe_keys
    restore_acl >/dev/null 2>&1 || true
    restart_redis >/dev/null 2>&1 || true
  fi

  exit 130
}

trap on_error ERR
trap on_signal INT TERM

# users.acl is mounted read-only into Redis.
# There is no privileged Redis ACL admin user in the live contract.
# Persist the new hashed users in the host ACL file, then perform one
# controlled Redis restart so Redis reloads that file.
restart_redis

back_allowed="$(
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_BACK_PASSWORD}" \
    "${redis_container}" \
    redis-cli \
    --user prod_back \
    --no-auth-warning \
    --raw \
    SET prod:backend:bootstrap:probe ok
)"

[[ "${back_allowed}" == OK ]]

back_denied="$(
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_BACK_PASSWORD}" \
    "${redis_container}" \
    redis-cli \
    --user prod_back \
    --no-auth-warning \
    --raw \
    SET conversation:bootstrap:forbidden nope \
    2>&1 || true
)"

grep -Fq \
  NOPERM \
  <<<"${back_denied}"

ai_allowed="$(
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_AI_PASSWORD}" \
    "${redis_container}" \
    redis-cli \
    --user prod_ai \
    --no-auth-warning \
    --raw \
    SET prod:ai:bootstrap:probe ok
)"

[[ "${ai_allowed}" == OK ]]

ai_conversation_allowed="$(
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_AI_PASSWORD}" \
    "${redis_container}" \
    redis-cli \
    --user prod_ai \
    --no-auth-warning \
    --raw \
    SET conversation:bootstrap:probe ok
)"

[[ "${ai_conversation_allowed}" == OK ]]

ai_denied="$(
  "${docker_bin}" exec \
    -e REDISCLI_AUTH="${PROD_REDIS_AI_PASSWORD}" \
    "${redis_container}" \
    redis-cli \
    --user prod_ai \
    --no-auth-warning \
    --raw \
    SET prod:backend:forbidden nope \
    2>&1 || true
)"

grep -Fq \
  NOPERM \
  <<<"${ai_denied}"

printf '%s\n%s\n%s\n' \
  "${acl_name}" \
  "${back_hash}" \
  "${ai_hash}" |
helper -eu -c '
  IFS= read -r acl_name
  IFS= read -r back_hash
  IFS= read -r ai_hash

  file="/acl/${acl_name}"

  grep -Eq \
    "^user prod_back on #${back_hash} ~prod:\\* " \
    "${file}"

  grep -Eq \
    "^user prod_ai on #${ai_hash} ~prod:ai:\\* ~conversation:\\* " \
    "${file}"

  ! grep -Eq \
    "^user prod_back .*~conversation:\\*" \
    "${file}"
'

cleanup_probe_keys

cleanup_backup

committed=1

trap - ERR INT TERM

echo 'PRODUCTION_REDIS_ACL_PERSISTENCE=PASS'

mkdir -p "$(dirname "${evidence}")"
EVIDENCE="${evidence}" python3 - <<'PY'
import datetime,json,os,pathlib
path=pathlib.Path(os.environ['EVIDENCE'])
doc={
 'schemaVersion':'1.1.0','state':'READY','environment':'production','bootstrapMode':'fresh-isolated',
 'databases':{
   'business':{'name':'festa_prod_business','role':'festa_prod_back_app'},
   'ai':{'name':'festa_prod_ai','role':'festa_prod_ai_app'}},
 'redis':{
   'backUser':'prod_back','backKeyPattern':'prod:*','aiUser':'prod_ai','aiKeyPattern':'prod:ai:*',
   'conversationKeyPattern':'conversation:*',
   'persistence':'host-acl-file-hashed'},
 'demoDataCopied':False,
 'verifiedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')}
tmp=path.with_suffix('.tmp')
tmp.write_text(json.dumps(doc,indent=2)+'\n',encoding='utf-8')
tmp.replace(path)
PY
printf 'PRODUCTION_DATA_EVIDENCE=%s\n' "${evidence}"
echo 'PRODUCTION_DATA_BOOTSTRAP=PASS'
