#!/usr/bin/env bash
set -euo pipefail

: "${OBSERVABILITY_FAILED_SERVICE:?}" "${APP_PROBE_COMMAND:?}" "${CI_PROBE_COMMAND:?}"
[[ "${OBSERVABILITY_FAILED_SERVICE}" =~ ^(alloy|loki|prometheus|grafana)$ ]] || { echo 'invalid observability service' >&2; exit 64; }
bash -o pipefail -c "${APP_PROBE_COMMAND}"
bash -o pipefail -c "${CI_PROBE_COMMAND}"
echo "ISOLATION_OK failed_observability=${OBSERVABILITY_FAILED_SERVICE} app=PASS ci=PASS"
