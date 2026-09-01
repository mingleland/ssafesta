#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_MANIFEST_PATH:?}" "${VERIFICATION_RESULT_PATH:?}" "${RECOVERY_DECISION_PATH:?}" "${DEPLOYMENT_RECORD_PATH:?}" "${RUN_ID:?}" "${DEPLOY_TARGET:?}"
native() { if [[ -z "$1" ]]; then return 0; elif command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
state_path="${STATE_DIR:-}/target-state.json"
rollback_path="${ROLLBACK_DECISION_PATH:-}"
python - "$(native "${RELEASE_MANIFEST_PATH}")" "$(native "${VERIFICATION_RESULT_PATH}")" "$(native "${RECOVERY_DECISION_PATH}")" "$(native "${DEPLOYMENT_RECORD_PATH}")" "$(native "${state_path}")" "$(native "${rollback_path}")" <<'PY'
import datetime,json,os,pathlib,sys
release=json.load(open(sys.argv[1],encoding='utf-8')); verify=json.load(open(sys.argv[2],encoding='utf-8')); recovery=json.load(open(sys.argv[3],encoding='utf-8'))
state=json.load(open(sys.argv[5],encoding='utf-8')) if sys.argv[5] and pathlib.Path(sys.argv[5]).is_file() else {}
rollback=json.load(open(sys.argv[6],encoding='utf-8')) if sys.argv[6] and pathlib.Path(sys.argv[6]).is_file() else None
decision=recovery['decision']; result='NOT_RUN'; current=state.get('currentReleaseId'); previous=state.get('previousKnownGoodReleaseId') or state.get('knownGoodReleaseId')
if verify['finalDecision']=='PASS' and decision=='NONE': final='ACTIVE'; current=release['releaseId']
elif decision=='AUTO_ROLLBACK':
 result=(rollback or {}).get('result','FAILED'); final='ROLLED_BACK' if result=='SUCCEEDED' else 'ROLLBACK_FAILED'
elif decision=='AI_RETRY': final='AWAITING_AI_RETRY_OR_APPROVAL'
else: final='MANUAL_ACTION_REQUIRED'
recovery_record={'decision':decision,'result':result,'reason':recovery['reason']}
if decision=='AUTO_ROLLBACK': recovery_record.update({'fromReleaseId':release['releaseId'],'toReleaseId':current or previous or 'unknown'})
now=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
d={'schemaVersion':'1.0.0','recordId':f"{os.environ['DEPLOY_TARGET']}-{release['releaseId']}",'targetId':os.environ['DEPLOY_TARGET'],'candidateReleaseId':release['releaseId'],'previousKnownGoodReleaseId':previous,'runId':os.environ['RUN_ID'],'state':final,'stages':[{'name':'verify','status':'SUCCEEDED' if verify['finalDecision']=='PASS' else 'FAILED',**({'failureCode':verify['failureCode']} if verify.get('failureCode') else {}),'evidenceRef':'verification-result.json'}],'recovery':recovery_record,'currentReleaseId':current,'startedAt':verify['startedAt'],'finishedAt':now}
p=pathlib.Path(sys.argv[4]); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8')
PY
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${script_dir}/../../jenkins/scripts/validate-contracts.sh" deployment-record "${DEPLOYMENT_RECORD_PATH}"
echo "${DEPLOYMENT_RECORD_PATH}"
