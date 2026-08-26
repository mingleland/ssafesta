#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
validator="${repo_root}/infra/jenkins/scripts/validate-contracts.sh"
fixtures="${script_dir}/fixtures"

bash "${validator}" deployment-record "${fixtures}/deployment-record-active.json" "${fixtures}/deployment-record-rolled-back.json"
if bash "${validator}" deployment-record "${fixtures}/deployment-record-invalid.json" >/dev/null 2>&1; then
  echo 'FAIL: invalid deployment state transition fixture passed' >&2; exit 1
fi
echo 'PASS: deployment record schema covers active and rollback histories'
