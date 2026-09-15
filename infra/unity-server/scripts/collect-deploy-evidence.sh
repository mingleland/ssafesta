#!/usr/bin/env bash
# Writes a redacted deployment evidence record from existing game state and readiness artifacts.
set -euo pipefail

: "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required}"
: "${CI_ARTIFACT_DIR:?CI_ARTIFACT_DIR is required}"
: "${CLIENT_RELEASE_REF:?CLIENT_RELEASE_REF is required}"

[[ "${CLIENT_RELEASE_REF}" =~ ^[A-Za-z0-9._:/@+-]+$ ]] || { echo 'CLIENT_RELEASE_REF contains unsupported characters' >&2; exit 64; }
state_path="${GAME_DEPLOY_STATE_PATH:-${GAME_DEPLOY_STATE_DIR}/current.json}"
[[ -f "${state_path}" ]] || state_path="${GAME_DEPLOY_STATE_DIR}/candidate.json"
readiness_path="${GAME_READINESS_PATH:-${CI_ARTIFACT_DIR}/game-readiness.json}"
before="${CI_ARTIFACT_DIR}/non-game-restarts-before.tsv"
after="${CI_ARTIFACT_DIR}/non-game-restarts-after.tsv"
output="${DEPLOY_EVIDENCE_PATH:-${CI_ARTIFACT_DIR}/deploy-evidence.json}"

[[ -f "${state_path}" && -f "${readiness_path}" && -f "${before}" && -f "${after}" ]] || { echo 'state, readiness, and restart snapshots are required' >&2; exit 66; }
python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }

CLIENT_RELEASE_REF_VALUE="${CLIENT_RELEASE_REF}" "${python_bin}" - "${state_path}" "${readiness_path}" "${before}" "${after}" "${output}" <<'PY'
import datetime, json, os, pathlib, sys

state_path, readiness_path, before_path, after_path, output_path = map(pathlib.Path, sys.argv[1:])
state = json.loads(state_path.read_text(encoding='utf-8'))
readiness = json.loads(readiness_path.read_text(encoding='utf-8'))
if state.get('targetId') != 'demo/game':
    raise SystemExit('deployment state target must be demo/game')
for key in ('releaseId', 'sourceCommit', 'imageRef', 'contentId', 'state'):
    if not isinstance(state.get(key), str):
        raise SystemExit(f'deployment state is missing {key}')
for key in ('processRunning', 'internalListener', 'externalWebSocket', 'approvedAdmission'):
    if readiness.get(key) not in ('PASS', 'FAIL', 'NOT_RUN'):
        raise SystemExit(f'readiness is missing {key}')
before = before_path.read_text(encoding='utf-8').splitlines()
after = after_path.read_text(encoding='utf-8').splitlines()
document = {
    'schemaVersion': '1.0.0', 'targetId': 'demo/game', 'serverReleaseId': state['releaseId'],
    'serverSourceCommit': state['sourceCommit'], 'serverImageRef': state['imageRef'], 'serverContentId': state['contentId'],
    'serverState': state['state'], 'clientReleaseRef': os.environ['CLIENT_RELEASE_REF_VALUE'],
    'readiness': {key: readiness[key] for key in ('processRunning', 'internalListener', 'externalWebSocket', 'approvedAdmission')},
    'nonGameRestartDelta': 0 if before == after else 1,
    'collectedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
}
output_path.parent.mkdir(parents=True, exist_ok=True)
temporary = output_path.with_suffix('.tmp')
temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8'); temporary.replace(output_path)
PY
