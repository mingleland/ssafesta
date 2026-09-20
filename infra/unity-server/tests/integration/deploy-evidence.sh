#!/usr/bin/env bash
# Verifies deploy evidence keeps release/readiness data without leaking runtime secrets.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT

mkdir -p "${fixture}/state" "${fixture}/artifacts"
cat >"${fixture}/state/current.json" <<'JSON'
{"targetId":"demo/game","releaseId":"game-release-1","sourceCommit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","imageRef":"festa-world:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","contentId":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","state":"CURRENT/KNOWN_GOOD"}
JSON
cat >"${fixture}/artifacts/game-readiness.json" <<'JSON'
{"processRunning":"PASS","internalListener":"PASS","externalWebSocket":"PASS","approvedAdmission":"PASS"}
JSON
printf 'demo-back|back-container|sha256:back|0\n' >"${fixture}/artifacts/non-game-restarts-before.tsv"
cp "${fixture}/artifacts/non-game-restarts-before.tsv" "${fixture}/artifacts/non-game-restarts-after.tsv"

GAME_DEPLOY_STATE_DIR="${fixture}/state" \
CI_ARTIFACT_DIR="${fixture}/artifacts" \
CLIENT_RELEASE_REF='webgl-7318f297a8cb' \
bash "${unity_server_dir}/scripts/collect-deploy-evidence.sh" >/dev/null

evidence="${fixture}/artifacts/deploy-evidence.json"
assert_contains "${evidence}" '"clientReleaseRef": "webgl-7318f297a8cb"' 'evidence must record the client release reference'
assert_contains "${evidence}" '"nonGameRestartDelta": 0' 'evidence must record unchanged non-game restarts'
assert_contains "${evidence}" '"approvedAdmission": "PASS"' 'evidence must record readiness stages'
assert_not_contains "${evidence}" 'TOKEN|SECRET|Authorization' 'evidence must not include sensitive runtime values'
pass 'deploy evidence records only release and readiness metadata'
