#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

write_manifest() {
  local classification="$1" data_change="$2" db_changed="$3" config_changed="$4"
  cat >"${work_dir}/release.json" <<JSON
{"rollbackSafety":{"classification":"${classification}","dataChange":"${data_change}","dbSchemaChanged":${db_changed},"secretOrConfigChanged":${config_changed}}}
JSON
}
write_verification() {
  local code="$1" non_ai="$2"
  cat >"${work_dir}/verification.json" <<JSON
{"failureCode":${code},"requiredNonAiPassed":${non_ai},"finalDecision":"MANUAL"}
JSON
}
decision() {
  RELEASE_MANIFEST_PATH="${work_dir}/release.json" VERIFICATION_RESULT_PATH="${work_dir}/verification.json" \
    RECOVERY_DECISION_PATH="${work_dir}/decision.json" bash "${repo_root}/infra/deploy/scripts/decide-recovery.sh" >/dev/null
  python -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["decision"])' "${work_dir}/decision.json"
}

write_manifest SAFE none false false; write_verification '"VERIFY_NON_AI"' false
[[ "$(decision)" == AUTO_ROLLBACK ]]
write_manifest SAFE none true false
[[ "$(decision)" == MANUAL ]]
write_manifest SAFE none false true
[[ "$(decision)" == MANUAL ]]
write_manifest UNSAFE irreversible false false
[[ "$(decision)" == MANUAL ]]
write_manifest SAFE none false false; write_verification '"VERIFY_AI_ONLY"' true
[[ "$(decision)" == AI_RETRY ]]
write_verification '"UNKNOWN"' false
[[ "$(decision)" == MANUAL ]]

ROLLBACK_RESULT=FAILED RECOVERY_DECISION_PATH="${work_dir}/rollback-failed.json" \
  bash "${repo_root}/infra/deploy/scripts/decide-recovery.sh" --rollback-result >/dev/null
grep -q '"decision": "MANUAL"' "${work_dir}/rollback-failed.json"
grep -q '"result": "FAILED"' "${work_dir}/rollback-failed.json"

echo 'PASS: recovery policy separates reversible, risky, AI-only, unknown and rollback failure cases'
