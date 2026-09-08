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
  local output="$1" expected_components="$2" expected_deploy="$3" expected_reason="$4"
  python3 - "${output}" "${expected_components}" "${expected_deploy}" "${expected_reason}" <<'PY'
import json, sys
actual = json.loads(sys.argv[1])
assert actual["eventKind"] == "jenkins_develop_push"
assert actual["components"] == json.loads(sys.argv[2])
assert actual["deployComponents"] == json.loads(sys.argv[3])
assert sys.argv[4] in actual["reasons"]
PY
}

run_case() {
  local expected_components="$1" expected_deploy="$2" expected_reason="$3"
  shift 3
  local base head output
  base="$(git rev-parse HEAD)"
  commit_paths "$@"
  head="$(git rev-parse HEAD)"
  output="$(BRANCH_NAME=develop "${detector}" --base "${base}" --head "${head}")"
  assert_selection "${output}" "${expected_components}" "${expected_deploy}" "${expected_reason}"
}

run_case '["ai"]' '["ai"]' component-source festa-ai/app/main.py
run_case '["back"]' '["back"]' component-source backend/src/main/java/App.java
run_case '["front"]' '["front"]' component-source festa-frontend/src/main.tsx
run_case '["game"]' '["game"]' component-source festa-unity/Assets/main.cs
run_case '["back","front"]' '["back","front"]' component-source backend/src/main/java/Multi.java festa-frontend/src/multi.tsx
run_case '["ai","back","front","game"]' '[]' shared-ci Jenkinsfile
run_case '[]' '[]' docs-only specs/infra-001-ci-cd-pipelines/notes.md

head="$(git rev-parse HEAD)"
output="$(BRANCH_NAME=develop "${detector}" --head "${head}")"
assert_selection "${output}" '[]' '[]' docs-only

base="$(git rev-parse HEAD)"
commit_paths infra/environments/compose/dev/unknown.yaml
head="$(git rev-parse HEAD)"
if BRANCH_NAME=develop "${detector}" --base "${base}" --head "${head}" >/dev/null 2>&1; then
  fail 'unclassified in-scope path must fail closed'
fi

echo 'PASS: changed component detector fixtures'
