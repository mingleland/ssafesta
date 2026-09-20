#!/usr/bin/env bash
# Verifies that a demo-game candidate deploy changes only demo-game and records a candidate.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
repo_root="$(cd "${unity_server_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

deploy_script="${unity_server_dir}/scripts/deploy-game.sh"
fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT

content_id='sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
source_commit='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
mkdir -p "${fixture}/bin" "${fixture}/artifacts" "${fixture}/state"
printf '%s' 'fixture-secret-with-at-least-thirty-two-bytes' | base64 >"${fixture}/connection-token-secret"
: >"${fixture}/game.env"

# deploy-game.sh는 실행 중인 Demo world를 교체하기 전에 rollback 가능한
# human-approved known-good baseline을 요구한다. 이 테스트도 실제 runtime
# 계약과 동일하게 baseline을 준비한다.
cat >"${fixture}/state/known-good.json" <<'JSON'
{
  "schemaVersion": "1.0.0",
  "targetId": "demo/game",
  "releaseId": "known-good-before-test",
  "sourceCommit": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "imageRef": "festa-world:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "contentId": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "state": "KNOWN_GOOD"
}
JSON

cat >"${fixture}/release.json" <<JSON
{
  "schemaVersion": "1.0.0",
  "releaseId": "game-${source_commit}-1",
  "scm": {"provider": "gitlab", "repository": "s15-metaverse-game-sub1/S15P21A604", "branch": "develop", "commit": "${source_commit}"},
  "jenkins": {"job": "fixture", "buildNumber": 1},
  "components": [{"name": "game", "storageMode": "local-docker", "imageRef": "festa-world:${source_commit}", "contentId": "${content_id}", "sourceCommit": "${source_commit}"}],
  "rollbackSafety": {"classification": "SAFE", "dataChange": "none", "dbSchemaChanged": false, "secretOrConfigChanged": false},
  "createdAt": "2026-09-12T00:00:00Z"
}
JSON

cat >"${fixture}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${FAKE_DOCKER_CALLS}"
content_id='sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
case "${1:-}" in
  image)
    [[ "${2:-}" == inspect ]] || exit 64
    if [[ "${3:-}" == --format ]]; then
      case "${4:-}" in
        '{{.Id}}') printf '%s\n' "${content_id}" ;;
        '{{.Architecture}}') printf 'amd64\n' ;;
        *) exit 64 ;;
      esac
    fi
    ;;
  network|volume) [[ "${2:-}" == inspect ]] ;;
  ps)
    printf 'game-container|demo-game\nback-container|demo-back\nai-container|demo-ai\nweb-container|demo-web\n'
    ;;
  inspect)
    format=''
    [[ "${2:-}" == --format ]] && format="${3}" && shift 3 || shift
    case "${format}" in
      '{{.Image}}|{{.RestartCount}}') printf '%s\n' "${content_id}|0" ;;
      '{{.Image}}') printf '%s\n' "${content_id}" ;;
      *) exit 64 ;;
    esac
    ;;
  compose)
    if [[ " $* " == *' config '* ]]; then
      printf 'host_ip: 127.0.0.1\npublished: "17777"\n'
    elif [[ " $* " == *' up -d --no-deps --wait demo-game '* ]]; then
      :
    elif [[ " $* " == *' ps -q demo-game '* ]]; then
      printf 'game-container\n'
    else
      exit 64
    fi
    ;;
  *) exit 64 ;;
esac
SH
chmod +x "${fixture}/bin/docker"

export PATH="${fixture}/bin:${PATH}"
export FAKE_DOCKER_CALLS="${fixture}/docker.calls"
export RELEASE_MANIFEST_PATH="${fixture}/release.json"
export CONNECTION_TOKEN_SECRET_FILE="${fixture}/connection-token-secret"
export GAME_ENV_FILE="${fixture}/game.env"
export GAME_DEPLOY_STATE_DIR="${fixture}/state"
export CI_ARTIFACT_DIR="${fixture}/artifacts"
export GAME_COMPOSE_FILE="${unity_server_dir}/compose.yaml"
export DEMO_NETWORK_NAME='festa-demo'
# 이 시험의 범위는 "demo-game 만 교체되는가" 다. WebGL 프리팹 guard 는 game-webgl-prefab-guard.sh 가
# 따로 본다 — 여기서는 배포된 클라이언트가 없는 상태로 고정해 guard 를 건너뛰게 한다.
export WEBGL_MANIFEST_PATH="${fixture}/no-deployed-webgl.json"

bash "${deploy_script}" >/dev/null

assert_contains "${FAKE_DOCKER_CALLS}" 'compose .* up -d --no-deps --wait demo-game' 'game deploy must use --no-deps for demo-game only'
assert_not_contains "${FAKE_DOCKER_CALLS}" ' up -d .*demo-back| up -d .*demo-ai| up -d .*demo-web' 'game deploy must not start non-game demo services'
cmp -s "${CI_ARTIFACT_DIR}/non-game-restarts-before.tsv" "${CI_ARTIFACT_DIR}/non-game-restarts-after.tsv" \
  || fail 'non-game restart snapshot changed during game-only deploy'
assert_contains "${GAME_DEPLOY_STATE_DIR}/candidate.json" '"state": "CANDIDATE"' 'candidate state must be recorded before promotion'
assert_contains "${GAME_DEPLOY_STATE_DIR}/candidate.json" '"targetId": "demo/game"' 'candidate must use the demo/game target'
assert_contains "${GAME_DEPLOY_STATE_DIR}/releases/game-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-1.json" '"name": "game"' 'release manifest must be archived for later promotion or rollback'
pass 'demo-game deploy preserves non-game restart state and records a candidate'
