#!/usr/bin/env bash
set -euo pipefail
ci_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ci_require() {
  for name in CI_COMPONENT CI_BRANCH CI_COMMIT_SHA CI_RUN_ID CI_ARTIFACT_DIR; do [[ -n "${!name:-}" ]] || { echo "missing ${name}" >&2; return 64; }; done
  [[ "${CI_COMPONENT}" =~ ^(ai|back|front|game)$ ]] || { echo 'invalid CI_COMPONENT' >&2; return 64; }
  [[ "${CI_COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]] || { echo 'CI_COMMIT_SHA must be a full lowercase SHA' >&2; return 64; }
}
ci_summary() {
  local stage="$1" status="$2" code="${3:-}" started="$4" finished
  finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  CI_STAGE="${stage}" CI_STAGE_STATUS="${status}" CI_FAILURE_CODE="${code}" CI_STARTED_AT="${started}" CI_FINISHED_AT="${finished}" \
    bash "${ci_root}/infra/jenkins/scripts/write-stage-summary.sh" >/dev/null
}
ci_run() {
  local stage="$1" failure_code="$2"; shift 2; local started
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; ci_require || exit $?
  if [[ "${CI_FORCE_FAILURE:-}" == "${stage}" ]]; then ci_summary "${stage}" FAILED "${failure_code}" "${started}"; echo "forced ${stage} failure" >&2; exit 1; fi
  if [[ "${CI_DRY_RUN:-0}" != 1 ]]; then "$@" || { local rc=$?; ci_summary "${stage}" FAILED "${failure_code}" "${started}"; exit "${rc}"; }; fi
  ci_summary "${stage}" SUCCEEDED '' "${started}"
}
ci_component_script() { echo "${ci_root}/${CI_COMPONENT}/ci/$1"; }
ci_dispatch_or() { local stage="$1"; shift; local part; part="$(ci_component_script "${stage}")"; if [[ -f "${part}" ]]; then bash "${part}"; else "$@"; fi; }
