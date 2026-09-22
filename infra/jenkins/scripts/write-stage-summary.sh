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
  fi
fi

# Node 이미지(front CI)에는 python3 가 없다. 그 하나 때문에 job 마다 apt-get 으로 약 9초를
# 쓰고 있었으므로, Python 이 없으면 이미 깔려 있는 Node 로 같은 JSON 을 만든다.
node_bin=""
if [[ -z "${python_bin}" ]]; then
  if command -v node >/dev/null 2>&1; then
    node_bin=node
  else
    echo "Python 3 or Node.js is required." >&2
    exit 69
  fi
fi

output_path="${CI_STAGE_SUMMARY_PATH:-${CI_ARTIFACT_DIR}/stage-summary.json}"
mkdir -p "$(dirname "${output_path}")"

if [[ -n "${node_bin}" ]]; then
  "${node_bin}" - "${output_path}" <<'JS'
'use strict';
const fs = require('fs');

const output = process.argv[2];
const component = process.env.CI_COMPONENT;
const commit = process.env.CI_COMMIT_SHA;
const stage = process.env.CI_STAGE;
const status = process.env.CI_STAGE_STATUS;
const failureCode = (process.env.CI_FAILURE_CODE || '').trim();

const components = new Set(['ai', 'back', 'front', 'game', 'develop']);
const statuses = new Set(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED']);
const failureCodes = new Set([
  'VALIDATION_FAILED', 'BUILD_FAILED', 'TEST_FAILED', 'PACKAGE_FAILED',
  'CONTAINER_START', 'VERIFY_NON_AI', 'VERIFY_AI_ONLY', 'DB_CHANGE',
  'SECRET_CONFIG', 'IRREVERSIBLE_CHANGE', 'UNKNOWN',
]);

function die(message) {
  process.stderr.write(message + '\n');
  process.exit(1);
}

if (!components.has(component)) die(`invalid CI_COMPONENT: ${component}`);
if (!/^[0-9a-f]{40}$/.test(commit)) die('CI_COMMIT_SHA must be a lowercase full 40-character SHA');
if (!statuses.has(status)) die(`invalid CI_STAGE_STATUS: ${status}`);
if (failureCode && !failureCodes.has(failureCode)) die(`invalid CI_FAILURE_CODE: ${failureCode}`);
if (status === 'FAILED' && !failureCode) die('CI_FAILURE_CODE is required when CI_STAGE_STATUS=FAILED');
if (status !== 'FAILED' && failureCode) die('CI_FAILURE_CODE is allowed only when CI_STAGE_STATUS=FAILED');

function parseTime(name) {
  const value = process.env[name];
  const normalized = value.endsWith('Z') ? value.slice(0, -1) + '+00:00' : value;
  const shape = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d{1,6})?)?([+-]\d{2}:?\d{2})?$/;
  if (!shape.test(normalized) || Number.isNaN(Date.parse(normalized))) {
    die(`${name} must be an ISO-8601 date-time: ${value}`);
  }
  return value;
}

const evidence = (process.env.CI_EVIDENCE_REFS || '')
  .split(/\r\n|[\n\r]/)
  .map((line) => line.trim())
  .filter((line) => line.length > 0);
for (const ref of evidence) {
  if (/(token|password|secret|authorization|cookie|webhook)=/i.test(ref)) {
    die('evidence reference appears to contain sensitive query data');
  }
}

const summary = {
  schemaVersion: '1.0.0',
  component,
  commit,
  stage,
  status,
  startedAt: parseTime('CI_STARTED_AT'),
  finishedAt: parseTime('CI_FINISHED_AT'),
  evidenceRefs: evidence,
};
if (failureCode) summary.failureCode = failureCode;

const temporary = output + '.tmp';
fs.writeFileSync(temporary, JSON.stringify(summary, null, 2) + '\n', 'utf8');
fs.renameSync(temporary, output);
process.stdout.write(output + '\n');
JS
  exit 0
fi

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
