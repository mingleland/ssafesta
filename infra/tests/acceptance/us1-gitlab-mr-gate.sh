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
require 'CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "main"' "$pipeline"
require 'CI_MERGE_REQUEST_SOURCE_PROJECT_ID == $CI_PROJECT_ID' "$pipeline"
job '.component-ci' | grep -Fq 'command -v python3' || fail 'CI summary runtime dependency'
status_section="$(job 'mr-status')"
[[ -n "$status_section" ]] || fail 'missing MR status job'
grep -Fq 'stage: validate' <<<"$status_section" || fail 'MR status stage'
grep -Fq 'CI_PIPELINE_SOURCE == "merge_request_event"' <<<"$status_section" || fail 'MR status rule'
[[ "$(grep -Fc 'bash infra/jenkins/scripts/secret-scan.sh --path .' "$pipeline")" == 1 ]] \
  || fail 'secret scan must run once in mr-status'
grep -Fq 'MR pipeline status only' <<<"$status_section" || fail 'MR status no-op command'
echo 'PASS: infra/docs-only MR receives a successful pipeline status'

# develop → main promotion MR: authoritative gate 는 GitLab 네이티브 mr-status 다. component job 은 develop 대상에서만
# 돌고, Jenkins external status(jenkinsci/branch) 는 merge 를 결정하지 않는다 — 그 계약은 merge 직전
# check-promotion-mr-gate.sh 로 강제한다 (Batch 1, 실측 !1220/!1229/!1232).
for component in ai back front game; do
  job ".${component}-changes" | grep -Fq 'CI_MERGE_REQUEST_TARGET_BRANCH_NAME != "develop"' \
    || fail "${component} component CI must be restricted to develop-target MRs"
done
gate_check="${repo_root}/infra/deploy/scripts/check-promotion-mr-gate.sh"
[[ -x "$gate_check" ]] || fail 'promotion MR gate check script is missing or not executable'
grep -Fq 'merge_request_event' "$gate_check" || fail 'gate check must require the GitLab MR pipeline as head pipeline'
grep -Fq 'squash' "$gate_check" || fail 'gate check must refuse squash on develop -> main'
grep -Fq 'GATE_BLOCK' "$gate_check" || fail 'gate check must fail closed'
bash "$gate_check" not-a-number >/dev/null 2>&1 && fail 'gate check must reject a non-numeric MR iid' || true
echo 'PASS: develop -> main promotion MR is gated by the native MR pipeline'

check_gate ai 'festa-ai/**/*'
echo 'PASS: ai-only MR gate'
check_gate front 'festa-frontend/**/*'
echo 'PASS: front-only MR gate'
check_gate back 'backend/**/*'
job 'back-test' | grep -Fq 'TESTCONTAINERS_HOST_OVERRIDE: 127.0.0.1' \
  || fail 'back-test Testcontainers host override'
back_cache="$(job '.back-maven-cache')"
grep -Fq 'MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"' <<<"$back_cache" \
  || fail 'back Maven local repository'
grep -Fq 'backend/pom.xml' <<<"$back_cache" || fail 'back Maven cache pom key'
grep -Fq 'backend/.mvn/wrapper/maven-wrapper.properties' <<<"$back_cache" \
  || fail 'back Maven cache wrapper key'
for back_job in back-test back-build; do
  job "$back_job" | grep -Fq '.back-maven-cache' || fail "$back_job Maven cache"
done
echo 'PASS: back-only MR gate'

game_rules="$(job '.game-changes')"
[[ -n "${game_rules}" ]] || fail 'missing game change rules'
grep -Fq 'festa-unity/**/*' <<<"${game_rules}" || fail 'game source path'
grep -Fq 'ci/test' <<<"${game_rules}" || fail 'game test adapter path'
! grep -Fq 'docs/**/*' <<<"${game_rules}" || fail 'docs-only MR must not select Unity gate'
! grep -Fq 'specs/**/*' <<<"${game_rules}" || fail 'spec-only MR must not select Unity gate'
game_dispatch="$(job 'unity-mr-validation-dispatch')"
[[ -n "${game_dispatch}" ]] || fail 'missing Unity MR validation dispatch job'
grep -Fq '.game-changes' <<<"${game_dispatch}" || fail 'Unity dispatch must use game change rules'
grep -Fq 'stage: test' <<<"${game_dispatch}" || fail 'Unity dispatch must run in test stage'
echo 'PASS: game-only MR selects Unity validation; docs-only MR does not'

for jira_job in jira-key-check jira-sync-in-progress jira-sync-in-review jira-sync-ready-for-deploy; do
  job_body="$(job "$jira_job")"
  if [[ -n "$job_body" ]]; then
    grep -Eq '^[[:space:]]+- when: never$' <<<"$job_body" || fail "${jira_job} must stay disabled"
  fi
done
echo 'PASS: shared CI/pipeline paths run AI, Front, and Back gates; jira jobs remain disabled'
