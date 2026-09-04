#!/usr/bin/env bash
# dev 환경 선언이 네 개의 독립 component target과 안전한 release 참조를 유지하는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

manifest="${repo_root}/infra/environments/config/manifests/dev.json"
schema="${repo_root}/specs/infra-002-environments/contracts/environment-manifest.schema.json"
validator="${repo_root}/infra/environments/scripts/validate-json-schema.sh"

assert_file "${manifest}"
bash "${validator}" "${schema}" "${manifest}" >/dev/null

python_bin="$(resolve_python)" || fail 'Python 3 is required'
"${python_bin}" - "${manifest}" <<'PY'
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_targets = {"ai": "dev-ai", "back": "dev-back", "front": "dev-front", "game": "dev-game"}
if manifest["deploymentTargetIds"] != expected_targets:
    raise SystemExit("dev deployment targets must be exactly dev-ai/dev-back/dev-front/dev-game")

services = manifest["runtimeServices"]
if {service["serviceId"] for service in services} != set(expected_targets.values()):
    raise SystemExit("dev manifest must declare exactly one runtime service per target")
for field in ("serviceId", "networkRefs", "persistentVolumeRefs"):
    values = []
    for service in services:
        value = service[field]
        values.extend(value if isinstance(value, list) else [value])
    if len(values) != len(set(values)):
        raise SystemExit(f"dev {field} values must be unique")

release_ref = manifest["releaseManifestRef"]
if not release_ref.startswith("infra/deploy/state/runtime/") or "${RELEASE_ID}" not in release_ref:
    raise SystemExit("dev release reference must address an immutable infra-001 release path")

for mock in manifest.get("mockAdapters", []):
    if not mock["approvalRef"]:
        raise SystemExit("mock adapters require an approval reference")
PY

pass 'dev manifest keeps targets, service resources, immutable releases, and mocks explicit'
