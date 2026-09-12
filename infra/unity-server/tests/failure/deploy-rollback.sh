#!/usr/bin/env bash
# Verifies a failed candidate is restored once to the last known-good game image only.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT

candidate_id='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
known_good_id='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
mkdir -p "${fixture}/bin" "${fixture}/state/releases" "${fixture}/artifacts"
: >"${fixture}/game.env"
cat >"${fixture}/state/candidate.json" <<JSON
{"targetId":"demo/game","releaseId":"candidate-1","imageRef":"festa-world:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","contentId":"${candidate_id}","state":"CANDIDATE"}
JSON
cat >"${fixture}/state/known-good.json" <<JSON
{"targetId":"demo/game","releaseId":"known-good-1","imageRef":"festa-world:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","contentId":"${known_good_id}","state":"CURRENT/KNOWN_GOOD"}
JSON

cat >"${fixture}/bin/docker" <<SH
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "\$*" >>"\${FAKE_DOCKER_CALLS}"
case "\${1:-}" in
  image) [[ "\${2:-}" == inspect && "\${4:-}" == '{{.Id}}' ]] && printf '%s\\n' '${known_good_id}' || exit 64 ;;
  compose)
    if [[ " \$* " == *' up -d --no-deps --wait demo-game '* ]]; then :
    elif [[ " \$* " == *' ps -q demo-game '* ]]; then printf 'game-container\\n'
    else exit 64; fi ;;
  inspect)
    [[ "\${3:-}" == '{{.Image}}' ]] && printf '%s\\n' '${known_good_id}' || exit 64 ;;
  *) exit 64 ;;
esac
SH
chmod +x "${fixture}/bin/docker"

export PATH="${fixture}/bin:${PATH}"
export FAKE_DOCKER_CALLS="${fixture}/docker.calls"
export DOCKER_BIN=docker
export GAME_ENV_FILE="${fixture}/game.env"
export GAME_DEPLOY_STATE_DIR="${fixture}/state"
export CI_ARTIFACT_DIR="${fixture}/artifacts"
export GAME_COMPOSE_FILE="${unity_server_dir}/compose.yaml"
export GAME_ROLLBACK_REASON='external_wss_failed'

bash "${unity_server_dir}/scripts/rollback-game.sh" >/dev/null

assert_contains "${FAKE_DOCKER_CALLS}" 'compose .* up -d --no-deps --wait demo-game' 'rollback must replace demo-game without dependencies'
assert_contains "${fixture}/state/failed/candidate-1.json" '"state": "FAILED"' 'failed candidate must be recorded'
assert_contains "${fixture}/state/rollback.json" '"state": "ROLLED_BACK"' 'rollback state must be recorded'
assert_contains "${fixture}/state/candidate.json" '"state": "ROLLED_BACK"' 'candidate state must show recovery'
if bash "${unity_server_dir}/scripts/rollback-game.sh" >/dev/null 2>&1; then
  fail 'automatic rollback must not retry the same candidate'
fi
pass 'failed candidate rolls back once to known-good demo-game'
