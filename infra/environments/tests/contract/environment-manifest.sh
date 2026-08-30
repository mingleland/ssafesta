#!/usr/bin/env bash
# 환경 매니페스트의 정상 사례와 닫힌 실패 경계를 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
source "${script_dir}/../lib/assert.sh"

validator="${repo_root}/infra/environments/scripts/validate-json-schema.sh"
schema="${repo_root}/specs/infra-002-environments/contracts/environment-manifest.schema.json"
fixtures="${script_dir}/fixtures"

bash "${validator}" "${schema}" "${fixtures}/environment-manifest-valid.json" >/dev/null

for invalid in \
  environment-manifest-invalid-target.json \
  environment-manifest-invalid-secret.json; do
  if bash "${validator}" "${schema}" "${fixtures}/${invalid}" >/dev/null 2>&1; then
    fail "invalid environment manifest passed: ${invalid}"
  fi
done

pass 'environment manifest accepts the valid boundary and rejects invalid fixtures'
