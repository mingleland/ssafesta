#!/usr/bin/env bash
# Deploys only the demo Unity Dedicated Server candidate and preserves non-game services.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
docker_bin="${DOCKER_BIN:-docker}"

: "${RELEASE_MANIFEST_PATH:?RELEASE_MANIFEST_PATH is required}"
: "${CONNECTION_TOKEN_SECRET_FILE:?CONNECTION_TOKEN_SECRET_FILE is required}"
: "${GAME_ENV_FILE:?GAME_ENV_FILE is required}"
: "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required}"
: "${CI_ARTIFACT_DIR:?CI_ARTIFACT_DIR is required}"

compose_file="${GAME_COMPOSE_FILE:-${repo_root}/infra/unity-server/compose.yaml}"
compose_project="${GAME_COMPOSE_PROJECT:-festa-demo-world}"
compose_service="${GAME_COMPOSE_SERVICE:-demo-game}"
deploy_target="${DEPLOY_TARGET:-demo/game}"
lock_timeout_seconds="${GAME_DEPLOY_LOCK_TIMEOUT_SECONDS:-300}"

[[ -f "${RELEASE_MANIFEST_PATH}" ]] || { echo 'release manifest is missing' >&2; exit 66; }
[[ -f "${GAME_ENV_FILE}" ]] || { echo 'game environment file is missing' >&2; exit 66; }
set -a
# shellcheck disable=SC1090
source "${GAME_ENV_FILE}"
set +a
[[ -f "${compose_file}" ]] || { echo 'game Compose file is missing' >&2; exit 66; }
[[ "${compose_project}" == 'festa-demo-world' ]] || { echo 'unexpected game Compose project' >&2; exit 64; }
[[ "${compose_service}" == 'demo-game' ]] || { echo 'unexpected game Compose service' >&2; exit 64; }
[[ "${deploy_target}" == 'demo/game' ]] || { echo 'unexpected game deploy target' >&2; exit 64; }
[[ "${lock_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || { echo 'GAME_DEPLOY_LOCK_TIMEOUT_SECONDS must be positive' >&2; exit 64; }

python_bin=''
for candidate in "${PYTHON_BIN:-}" python3 python; do
  [[ -n "${candidate}" ]] || continue
  if command -v "${candidate}" >/dev/null 2>&1 && "${candidate}" -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
    python_bin="${candidate}"
    break
  fi
done
[[ -n "${python_bin}" ]] || { echo 'Python 3 is required' >&2; exit 69; }

IFS=$'\t' read -r release_id branch source_commit image_ref content_id < <(
  "${python_bin}" - "${RELEASE_MANIFEST_PATH}" <<'PY'
import json, pathlib, sys

release = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
matches = [item for item in release['components'] if item['name'] == 'game']
if len(matches) != 1:
    raise SystemExit('release manifest must contain exactly one game component')
game = matches[0]
if game['sourceCommit'] != release['scm']['commit']:
    raise SystemExit('game source commit differs from release commit')
print(release['releaseId'], release['scm']['branch'], game['sourceCommit'], game['imageRef'], game['contentId'], sep='\t')
PY
)

[[ "${branch}" == develop ]] || { echo 'game candidate must originate from develop' >&2; exit 65; }
[[ "${source_commit}" =~ ^[0-9a-f]{40}$ ]] || { echo 'game source commit must be a full SHA' >&2; exit 65; }
[[ "${image_ref}" =~ @sha256:[0-9a-f]{64}$ || "${image_ref}" =~ :[0-9a-f]{40}$ ]] || { echo 'game image ref must be immutable' >&2; exit 65; }
[[ "${content_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'game image content ID is invalid' >&2; exit 65; }

# 배포된 WebGL 클라이언트와 NGO 프리팹이 어긋나면 운영 월드를 건드리지 않는다 (INFRA-T-111).
#
# NGO 는 프리팹 위 NetworkBehaviour 를 **순서 인덱스**로 식별한다. 한쪽에만 있는 NetworkBehaviour 가
# 하나라도 생기면 그 지점 이후의 RPC 가 전부 다른 컴포넌트로 디스패치되고, WebGL 에서는
# `RuntimeError: function signature mismatch` 로 죽는다. 2026-09-14~16 에 클라이언트 bf90136a 와
# 서버 85ae541f 가 어긋난 채 이틀을 돌았고, 사람이 그 NPC 근처에 갈 때까지 아무 경보가 없었다.
#
# **커밋 SHA 로 대조하지 않는다.** 문서·인프라 커밋도 SHA 는 달라지므로 그렇게 하면 게임 배포가 영영
# 나가지 못한다(오늘 복구에 쓴 48c8e390 도 커밋은 다르지만 프리팹은 같다). 색인을 실제로 결정하는
# 것은 프리팹이라 프리팹 트리 해시만 본다.
# 한계: 프리팹을 건드리지 않고 RPC 시그니처만 바꾸는 변경은 이 검사로 잡지 못한다.
#
# **교체 전에 끝내야 하므로 docker 를 부르기 전에 둔다.** 여기서 빠져나가면 돌고 있는 컨테이너는
# 손도 타지 않는다. exit 75 는 deploy-dev-batch.sh 와 같은 "건너뜀" 관례다 — 실패가 아니다.
webgl_manifest="${WEBGL_MANIFEST_PATH:-${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}/current/manifest.json}"
prefab_tree="${GAME_PREFAB_TREE_PATH:-festa-unity/Assets/_Project/Prefabs}"
if [[ -f "${webgl_manifest}" ]]; then
  webgl_commit="$("${python_bin}" -c 'import json,pathlib,sys; print(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")).get("sourceCommit") or "")' "${webgl_manifest}" 2>/dev/null || true)"
  [[ "${webgl_commit}" =~ ^[0-9a-f]{40}$ ]] \
    || { echo "deployed WebGL manifest has no usable sourceCommit (${webgl_manifest}); leaving the running world untouched" >&2; exit 75; }
  candidate_prefabs="$(git -C "${repo_root}" rev-parse "${source_commit}:${prefab_tree}" 2>/dev/null || true)"
  webgl_prefabs="$(git -C "${repo_root}" rev-parse "${webgl_commit}:${prefab_tree}" 2>/dev/null || true)"
  [[ -n "${candidate_prefabs}" && -n "${webgl_prefabs}" ]] \
    || { echo "cannot resolve ${prefab_tree} for candidate ${source_commit} or deployed WebGL ${webgl_commit}; leaving the running world untouched" >&2; exit 75; }
  [[ "${candidate_prefabs}" == "${webgl_prefabs}" ]] \
    || { echo "deployed WebGL ${webgl_commit} and game candidate ${source_commit} disagree on ${prefab_tree}; leaving the running world untouched" >&2; exit 75; }
  echo "WebGL ${webgl_commit} and game candidate ${source_commit} share one NGO prefab set"
else
  echo "no deployed WebGL manifest at ${webgl_manifest}; skipping the client/server prefab guard" >&2
fi

actual_content_id="$(${docker_bin} image inspect --format '{{.Id}}' "${image_ref}")"
[[ "${actual_content_id}" == "${content_id}" ]] || { echo "game image content ID mismatch: expected=${content_id} actual=${actual_content_id}" >&2; exit 65; }

export GAME_IMAGE_REF="${image_ref}"
bash "${script_dir}/preflight.sh"

mkdir -p "${GAME_DEPLOY_STATE_DIR}/releases" "${CI_ARTIFACT_DIR}"
command -v flock >/dev/null 2>&1 || { echo 'flock is required for the game deployment lock' >&2; exit 69; }
exec {lock_fd}>"${GAME_DEPLOY_STATE_DIR}/.deploy.lock"
flock -w "${lock_timeout_seconds}" "${lock_fd}" || { echo 'another demo/game deployment owns the lock' >&2; exit 73; }
release_lock() { flock -u "${lock_fd}" || true; }
trap release_lock EXIT HUP INT TERM

snapshot_non_targets() {
  local output="$1" container service details
  : >"${output}"
  while IFS='|' read -r container service; do
    [[ -n "${container}" && -n "${service}" ]] || continue
    [[ "${service}" != "${compose_service}" ]] || continue
    details="$(${docker_bin} inspect --format '{{.Image}}|{{.RestartCount}}' "${container}")"
    printf '%s|%s|%s\n' "${service}" "${container}" "${details}" >>"${output}"
  done < <("${docker_bin}" ps -a --filter "label=com.docker.compose.project=${compose_project}" --format '{{.Names}}|{{.Label "com.docker.compose.service"}}')
  sort -o "${output}" "${output}"
}

before_restarts="${CI_ARTIFACT_DIR}/non-game-restarts-before.tsv"
after_restarts="${CI_ARTIFACT_DIR}/non-game-restarts-after.tsv"
snapshot_non_targets "${before_restarts}"

candidate_path="${GAME_DEPLOY_STATE_DIR}/candidate.json"
release_copy="${GAME_DEPLOY_STATE_DIR}/releases/${release_id}.json"
RELEASE_ID_VALUE="${release_id}" SOURCE_COMMIT_VALUE="${source_commit}" IMAGE_REF_VALUE="${image_ref}" CONTENT_ID_VALUE="${content_id}" DEPLOY_TARGET_VALUE="${deploy_target}" \
  "${python_bin}" - "${candidate_path}" "${release_copy}" "${RELEASE_MANIFEST_PATH}" <<'PY'
import datetime, json, os, pathlib, sys

candidate_path, release_copy, manifest_path = map(pathlib.Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
release_copy.parent.mkdir(parents=True, exist_ok=True)
if release_copy.exists() and release_copy.read_bytes() != manifest_path.read_bytes():
    raise SystemExit('release ID already exists with different content')
if not release_copy.exists():
    temporary = release_copy.with_suffix('.tmp')
    temporary.write_bytes(manifest_path.read_bytes())
    temporary.replace(release_copy)
document = {
    'schemaVersion': '1.0.0', 'targetId': os.environ['DEPLOY_TARGET_VALUE'],
    'releaseId': os.environ['RELEASE_ID_VALUE'], 'sourceCommit': os.environ['SOURCE_COMMIT_VALUE'],
    'imageRef': os.environ['IMAGE_REF_VALUE'], 'contentId': os.environ['CONTENT_ID_VALUE'],
    'state': 'CANDIDATE',
    'startedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
}
temporary = candidate_path.with_suffix('.tmp')
temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
temporary.replace(candidate_path)
PY

"${docker_bin}" compose --env-file "${GAME_ENV_FILE}" --project-name "${compose_project}" --file "${compose_file}" up -d --no-deps --wait "${compose_service}"

container_id="$(${docker_bin} compose --env-file "${GAME_ENV_FILE}" --project-name "${compose_project}" --file "${compose_file}" ps -q "${compose_service}")"
[[ -n "${container_id}" ]] || { echo 'demo-game container was not created' >&2; exit 1; }
container_image_id="$(${docker_bin} inspect --format '{{.Image}}' "${container_id}")"
[[ "${container_image_id}" == "${content_id}" ]] || { echo "demo-game container image mismatch: expected=${content_id} actual=${container_image_id}" >&2; exit 1; }

snapshot_non_targets "${after_restarts}"
cmp -s "${before_restarts}" "${after_restarts}" || { echo 'non-game container image or restart count changed' >&2; exit 1; }

printf '{"targetId":"%s","releaseId":"%s","imageRef":"%s","contentId":"%s","candidateState":"%s"}\n' \
  "${deploy_target}" "${release_id}" "${image_ref}" "${content_id}" "${candidate_path}"
