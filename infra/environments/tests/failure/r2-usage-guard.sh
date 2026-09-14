#!/usr/bin/env bash
# R2 사용량 경계와 60분 stale fail-closed 판정을 실제 evaluator 입력으로 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
install_cleanup_trap

temp_dir="$(mktemp -d)"
register_cleanup "${temp_dir}"
evaluator="${repo_root}/infra/environments/storage/usage-guard/evaluate.sh"
validator="${repo_root}/infra/environments/scripts/validate-json-schema.sh"
schema="${repo_root}/specs/infra-002-environments/contracts/usage-guard.schema.json"

write_input() {
  local name="$1" ratio="$2" fresh="$3"
  python3 - "${temp_dir}/${name}.json" "${ratio}" "${fresh}" <<'PY'
import json
import pathlib
import sys

path, ratio, fresh = pathlib.Path(sys.argv[1]), float(sys.argv[2]), sys.argv[3]
path.write_text(json.dumps({
    "schemaVersion": "1.0.0", "billingMonthUtc": "2026-08", "collectedAt": "2026-08-30T01:00:00Z",
    "dataFreshThrough": fresh, "source": "mock-test", "currentStorageBytes": 0,
    "classARequests": int(ratio * 1000), "classBRequests": 0,
    "limits": {"storageGbMonth": 10, "classARequests": 1000, "classBRequests": 1000,
               "verifiedAt": "2026-08-24", "sourceUrl": "https://developers.cloudflare.com/r2/pricing/"},
    "evidenceRef": "test-evidence"
}), encoding="utf-8")
PY
}

check_state() {
  local name="$1" expected="$2" actual
  bash "${evaluator}" --input "${temp_dir}/${name}.json" --output "${temp_dir}/${name}.snapshot.json" --now '2026-08-30T02:00:00Z'
  bash "${validator}" "${schema}" "${temp_dir}/${name}.snapshot.json" >/dev/null
  actual="$(python3 -c "import json; print(json.load(open('${temp_dir}/${name}.snapshot.json'))['state'])")"
  assert_equals "${expected}" "${actual}" "${name} state"
}

write_input normal 0.79 '2026-08-30T01:00:00Z'
write_input warning 0.80 '2026-08-30T01:00:00Z'
write_input blocked 0.90 '2026-08-30T01:00:00Z'
write_input stale 0.01 '2026-08-30T00:59:00Z'
check_state normal NORMAL
check_state warning WARNING
check_state blocked UPLOAD_BLOCKED
check_state stale STALE_BLOCKED
pass 'R2 usage evaluator covers 79/80/90 percent and 61-minute stale boundaries'
