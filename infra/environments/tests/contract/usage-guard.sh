#!/usr/bin/env bash
# R2 사용량 상태 경계와 active provider 혼입 금지를 계약으로 고정한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"
install_cleanup_trap

validator="${repo_root}/infra/environments/scripts/validate-json-schema.sh"
schema="${repo_root}/specs/infra-002-environments/contracts/usage-guard.schema.json"
fixture_dir="$(mktemp -d)"
register_cleanup "${fixture_dir}"

python_bin="$(resolve_python)" || fail 'Python 3 is required'
"${python_bin}" - "${fixture_dir}" <<'PY'
import copy
import json
import pathlib
import sys

output = pathlib.Path(sys.argv[1])
base = {
    "schemaVersion": "1.0.0",
    "billingMonthUtc": "2026-08",
    "collectedAt": "2026-08-30T01:01:00Z",
    "dataFreshThrough": "2026-08-30T01:00:00Z",
    "source": "mock-test",
    "currentStorageBytes": 1,
    "projectedGbMonth": 1,
    "classARequests": 1,
    "classBRequests": 1,
    "limits": {
        "storageGbMonth": 10,
        "classARequests": 1000000,
        "classBRequests": 10000000,
        "verifiedAt": "2026-08-30",
        "sourceUrl": "https://developers.cloudflare.com/r2/pricing/"
    },
    "ratios": {
        "currentStorage": 0.1,
        "projectedStorage": 0.1,
        "classA": 0.1,
        "classB": 0.1,
        "max": 0.79
    },
    "state": "NORMAL",
    "existingReadsAllowed": True,
    "reason": "contract fixture",
    "evidenceRef": "evidence/usage.json"
}

for name, ratio, state in (
    ("79-normal", 0.79, "NORMAL"),
    ("80-warning", 0.80, "WARNING"),
    ("90-blocked", 0.90, "UPLOAD_BLOCKED"),
):
    document = copy.deepcopy(base)
    document["ratios"]["max"] = ratio
    document["state"] = state
    (output / f"{name}.json").write_text(json.dumps(document), encoding="utf-8")

stale = copy.deepcopy(base)
stale["collectedAt"] = "2026-08-30T02:01:00Z"
stale["state"] = "STALE_BLOCKED"
(output / "61-stale.json").write_text(json.dumps(stale), encoding="utf-8")

mixed = copy.deepcopy(base)
mixed["activeWriteProvider"] = "MINIO_LOCAL"
(output / "active-provider-mixed.json").write_text(json.dumps(mixed), encoding="utf-8")
PY

bash "${validator}" "${schema}" \
  "${fixture_dir}/79-normal.json" \
  "${fixture_dir}/80-warning.json" \
  "${fixture_dir}/90-blocked.json" \
  "${fixture_dir}/61-stale.json" >/dev/null

if bash "${validator}" "${schema}" "${fixture_dir}/active-provider-mixed.json" >/dev/null 2>&1; then
  fail 'usage snapshot accepted activeWriteProvider'
fi

pass 'usage guard covers 79/80/90 percent, 61-minute stale, and provider separation'
