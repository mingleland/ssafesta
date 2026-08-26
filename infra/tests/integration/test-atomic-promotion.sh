#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

mkdir -p "${work_dir}/state"
cat >"${work_dir}/release.json" <<'JSON'
{"releaseId":"candidate-2","components":[]}
JSON
cat >"${work_dir}/verification.json" <<'JSON'
{"finalDecision":"ROLLBACK","requiredNonAiPassed":false}
JSON
cat >"${work_dir}/state/target-state.json" <<'JSON'
{"currentReleaseId":"release-1","knownGoodReleaseId":"release-1","releaseSequence":1}
JSON

if STATE_DIR="${work_dir}/state" RELEASE_MANIFEST_PATH="${work_dir}/release.json" VERIFICATION_RESULT_PATH="${work_dir}/verification.json" \
  bash "${repo_root}/infra/deploy/scripts/promote-release.sh" >/dev/null 2>&1; then
  echo 'FAIL: failed verification was promoted' >&2; exit 1
fi
grep -q '"currentReleaseId":"release-1"' "${work_dir}/state/target-state.json"

python - "${work_dir}/verification.json" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p,encoding='utf-8')); d['finalDecision']='PASS'; d['requiredNonAiPassed']=True; d['aiPassed']=True
open(p,'w',encoding='utf-8').write(json.dumps(d))
PY
STATE_DIR="${work_dir}/state" RELEASE_MANIFEST_PATH="${work_dir}/release.json" VERIFICATION_RESULT_PATH="${work_dir}/verification.json" \
  bash "${repo_root}/infra/deploy/scripts/promote-release.sh" >/dev/null
python "${script_dir}/../helpers/assert-json.py" "${work_dir}/state/target-state.json" \
  'document["currentReleaseId"] == "candidate-2"' \
  'document["knownGoodReleaseId"] == "candidate-2"' \
  'document["releaseSequence"] == 2'

echo 'PASS: promotion updates one atomic target state only after complete verification'
