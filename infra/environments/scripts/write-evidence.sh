#!/usr/bin/env bash
# 민감정보가 없는 구조화 검증 근거만 JSON 파일로 기록한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/../tests/lib/assert.sh"

usage() {
  echo "usage: $0 --output <file> --scenario <id> --environment <id> --release-id <id> --result PASS|FAIL|SKIP --command <sanitized> [--evidence-ref <ref> ...]" >&2
}

output=''
scenario=''
environment=''
release_id=''
result=''
command_text=''
evidence_refs=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) output="${2:-}"; shift 2 ;;
    --scenario) scenario="${2:-}"; shift 2 ;;
    --environment) environment="${2:-}"; shift 2 ;;
    --release-id) release_id="${2:-}"; shift 2 ;;
    --result) result="${2:-}"; shift 2 ;;
    --command) command_text="${2:-}"; shift 2 ;;
    --evidence-ref) evidence_refs+=("${2:-}"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

for required in output scenario environment release_id result command_text; do
  [[ -n "${!required}" ]] || { usage; echo "--${required//_/-} is required" >&2; exit 64; }
done
case "${result}" in PASS|FAIL|SKIP) ;; *) echo 'result must be PASS, FAIL, or SKIP' >&2; exit 64 ;; esac

python_bin="$(resolve_python)" || fail 'Python 3 is required'
"${python_bin}" - "${output}" "${scenario}" "${environment}" "${release_id}" "${result}" "${command_text}" "${evidence_refs[@]}" <<'PY'
import datetime
import json
import pathlib
import re
import sys

output, scenario, environment, release_id, result, command, *refs = sys.argv[1:]
values = [scenario, environment, release_id, command, *refs]
secret_patterns = (
    r"authorization\s*:",
    r"cookie\s*:",
    r"(?:password|secret|token|private[_-]?key)\s*[:=]",
    r"x-amz-(?:signature|credential)=",
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"(?:^|[;&|\s])(?:env|printenv|export\s+-p)(?:$|[;&|\s])",
)
for value in values:
    if any(re.search(pattern, value, re.IGNORECASE) for pattern in secret_patterns):
        raise SystemExit("refusing evidence containing credentials or a raw environment dump")

document = {
    "schemaVersion": "1.0.0",
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "scenario": scenario,
    "environmentId": environment,
    "releaseId": release_id,
    "sanitizedCommand": command,
    "result": result,
    "evidenceRefs": refs,
}
path = pathlib.Path(output)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

echo "PASS: sanitized evidence written to ${output}"
