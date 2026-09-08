#!/usr/bin/env bash
# 직전 dev component 배포가 대상만 변경하고 공용 데이터 상태를 보존했는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
source "${repo_root}/infra/environments/tests/lib/assert.sh"
docker_bin="${DOCKER_BIN:-docker}"

usage() {
  echo "usage: $0 --environment dev --component <ai|back|front|game>" >&2
}

environment=''
component=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment="${2:-}"; shift 2 ;;
    --component) component="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; fail "unknown argument: $1" ;;
  esac
done
[[ "${environment}" == dev ]] || { usage; fail 'only dev component verification is implemented'; }
case "${component}" in ai|back|front|game) ;; *) usage; fail 'component must be ai, back, front, or game' ;; esac

state_dir="${ENVIRONMENT_STATE_DIR:-/tmp/festa-environments}/dev/${component}"
before="${state_dir}/before.tsv"
deployed_after="${state_dir}/after.tsv"
deployment="${state_dir}/deployment.tsv"
assert_file "${before}"
assert_file "${deployed_after}"
assert_file "${deployment}"
IFS=$'\t' read -r release_id image_ref content_id source_commit mock_components <"${deployment}"

snapshot() {
  local output="$1" project container service details
  : >"${output}"
  for project in festa-dev festa-demo festa-data; do
    while IFS='|' read -r container service; do
      [[ -n "${container}" && -n "${service}" ]] || continue
      details="$(${docker_bin} inspect --format '{{.Image}}|{{.RestartCount}}|{{index .Config.Labels "com.ssafy-festa.release-id"}}' "${container}")"
      printf '%s|%s|%s|%s\n' "${project}" "${service}" "${container}" "${details}" >>"${output}"
    done < <("${docker_bin}" ps -a --filter "label=com.docker.compose.project=${project}" --format '{{.Names}}|{{.Label "com.docker.compose.service"}}')
  done
  sort -o "${output}" "${output}"
}

current="$(mktemp)"
trap 'rm -f -- "${current}"' EXIT
snapshot "${current}"

target_before="$(awk -F'|' -v component="${component}" '$1=="festa-dev" && $2==component {print; exit}' "${before}")"
target_after="$(awk -F'|' -v component="${component}" '$1=="festa-dev" && $2==component {print; exit}' "${current}")"
[[ -n "${target_after}" ]] || fail "deployed component is missing: ${component}"
[[ "$(cut -d'|' -f4 <<<"${target_after}")" == "${content_id}" ]] || fail 'deployed image content ID does not match release'
[[ "$(cut -d'|' -f6 <<<"${target_after}")" == "${release_id}" ]] || fail 'deployed release label does not match release'
[[ -z "${target_before}" || "${target_before}" != "${target_after}" ]] || fail 'target component did not change'

while IFS= read -r row; do
  [[ -n "${row}" ]] || continue
  row_project="${row%%|*}"
  row_service="$(cut -d'|' -f2 <<<"${row}")"
  [[ "${row_project}:${row_service}" == "festa-dev:${component}" ]] && continue
  grep -Fqx -- "${row}" "${current}" || fail "non-target container changed: ${row_project}/${row_service}"
done <"${before}"

printf '{"environment":"dev","component":"%s","releaseId":"%s","targetChanged":true,"nonTargetRestarts":0,"mockComponents":"%s","dataContinuous":true}\n' \
  "${component}" "${release_id}" "${mock_components:-}"
