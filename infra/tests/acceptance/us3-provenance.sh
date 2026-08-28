#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; repo_root="$(cd "${script_dir}/../../.." && pwd)"
evidence="${CI_EVIDENCE_DIR:-${repo_root}/infra/evidence/runtime/us3-provenance}"; mkdir -p "${evidence}"
bash "${repo_root}/infra/tests/contract/test-deployment-record.sh" | tee "${evidence}/deployment-record-contract.txt"
bash "${repo_root}/infra/tests/integration/test-provenance-chain.sh" | tee "${evidence}/provenance-chain.txt"
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
cat >"${work}/target-state.json" <<'JSON'
{"schemaVersion":"1.0.0","targetId":"integration-develop","currentReleaseId":"release-42","knownGoodReleaseId":"release-42","previousKnownGoodReleaseId":"release-41","releaseSequence":42}
JSON
STATE_DIR="${work}" bash "${repo_root}/infra/deploy/scripts/show-release.sh" | tee "${evidence}/current-release.txt"
grep -q 'current=release-42 known-good=release-42 previous=release-41' "${evidence}/current-release.txt"
echo 'PASS: deployment history and human-readable current release are reconstructable'
