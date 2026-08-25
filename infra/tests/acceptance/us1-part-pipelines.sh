#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
evidence="${CI_EVIDENCE_DIR:-${repo_root}/infra/jenkins/runtime/evidence/us1}"
if ! mkdir -p "${evidence}" 2>/dev/null; then evidence="$(mktemp -d)"; fi
tests=(
  infra/tests/contract/test-component-adapter.sh
  infra/tests/integration/test-component-isolation.sh
  infra/tests/integration/test-deploy-freshness.sh
  infra/tests/integration/test-local-image-store.sh
)
for test in "${tests[@]}"; do bash "${repo_root}/${test}" | tee "${evidence}/$(basename "${test}").log"; done
grep -q 'BuildWebGL' "${repo_root}/festa-unity/Assets/_Project/Editor/CI/CIBuild.cs"
grep -q 'BuildLinuxServer' "${repo_root}/festa-unity/Assets/_Project/Editor/CI/CIBuild.cs"
grep -q "node('unity-6000.0.78f1')" "${repo_root}/infra/jenkins/pipelines/unity.groovy"
grep -q "lock(resource: 'deploy-dev-game')" "${repo_root}/infra/jenkins/pipelines/unity.groovy"
echo 'STATIC_GATE: Unity Editor/module/license execution remains SERVER-GATE' | tee "${evidence}/unity-gate.log"
echo "PASS: US1 repository-controlled acceptance"
