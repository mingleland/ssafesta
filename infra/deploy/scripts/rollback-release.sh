#!/usr/bin/env bash
set -euo pipefail

: "${STATE_DIR:?}" "${KNOWN_GOOD_MANIFEST_PATH:?}" "${CI_ARTIFACT_DIR:?}" "${RELEASE_ID:?}" "${DEPLOY_TARGET:?}"
attempt_marker="${STATE_DIR}/rollback-attempted-${RELEASE_ID}"
[[ ! -e "${attempt_marker}" ]] || { echo 'rollback already attempted; automatic retry forbidden' >&2; exit 75; }
mkdir -p "${STATE_DIR}" "${CI_ARTIFACT_DIR}"; : >"${attempt_marker}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RELEASE_MANIFEST_PATH="${KNOWN_GOOD_MANIFEST_PATH}"
result=FAILED; deployed=false
if [[ -n "${ROLLBACK_DEPLOY_COMMAND:-}" ]]; then
  if bash -o pipefail -c "${ROLLBACK_DEPLOY_COMMAND}"; then deployed=true; fi
elif bash "${script_dir}/deploy-release.sh"; then deployed=true
fi
if [[ "${deployed}" == true && -n "${ROLLBACK_VERIFY_COMMAND:-}" ]]; then
  if bash -o pipefail -c "${ROLLBACK_VERIFY_COMMAND}"; then result=SUCCEEDED; fi
elif [[ "${deployed}" == true ]]; then
  export SKIP_AI_VERIFY=1
  if bash "${script_dir}/verify-release.sh"; then result=SUCCEEDED; fi
fi
export ROLLBACK_RESULT="${result}" RECOVERY_DECISION_PATH="${CI_ARTIFACT_DIR}/rollback-decision.json"
bash "${script_dir}/decide-recovery.sh" --rollback-result >/dev/null
[[ "${result}" == SUCCEEDED ]] || { echo 'rollback failed; automatic retry forbidden' >&2; exit 1; }
echo 'rollback succeeded and non-AI verification passed'
