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
