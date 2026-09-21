#!/usr/bin/env bash
set -euo pipefail

: "${ACTIVE_DEV_RELEASE_MANIFEST:?}" "${ACTIVE_DEV_VERIFICATION_RESULT:?}"
state_root="${DEV_BATCH_STATE_DIR:-${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}/dev/batches}"
known_good="${state_root}/known-good"
game_state_root="${GAME_DEPLOY_STATE_DIR:-${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}/demo/game}"
game_known_good="${game_state_root}/known-good.json"

for component in ai back front; do
  [[ -f "${known_good}/${component}.json" ]] || { echo "missing active dev known-good manifest: ${component}" >&2; exit 66; }
done
[[ -f "${game_known_good}" ]] || { echo 'missing active demo game known-good state' >&2; exit 66; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for component in ai back front; do
  bash "${script_dir}/validate-contracts.sh" release "${known_good}/${component}.json" >/dev/null
done

game_release="$(
  python3 - "${game_known_good}" "${game_state_root}" <<'PYGAME'
import json, pathlib, sys

state_path = pathlib.Path(sys.argv[1])
state_root = pathlib.Path(sys.argv[2])

state = json.loads(state_path.read_text(encoding='utf-8'))
if state.get('state') not in ('KNOWN_GOOD', 'CURRENT/KNOWN_GOOD'):
    raise SystemExit('demo game known-good state is not approved')

release_id = state.get('releaseId')
if not isinstance(release_id, str) or not release_id:
    raise SystemExit('demo game known-good state has no releaseId')

release_path = state_root / 'releases' / f'{release_id}.json'
if not release_path.is_file():
    raise SystemExit('demo game known-good release archive is missing')

print(release_path)
PYGAME
)"

bash "${script_dir}/validate-contracts.sh" release "${game_release}" >/dev/null

python3 - "${known_good}" "${game_release}" "${ACTIVE_DEV_RELEASE_MANIFEST}" "${ACTIVE_DEV_VERIFICATION_RESULT}" <<'PY'
import datetime,hashlib,json,pathlib,sys
root,game_manifest,out,verify=map(pathlib.Path,sys.argv[1:])
items=[]; source=None; digest=hashlib.sha256()
for name in ('ai','back','front','game'):
    path = game_manifest if name == 'game' else root/(name+'.json')
    raw=path.read_bytes(); digest.update(raw)
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
