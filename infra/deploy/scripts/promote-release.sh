#!/usr/bin/env bash
set -euo pipefail

: "${STATE_DIR:?}" "${RELEASE_MANIFEST_PATH:?}" "${VERIFICATION_RESULT_PATH:?}"
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
python - "$(native "${STATE_DIR}")" "$(native "${RELEASE_MANIFEST_PATH}")" "$(native "${VERIFICATION_RESULT_PATH}")" <<'PY'
import json,os,pathlib,sys,tempfile
state_dir=pathlib.Path(sys.argv[1]); release_path=pathlib.Path(sys.argv[2]); verification_path=pathlib.Path(sys.argv[3])
release=json.loads(release_path.read_text(encoding='utf-8')); verification=json.loads(verification_path.read_text(encoding='utf-8'))
if verification.get('finalDecision')!='PASS' or not verification.get('requiredNonAiPassed') or verification.get('aiPassed') is not True:
 raise SystemExit('promotion denied: full verification did not pass')
state_dir.mkdir(parents=True,exist_ok=True); (state_dir/'releases').mkdir(exist_ok=True)
state_path=state_dir/'target-state.json'
old=json.loads(state_path.read_text(encoding='utf-8')) if state_path.exists() else {'currentReleaseId':None,'knownGoodReleaseId':None,'releaseSequence':0}
release_id=release['releaseId']; archived=state_dir/'releases'/f'{release_id}.json'
if archived.exists() and archived.read_bytes()!=release_path.read_bytes(): raise SystemExit('release ID already exists with different content')
if not archived.exists():
 tmp=archived.with_suffix('.tmp'); tmp.write_bytes(release_path.read_bytes()); os.replace(tmp,archived)
new={'schemaVersion':'1.0.0','targetId':os.environ.get('DEPLOY_TARGET','integration-develop'),'currentReleaseId':release_id,'knownGoodReleaseId':release_id,'previousKnownGoodReleaseId':old.get('knownGoodReleaseId'),'releaseSequence':int(old.get('releaseSequence',0))+1}
tmp=state_path.with_suffix('.tmp'); tmp.write_text(json.dumps(new,indent=2)+'\n',encoding='utf-8'); os.replace(tmp,state_path)
print(state_path)
PY
