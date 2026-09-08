#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
source "${repo_root}/infra/environments/tests/lib/dev-deploy-fixture.sh"
create_dev_deploy_fixture
trap destroy_dev_deploy_fixture EXIT

export CI_COMPONENT=back CI_BRANCH=develop CI_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567
export CI_RUN_ID=jenkins-dev-test CI_ARTIFACT_DIR="${FIXTURE_ROOT}/artifacts"
export DEPLOY_TARGET=dev-back RELEASE_ID=dev-0123456789abcdef0123456789abcdef01234567-1
export RELEASE_MANIFEST_PATH="${RELEASE_MANIFEST}"
export BUILD_DOCKER_HOST=source DOCKER_HOST=target

verify_command="${DOCKER_BIN} ps --filter label=com.docker.compose.project=festa-dev --filter label=com.docker.compose.service=back --filter health=healthy --format '{{.Label \"com.docker.compose.service\"}}' | grep -qx back"
bash -o pipefail -c "${verify_command}"

if ! bash "${repo_root}/infra/jenkins/scripts/deploy-dev-component.sh"; then
  [[ ! -f "${CI_ARTIFACT_DIR}/verification-result.json" ]] || cat "${CI_ARTIFACT_DIR}/verification-result.json" >&2
  cat "${FAKE_DOCKER_LOG}" >&2
  exit 1
fi
[[ -f "${CI_ARTIFACT_DIR}/environment-verification.json" ]]
[[ -f "${CI_ARTIFACT_DIR}/verification-result.json" ]]
[[ -f "${ENVIRONMENT_STATE_DIR}/dev/releases/${RELEASE_ID}/release-manifest.json" ]]
grep -q 'targetChanged' "${CI_ARTIFACT_DIR}/environment-verification.json"
grep -q '"finalDecision": "PASS"' "${CI_ARTIFACT_DIR}/verification-result.json"
echo 'PASS: Jenkins deploy adapter transfers, deploys, verifies, and preserves its manifest'
