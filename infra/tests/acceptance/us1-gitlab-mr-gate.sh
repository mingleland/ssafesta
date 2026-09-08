#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
pipeline="${repo_root}/.gitlab-ci.yml"

fail() { echo "FAIL: $*" >&2; exit 1; }
require() { grep -Fq -- "$1" "$2" || fail "missing '$1' in $2"; }
job() {
  awk -v name="$1" '
    $0 == name ":" { printing=1; next }
    printing && /^[^[:space:]#][^:]*:/ { exit }
    printing { print }
  ' "$pipeline"
}
check_gate() {
  local component="$1" source_path="$2" section rules_section
  rules_section="$(job ".${component}-changes")"
  [[ -n "$rules_section" ]] || fail "missing ${component} change rules"
  grep -Fq "$source_path" <<<"$rules_section" || fail "${component} source path"
  grep -Fq "ci/**/*" <<<"$rules_section" || fail "${component} shared CI path"
  grep -Fq ".gitlab-ci.yml" <<<"$rules_section" || fail "${component} shared pipeline path"
  for stage in test build; do
    section="$(job "${component}-${stage}")"
    [[ -n "$section" ]] || fail "missing ${component}-${stage} job"
    grep -Fq "CI_COMPONENT: ${component}" <<<"$section" || fail "${component}-${stage} component"
    grep -Fq "bash ci/${stage}" <<<"$section" || fail "${component}-${stage} adapter"
    grep -Fq ".${component}-changes" <<<"$section" || fail "${component}-${stage} change rules"
  done
}

require 'CI_PIPELINE_SOURCE == "merge_request_event"' "$pipeline"
require 'CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "develop"' "$pipeline"
require 'CI_MERGE_REQUEST_SOURCE_PROJECT_ID == $CI_PROJECT_ID' "$pipeline"
require 'CI_MERGE_REQUEST_SOURCE_BRANCH_NAME =~ /^feature\//' "$pipeline"
job '.component-ci' | grep -Fq 'command -v python3' || fail 'CI summary runtime dependency'

check_gate front 'festa-frontend/**/*'
echo 'PASS: front-only MR gate'
check_gate back 'backend/**/*'
job 'back-test' | grep -Fq 'TESTCONTAINERS_HOST_OVERRIDE: 127.0.0.1' \
  || fail 'back-test Testcontainers host override'
echo 'PASS: back-only MR gate'

for jira_job in jira-key-check jira-sync-in-progress jira-sync-in-review jira-sync-ready-for-deploy; do
  job "$jira_job" | grep -Eq '^[[:space:]]+- when: never$' || fail "${jira_job} must stay disabled"
done
echo 'PASS: shared CI/pipeline paths run both gates; jira jobs remain disabled'
