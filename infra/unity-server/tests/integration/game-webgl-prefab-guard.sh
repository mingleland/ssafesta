#!/usr/bin/env bash
# 배포된 WebGL 과 NGO 프리팹이 어긋난 후보는 운영 월드를 건드리지 않고 건너뛴다 (INFRA-T-111).
#
# 2026-09-14~16 사고의 실제 커밋 셋을 그대로 쓴다. 통과만 보고 넘기지 않으려고 docker 를 부르면
# 곧바로 97 로 죽는 stub 을 PATH 에 심어 둔다 — 판정이 docker 앞에서 끝나는지까지 증명한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
unity_server_dir="$(cd "${script_dir}/../.." && pwd)"
repo_root="$(cd "${unity_server_dir}/../.." && pwd)"
source "${unity_server_dir}/tests/lib/assert.sh"

deploy_script="${unity_server_dir}/scripts/deploy-game.sh"
prefab_tree='festa-unity/Assets/_Project/Prefabs'
content_id='sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

webgl_commit='bf90136ab9b8c6edc0bc9b002e502f63229817c2'   # demo 에 배포돼 있던 WebGL
same_prefabs='48c8e390a8da7876a6bd1c2988703acb8ad1c0c6'   # 커밋은 다르지만 프리팹이 같다 (복구에 쓴 이미지)
stale_prefabs='85ae541f871be1c612538b9898349a3935d25664'  # 프리팹이 어긋난다 (사고를 일으킨 이미지)

for commit in "${webgl_commit}" "${same_prefabs}" "${stale_prefabs}"; do
  git -C "${repo_root}" rev-parse "${commit}:${prefab_tree}" >/dev/null 2>&1 \
    || { skip "history for ${commit} is unavailable (shallow clone?); prefab guard check deferred"; exit 0; }
done

fixture="$(mktemp -d)"
trap 'rm -rf "${fixture}"' EXIT
mkdir -p "${fixture}/state" "${fixture}/artifacts" "${fixture}/bin"
printf '%s' 'fixture-secret-with-at-least-thirty-two-bytes' | base64 >"${fixture}/connection-token-secret"
: >"${fixture}/game.env"

cat >"${fixture}/bin/docker" <<'SH'
#!/usr/bin/env bash
echo 'REACHED_DOCKER' >&2
exit 97
SH
chmod +x "${fixture}/bin/docker"
export PATH="${fixture}/bin:${PATH}"

export CONNECTION_TOKEN_SECRET_FILE="${fixture}/connection-token-secret"
export GAME_ENV_FILE="${fixture}/game.env"
export GAME_DEPLOY_STATE_DIR="${fixture}/state"
export CI_ARTIFACT_DIR="${fixture}/artifacts"
export GAME_COMPOSE_FILE="${unity_server_dir}/compose.yaml"
export DEMO_NETWORK_NAME='festa-demo'
export RELEASE_MANIFEST_PATH="${fixture}/release.json"

write_release() {
  cat >"${fixture}/release.json" <<JSON
{
  "schemaVersion": "1.0.0",
  "releaseId": "game-${1}-1",
  "scm": {"provider": "gitlab", "repository": "s15-metaverse-game-sub1/S15P21A604", "branch": "develop", "commit": "${1}"},
  "jenkins": {"job": "fixture", "buildNumber": 1},
  "components": [{"name": "game", "storageMode": "local-docker", "imageRef": "festa-game:${1}", "contentId": "${content_id}", "sourceCommit": "${1}"}],
  "rollbackSafety": {"classification": "SAFE", "dataChange": "none", "dbSchemaChanged": false, "secretOrConfigChanged": false},
  "createdAt": "2026-09-16T00:00:00Z"
}
JSON
}

run_deploy() {
  set +e
  bash "${deploy_script}" >"${fixture}/out.log" 2>&1
  deploy_status=$?
  set -e
}

# ① 프리팹이 어긋나면 docker 에 닿기 전에 75 로 건너뛴다.
printf '{"schemaVersion":"1.0.0","sourceCommit":"%s","sourceBranch":"develop"}\n' "${webgl_commit}" >"${fixture}/webgl.json"
export WEBGL_MANIFEST_PATH="${fixture}/webgl.json"
write_release "${stale_prefabs}"
run_deploy
[[ "${deploy_status}" -eq 75 ]] || fail "mismatched prefab set must skip with exit 75 (got ${deploy_status})"
assert_contains "${fixture}/out.log" 'disagree on festa-unity/Assets/_Project/Prefabs' 'skip must name the disagreeing prefab tree'
assert_contains "${fixture}/out.log" 'leaving the running world untouched' 'skip must state that the running world was not replaced'
assert_not_contains "${fixture}/out.log" 'REACHED_DOCKER' 'the guard must decide before any docker call'
[[ ! -f "${fixture}/state/candidate.json" ]] || fail 'a skipped deploy must not record a candidate'
pass 'mismatched WebGL prefab set skips without touching the running world'

# ② 커밋이 달라도 프리팹이 같으면 통과한다 — 문서·인프라 커밋이 게임 배포를 영영 막으면 안 된다.
write_release "${same_prefabs}"
run_deploy
assert_contains "${fixture}/out.log" 'share one NGO prefab set' 'a matching prefab set must pass the guard'
assert_contains "${fixture}/out.log" 'REACHED_DOCKER' 'a passing guard must fall through to the deploy path'
[[ "${deploy_status}" -ne 75 ]] || fail 'a matching prefab set must not be skipped'
pass 'different commits with one prefab set pass the guard'

# ③ 배포된 WebGL 이 없으면 막을 클라이언트도 없다 — 건너뛰지 않는다.
export WEBGL_MANIFEST_PATH="${fixture}/absent.json"
run_deploy
assert_contains "${fixture}/out.log" 'skipping the client/server prefab guard' 'an absent WebGL manifest must be reported, not silently ignored'
[[ "${deploy_status}" -ne 75 ]] || fail 'an absent WebGL manifest must not block the deploy'
pass 'absent WebGL manifest leaves the guard inactive'
