#!/usr/bin/env bash
# Verifies that readiness requires the running process, listener, public WSS and approved admission.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT

content_id='sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
mkdir -p "${fixture}/bin" "${fixture}/state/releases" "${fixture}/artifacts"
: >"${fixture}/game.env"
printf 'PASS\n' >"${fixture}/approval.txt"
cat >"${fixture}/state/candidate.json" <<JSON
{"targetId":"demo/game","releaseId":"game-ready","contentId":"${content_id}","state":"CANDIDATE"}
JSON
printf 'demo-back|back-container|sha256:back|0\n' >"${fixture}/artifacts/non-game-restarts-before.tsv"
cp "${fixture}/artifacts/non-game-restarts-before.tsv" "${fixture}/artifacts/non-game-restarts-after.tsv"

cat >"${fixture}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  compose)
    if [[ " $* " == *' ps -q demo-game '* ]]; then printf 'game-container\n'; else exit 64; fi ;;
  inspect)
    case "${3:-}" in
      '{{.State.Running}}') printf 'true\n' ;;
      '{{range (index .NetworkSettings.Ports "7777/tcp")}}{{.HostIp}}:{{.HostPort}}{{end}}') printf '127.0.0.1:17777\n' ;;
      *) exit 64 ;;
    esac ;;
  *) exit 64 ;;
esac
SH
cat >"${fixture}/verify-public-wss.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == '--approval-evidence' && "$3" == '--output' ]]
printf 'websocketUpgrade=PASS\napprovedAdmission=PASS\n' >"$4"
SH
chmod +x "${fixture}/bin/docker" "${fixture}/verify-public-wss.sh"

export PATH="${fixture}/bin:${PATH}"
export DOCKER_BIN=docker
export GAME_ENV_FILE="${fixture}/game.env"
export GAME_DEPLOY_STATE_DIR="${fixture}/state"
export CI_ARTIFACT_DIR="${fixture}/artifacts"
export APPROVAL_EVIDENCE_FILE="${fixture}/approval.txt"
export PUBLIC_WSS_VERIFY_SCRIPT="${fixture}/verify-public-wss.sh"
export GAME_COMPOSE_FILE="${unity_server_dir}/compose.yaml"
export GAME_READINESS_SKIP_LISTENER_CONNECT=1

bash "${unity_server_dir}/scripts/game-readiness.sh" >/dev/null

assert_contains "${fixture}/artifacts/game-readiness.json" '"processRunning": "PASS"' 'readiness must record process state'
assert_contains "${fixture}/artifacts/game-readiness.json" '"internalListener": "PASS"' 'readiness must record listener state'
assert_contains "${fixture}/artifacts/game-readiness.json" '"externalWebSocket": "PASS"' 'readiness must record WSS state'
assert_contains "${fixture}/artifacts/game-readiness.json" '"approvedAdmission": "PASS"' 'readiness must record admission state'
printf '{}' >"${fixture}/state/releases/game-ready.json"
bash "${unity_server_dir}/scripts/promote-game.sh"
assert_contains "${fixture}/state/current.json" '"state": "CURRENT/KNOWN_GOOD"' 'promotion must update current only after all gates pass'
assert_contains "${fixture}/state/known-good.json" '"releaseId": "game-ready"' 'promotion must update known-good with the verified candidate'

mkdir -p "${fixture}/failed-state/releases"
cp "${fixture}/state/releases/game-ready.json" "${fixture}/failed-state/releases/game-ready.json"
sed 's/"state": "CURRENT\/KNOWN_GOOD"/"state": "CANDIDATE"/' "${fixture}/state/candidate.json" >"${fixture}/failed-state/candidate.json"
sed 's/"externalWebSocket": "PASS"/"externalWebSocket": "FAIL"/' "${fixture}/artifacts/game-readiness.json" >"${fixture}/failed-readiness.json"
if GAME_DEPLOY_STATE_DIR="${fixture}/failed-state" GAME_READINESS_PATH="${fixture}/failed-readiness.json" bash "${unity_server_dir}/scripts/promote-game.sh" >/dev/null 2>&1; then
  fail 'promotion must reject a candidate with a failed external WSS gate'
fi
[[ ! -e "${fixture}/failed-state/current.json" ]] || fail 'failed readiness must not update current'
pass 'game readiness requires all promotion gates'
