#!/usr/bin/env bash
# infra-002 JSON 계약을 기존 의존성만으로 검증하는 공통 진입점이다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/../tests/lib/assert.sh"

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <schema.json> <document.json> [document.json ...]" >&2
  exit 64
fi

schema="$1"
shift

python_bin="$(resolve_python)" || fail 'Python 3 is required'

"${python_bin}" - "${schema}" "$@" <<'PY'
import json
import pathlib
import sys

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ImportError:
    print("python package 'jsonschema' is required", file=sys.stderr)
    raise SystemExit(69)

schema_path = pathlib.Path(sys.argv[1])
document_paths = [pathlib.Path(value) for value in sys.argv[2:]]

try:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    print(f"schema load failed: {schema_path}: {exc}", file=sys.stderr)
    raise SystemExit(66)

Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=FormatChecker())
failed = False

for document_path in document_paths:
    try:
        document = json.loads(document_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"INVALID {document_path}: cannot load JSON: {exc}", file=sys.stderr)
        failed = True
        continue

    errors = sorted(validator.iter_errors(document), key=lambda error: list(error.absolute_path))
    if errors:
        failed = True
        print(f"INVALID {document_path}", file=sys.stderr)
        for error in errors:
            location = "/" + "/".join(str(part) for part in error.absolute_path)
            print(f"  {location}: {error.message}", file=sys.stderr)
    else:
        print(f"VALID {document_path}")

raise SystemExit(1 if failed else 0)
PY
