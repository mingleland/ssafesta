#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
repo_root="$(cd "${unity_server_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

forbidden_files="$(git -C "${repo_root}" ls-files -- \
  'infra/unity-server/*.pem' 'infra/unity-server/*.p12' 'infra/unity-server/*.key' \
  'infra/unity-server/**/.env' 'infra/unity-server/**/.env.*' \
  ':!infra/unity-server/.env.example' 2>/dev/null || true)"
[[ -z "${forbidden_files}" ]] || fail "forbidden Secret files are tracked: ${forbidden_files}"

scan_files=()
while IFS= read -r -d '' file; do
  scan_files+=("${file}")
done < <(find "${unity_server_dir}" -type f \
  ! -path '*/tests/security/secret-scan.sh' \
  ! -name '*.md' -print0)

if [[ ${#scan_files[@]} -gt 0 ]]; then
  if grep -En -- '-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----' "${scan_files[@]}"; then
    fail 'private key material detected'
  fi
  if grep -En -- 'Authorization:[[:space:]]*(Bearer|Basic)[[:space:]]+[A-Za-z0-9._~+/-]{12,}' "${scan_files[@]}"; then
    fail 'authorization credential detected'
  fi
  if grep -En -- '^CONNECTION_TOKEN_SECRET=.+$' "${scan_files[@]}"; then
    fail 'connection token Secret value detected'
  fi
  if grep -En -- 'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}' "${scan_files[@]}"; then
    fail 'JWT-like token detected'
  fi
fi

pass 'no tracked Secret file, private key, credential or JWT-like token'
