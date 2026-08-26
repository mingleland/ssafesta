#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
evidence="${CI_EVIDENCE_DIR:-${repo_root}/infra/evidence/runtime/us2-develop-release}"; mkdir -p "${evidence}"
for test in test-develop-release.sh test-recovery-policy.sh test-atomic-promotion.sh; do
  bash "${repo_root}/infra/tests/integration/${test}" | tee "${evidence}/${test%.sh}.txt"
done

work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
mkdir -p "${work}/state" "${work}/evidence"; cp "${repo_root}/infra/tests/contract/fixtures/release-valid.json" "${work}/known-good.json"
STATE_DIR="${work}/state" KNOWN_GOOD_MANIFEST_PATH="${work}/known-good.json" CI_ARTIFACT_DIR="${work}/evidence" \
RELEASE_ID=candidate-safe DEPLOY_TARGET=integration-develop ROLLBACK_DEPLOY_COMMAND=true ROLLBACK_VERIFY_COMMAND=true \
  bash "${repo_root}/infra/deploy/scripts/rollback-release.sh" | tee "${evidence}/auto-rollback.txt"
if STATE_DIR="${work}/state" KNOWN_GOOD_MANIFEST_PATH="${work}/known-good.json" CI_ARTIFACT_DIR="${work}/evidence" \
  RELEASE_ID=candidate-safe DEPLOY_TARGET=integration-develop ROLLBACK_DEPLOY_COMMAND=true ROLLBACK_VERIFY_COMMAND=true \
  bash "${repo_root}/infra/deploy/scripts/rollback-release.sh" >/dev/null 2>&1; then echo 'FAIL: rollback repeated' >&2; exit 1; fi

STATE_DIR="${work}/failed-state" KNOWN_GOOD_MANIFEST_PATH="${work}/known-good.json" CI_ARTIFACT_DIR="${work}/evidence" \
RELEASE_ID=candidate-failed DEPLOY_TARGET=integration-develop ROLLBACK_DEPLOY_COMMAND=false ROLLBACK_VERIFY_COMMAND=true \
  bash "${repo_root}/infra/deploy/scripts/rollback-release.sh" >/dev/null 2>&1 && { echo 'FAIL: rollback failure passed' >&2; exit 1; }
grep -q '"result": "FAILED"' "${work}/evidence/rollback-decision.json"
grep -q 'reversible, risky, AI-only, unknown' "${evidence}/test-recovery-policy.txt"
echo 'PASS: normal, rollback, manual, AI-only and rollback-failure policies are covered'
