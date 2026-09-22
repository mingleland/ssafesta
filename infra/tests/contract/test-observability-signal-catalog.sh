#!/usr/bin/env bash
# 관측 카탈로그가 PII·고카디널리티 값을 메트릭/로그 label로 허용하지 않는지 고정한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
catalog="${repo_root}/infra/observability/signal-catalog.json"
validator="${repo_root}/infra/observability/scripts/validate-signal-catalog.py"
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; assert sys.version_info >= (3, 10)' >/dev/null 2>&1; then
  python_cmd=(python3)
elif command -v uv >/dev/null 2>&1; then
  python_cmd=(uv run python)
else
  echo 'Python 3 is required' >&2
  exit 69
fi

"${python_cmd[@]}" "${validator}" "${catalog}"

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
"${python_cmd[@]}" - "${catalog}" "${work_dir}/unsafe.json" <<'PY'
import json
import pathlib
import sys

catalog = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
catalog["signals"][0]["labels"].append("userId")
pathlib.Path(sys.argv[2]).write_text(json.dumps(catalog), encoding="utf-8")
PY
if "${python_cmd[@]}" "${validator}" "${work_dir}/unsafe.json" >"${work_dir}/stdout" 2>"${work_dir}/stderr"; then
  echo 'unsafe catalog unexpectedly passed' >&2
  exit 1
fi
grep -q 'prohibited labels' "${work_dir}/stderr"
echo 'PASS: observability signal catalog and privacy policy'
