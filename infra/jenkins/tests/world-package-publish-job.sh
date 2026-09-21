#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

security="${repo_root}/infra/jenkins/casc/security.yaml"
controller="${repo_root}/infra/jenkins/controller/compose.yaml"
jobs="${repo_root}/infra/jenkins/casc/jobs.yaml"
job="${repo_root}/infra/jenkins/jobs/gitlab-world-package-publish.groovy"
pipeline="${repo_root}/infra/jenkins/pipelines/world-package-publish.groovy"
validator="${repo_root}/infra/jenkins/scripts/validate-world-package-source.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

grep -q 'key: GITLAB_PACKAGE_WRITE_CREDENTIAL_ID' "${security}" \
  || fail "JCasC does not expose the package write credential ID"

grep -q \
  'GITLAB_PACKAGE_WRITE_CREDENTIAL_ID: ${GITLAB_PACKAGE_WRITE_CREDENTIAL_ID:-gitlab-package-write}' \
  "${controller}" \
  || fail "controller does not receive the package write credential ID"

grep -Fq \
  '/var/jenkins_home/job_dsl/gitlab-world-package-publish.groovy' \
  "${jobs}" \
  || fail "JCasC does not apply the World package publisher job"

grep -q "pipelineJob('festa-world-package-publish')" "${job}" \
  || fail "World package publisher job is missing"

grep -q "branches('\*/develop')" "${job}" \
  || fail "World package publisher is not pinned to develop"

grep -q \
  "scriptPath('infra/jenkins/pipelines/world-package-publish.groovy')" \
  "${job}" \
  || fail "World package job does not use its repository pipeline"

grep -q "agent { label 'deploy' }" "${pipeline}" \
  || fail "World package publication does not run on the deploy agent"

grep -q "lock(resource: 'deploy-dev-game')" "${pipeline}" \
  || fail "World publication does not serialize against game deployment"

grep -q 'GITLAB_PACKAGE_WRITE_CREDENTIAL_ID' "${pipeline}" \
  || fail "World pipeline does not select the package write credential"

grep -q 'string(' "${pipeline}" \
  || fail "World pipeline does not bind a Secret text credential"

grep -q 'validate-world-package-source.sh' "${pipeline}" \
  || fail "World pipeline does not gate on Demo known-good identity"

grep -q 'publish-world-release.sh' "${pipeline}" \
  || fail "World pipeline does not call the immutable publisher"

for forbidden in \
  'docker build' \
  'ci/build' \
  'ci/package' \
  'festa-unity/ci/build' \
  'package-local-image.sh'
do
  if grep -Fq "${forbidden}" "${pipeline}"; then
    fail "World publisher pipeline contains forbidden rebuild path: ${forbidden}"
  fi
done

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

mkdir -p \
  "${work}/state/demo/game" \
  "${work}/state/dev/batches/known-good"

sha='0123456789abcdef0123456789abcdef01234567'
image_ref="festa-game:${sha}"
content_id="sha256:$(printf 'a%.0s' {1..64})"
release_id="develop-${sha}-1"

cat >"${work}/state/demo/game/known-good.json" <<EOF_GAME_FIXTURE
{
  "schemaVersion": "1.0.0",
  "targetId": "demo/game",
  "releaseId": "${release_id}",
  "sourceCommit": "${sha}",
  "imageRef": "${image_ref}",
  "contentId": "${content_id}",
  "state": "KNOWN_GOOD",
  "approvedAt": "2026-09-19T00:00:00Z"
}
EOF_GAME_FIXTURE

cat >"${work}/state/dev/batches/known-good/environment.json" <<EOF_ENV_FIXTURE
{
  "schemaVersion": "1.0.0",
  "state": "KNOWN_GOOD",
  "components": {
    "game": {
      "releaseId": "${release_id}",
      "sourceCommit": "${sha}",
      "imageRef": "${image_ref}",
      "contentId": "${content_id}"
    }
  }
}
EOF_ENV_FIXTURE

result="$(
  ENVIRONMENT_STATE_DIR="${work}/state" \
  WORLD_SOURCE_COMMIT="${sha}" \
  WORLD_IMAGE_REF="${image_ref}" \
  WORLD_CONTENT_ID="${content_id}" \
  WORLD_PUBLISHED_BY='fixture-operator' \
  PYTHON_BIN=python3 \
    "${validator}"
)"

[[ "${result}" == "${release_id}" ]] \
  || fail "exact Demo known-good World identity was rejected"

if \
  ENVIRONMENT_STATE_DIR="${work}/state" \
  WORLD_SOURCE_COMMIT="${sha}" \
  WORLD_IMAGE_REF="${image_ref}" \
  WORLD_CONTENT_ID="sha256:$(printf '9%.0s' {1..64})" \
  WORLD_PUBLISHED_BY='fixture-operator' \
  PYTHON_BIN=python3 \
    "${validator}" \
    >/dev/null 2>&1
then
  fail "World source gate accepted another content ID"
fi

echo 'PASS: World publisher job uses a dedicated write credential and only preserves the exact Demo-known-good image without rebuilding'
