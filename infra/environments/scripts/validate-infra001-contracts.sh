#!/usr/bin/env bash
# 환경 매니페스트가 infra-001 정본 계약과 같은 release·target을 참조하는지 검증한다.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <environment-manifest.json> [verification-result.json]" >&2
  exit 64
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
source "${repo_root}/infra/environments/tests/lib/assert.sh"
manifest="$1"
verification="${2:-}"
environment_schema="${repo_root}/specs/infra-002-environments/contracts/environment-manifest.schema.json"
release_schema="${repo_root}/specs/infra-001-ci-cd-pipelines/contracts/release-manifest.schema.json"
verification_schema="${repo_root}/specs/infra-001-ci-cd-pipelines/contracts/verification-result.schema.json"
validator="${script_dir}/validate-json-schema.sh"

for required in "${manifest}" "${environment_schema}" "${release_schema}" "${verification_schema}"; do
  [[ -f "${required}" ]] || { echo "missing contract input: ${required}" >&2; exit 66; }
done

bash "${validator}" "${environment_schema}" "${manifest}" >/dev/null

python_bin="$(resolve_python)" || fail 'Python 3 is required'
release_ref="$("${python_bin}" -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["releaseManifestRef"])' "${manifest}")"
if [[ "${release_ref}" = /* ]]; then
  release_manifest="${release_ref}"
else
  release_manifest="${repo_root}/${release_ref}"
fi
[[ -f "${release_manifest}" ]] || { echo "missing release manifest reference: ${release_ref}" >&2; exit 66; }
bash "${validator}" "${release_schema}" "${release_manifest}" >/dev/null

if [[ -n "${verification}" ]]; then
  [[ -f "${verification}" ]] || { echo "missing verification result: ${verification}" >&2; exit 66; }
  bash "${validator}" "${verification_schema}" "${verification}" >/dev/null
  "${python_bin}" - "${manifest}" "${release_manifest}" "${verification}" <<'PY'
import json
import pathlib
import sys

environment = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
release = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
verification = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding="utf-8"))

targets = set(environment["deploymentTargetIds"].values())
if verification["targetId"] not in targets:
    raise SystemExit(f"verification target is not declared by environment: {verification['targetId']}")
if verification["releaseId"] != release["releaseId"]:
    raise SystemExit("verification releaseId does not match release manifest")
PY
fi

echo 'PASS: infra-001 schemas are referenced without duplication'
