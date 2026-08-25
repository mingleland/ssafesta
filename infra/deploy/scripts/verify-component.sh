#!/usr/bin/env bash
set -euo pipefail
: "${CI_COMPONENT:?}" "${RELEASE_ID:?}" "${DEPLOY_TARGET:?}" "${CI_ARTIFACT_DIR:?}"
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; start_ms="$(date +%s%3N)"
status=PASSED; decision=PASS; failure_py=None; exit_code=0
if [[ -n "${COMPONENT_VERIFY_COMMAND:-}" ]]; then
  if ! bash -o pipefail -c "${COMPONENT_VERIFY_COMMAND}"; then status=FAILED; decision=ROLLBACK; failure_py='"VERIFY_NON_AI"'; exit_code=1; fi
elif [[ -n "${VERIFY_URL:-}" ]]; then
  if ! curl --fail --silent --show-error --max-time "${VERIFY_TIMEOUT_SECONDS:-15}" "${VERIFY_URL}" >/dev/null; then status=FAILED; decision=ROLLBACK; failure_py='"VERIFY_NON_AI"'; exit_code=1; fi
else
  echo 'COMPONENT_VERIFY_COMMAND or VERIFY_URL is required' >&2; exit 64
fi
finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; end_ms="$(date +%s%3N)"; duration=$((end_ms-start_ms))
mkdir -p "${CI_ARTIFACT_DIR}"; output="${CI_ARTIFACT_DIR}/verification-result.json"
python_output="${output}"; if command -v cygpath >/dev/null 2>&1; then python_output="$(cygpath -w "${output}")"; fi
python - "${python_output}" <<PY
import json,pathlib
d={'schemaVersion':'1.0.0','verificationId':'verify-${RELEASE_ID}-${CI_COMPONENT}','releaseId':'${RELEASE_ID}','targetId':'${DEPLOY_TARGET}','checks':[{'name':'component-readiness','status':'${status}','durationMs':${duration}}],'requiredNonAiPassed':${status@Q} == 'PASSED','aiPassed':None,'failureCode':${failure_py},'evidenceRefs':[],'finalDecision':'${decision}','startedAt':'${started}','finishedAt':'${finished}'}
pathlib.Path(r'''${python_output}''').write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
PY
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${script_dir}/../../jenkins/scripts/validate-contracts.sh" verification "${output}"
echo "${output}"; exit "${exit_code}"
