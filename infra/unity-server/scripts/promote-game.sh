#!/usr/bin/env bash
# Promotes only a fully verified demo-game candidate to current and known-good.
set -euo pipefail

: "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required}"
: "${CI_ARTIFACT_DIR:?CI_ARTIFACT_DIR is required}"

candidate_path="${GAME_DEPLOY_STATE_DIR}/candidate.json"
readiness_path="${GAME_READINESS_PATH:-${CI_ARTIFACT_DIR}/game-readiness.json}"
lock_timeout_seconds="${GAME_DEPLOY_LOCK_TIMEOUT_SECONDS:-300}"
[[ "${lock_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || { echo 'GAME_DEPLOY_LOCK_TIMEOUT_SECONDS must be positive' >&2; exit 64; }
[[ -f "${candidate_path}" && -f "${readiness_path}" ]] || { echo 'candidate and readiness evidence are required' >&2; exit 66; }
command -v flock >/dev/null 2>&1 || { echo 'flock is required for the game deployment lock' >&2; exit 69; }

exec {lock_fd}>"${GAME_DEPLOY_STATE_DIR}/.deploy.lock"
flock -w "${lock_timeout_seconds}" "${lock_fd}" || { echo 'another demo/game deployment owns the lock' >&2; exit 73; }
trap 'flock -u "${lock_fd}" || true' EXIT HUP INT TERM

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }
"${python_bin}" - "${candidate_path}" "${readiness_path}" "${GAME_DEPLOY_STATE_DIR}" <<'PY'
import datetime, json, pathlib, sys

candidate_path, readiness_path, state_dir = map(pathlib.Path, sys.argv[1:])
candidate = json.loads(candidate_path.read_text(encoding='utf-8'))
readiness = json.loads(readiness_path.read_text(encoding='utf-8'))
if candidate.get('targetId') != 'demo/game' or candidate.get('state') != 'CANDIDATE':
    raise SystemExit('current candidate is not eligible for promotion')
if any(readiness.get(key) != 'PASS' for key in ('processRunning', 'internalListener', 'externalWebSocket', 'approvedAdmission')):
    raise SystemExit('candidate readiness is incomplete')
if any(readiness.get(key) != candidate.get(key) for key in ('targetId', 'releaseId', 'contentId')):
    raise SystemExit('readiness evidence does not match the current candidate')
if not (state_dir / 'releases' / f"{candidate['releaseId']}.json").is_file():
    raise SystemExit('candidate release archive is missing')
document = dict(candidate)
document.update({'state': 'CURRENT/KNOWN_GOOD', 'promotedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')})
for name in ('current.json', 'known-good.json', 'candidate.json'):
    path = state_dir / name; temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8'); temporary.replace(path)
PY
