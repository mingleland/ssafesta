#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_ID:?}" "${DEPLOY_TARGET:?}" "${CI_ARTIFACT_DIR:?}"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkdir -p "${CI_ARTIFACT_DIR}"
results="${CI_ARTIFACT_DIR}/verification-checks.tsv"; : >"${results}"
non_ai=true; ai_passed=true; failure_code=null; decision=PASS

run_check() {
  local name="$1" command_value="$2" required="$3" start end status
  start="$(date +%s%3N)"
  if [[ -z "${command_value}" ]]; then
    echo "verification command missing: ${name}" >&2; status=FAILED
  elif bash -o pipefail -c "${command_value}"; then status=PASSED
  else status=FAILED
  fi
  end="$(date +%s%3N)"; printf '%s\t%s\t%s\n' "${name}" "${status}" "$((end-start))" >>"${results}"
  if [[ "${status}" == FAILED && "${required}" == non-ai ]]; then non_ai=false; failure_code='"VERIFY_NON_AI"'; decision=ROLLBACK; return 1; fi
  if [[ "${status}" == FAILED && "${required}" == ai ]]; then ai_passed=false; failure_code='"VERIFY_AI_ONLY"'; decision=AI_RETRY; return 1; fi
}

run_check web "${VERIFY_WEB_COMMAND:-}" non-ai || true
if [[ "${non_ai}" == true ]]; then run_check login "${VERIFY_LOGIN_COMMAND:-}" non-ai || true; else printf 'login\tSKIPPED\t0\n' >>"${results}"; fi
if [[ "${non_ai}" == true ]]; then run_check world "${VERIFY_WORLD_COMMAND:-}" non-ai || true; else printf 'world\tSKIPPED\t0\n' >>"${results}"; fi
if [[ "${SKIP_AI_VERIFY:-0}" == 1 ]]; then ai_passed=null; printf 'ai\tSKIPPED\t0\n' >>"${results}"
elif [[ "${non_ai}" == true ]]; then run_check ai "${VERIFY_AI_COMMAND:-}" ai || true
else ai_passed=null; printf 'ai\tSKIPPED\t0\n' >>"${results}"
fi

finished="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; output="${CI_ARTIFACT_DIR}/verification-result.json"
export VERIFY_STARTED_AT="${started}" VERIFY_FINISHED_AT="${finished}"
python - "$(native "${results}")" "$(native "${output}")" "${non_ai}" "${ai_passed}" "${failure_code}" "${decision}" <<'PY'
import json,os,pathlib,sys
checks=[]
for line in pathlib.Path(sys.argv[1]).read_text(encoding='utf-8').splitlines():
 name,status,duration=line.split('\t'); checks.append({'name':name,'status':status,'durationMs':int(duration)})
def tri(value): return None if value=='null' else value=='true'
d={'schemaVersion':'1.0.0','verificationId':f"verify-{os.environ['RELEASE_ID']}",'releaseId':os.environ['RELEASE_ID'],'targetId':os.environ['DEPLOY_TARGET'],'checks':checks,'requiredNonAiPassed':tri(sys.argv[3]),'aiPassed':tri(sys.argv[4]),'failureCode':json.loads(sys.argv[5]),'evidenceRefs':['verification-checks.tsv'],'finalDecision':sys.argv[6],'startedAt':os.environ['VERIFY_STARTED_AT'],'finishedAt':os.environ['VERIFY_FINISHED_AT']}
pathlib.Path(sys.argv[2]).write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
PY
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${script_dir}/../../jenkins/scripts/validate-contracts.sh" verification "${output}"
echo "${output}"
[[ "${decision}" == PASS ]] || exit 1
