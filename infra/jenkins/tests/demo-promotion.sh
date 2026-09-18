#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
commit=0123456789abcdef0123456789abcdef01234567

python3 - "${work}/release.json" "${work}/verification.json" "${commit}" <<'PY'
import json,pathlib,sys
release_path,verification_path=map(pathlib.Path,sys.argv[1:3]); commit=sys.argv[3]
components=[{'name':name,'storageMode':'local-docker','imageRef':f'festa-{name}:{commit}','contentId':'sha256:'+char*64,'sourceCommit':commit} for name,char in zip(('ai','back','front','game'),'abcd')]
release={'schemaVersion':'1.0.0','releaseId':f'develop-{commit}-7','scm':{'provider':'gitlab','repository':'festa/test','branch':'develop','commit':commit},'jenkins':{'job':'festa-gitlab-develop/develop','buildNumber':7},'components':components,'rollbackSafety':{'classification':'SAFE','dataChange':'none','dbSchemaChanged':False,'secretOrConfigChanged':False},'createdAt':'2026-09-11T00:00:00Z'}
verification={'schemaVersion':'1.0.0','verificationId':'verify-dev-7','releaseId':release['releaseId'],'targetId':'dev-batch-7','checks':[{'name':name,'status':'PASSED'} for name in ('web','login','world','ai')],'requiredNonAiPassed':True,'aiPassed':True,'failureCode':None,'evidenceRefs':[],'finalDecision':'PASS','startedAt':'2026-09-11T00:00:00Z','finishedAt':'2026-09-11T00:00:01Z'}
release_path.write_text(json.dumps(release),encoding='utf-8'); verification_path.write_text(json.dumps(verification),encoding='utf-8')
PY

# readiness 통과만으로는 승격할 수 없다 — 사람이 승인한 known-good 조합이 있어야 한다 (규칙 5).
known_good="${work}/known-good/environment.json"
mkdir -p "$(dirname "${known_good}")"
if RELEASE_MANIFEST_PATH="${work}/release.json" DEV_VERIFICATION_RESULT_PATH="${work}/verification.json" \
  KNOWN_GOOD_ENVIRONMENT_PATH="${known_good}" \
  DEMO_APPROVED_BY=release.manager bash "${repo_root}/infra/jenkins/scripts/validate-demo-promotion.sh" >/dev/null 2>&1; then
  echo 'FAIL: release without a known-good environment was accepted' >&2; exit 1
fi

python3 - "${known_good}" "${commit}" <<'PY'
import json,pathlib,sys
path,commit=pathlib.Path(sys.argv[1]),sys.argv[2]
components={name:{'imageRef':f'festa-{name}:{commit}'} for name in ('ai','back','front','game')}
path.write_text(json.dumps({'schemaVersion':'1.0.0','state':'KNOWN_GOOD','components':components}),encoding='utf-8')
PY

if RELEASE_MANIFEST_PATH="${work}/release.json" DEV_VERIFICATION_RESULT_PATH="${work}/verification.json" \
  DEMO_APPROVED_BY='' bash "${repo_root}/infra/jenkins/scripts/validate-demo-promotion.sh" >/dev/null 2>&1; then
  echo 'FAIL: unapproved release was accepted' >&2; exit 1
fi

python3 - "${work}/verification.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d['aiPassed']=False; d['finalDecision']='AI_RETRY'; p.write_text(json.dumps(d),encoding='utf-8')
PY
if RELEASE_MANIFEST_PATH="${work}/release.json" DEV_VERIFICATION_RESULT_PATH="${work}/verification.json" \
  KNOWN_GOOD_ENVIRONMENT_PATH="${known_good}" \
  DEMO_APPROVED_BY=release.manager bash "${repo_root}/infra/jenkins/scripts/validate-demo-promotion.sh" >/dev/null 2>&1; then
  echo 'FAIL: unverified release was accepted' >&2; exit 1
fi

python3 - "${work}/verification.json" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d['aiPassed']=True; d['finalDecision']='PASS'; p.write_text(json.dumps(d),encoding='utf-8')
PY
release_id="$(RELEASE_MANIFEST_PATH="${work}/release.json" DEV_VERIFICATION_RESULT_PATH="${work}/verification.json" \
  KNOWN_GOOD_ENVIRONMENT_PATH="${known_good}" \
  DEMO_APPROVED_BY=release.manager bash "${repo_root}/infra/jenkins/scripts/validate-demo-promotion.sh")"
[[ "${release_id}" == "develop-${commit}-7" ]]
echo 'PASS: demo promotion rejects unapproved or unverified manifests and accepts a fully verified dev release'
