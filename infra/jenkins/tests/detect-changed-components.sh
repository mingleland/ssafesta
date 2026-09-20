#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
detector="${repo_root}/infra/jenkins/scripts/detect-changed-components.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
cd "${tmp}"
git init -q
git config user.email ci@example.invalid
git config user.name ci
mkdir -p docs
printf 'base\n' > docs/base.md
git add .
git commit -qm base

commit_paths() {
  local path
  for path in "$@"; do
    mkdir -p "$(dirname "${path}")"
    printf '%s\n' "${RANDOM}" > "${path}"
  done
  git add .
  git commit -qm change
}

# 네 축 계약: validation / build / deploy / gameBuildRequired (+reason). components 는 validation 의 별칭이다.
assert_selection() {
  local output="$1" expected_validation="$2" expected_build="$3" expected_deploy="$4" expected_reason="$5" expected_game_build="$6"
  python3 - "${output}" "${expected_validation}" "${expected_build}" "${expected_deploy}" "${expected_reason}" "${expected_game_build}" <<'PY'
import json, sys
actual = json.loads(sys.argv[1])
assert actual["eventKind"] == "jenkins_develop_push"
assert actual["version"] == 2
assert actual["components"] == actual["validationComponents"], "components must alias validationComponents"
assert actual["validationComponents"] == json.loads(sys.argv[2]), ("validation", actual["validationComponents"])
assert actual["buildComponents"] == json.loads(sys.argv[3]), ("build", actual["buildComponents"])
assert actual["deployComponents"] == json.loads(sys.argv[4]), ("deploy", actual["deployComponents"])
assert sys.argv[5] in actual["reasons"], ("reason", actual["reasons"])
assert actual["gameBuildRequired"] is (sys.argv[6] == "true"), ("game", actual["gameBuildRequired"])
assert isinstance(actual["sharedCiChanged"], bool)
# build 에 game 이 있으면 반드시 gameBuildRequired 다 — ci_shared 만으로 Unity 를 돌리지 않는다.
assert not ("game" in actual["buildComponents"] and not actual["gameBuildRequired"])
PY
}

run_case() {
  local expected_validation="$1" expected_build="$2" expected_deploy="$3" expected_reason="$4" expected_game_build="$5"
  shift 5
  local base head output
  base="$(git rev-parse HEAD)"
  commit_paths "$@"
  head="$(git rev-parse HEAD)"
  output="$(BRANCH_NAME=develop "${detector}" --base "${base}" --head "${head}")"
  assert_selection "${output}" "${expected_validation}" "${expected_build}" "${expected_deploy}" "${expected_reason}" "${expected_game_build}"
}

ALL='["ai","back","front","game"]'
APPS='["ai","back","front"]'

# 단일 컴포넌트
run_case '["ai"]' '["ai"]' '["ai"]' component-source false festa-ai/app/main.py
run_case '["back"]' '["back"]' '["back"]' component-source false backend/src/main/java/App.java
run_case '["front"]' '["front"]' '["front"]' component-source false festa-frontend/src/main.tsx
run_case '["game"]' '["game"]' '["game"]' component-source true festa-unity/Assets/main.cs
run_case '["game"]' '["game"]' '["game"]' component-source true infra/unity-server/scripts/deploy-game.sh
run_case '["back","front"]' '["back","front"]' '["back","front"]' component-source false backend/src/main/java/Multi.java festa-frontend/src/multi.tsx
run_case '["back"]' '["back"]' '["back"]' component-deploy-config false infra/environments/compose/demo/back.yaml

# app + ci_only_shared: 검증은 넓어지고 빌드·배포는 그 app 만. Unity 는 돌지 않는다.
run_case "${ALL}" '["front"]' '["front"]' shared-ci false festa-frontend/src/x.tsx Jenkinsfile
run_case "${ALL}" '["back"]' '["back"]' shared-ci false backend/src/main/java/X.java infra/jenkins/scripts/deploy-dev-batch.sh
run_case "${ALL}" '["ai"]' '["ai"]' shared-ci false festa-ai/app/x.py .gitlab-ci.yml
run_case "${ALL}" '["game"]' '["game"]' shared-ci true festa-unity/Assets/x.cs infra/jenkins/pipelines/develop.groovy

# app + runtime_shared: app 3종 전부 재빌드·재배포.
run_case "${ALL}" "${APPS}" "${APPS}" runtime-shared false festa-frontend/src/x.tsx infra/environments/compose/demo/base.yaml

# ci_only_shared 만: 검증 4, 빌드·배포 없음.
run_case "${ALL}" '[]' '[]' shared-ci false Jenkinsfile
run_case "${ALL}" '[]' '[]' shared-ci false infra/jenkins/pipelines/develop.groovy
run_case "${ALL}" '[]' '[]' shared-ci false infra/jenkins/scripts/validate-production-promotion.sh
run_case "${ALL}" '[]' '[]' shared-ci false infra/deploy/scripts/activate-production-release.sh
run_case "${ALL}" '[]' '[]' shared-ci false infra/.env.example infra/environments/nginx/sites/demo.conf.template

# runtime_shared 만: app 3종 재빌드·재배포. Unity 는 versions.env 처럼 game build input 일 때만.
run_case "${ALL}" "${APPS}" "${APPS}" runtime-shared false infra/environments/compose/demo/base.yaml
run_case "${ALL}" "${APPS}" "${APPS}" runtime-shared false infra/environments/scripts/deploy-environment.sh
run_case "${ALL}" "${ALL}" "${APPS}" runtime-shared true infra/versions.env

# Unity build input 만: Unity 는 만들되 game 소스가 안 바뀌었으므로 배포하지 않는다.
run_case "${ALL}" '["game"]' '[]' shared-ci true ci/test
run_case "${ALL}" '["game"]' '[]' shared-ci true infra/jenkins/pipelines/component.groovy
run_case "${ALL}" '["game"]' '[]' shared-ci true infra/jenkins/agents/compose.yaml
# LFS 규칙과 game CI 어댑터는 checkout/build 입력이다 (Batch 2).
run_case "${ALL}" '["game"]' '[]' shared-ci true .gitattributes
run_case '["game"]' '["game"]' '["game"]' component-source true festa-unity/ci/preflight-license
# consumer 스크립트는 shared-ci 일 뿐 Unity 를 돌리지 않는다.
run_case "${ALL}" '[]' '[]' shared-ci false infra/jenkins/scripts/publish-webgl-release.sh infra/jenkins/scripts/resolve-game-artifacts.sh

# 검증 전용·dev 전용·문서: 아무것도 고르지 않는다.
run_case '[]' '[]' '[]' validation-only false infra/unity-server/tests/integration/example.sh
run_case '[]' '[]' '[]' validation-only false infra/tests/acceptance/example.sh infra/environments/tests/contract/demo-webgl.sh
run_case '[]' '[]' '[]' validation-only false infra/environments/compose/dev/ai.yaml infra/environments/config/environments/dev.env.example
run_case '[]' '[]' '[]' docs-only false specs/infra-001-ci-cd-pipelines/notes.md infra/deploy/runbooks/x.md

head="$(git rev-parse HEAD)"
output="$(BRANCH_NAME=develop "${detector}" --head "${head}")"
assert_selection "${output}" '[]' '[]' '[]' docs-only false

output="$(BRANCH_NAME=develop "${detector}" --base "${head}" --head "${head}")"
assert_selection "${output}" '[]' '[]' '[]' docs-only false

base="$(git rev-parse HEAD)"
commit_paths infra/environments/compose/demo/unknown.yaml
head="$(git rev-parse HEAD)"
if BRANCH_NAME=develop "${detector}" --base "${base}" --head "${head}" >/dev/null 2>&1; then
  fail 'unclassified demo overlay must fail closed'
fi

echo 'PASS: changed component detector fixtures'
