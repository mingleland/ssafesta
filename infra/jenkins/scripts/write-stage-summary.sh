#!/usr/bin/env bash
set -euo pipefail

required=(CI_ARTIFACT_DIR CI_COMPONENT CI_COMMIT_SHA CI_STAGE CI_STAGE_STATUS CI_STARTED_AT CI_FINISHED_AT)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Required environment variable is missing: ${name}" >&2
    exit 64
  fi
done

python_bin="${PYTHON_BIN:-}"
if [[ -z "${python_bin}" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
    python_bin=python3
  elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
    python_bin=python
  else
    echo "Python 3 is required." >&2
    exit 69
  fi
fi

mkdir -p "${CI_ARTIFACT_DIR}"
output_path="${CI_STAGE_SUMMARY_PATH:-${CI_ARTIFACT_DIR}/stage-summary.json}"

"${python_bin}" - "${output_path}" <<'PY'
import datetime as dt
import json
import os
import pathlib
import re
import sys

output = pathlib.Path(sys.argv[1])
component = os.environ["CI_COMPONENT"]
commit = os.environ["CI_COMMIT_SHA"]
stage = os.environ["CI_STAGE"]
status = os.environ["CI_STAGE_STATUS"]
failure_code = os.environ.get("CI_FAILURE_CODE", "").strip()

components = {"ai", "back", "front", "game", "develop"}
statuses = {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "SUPERSEDED"}
failure_codes = {
    "VALIDATION_FAILED", "TEST_FAILED", "BUILD_FAILED", "PACKAGE_FAILED",
    "CONTAINER_START", "VERIFY_NON_AI", "VERIFY_AI_ONLY", "DB_CHANGE",
    "SECRET_CONFIG", "IRREVERSIBLE_CHANGE", "UNKNOWN"
}

if component not in components:
    raise SystemExit(f"invalid CI_COMPONENT: {component}")
if not re.fullmatch(r"[0-9a-f]{40}", commit):
    raise SystemExit("CI_COMMIT_SHA must be a lowercase full 40-character SHA")
if status not in statuses:
    raise SystemExit(f"invalid CI_STAGE_STATUS: {status}")
if failure_code and failure_code not in failure_codes:
    raise SystemExit(f"invalid CI_FAILURE_CODE: {failure_code}")
if status == "FAILED" and not failure_code:
    raise SystemExit("CI_FAILURE_CODE is required when CI_STAGE_STATUS=FAILED")
if status != "FAILED" and failure_code:
    raise SystemExit("CI_FAILURE_CODE is allowed only when CI_STAGE_STATUS=FAILED")

def parse_time(name: str) -> str:
    value = os.environ[name]
    normalized = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        dt.datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise SystemExit(f"{name} must be an ISO-8601 date-time: {exc}")
    return value

evidence = [line.strip() for line in os.environ.get("CI_EVIDENCE_REFS", "").splitlines() if line.strip()]
for ref in evidence:
    if re.search(r"(?i)(token|password|secret|authorization|cookie|webhook)=", ref):
        raise SystemExit("evidence reference appears to contain sensitive query data")

summary = {
    "schemaVersion": "1.0.0",
    "component": component,
    "commit": commit,
    "stage": stage,
    "status": status,
    "startedAt": parse_time("CI_STARTED_AT"),
    "finishedAt": parse_time("CI_FINISHED_AT"),
    "evidenceRefs": evidence,
}
if failure_code:
    summary["failureCode"] = failure_code

temporary = output.with_name(output.name + ".tmp")
temporary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
temporary.replace(output)
print(output)
PY
