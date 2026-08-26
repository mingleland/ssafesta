#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT
bash "${repo_root}/infra/tests/security/test-secret-leak.sh"
export CI_RUNTIME_SECRET='FESTA_RUNTIME_CANARY_71ab2c' CI_BOUND_CREDENTIAL_NAMES=CI_RUNTIME_SECRET
bash "${repo_root}/infra/jenkins/scripts/with-credentials.sh" -- bash -c 'test -n "$CI_RUNTIME_SECRET"'
printf '%s\n' 'clean output' >"${tmp}/console.log"
SECRET_CANARY=FESTA_RUNTIME_CANARY_71ab2c bash "${repo_root}/infra/jenkins/scripts/secret-scan.sh" --path "${tmp}"
printf '%s\n' "$CI_RUNTIME_SECRET" >"${tmp}/cache.bin"
if SECRET_CANARY=FESTA_RUNTIME_CANARY_71ab2c bash "${repo_root}/infra/jenkins/scripts/secret-scan.sh" --path "${tmp}" >/dev/null 2>&1; then
  echo 'canary in cache was not detected' >&2; exit 1
fi
grep -q 'Credentials/ManageDomains' "${repo_root}/infra/jenkins/casc/authorization.yaml"
grep -q 'hostnameSpecification' "${repo_root}/infra/jenkins/casc/security.yaml"
echo "PASS: US4 secret safety acceptance"
