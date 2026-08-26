#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <release|verification|deployment-record|detection-rule> <json-file> [json-file ...]" >&2
}

if [[ $# -lt 2 ]]; then
  usage
  exit 64
fi

contract_type="$1"
shift

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

case "${contract_type}" in
  release) schema="${repo_root}/specs/infra-001-ci-cd-pipelines/contracts/release-manifest.schema.json" ;;
  verification) schema="${repo_root}/specs/infra-001-ci-cd-pipelines/contracts/verification-result.schema.json" ;;
  deployment-record) schema="${repo_root}/infra/contracts/deployment-record.schema.json" ;;
  detection-rule) schema="${repo_root}/specs/infra-001-ci-cd-pipelines/contracts/detection-rule.schema.json" ;;
  *)
    echo "Unknown contract type: ${contract_type}" >&2
    usage
    exit 64
    ;;
esac

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

"${python_bin}" - "${schema}" "$@" <<'PY'
import json
import pathlib
import sys

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    print("python package 'jsonschema' is required (pinned in infra/versions.env)", file=sys.stderr)
    raise SystemExit(69)

schema_path = pathlib.Path(sys.argv[1])
instance_paths = [pathlib.Path(value) for value in sys.argv[2:]]

try:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    print(f"schema load failed: {schema_path}: {exc}", file=sys.stderr)
    raise SystemExit(66)

Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=FormatChecker())
failed = False

for instance_path in instance_paths:
    try:
        instance = json.loads(instance_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"INVALID {instance_path}: cannot load JSON: {exc}", file=sys.stderr)
        failed = True
        continue

    errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.absolute_path))
    if errors:
        failed = True
        print(f"INVALID {instance_path}", file=sys.stderr)
        for error in errors:
            location = "/" + "/".join(str(part) for part in error.absolute_path)
            print(f"  {location}: {error.message}", file=sys.stderr)
    else:
        print(f"VALID {instance_path}")

raise SystemExit(1 if failed else 0)
PY
