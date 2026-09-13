#!/usr/bin/env bash
# Shared guardrails for PostgreSQL backups stored only in the private R2 backup bucket.
set -euo pipefail

backup_die() { printf 'ERROR: %s\n' "$*" >&2; exit 64; }
backup_require() { [[ -n "${!1:-}" ]] || backup_die "$1 is required"; }

backup_init() {
  backup_require R2_BACKUP_ENDPOINT
  backup_require R2_BACKUP_BUCKET
  backup_require AWS_ACCESS_KEY_ID
  backup_require AWS_SECRET_ACCESS_KEY
  backup_require PGPASSWORD
  : "${AWS_DEFAULT_REGION:=auto}"
  : "${AWS_CLI:=aws}"
  : "${DOCKER_BIN:=docker}"
  : "${PYTHON_BIN:=python3}"
  : "${POSTGRES_CONTAINER:=festa-data-postgres-1}"
  : "${BACKUP_STATE_DIR:=/var/lib/festa-environments/postgres-backups}"
  command -v "${AWS_CLI}" >/dev/null 2>&1 || backup_die 'aws CLI is required'
  command -v "${DOCKER_BIN}" >/dev/null 2>&1 || backup_die 'docker is required'
  command -v "${PYTHON_BIN}" >/dev/null 2>&1 || backup_die 'python3 is required'
  mkdir -p "${BACKUP_STATE_DIR}"
  AWS_CONFIG_FILE="${BACKUP_STATE_DIR}/awscli-r2.conf"
  local config_tmp="${AWS_CONFIG_FILE}.$$"
  printf '[default]\nregion = %s\ns3 =\n    addressing_style = path\n' "${AWS_DEFAULT_REGION}" >"${config_tmp}"
  mv "${config_tmp}" "${AWS_CONFIG_FILE}"
  export AWS_DEFAULT_REGION AWS_CONFIG_FILE
}

backup_aws() { "${AWS_CLI}" --endpoint-url "${R2_BACKUP_ENDPOINT}" s3 "$@"; }
backup_s3api() { "${AWS_CLI}" --endpoint-url "${R2_BACKUP_ENDPOINT}" s3api "$@"; }
backup_docker_exec() { "${DOCKER_BIN}" exec -e PGPASSWORD "${POSTGRES_CONTAINER}" "$@"; }
backup_psql() { backup_docker_exec psql -U festa_admin -d "$1" -v ON_ERROR_STOP=1 "${@:2}"; }
backup_python() { "${PYTHON_BIN}" "$@"; }

backup_databases() {
  case "$1" in
    dev) printf '%s\n' festa_dev_business festa_dev_ai ;;
    demo) printf '%s\n' festa_demo_business festa_demo_ai ;;
    *) backup_die 'environment must be dev or demo' ;;
  esac
}

backup_safe_id() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || backup_die "invalid identifier: $1"; }

backup_target_database() {
  local target="$1" source="$2" suffix
  [[ "$target" =~ ^disposable-[a-z0-9][a-z0-9-]{0,62}$ ]] || backup_die 'target must start with disposable-'
  suffix="${source#festa_}"; suffix="${suffix//_/-}"
  local database="festa_restore_${target//-/_}_${suffix//-/_}"
  ((${#database} <= 63)) || backup_die 'generated restore database name exceeds PostgreSQL limit'
  printf '%s\n' "$database"
}
