#!/usr/bin/env bash
set -euo pipefail

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
: "${RECOVERY_DECISION_PATH:?}"
output="$(native "${RECOVERY_DECISION_PATH}")"

if [[ "${1:-}" == --rollback-result ]]; then
  : "${ROLLBACK_RESULT:?}"
  [[ "${ROLLBACK_RESULT}" =~ ^(SUCCEEDED|FAILED)$ ]] || { echo 'invalid rollback result' >&2; exit 64; }
  python - "${output}" <<'PY'
import json,os,pathlib
result=os.environ['ROLLBACK_RESULT']
d={'failureClass':'UNKNOWN' if result=='FAILED' else 'REVERSIBLE_RUNTIME','rollbackSafety':'UNASSESSED','decision':'MANUAL' if result=='FAILED' else 'NONE','reason':'rollback failed; automatic retry is forbidden' if result=='FAILED' else 'rollback completed','result':result}
p=pathlib.Path(os.sys.argv[1]); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
PY
  echo "${RECOVERY_DECISION_PATH}"; exit 0
fi

: "${RELEASE_MANIFEST_PATH:?}" "${VERIFICATION_RESULT_PATH:?}"
python - "$(native "${RELEASE_MANIFEST_PATH}")" "$(native "${VERIFICATION_RESULT_PATH}")" "${output}" <<'PY'
import json,pathlib,sys
release=json.load(open(sys.argv[1],encoding='utf-8')); verification=json.load(open(sys.argv[2],encoding='utf-8'))
safety=release['rollbackSafety']; code=verification.get('failureCode')
if verification.get('finalDecision')=='PASS': cls,decision,reason='PRE_DEPLOY_FAILURE','NONE','all required checks passed'
elif code=='VERIFY_AI_ONLY' and verification.get('requiredNonAiPassed'): cls,decision,reason='AI_EXTERNAL_ONLY','AI_RETRY','non-AI services remain available; AI retry or approval required'
elif safety['dbSchemaChanged'] or safety['secretOrConfigChanged'] or safety['dataChange']=='irreversible' or code in ('DB_CHANGE','SECRET_CONFIG','IRREVERSIBLE_CHANGE'):
 cls,decision,reason='DATA_OR_CONFIG_RISK','MANUAL','data, database, Secret or configuration risk forbids automatic rollback'
elif code in ('CONTAINER_START','VERIFY_NON_AI') and safety['classification']=='SAFE' and safety['dataChange']!='irreversible':
 cls,decision,reason='REVERSIBLE_RUNTIME','AUTO_ROLLBACK','safe reversible runtime failure'
else: cls,decision,reason='UNKNOWN','MANUAL','unknown or unassessed failure requires manual action'
d={'failureClass':cls,'rollbackSafety':safety['classification'],'decision':decision,'reason':reason,'result':'NOT_RUN'}
p=pathlib.Path(sys.argv[3]); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
PY
echo "${RECOVERY_DECISION_PATH}"
