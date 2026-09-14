#!/usr/bin/env bash
# Cloudflare GraphQL의 계정 전체 R2 storage·operation 측정값을 usage guard 입력으로 저장한다.
set -euo pipefail

usage() {
  echo "usage: $0 --limits <limits.json> --output <collected.json> [--response-file <graphql-response.json>]" >&2
}

limits=''
output=''
response_file=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --limits) limits="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --response-file) response_file="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 64 ;;
  esac
done
[[ -n "${limits}" && -n "${output}" ]] || { usage; exit 64; }
[[ -f "${limits}" ]] || { echo "limits file not found: ${limits}" >&2; exit 66; }

if [[ -z "${response_file}" ]]; then
  : "${CLOUDFLARE_ACCOUNT_ID:?CLOUDFLARE_ACCOUNT_ID is required}"
  : "${CLOUDFLARE_ANALYTICS_TOKEN:?CLOUDFLARE_ANALYTICS_TOKEN is required}"
  response_file="$(mktemp)"
  trap 'rm -f -- "${response_file}"' EXIT
  month_start="$(date -u +%Y-%m-01T00:00:00Z)"
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  query='query R2Usage($accountTag: String!, $start: DateTime!, $end: DateTime!) { viewer { accounts(filter: {accountTag: $accountTag}) { r2OperationsAdaptiveGroups(limit: 10000, filter: {datetime_geq: $start, datetime_leq: $end}) { dimensions { action } sum { requests } } r2StorageAdaptiveGroups(limit: 10000, filter: {datetime_geq: $start, datetime_leq: $end}) { max { payloadSize } } } } }'
  curl --fail --silent --show-error --request POST 'https://api.cloudflare.com/client/v4/graphql' \
    --header "Authorization: Bearer ${CLOUDFLARE_ANALYTICS_TOKEN}" \
    --header 'Content-Type: application/json' \
    --data "$(jq -nc --arg query "${query}" --arg account "${CLOUDFLARE_ACCOUNT_ID}" --arg start "${month_start}" --arg end "${now}" '{query:$query,variables:{accountTag:$account,start:$start,end:$end}}')" \
    --output "${response_file}"
fi

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }
"${python_bin}" - "${limits}" "${response_file}" "${output}" <<'PY'
import datetime as dt
import json
import pathlib
import tempfile
import sys

limits_path, response_path, output_path = map(pathlib.Path, sys.argv[1:])

try:
    limits = json.loads(limits_path.read_text(encoding="utf-8"))
    response = json.loads(response_path.read_text(encoding="utf-8"))
    if response.get("errors"):
        raise ValueError("Cloudflare GraphQL returned errors")
    account = response["data"]["viewer"]["accounts"][0]
except (OSError, KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
    raise SystemExit(f"invalid Cloudflare R2 response: {exc}")

def number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
        raise ValueError("metric must be a non-negative number")
    return int(value)

class_a_actions = {"PUT", "POST", "LIST", "COPY", "PUTOBJECT", "POSTOBJECT", "LISTBUCKET", "COPYOBJECT"}
class_b_actions = {"GET", "HEAD", "GETOBJECT", "HEADOBJECT"}
class_a = class_b = 0
try:
    for group in account.get("r2OperationsAdaptiveGroups", []):
        action = str(group["dimensions"]["action"]).replace("_", "").upper()
        requests = number(group["sum"]["requests"])
        if action in class_a_actions:
            class_a += requests
        elif action in class_b_actions:
            class_b += requests
        else:
            raise ValueError(f"unclassified R2 operation: {action}")
    storage_groups = account.get("r2StorageAdaptiveGroups", [])
    current_storage = max((number(group["max"]["payloadSize"]) for group in storage_groups), default=0)
    for key in ("storageGbMonth", "classARequests", "classBRequests", "verifiedAt", "sourceUrl"):
        if key not in limits:
            raise ValueError(f"limits.{key} is required")
except (KeyError, TypeError, ValueError) as exc:
    raise SystemExit(f"invalid Cloudflare R2 metric: {exc}")

now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
result = {
    "schemaVersion": "1.0.0",
    "billingMonthUtc": now.strftime("%Y-%m"),
    "collectedAt": now.isoformat().replace("+00:00", "Z"),
    "dataFreshThrough": now.isoformat().replace("+00:00", "Z"),
    "source": "cloudflare-graphql",
    "currentStorageBytes": current_storage,
    "classARequests": class_a,
    "classBRequests": class_b,
    "limits": limits,
    "evidenceRef": f"r2-usage/{now.strftime('%Y%m%dT%H%M%SZ')}.json",
}
output_path.parent.mkdir(parents=True, exist_ok=True)
with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=output_path.parent, delete=False) as handle:
    json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
    handle.write("\n")
    temporary = pathlib.Path(handle.name)
temporary.replace(output_path)
PY
