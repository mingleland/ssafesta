#!/usr/bin/env bash
set -euo pipefail

: "${ACTIVE_DEV_RELEASE_MANIFEST:?}" "${ACTIVE_DEV_VERIFICATION_RESULT:?}"
state_root="${DEV_BATCH_STATE_DIR:-${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}/dev/batches}"
known_good="${state_root}/known-good"
for component in ai back front game; do
  [[ -f "${known_good}/${component}.json" ]] || { echo "missing active dev known-good manifest: ${component}" >&2; exit 66; }
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for component in ai back front game; do bash "${script_dir}/validate-contracts.sh" release "${known_good}/${component}.json" >/dev/null; done
python3 - "${known_good}" "${ACTIVE_DEV_RELEASE_MANIFEST}" "${ACTIVE_DEV_VERIFICATION_RESULT}" <<'PY'
import datetime,hashlib,json,pathlib,sys
root,out,verify=map(pathlib.Path,sys.argv[1:])
items=[]; source=None; digest=hashlib.sha256()
for name in ('ai','back','front','game'):
    raw=(root/(name+'.json')).read_bytes(); digest.update(raw)
    release=json.loads(raw)
    if release['scm']['branch'] != 'develop' or not release['jenkins']['job'].startswith('festa-gitlab-develop/develop'):
        raise SystemExit(f'{name} known-good is not from develop')
    matches=[item for item in release['components'] if item['name']==name]
    if len(matches)!=1: raise SystemExit(f'{name} known-good has invalid component metadata')
    items.append(matches[0]); source=source or release['scm']
release_id='dev-active-'+digest.hexdigest()[:16]; now=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
document={'schemaVersion':'1.0.0','releaseId':release_id,'scm':source,'jenkins':{'job':'festa-gitlab-develop/develop','buildNumber':1},'components':items,'rollbackSafety':{'classification':'UNASSESSED','dataChange':'none','dbSchemaChanged':False,'secretOrConfigChanged':False},'createdAt':now}
result={'schemaVersion':'1.0.0','verificationId':'verify-'+release_id,'releaseId':release_id,'targetId':'dev-active','checks':[{'name':'component-readiness','status':'PASSED'} for _ in items],'requiredNonAiPassed':True,'aiPassed':True,'failureCode':None,'evidenceRefs':['active-dev-known-good'],'finalDecision':'PASS','startedAt':now,'finishedAt':now}
for path,value in ((out,document),(verify,result)):
    path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
PY
bash "${script_dir}/validate-contracts.sh" release "${ACTIVE_DEV_RELEASE_MANIFEST}" >/dev/null
bash "${script_dir}/validate-contracts.sh" verification "${ACTIVE_DEV_VERIFICATION_RESULT}" >/dev/null
echo "${ACTIVE_DEV_RELEASE_MANIFEST}"
