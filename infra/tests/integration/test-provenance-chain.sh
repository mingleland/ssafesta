#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

cp "${repo_root}/infra/tests/contract/fixtures/release-valid.json" "${work_dir}/release.json"
cp "${repo_root}/infra/tests/contract/fixtures/verification-valid.json" "${work_dir}/verification.json"
python - "${work_dir}/release.json" "${work_dir}/target-state.json" <<'PY'
import json,sys
release=json.load(open(sys.argv[1],encoding='utf-8')); path=sys.argv[2]
open(path,'w',encoding='utf-8').write(json.dumps({'targetId':'dev-back','currentReleaseId':release['releaseId'],'knownGoodReleaseId':release['releaseId'],'previousKnownGoodReleaseId':'back-previous','releaseSequence':42}))
PY
cat >"${work_dir}/recovery.json" <<'JSON'
{"failureClass":"PRE_DEPLOY_FAILURE","rollbackSafety":"SAFE","decision":"NONE","reason":"all required checks passed","result":"NOT_RUN"}
JSON
STATE_DIR="${work_dir}" RELEASE_MANIFEST_PATH="${work_dir}/release.json" VERIFICATION_RESULT_PATH="${work_dir}/verification.json" \
  RECOVERY_DECISION_PATH="${work_dir}/recovery.json" DEPLOYMENT_RECORD_PATH="${work_dir}/deployment.json" RUN_ID=festa-develop-42 DEPLOY_TARGET=dev-back \
  bash "${repo_root}/infra/deploy/scripts/write-deployment-record.sh" >/dev/null

PROVENANCE_LOG="${work_dir}/history.jsonl" RUN_ID=festa-develop-42 \
  RELEASE_MANIFEST_PATH="${work_dir}/release.json" VERIFICATION_RESULT_PATH="${work_dir}/verification.json" \
  DEPLOYMENT_RECORD_PATH="${work_dir}/deployment.json" bash "${repo_root}/infra/jenkins/scripts/provenance.sh"

python - "${work_dir}/history.jsonl" <<'PY'
import json,sys
rows=[json.loads(line) for line in open(sys.argv[1],encoding='utf-8')]
assert len(rows)==1
row=rows[0]
assert row['runId']=='festa-develop-42'
assert row['release']['releaseId']==row['deployment']['candidateReleaseId']
assert row['verification']['releaseId']==row['release']['releaseId']
assert row['deployment']['currentReleaseId']==row['release']['releaseId']
assert 'components' in row['release'] and all('contentId' in c for c in row['release']['components'])
PY
echo 'PASS: provenance reconstructs run to release, verification, recovery and current state'
