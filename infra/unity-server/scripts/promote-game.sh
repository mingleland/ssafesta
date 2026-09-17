#!/usr/bin/env bash
# Promotes a fully verified demo-game candidate to CURRENT.
# known-good 승격은 사람이 approve-known-good.sh 로 별도 수행한다 (spec infra-001/003 §Session 2026-09-17).
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
import datetime, json, os, pathlib, sys

candidate_path, readiness_path, state_dir = map(pathlib.Path, sys.argv[1:])
candidate = json.loads(candidate_path.read_text(encoding='utf-8'))
readiness = json.loads(readiness_path.read_text(encoding='utf-8'))
if candidate.get('targetId') != 'demo/game' or candidate.get('state') != 'CANDIDATE':
    raise SystemExit('current candidate is not eligible for promotion')
if any(readiness.get(key) != 'PASS' for key in ('processRunning', 'internalListener', 'externalWebSocket')):
    raise SystemExit('candidate readiness is incomplete')
if readiness.get('approvedAdmission') not in ('PASS', 'SKIPPED'):
    raise SystemExit('approved admission verification failed')
if any(readiness.get(key) != candidate.get(key) for key in ('targetId', 'releaseId', 'contentId')):
    raise SystemExit('readiness evidence does not match the current candidate')
if not (state_dir / 'releases' / f"{candidate['releaseId']}.json").is_file():
    raise SystemExit('candidate release archive is missing')
document = dict(candidate)
document.update({'state': 'CURRENT', 'promotedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')})
# candidate.json 도 CURRENT 로 갱신 — 다음 배포가 새 CANDIDATE 로 덮을 때까지 마지막 배치 상태를 보존한다.
for name in ('current.json', 'candidate.json'):
    path = state_dir / name; temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8'); temporary.replace(path)

# Atomic sync to infra-001 active dev **current** if release archive exists.
# known-good 승격은 사람이 approve-known-good.sh 를 호출할 때 별도로 이뤄진다.
env_state_root = os.environ.get('ENVIRONMENT_STATE_DIR') or os.environ.get('DEV_BATCH_STATE_DIR') or '/var/lib/festa-environments'
current_dir = pathlib.Path(env_state_root) / 'dev' / 'batches' / 'current'
release_archive = state_dir / 'releases' / f"{candidate['releaseId']}.json"
if release_archive.is_file():
    current_dir.mkdir(parents=True, exist_ok=True)
    target = current_dir / 'game.json'
    tmp_target = target.with_suffix('.tmp')
    tmp_target.write_bytes(release_archive.read_bytes())
    tmp_target.replace(target)
PY
