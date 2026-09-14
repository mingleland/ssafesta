#!/usr/bin/env bash
# Cloudflare R2 측정값을 업로드 허용 여부가 담긴 검증 가능한 usage snapshot으로 바꾼다.
set -euo pipefail

usage() {
  echo "usage: $0 --input <collected.json> --output <snapshot.json> [--now <ISO-8601>]" >&2
}

input=''
output=''
now=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) input="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --now) now="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 64 ;;
  esac
done
[[ -n "${input}" && -n "${output}" ]] || { usage; exit 64; }

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }

"${python_bin}" - "${input}" "${output}" "${now}" <<'PY'
import datetime as dt
import json
import math
import pathlib
import sys
import tempfile

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
override_now = sys.argv[3]

def parse_time(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(dt.timezone.utc)

try:
    raw = json.loads(source.read_text(encoding="utf-8"))
    collected_at = parse_time(raw["collectedAt"])
    fresh_through = parse_time(raw["dataFreshThrough"])
    limits = raw["limits"]
    current_bytes = int(raw["currentStorageBytes"])
    class_a = int(raw["classARequests"])
    class_b = int(raw["classBRequests"])
    for name in ("storageGbMonth", "classARequests", "classBRequests"):
        if float(limits[name]) <= 0:
            raise ValueError(f"limits.{name} must be positive")
except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
    raise SystemExit(f"invalid collected R2 usage input: {exc}")

now = parse_time(override_now) if override_now else dt.datetime.now(dt.timezone.utc)
if now < fresh_through:
    raise SystemExit("invalid collected R2 usage input: dataFreshThrough is in the future")

gib = 1024 ** 3
current_storage = current_bytes / gib / float(limits["storageGbMonth"])
# The collector records the account-wide current peak. Treating that as the remaining month's
# average is conservative without inventing a growth rate that the account data does not prove.
projected_gb_month = current_bytes / gib
projected_storage = projected_gb_month / float(limits["storageGbMonth"])
class_a_ratio = class_a / float(limits["classARequests"])
class_b_ratio = class_b / float(limits["classBRequests"])
maximum = max(current_storage, projected_storage, class_a_ratio, class_b_ratio)
stale = now - fresh_through > dt.timedelta(minutes=60)
if stale:
    state, reason = "STALE_BLOCKED", "R2 usage snapshot is older than 60 minutes"
elif maximum >= 0.90:
    state, reason = "UPLOAD_BLOCKED", "R2 usage reached the 90 percent upload block threshold"
elif maximum >= 0.80:
    state, reason = "WARNING", "R2 usage reached the 80 percent warning threshold"
else:
    state, reason = "NORMAL", "R2 usage is below the 80 percent warning threshold"

snapshot = {
    "schemaVersion": "1.0.0",
    "billingMonthUtc": raw["billingMonthUtc"],
    "collectedAt": collected_at.isoformat().replace("+00:00", "Z"),
    "dataFreshThrough": fresh_through.isoformat().replace("+00:00", "Z"),
    "source": raw["source"],
    "currentStorageBytes": current_bytes,
    "projectedGbMonth": projected_gb_month,
    "classARequests": class_a,
    "classBRequests": class_b,
    "limits": limits,
    "ratios": {
        "currentStorage": current_storage,
        "projectedStorage": projected_storage,
        "classA": class_a_ratio,
        "classB": class_b_ratio,
        "max": maximum,
    },
    "state": state,
    "existingReadsAllowed": True,
    "reason": reason,
    "evidenceRef": raw["evidenceRef"],
}
destination.parent.mkdir(parents=True, exist_ok=True)
with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=destination.parent, delete=False) as handle:
    json.dump(snapshot, handle, ensure_ascii=False, separators=(",", ":"))
    handle.write("\n")
    temporary = pathlib.Path(handle.name)
temporary.replace(destination)
PY
