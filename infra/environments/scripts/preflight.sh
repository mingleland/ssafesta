#!/usr/bin/env bash
# 실행 환경 단계마다 필요한 도구·운영 입력·Secret Reference를 변경 전에 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
source "${repo_root}/infra/environments/tests/lib/assert.sh"

usage() {
  echo "usage: $0 --manifest <path> [--stage contract|data|ingress|storage|all] [--check-only]" >&2
}

manifest=''
stage=all
while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest) manifest="${2:-}"; shift 2 ;;
    --stage) stage="${2:-}"; shift 2 ;;
    --check-only) shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; fail "unknown argument: $1" ;;
  esac
done

[[ -n "${manifest}" ]] || { usage; fail '--manifest is required'; }
case "${stage}" in contract|data|ingress|storage|all) ;; *) fail "unknown stage: ${stage}" ;; esac

assert_file "${manifest}"
assert_command bash

validator="${script_dir}/validate-json-schema.sh"
schema="${repo_root}/specs/infra-002-environments/contracts/environment-manifest.schema.json"
bash "${validator}" "${schema}" "${manifest}" >/dev/null
pass 'environment manifest contract'

[[ "${stage}" == contract ]] && exit 0

require_values() {
  local name
  for name in "$@"; do
    [[ -n "${!name:-}" ]] || fail "${name} is required for stage ${stage}"
  done
}

version_at_least() {
  local actual="$1" minimum="$2"
  [[ "$(printf '%s\n%s\n' "${minimum}" "${actual}" | sort -V | head -n1)" == "${minimum}" ]]
}

assert_command docker
docker_min="$(. "${repo_root}/infra/versions.env"; printf '%s' "${DOCKER_ENGINE_MIN_VERSION}")"
compose_min="$(. "${repo_root}/infra/versions.env"; printf '%s' "${DOCKER_COMPOSE_MIN_VERSION}")"
docker_version="$(docker version --format '{{.Client.Version}}')"
compose_version="$(docker compose version --short)"
version_at_least "${docker_version}" "${docker_min}" || fail "Docker ${docker_min}+ is required; found ${docker_version}"
version_at_least "${compose_version}" "${compose_min}" || fail "Docker Compose ${compose_min}+ is required; found ${compose_version}"
pass 'Docker and Compose versions'

if [[ "${stage}" == data || "${stage}" == all ]]; then
  assert_command psql
  assert_command redis-cli
  require_values EC2_VCPU EC2_RAM_MB EC2_DISK_GB EC2_OS \
    POSTGRES_ADMIN_CREDENTIAL_REF POSTGRES_DEV_BACK_CREDENTIAL_REF \
    POSTGRES_DEV_AI_CREDENTIAL_REF POSTGRES_DEMO_BACK_CREDENTIAL_REF \
    POSTGRES_DEMO_AI_CREDENTIAL_REF REDIS_ADMIN_CREDENTIAL_REF
fi

if [[ "${stage}" == ingress || "${stage}" == all ]]; then
  assert_command curl
  assert_command openssl
  require_values EC2_PUBLIC_IP SG_CHANGE_OWNER SG_80_443_READY ROOT_DOMAIN TLS_PRIVATE_KEY_REF
  [[ "${SG_80_443_READY}" == true ]] || fail 'SG_80_443_READY must be true before ingress deployment'
fi

if [[ "${stage}" == storage || "${stage}" == all ]]; then
  assert_command curl
  assert_command jq
  require_values CLOUDFLARE_ACCOUNT_ID_REF CLOUDFLARE_ANALYTICS_TOKEN_REF \
    R2_DOCUMENT_SIGNER_CREDENTIAL_REF R2_DOCUMENT_READER_CREDENTIAL_REF \
    R2_BACKUP_WRITER_CREDENTIAL_REF R2_RESTORE_READER_CREDENTIAL_REF
fi

pass "preflight stage ${stage}"
