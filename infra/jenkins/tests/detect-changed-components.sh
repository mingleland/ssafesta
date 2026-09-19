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

assert_selection() {
  local output="$1" expected_components="$2" expected_deploy="$3" expected_reason="$4" expected_game_build="$5"
  python3 - "${output}" "${expected_components}" "${expected_deploy}" "${expected_reason}" "${expected_game_build}" <<'PY'
import json, sys
actual = json.loads(sys.argv[1])
assert actual["eventKind"] == "jenkins_develop_push"
assert actual["components"] == json.loads(sys.argv[2])
assert actual["deployComponents"] == json.loads(sys.argv[3])
assert sys.argv[4] in actual["reasons"]
assert actual["gameBuildRequired"] is (sys.argv[5] == "true")
PY
}

run_case() {
  local expected_components="$1" expected_deploy="$2" expected_reason="$3" expected_game_build="$4"
  shift 4
  local base head output
  base="$(git rev-parse HEAD)"
  commit_paths "$@"
  head="$(git rev-parse HEAD)"
  output="$(BRANCH_NAME=develop "${detector}" --base "${base}" --head "${head}")"
  assert_selection "${output}" "${expected_components}" "${expected_deploy}" "${expected_reason}" "${expected_game_build}"
}

run_case '["ai"]' '["ai"]' component-source false festa-ai/app/main.py
run_case '["back"]' '["back"]' component-source false backend/src/main/java/App.java
run_case '["front"]' '["front"]' component-source false festa-frontend/src/main.tsx
run_case '["game"]' '["game"]' component-source true festa-unity/Assets/main.cs
run_case '["back","front"]' '["back","front"]' component-source false backend/src/main/java/Multi.java festa-frontend/src/multi.tsx

# Shared orchestration does not by itself change a Unity artifact.
run_case '["ai","back","front","game"]' '[]' shared-ci false Jenkinsfile
run_case '["ai","back","front","game"]' '[]' shared-ci false infra/jenkins/pipelines/develop.groovy
run_case '["ai","back","front","game"]' '[]' shared-ci false infra/.env.example infra/environments/config/environments/dev.env.example infra/environments/nginx/sites/demo.conf.template infra/environments/tests/contract/demo-webgl.sh

# Inputs that can actually affect game CI/build still require the Unity worker.
run_case '["ai","back","front","game"]' '[]' shared-ci true ci/test
run_case '["ai","back","front","game"]' '[]' shared-ci true infra/jenkins/pipelines/component.groovy
run_case '["ai","back","front","game"]' '[]' shared-ci true infra/jenkins/agents/compose.yaml

# Unity Server tests validate deployment machinery only.
run_case '[]' '[]' validation-only false infra/unity-server/tests/integration/example.sh

run_case '[]' '[]' docs-only false specs/infra-001-ci-cd-pipelines/notes.md

head="$(git rev-parse HEAD)"
output="$(BRANCH_NAME=develop "${detector}" --head "${head}")"
assert_selection "${output}" '[]' '[]' docs-only false

output="$(BRANCH_NAME=develop "${detector}" --base "${head}" --head "${head}")"
assert_selection "${output}" '[]' '[]' docs-only false

base="$(git rev-parse HEAD)"
commit_paths infra/environments/compose/dev/unknown.yaml
head="$(git rev-parse HEAD)"
if BRANCH_NAME=develop "${detector}" --base "${base}" --head "${head}" >/dev/null 2>&1; then
  fail 'unclassified in-scope path must fail closed'
fi

echo 'PASS: changed component detector fixtures'
