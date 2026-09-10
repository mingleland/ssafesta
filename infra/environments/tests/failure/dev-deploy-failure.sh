#!/usr/bin/env bash
# 잘못된 dev 이미지가 Compose 변경 전에 거부되는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/dev-deploy-fixture.sh"
create_dev_deploy_fixture
trap destroy_dev_deploy_fixture EXIT

cp "${RELEASE_MANIFEST}" "${FIXTURE_ROOT}/candidate-manifest.json"
find "${FAKE_DOCKER_STATE}" -type f -exec sha256sum {} + | sort >"${FIXTURE_ROOT}/before"

if DEV_MOCK_COMPONENTS=ai bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" \
  --environment dev --component ai --release-manifest "${RELEASE_MANIFEST}" \
  >"${FIXTURE_ROOT}/mock.out" 2>&1; then
  echo 'unapproved mock component was accepted' >&2
  exit 1
fi
grep -q 'mock components lack approval: ai' "${FIXTURE_ROOT}/mock.out"
! grep -Eq 'compose .* (up|down)([[:space:]]|$)' "${FAKE_DOCKER_LOG}"

sed -i 's/sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc/' "${RELEASE_MANIFEST}"

if bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" \
  --environment dev --component back --release-manifest "${RELEASE_MANIFEST}" \
  >"${FIXTURE_ROOT}/deploy.out" 2>&1; then
  echo 'invalid image content ID was accepted' >&2
  exit 1
fi
grep -q 'image content ID mismatch' "${FIXTURE_ROOT}/deploy.out" || {
  echo 'deploy did not reject the invalid image for the expected reason' >&2
  exit 1
}

find "${FAKE_DOCKER_STATE}" -type f -exec sha256sum {} + | sort >"${FIXTURE_ROOT}/after"
cmp -s "${FIXTURE_ROOT}/before" "${FIXTURE_ROOT}/after" || { echo 'failed deploy changed container state' >&2; exit 1; }
! grep -Eq 'compose .* (up|down)([[:space:]]|$)' "${FAKE_DOCKER_LOG}"
cp "${FIXTURE_ROOT}/candidate-manifest.json" "${RELEASE_MANIFEST}"

old_manifest="${FIXTURE_ROOT}/old-manifest.json"
sed \
  -e 's/dev-0123456789abcdef0123456789abcdef01234567-1/dev-previous-release/' \
  -e 's/:new"/:old"/g' \
  -e 's/sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/g' \
  "${RELEASE_MANIFEST}" >"${old_manifest}"

batch_state="${FIXTURE_ROOT}/batch-state"
mkdir -p "${batch_state}/known-good"
cp "${old_manifest}" "${batch_state}/known-good/back.json"
cp "${old_manifest}" "${batch_state}/known-good/front.json"

cat >"${FIXTURE_ROOT}/verify-with-front-failure" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
bash "${REPO_ROOT}/infra/environments/scripts/verify-environment.sh" \
  --environment dev --component "${CI_COMPONENT}"
if [[ "${CI_COMPONENT}" == front && "${RELEASE_MANIFEST_PATH}" == "${FORCED_FAIL_MANIFEST}" ]]; then
  exit 1
fi
SH
chmod +x "${FIXTURE_ROOT}/verify-with-front-failure"

if DEV_BATCH_ID=multi-component-failure DEPLOY_COMPONENTS=back,front \
  RELEASE_MANIFEST_PATH="${RELEASE_MANIFEST}" CI_ARTIFACT_DIR="${FIXTURE_ROOT}/artifacts" \
  DEV_BATCH_STATE_DIR="${batch_state}" DEV_BACK_ENV_FILE="${COMPONENT_ENV_FILE}" \
  REPO_ROOT="${repo_root}" FORCED_FAIL_MANIFEST="${RELEASE_MANIFEST}" \
  VERIFY_COMPONENT_COMMAND="${FIXTURE_ROOT}/verify-with-front-failure" \
  bash "${repo_root}/infra/jenkins/scripts/deploy-dev-batch.sh"; then
  echo 'multi-component verification failure unexpectedly passed' >&2
  exit 1
fi

grep -Fqx "festa-dev-back-2|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|0|dev-previous-release" \
  "${FAKE_DOCKER_STATE}/festa-dev__back"
grep -Fqx "festa-dev-front-2|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|0|dev-previous-release" \
  "${FAKE_DOCKER_STATE}/festa-dev__front"
grep -q '"status": "ROLLED_BACK"' "${FIXTURE_ROOT}/artifacts/dev-batch-result.json"
! grep -q '"status": "ACTIVE"' "${FIXTURE_ROOT}/artifacts/dev-batch-result.json"
grep -q 'compose.*--project-name festa-dev.*up -d --no-deps.*back' "${FAKE_DOCKER_LOG}"
grep -q 'compose.*--project-name festa-dev.*up -d --no-deps.*front' "${FAKE_DOCKER_LOG}"
! grep -Eq 'compose .* down([[:space:]]|$)' "${FAKE_DOCKER_LOG}"
echo 'PASS: invalid release makes no change and multi-component verification failure restores the batch snapshot'
