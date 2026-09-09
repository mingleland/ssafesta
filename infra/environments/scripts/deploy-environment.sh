#!/usr/bin/env bash
# infra-001 릴리스를 선택한 환경의 한 component에만 적용한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
source "${repo_root}/infra/environments/tests/lib/assert.sh"
docker_bin="${DOCKER_BIN:-docker}"

usage() {
  echo "usage: $0 --environment dev --component <ai|back|front|game> --release-manifest <path>" >&2
}

environment=''
component=''
release_manifest=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment) environment="${2:-}"; shift 2 ;;
    --component) component="${2:-}"; shift 2 ;;
    --release-manifest) release_manifest="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; fail "unknown argument: $1" ;;
  esac
done

[[ "${environment}" == dev ]] || { usage; fail 'only dev component deployment is implemented'; }
case "${component}" in ai|back|front|game) ;; *) usage; fail 'component must be ai, back, front, or game' ;; esac
assert_file "${release_manifest}"

python_bin="$(resolve_python)" || fail 'Python 3 is required'
release_schema="${repo_root}/specs/infra-001-ci-cd-pipelines/contracts/release-manifest.schema.json"
bash "${script_dir}/validate-json-schema.sh" "${release_schema}" "${release_manifest}" >/dev/null

IFS=$'\t' read -r release_id branch image_ref content_id source_commit < <(
  "${python_bin}" - "${release_manifest}" "${component}" <<'PY'
import json, pathlib, sys
release = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
matches = [item for item in release["components"] if item["name"] == sys.argv[2]]
if not matches:
    raise SystemExit(f"release does not contain component: {sys.argv[2]}")
if len(matches) != 1:
    raise SystemExit(f"release contains duplicate component: {sys.argv[2]}")
component = matches[0]
print(release["releaseId"], release["scm"]["branch"], component["imageRef"], component["contentId"], component["sourceCommit"], sep="\t")
PY
)
source_commit="${source_commit%$'\r'}"
[[ "${DEV_MOCK_COMPONENTS:-}" =~ ^$|^(ai|back|front|game)(,(ai|back|front|game))*$ ]] || fail 'DEV_MOCK_COMPONENTS must be a comma-separated component list'

if [[ "${DEV_BATCH_ROLLBACK:-0}" == 1 ]]; then
  batch_state_root="${DEV_BATCH_STATE_ROOT:-${ENVIRONMENT_STATE_DIR:-/tmp/festa-environments}/dev/batches}"
  expected_snapshot="${batch_state_root}/${DEV_BATCH_ID:-}/before/${component}.json"
  [[ -n "${DEV_BATCH_ID:-}" && -f "${expected_snapshot}" ]] || fail 'dev batch rollback requires its captured snapshot'
  [[ "$(readlink -f -- "${release_manifest}")" == "$(readlink -f -- "${expected_snapshot}")" ]] || fail 'dev batch rollback manifest is not the captured snapshot'
else
  CI_BRANCH="${CI_BRANCH:-${branch}}" FRESHNESS_EXPECTED_SHA="${source_commit}" \
    bash "${repo_root}/infra/jenkins/scripts/freshness.sh" >/dev/null
fi

actual_content_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}")"
[[ "${actual_content_id}" == "${content_id}" ]] || fail "image content ID mismatch: expected=${content_id} actual=${actual_content_id}"

case "${component}" in
  ai|back)
    : "${COMPONENT_ENV_FILE:?COMPONENT_ENV_FILE is required}"
    : "${INTERNAL_SPRING_TO_AI_TOKENS:?INTERNAL_SPRING_TO_AI_TOKENS is required}"
    : "${INTERNAL_AI_TO_SPRING_TOKENS:?INTERNAL_AI_TO_SPRING_TOKENS is required}"
    assert_file "${COMPONENT_ENV_FILE}"
    "${docker_bin}" network inspect festa-data-private >/dev/null
    ;;
  front)
    : "${PUBLIC_API_BASE_URL:?PUBLIC_API_BASE_URL is required}"
    ;;
  game)
    : "${CONNECTION_TOKEN_SECRET_FILE:?CONNECTION_TOKEN_SECRET_FILE is required}"
    [[ -r "${CONNECTION_TOKEN_SECRET_FILE}" ]] || fail 'game connection token Secret file is not readable'
    if ! decoded_bytes="$(base64 -d <"${CONNECTION_TOKEN_SECRET_FILE}" 2>/dev/null | wc -c | tr -d '[:space:]')"; then
      fail 'game connection token Secret must be valid Base64'
    fi
    (( decoded_bytes >= 32 )) || fail 'game connection token Secret must decode to at least 32 bytes'
    ;;
esac

environment_manifest="${repo_root}/infra/environments/config/manifests/dev.json"
bash "${script_dir}/preflight.sh" --manifest "${environment_manifest}" --stage contract --check-only >/dev/null
"${python_bin}" - "${environment_manifest}" "${DEV_MOCK_COMPONENTS:-}" <<'PY'
import json, pathlib, sys
manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
selected = {item for item in sys.argv[2].split(",") if item}
approved = {item["component"] for item in manifest.get("mockAdapters", []) if item.get("approvalRef")}
unapproved = selected - approved
if unapproved:
    raise SystemExit(f"mock components lack approval: {','.join(sorted(unapproved))}")
PY

snapshot() {
  local output="$1" project entry container service details
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

state_dir="${ENVIRONMENT_STATE_DIR:-/tmp/festa-environments}/dev/${component}"
mkdir -p "${state_dir}"
snapshot "${state_dir}/before.tsv"

export COMPONENT_IMAGE_REF="${image_ref}" RELEASE_ID="${release_id}" SOURCE_COMMIT="${source_commit}"
base="${repo_root}/infra/environments/compose/dev/base.yaml"
overlay="${repo_root}/infra/environments/compose/dev/${component}.yaml"
compose=("${docker_bin}" compose --project-name festa-dev --file "${base}" --file "${overlay}" --profile "${component}")
"${compose[@]}" config --quiet
"${compose[@]}" up -d --no-deps --wait "${component}"
snapshot "${state_dir}/after.tsv"
printf '%s\t%s\t%s\t%s\t%s\n' "${release_id}" "${image_ref}" "${content_id}" "${source_commit}" "${DEV_MOCK_COMPONENTS:-}" \
  >"${state_dir}/deployment.tsv"

printf '{"environment":"dev","component":"%s","releaseId":"%s","imageRef":"%s"}\n' \
  "${component}" "${release_id}" "${image_ref}"
