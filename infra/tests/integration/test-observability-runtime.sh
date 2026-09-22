#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
compose="${repo_root}/infra/observability/compose.yaml"

command -v docker >/dev/null || { echo 'SKIP: Docker CLI is unavailable'; exit 0; }
docker info >/dev/null 2>&1 || { echo 'SKIP: Docker daemon is unavailable'; exit 0; }

resource_prefix="festa-observability-test-$RANDOM-$RANDOM"
project="${resource_prefix}"
env_file="$(mktemp)"
cleanup() {
  docker compose --project-name "${project}" --env-file "${env_file}" -f "${compose}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "${env_file}"
}
trap cleanup EXIT

printf '%s\n' \
  "OBSERVABILITY_RESOURCE_PREFIX=${resource_prefix}" \
  'GRAFANA_ADMIN_USER=runtime-test' \
  'GRAFANA_ADMIN_PASSWORD=runtime-test-only' \
  'LOKI_RETENTION_PERIOD=24h' \
  'PROMETHEUS_RETENTION_TIME=24h' \
  'PROMETHEUS_RETENTION_SIZE=1GB' \
  'ALLOY_CPU_LIMIT=0.2' \
  'ALLOY_MEMORY_LIMIT=128M' \
  'ALLOY_CADVISOR_CPU_LIMIT=0.2' \
  'ALLOY_CADVISOR_MEMORY_LIMIT=256M' \
  'LOKI_CPU_LIMIT=0.3' \
  'LOKI_MEMORY_LIMIT=384M' \
  'PROMETHEUS_CPU_LIMIT=0.3' \
  'PROMETHEUS_MEMORY_LIMIT=384M' \
  'GRAFANA_CPU_LIMIT=0.2' \
  'GRAFANA_MEMORY_LIMIT=256M' \
  'GRAFANA_LOOPBACK_PORT=0' >"${env_file}"

docker compose --project-name "${project}" --env-file "${env_file}" -f "${compose}" up -d --wait --wait-timeout 90 alloy loki prometheus grafana
loki_id="$(docker compose --project-name "${project}" --env-file "${env_file}" -f "${compose}" ps -q loki)"
[[ -n "${loki_id}" ]]
[[ "$(docker inspect --format '{{.Config.User}}' "${loki_id}")" == '10001:10001' ]]
echo 'PASS: Alloy parses and the fresh Loki volume starts with non-root runtime ownership'
