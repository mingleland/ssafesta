#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
scanner="${repo_root}/infra/jenkins/scripts/secret-scan.sh"
safe="${repo_root}/infra/tests/security/fixtures/safe"; leaked="${repo_root}/infra/tests/security/fixtures/leaked"
SECRET_CANARY=FESTA_CANARY_DO_NOT_COMMIT_8f31d2 bash "${scanner}" --path "${safe}"
if SECRET_CANARY=FESTA_CANARY_DO_NOT_COMMIT_8f31d2 bash "${scanner}" --path "${leaked}" >/dev/null 2>&1; then
  echo 'leaked fixture passed secret scan' >&2; exit 1
fi
echo "PASS: secret leak fixtures"
