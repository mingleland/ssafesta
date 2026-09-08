#!/usr/bin/env bash
# component별 dev 배포가 다른 dev·demo·data 컨테이너를 재생성하지 않는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/dev-deploy-fixture.sh"
create_dev_deploy_fixture
trap destroy_dev_deploy_fixture EXIT

snapshot_non_target() {
  local target="$1" output="$2" project service row
  : >"${output}"
  for project in festa-dev festa-demo festa-data; do
    for row in "${FAKE_DOCKER_STATE}/${project}"__*; do
      [[ -f "${row}" ]] || continue
      service="${row##*__}"
      [[ "${project}:${service}" == "festa-dev:${target}" ]] && continue
      printf '%s:%s=%s\n' "${project}" "${service}" "$(cat "${row}")" >>"${output}"
    done
  done
  sort -o "${output}" "${output}"
}

for component in ai back front game; do
  before="${FIXTURE_ROOT}/${component}-before"
  after="${FIXTURE_ROOT}/${component}-after"
  snapshot_non_target "${component}" "${before}"
  bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" \
    --environment dev --component "${component}" --release-manifest "${RELEASE_MANIFEST}"
  bash "${repo_root}/infra/environments/scripts/verify-environment.sh" \
    --environment dev --component "${component}"
  snapshot_non_target "${component}" "${after}"
  cmp -s "${before}" "${after}" || { echo "non-target changed during ${component} deploy" >&2; exit 1; }
done

IFS='|' read -r container image restart release <"${FAKE_DOCKER_STATE}/festa-data__redis"
printf '%s|%s|1|%s\n' "${container}" "${image}" "${release:-old-release}" >"${FAKE_DOCKER_STATE}/festa-data__redis"
if bash "${repo_root}/infra/environments/scripts/verify-environment.sh" \
  --environment dev --component game >"${FIXTURE_ROOT}/tamper.out" 2>&1; then
  echo 'verification accepted a changed data container' >&2
  exit 1
fi
grep -q 'non-target container changed: festa-data/redis' "${FIXTURE_ROOT}/tamper.out"

grep -q 'compose.*--project-name festa-dev.*up -d --no-deps.*ai' "${FAKE_DOCKER_LOG}"
grep -q 'compose.*--project-name festa-dev.*up -d --no-deps.*back' "${FAKE_DOCKER_LOG}"
grep -q 'compose.*--project-name festa-dev.*up -d --no-deps.*front' "${FAKE_DOCKER_LOG}"
grep -q 'compose.*--project-name festa-dev.*up -d --no-deps.*game' "${FAKE_DOCKER_LOG}"
! grep -Eq 'compose .* down([[:space:]]|$)' "${FAKE_DOCKER_LOG}"
echo 'PASS: dev component deployments preserve every non-target container'
