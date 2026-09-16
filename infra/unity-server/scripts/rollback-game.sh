#!/usr/bin/env bash
# Restores only demo-game to the last known-good image after a failed candidate verification.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
docker_bin="${DOCKER_BIN:-docker}"

: "${GAME_ENV_FILE:?GAME_ENV_FILE is required}"
: "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required}"
: "${CI_ARTIFACT_DIR:?CI_ARTIFACT_DIR is required}"

compose_file="${GAME_COMPOSE_FILE:-${repo_root}/infra/unity-server/compose.yaml}"
compose_project="${GAME_COMPOSE_PROJECT:-festa-demo-world}"
compose_service="${GAME_COMPOSE_SERVICE:-demo-game}"
reason="${GAME_ROLLBACK_REASON:-candidate_verification_failed}"
candidate_path="${GAME_DEPLOY_STATE_DIR}/candidate.json"
known_good_path="${GAME_DEPLOY_STATE_DIR}/known-good.json"
lock_timeout_seconds="${GAME_DEPLOY_LOCK_TIMEOUT_SECONDS:-300}"

[[ -f "${GAME_ENV_FILE}" && -f "${candidate_path}" && -f "${known_good_path}" ]] || { echo 'game environment, candidate, and known-good state are required' >&2; exit 66; }
[[ "${compose_project}" == 'festa-demo-world' && "${compose_service}" == 'demo-game' ]] || { echo 'unexpected game Compose target' >&2; exit 64; }
[[ "${lock_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || { echo 'GAME_DEPLOY_LOCK_TIMEOUT_SECONDS must be positive' >&2; exit 64; }
[[ "${reason}" =~ ^(candidate_verification_failed|internal_listener_failed|external_wss_failed|approved_admission_failed)$ ]] || { echo 'unsupported rollback reason' >&2; exit 64; }
command -v flock >/dev/null 2>&1 || { echo 'flock is required for the game deployment lock' >&2; exit 69; }

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }
IFS=$'\t' read -r candidate_release candidate_ref known_good_release known_good_ref known_good_id < <(
  "${python_bin}" - "${candidate_path}" "${known_good_path}" <<'PY'
import json, pathlib, sys
candidate, known_good = (json.loads(pathlib.Path(path).read_text(encoding='utf-8')) for path in sys.argv[1:])
for state in (candidate, known_good):
    if state.get('targetId') != 'demo/game' or not isinstance(state.get('releaseId'), str) or not state['releaseId'].replace('-', '').replace('_', '').replace('.', '').isalnum():
        raise SystemExit('invalid demo/game release state')
if candidate.get('state') != 'CANDIDATE' or known_good.get('state') != 'CURRENT/KNOWN_GOOD':
    raise SystemExit('candidate or known-good state is not eligible for rollback')
if candidate['releaseId'] == known_good['releaseId']:
    print('NO_OP', candidate['releaseId'], known_good['releaseId'], '', '', sep='\t')
    sys.exit(0)
if not isinstance(known_good.get('imageRef'), str) or not isinstance(known_good.get('contentId'), str):
    raise SystemExit('known-good image identity is missing')
print(candidate['releaseId'], candidate.get('imageRef', ''), known_good['releaseId'], known_good['imageRef'], known_good['contentId'], sep='\t')
PY
)
if [[ "${candidate_release}" == 'NO_OP' ]]; then
  echo 'rollback no-op: candidate is already the known-good release'
  exit 0
fi
[[ "${known_good_ref}" =~ @sha256:[0-9a-f]{64}$ || "${known_good_ref}" =~ :[0-9a-f]{40}$ ]] || { echo 'known-good image ref must be immutable' >&2; exit 65; }
[[ "${known_good_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'known-good content ID is invalid' >&2; exit 65; }
actual_id="$("${docker_bin}" image inspect --format '{{.Id}}' "${known_good_ref}")"
[[ "${actual_id}" == "${known_good_id}" ]] || { echo 'known-good image content ID mismatch' >&2; exit 65; }

mkdir -p "${GAME_DEPLOY_STATE_DIR}/failed" "${CI_ARTIFACT_DIR}"
exec {lock_fd}>"${GAME_DEPLOY_STATE_DIR}/.deploy.lock"
flock -w "${lock_timeout_seconds}" "${lock_fd}" || { echo 'another demo/game deployment owns the lock' >&2; exit 73; }
trap 'flock -u "${lock_fd}" || true' EXIT HUP INT TERM

attempt_marker="${GAME_DEPLOY_STATE_DIR}/rollback-attempted-${candidate_release}"
[[ ! -e "${attempt_marker}" ]] || { echo 'rollback already attempted for this candidate' >&2; exit 75; }
: >"${attempt_marker}"

REASON_VALUE="${reason}" "${python_bin}" - "${candidate_path}" "${GAME_DEPLOY_STATE_DIR}/failed/${candidate_release}.json" <<'PY'
import datetime, json, os, pathlib, sys
candidate_path, failed_path = map(pathlib.Path, sys.argv[1:])
candidate = json.loads(candidate_path.read_text(encoding='utf-8'))
failed = dict(candidate)
failed.update({'state': 'FAILED', 'failureReason': os.environ['REASON_VALUE'], 'failedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')})
temporary = failed_path.with_suffix('.tmp')
temporary.write_text(json.dumps(failed, indent=2) + '\n', encoding='utf-8'); temporary.replace(failed_path)
PY

GAME_IMAGE_REF="${known_good_ref}" "${docker_bin}" compose --env-file "${GAME_ENV_FILE}" --project-name "${compose_project}" --file "${compose_file}" up -d --no-deps --wait "${compose_service}"
container_id="$("${docker_bin}" compose --env-file "${GAME_ENV_FILE}" --project-name "${compose_project}" --file "${compose_file}" ps -q "${compose_service}")"
[[ -n "${container_id}" ]] || { echo 'known-good demo-game container was not created' >&2; exit 1; }
[[ "$("${docker_bin}" inspect --format '{{.Image}}' "${container_id}")" == "${known_good_id}" ]] || { echo 'known-good demo-game image mismatch after rollback' >&2; exit 1; }

"${python_bin}" - "${candidate_path}" "${known_good_path}" "${GAME_DEPLOY_STATE_DIR}" <<'PY'
import datetime, json, pathlib, sys
candidate_path, known_good_path, state_dir = map(pathlib.Path, sys.argv[1:])
candidate = json.loads(candidate_path.read_text(encoding='utf-8'))
known_good = json.loads(known_good_path.read_text(encoding='utf-8'))
timestamp = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
rollback = dict(known_good); rollback.update({'state': 'ROLLED_BACK', 'failedReleaseId': candidate['releaseId'], 'rolledBackAt': timestamp})
for path, document in ((state_dir / 'rollback.json', rollback), (candidate_path, rollback)):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8'); temporary.replace(path)
PY
