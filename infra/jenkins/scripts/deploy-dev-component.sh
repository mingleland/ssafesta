#!/usr/bin/env bash
# Jenkins deploy agent에서 이미지 전달과 infra-002 dev 배포·검증을 한 번에 수행한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
: "${CI_COMPONENT:?}" "${CI_ARTIFACT_DIR:?}" "${RELEASE_ID:?}" "${RELEASE_MANIFEST_PATH:?}"
: "${BUILD_DOCKER_HOST:?}" "${DOCKER_HOST:?}" "${ENVIRONMENT_STATE_DIR:?}"

read -r image_ref content_id < <(
  python - "${RELEASE_MANIFEST_PATH}" "${CI_COMPONENT}" <<'PY'
import json,pathlib,sys
release=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
matches=[item for item in release['components'] if item['name']==sys.argv[2]]
if len(matches) != 1:
    raise SystemExit(f'release component count must be one: {sys.argv[2]}')
print(matches[0]['imageRef'],matches[0]['contentId'])
PY
)
content_id="${content_id%$'\r'}"

IMAGE_REF="${image_ref}" CONTENT_ID="${content_id}" DEPLOY_DOCKER_HOST="${DOCKER_HOST}" \
  bash "${repo_root}/infra/deploy/scripts/transfer-local-image.sh"

bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" \
  --environment dev --component "${CI_COMPONENT}" --release-manifest "${RELEASE_MANIFEST_PATH}"
mkdir -p "${CI_ARTIFACT_DIR}"
bash "${repo_root}/infra/environments/scripts/verify-environment.sh" \
  --environment dev --component "${CI_COMPONENT}" | tee "${CI_ARTIFACT_DIR}/environment-verification.json"

export COMPONENT_VERIFY_COMMAND="${DOCKER_BIN:-docker} ps --filter label=com.docker.compose.project=festa-dev --filter label=com.docker.compose.service=${CI_COMPONENT} --filter health=healthy --format '{{.Label \"com.docker.compose.service\"}}' | grep -qx ${CI_COMPONENT}"
bash "${repo_root}/ci/verify"

release_dir="${ENVIRONMENT_STATE_DIR}/dev/releases/${RELEASE_ID}"
mkdir -p "${release_dir}"
cp "${RELEASE_MANIFEST_PATH}" "${release_dir}/release-manifest.json"
