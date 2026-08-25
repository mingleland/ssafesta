#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

commit=0123456789abcdef0123456789abcdef01234567
for component in ai back front game; do
  cat >"${work_dir}/${component}.json" <<JSON
{"schemaVersion":"1.0.0","component":"${component}","sourceCommit":"${commit}","storageMode":"local-docker","imageRef":"festa-${component}:${commit}","contentId":"sha256:$(printf '%064d' 1)"}
JSON
done

export RELEASE_ID="develop-${commit}-1"
export SCM_PROVIDER=github SCM_REPOSITORY=ssafy/festa SCM_BRANCH=develop CI_COMMIT_SHA="${commit}"
export JENKINS_JOB=festa-develop JENKINS_BUILD_NUMBER=1 JENKINS_BUILD_URL=https://ci.example.invalid/job/festa/1/
export ROLLBACK_CLASSIFICATION=SAFE DATA_CHANGE=none DB_SCHEMA_CHANGED=false SECRET_OR_CONFIG_CHANGED=false
export COMPONENT_METADATA_DIR="${work_dir}" RELEASE_MANIFEST_PATH="${work_dir}/release.json"

bash "${repo_root}/infra/deploy/scripts/build-release-manifest.sh"
bash "${repo_root}/infra/jenkins/scripts/validate-contracts.sh" release "${RELEASE_MANIFEST_PATH}"

python "${script_dir}/../helpers/assert-json.py" "${RELEASE_MANIFEST_PATH}" \
  'len(document["components"]) == 4' \
  'set(item["name"] for item in document["components"]) == {"ai","back","front","game"}'

cat >"${work_dir}/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1 $2" == 'image inspect' ]]; then printf 'sha256:%064d\n' 1
elif [[ "$1" == compose ]]; then printf '%s\n' "$*" >>"${FAKE_DOCKER_LOG}"
else echo "unexpected docker arguments: $*" >&2; exit 64
fi
SH
chmod +x "${work_dir}/docker"; export FAKE_DOCKER_LOG="${work_dir}/docker.log"
export DOCKER_BIN="${work_dir}/docker" COMPOSE_FILE="${repo_root}/infra/deploy/compose/integration/compose.yaml" COMPOSE_PROJECT=festa-integration
export BACK_BASE_URL=http://back:8080 AI_BASE_URL=http://ai:8000 PUBLIC_API_BASE_URL=http://front.invalid/api
bash "${repo_root}/infra/deploy/scripts/deploy-release.sh"
grep -q 'up -d --wait ai back front game' "${FAKE_DOCKER_LOG}"
[[ ! -e "${work_dir}/target-state.json" ]]

export DEPLOY_TARGET=integration-develop CI_ARTIFACT_DIR="${work_dir}/evidence"
export VERIFY_WEB_COMMAND=true VERIFY_LOGIN_COMMAND=true VERIFY_WORLD_COMMAND=true VERIFY_AI_COMMAND=true
bash "${repo_root}/infra/deploy/scripts/verify-release.sh"
bash "${repo_root}/infra/jenkins/scripts/validate-contracts.sh" verification "${CI_ARTIFACT_DIR}/verification-result.json"
grep -q '"finalDecision": "PASS"' "${CI_ARTIFACT_DIR}/verification-result.json"

echo 'PASS: develop release requires all component identities and ordered verification'
