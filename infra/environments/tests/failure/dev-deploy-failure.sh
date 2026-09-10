#!/usr/bin/env bash
# 잘못된 dev 이미지가 Compose 변경 전에 거부되는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/dev-deploy-fixture.sh"
create_dev_deploy_fixture
trap destroy_dev_deploy_fixture EXIT

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
echo 'PASS: invalid dev release changes no dev, demo, or data container'
