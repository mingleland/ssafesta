#!/usr/bin/env bash
# Records the four mandatory readiness gates for a demo-game candidate.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
docker_bin="${DOCKER_BIN:-docker}"

: "${GAME_ENV_FILE:?GAME_ENV_FILE is required}"
: "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required}"
: "${CI_ARTIFACT_DIR:?CI_ARTIFACT_DIR is required}"
: "${APPROVAL_EVIDENCE_FILE:?APPROVAL_EVIDENCE_FILE is required}"

compose_file="${GAME_COMPOSE_FILE:-${repo_root}/infra/unity-server/compose.yaml}"
compose_project="${GAME_COMPOSE_PROJECT:-festa-demo-world}"
compose_service="${GAME_COMPOSE_SERVICE:-demo-game}"
public_wss_verify_script="${PUBLIC_WSS_VERIFY_SCRIPT:-${script_dir}/verify-public-wss.sh}"
candidate_path="${GAME_DEPLOY_STATE_DIR}/candidate.json"
output="${GAME_READINESS_PATH:-${CI_ARTIFACT_DIR}/game-readiness.json}"

[[ -f "${GAME_ENV_FILE}" && -f "${candidate_path}" && -f "${APPROVAL_EVIDENCE_FILE}" ]] || { echo 'candidate, game environment, and approval evidence are required' >&2; exit 66; }
[[ "${compose_project}" == 'festa-demo-world' && "${compose_service}" == 'demo-game' ]] || { echo 'unexpected game Compose target' >&2; exit 64; }
[[ -x "${public_wss_verify_script}" || -f "${public_wss_verify_script}" ]] || { echo 'public WSS verifier is missing' >&2; exit 66; }

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }
"${python_bin}" - "${candidate_path}" <<'PY'
import json, pathlib, sys
candidate = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
if candidate.get('targetId') != 'demo/game' or candidate.get('state') != 'CANDIDATE':
    raise SystemExit('current candidate is not a demo/game candidate')
PY

container_id="$("${docker_bin}" compose --env-file "${GAME_ENV_FILE}" --project-name "${compose_project}" --file "${compose_file}" ps -q "${compose_service}")"
[[ -n "${container_id}" ]] || { echo 'demo-game container is missing' >&2; exit 1; }
[[ "$("${docker_bin}" inspect --format '{{.State.Running}}' "${container_id}")" == true ]] || { echo 'demo-game process is not running' >&2; exit 1; }

listener="$("${docker_bin}" inspect --format '{{range (index .NetworkSettings.Ports "7777/tcp")}}{{.HostIp}}:{{.HostPort}}{{end}}' "${container_id}")"
[[ "${listener}" =~ ^127\.0\.0\.1:[1-9][0-9]*$ ]] || { echo 'demo-game listener is not loopback-only' >&2; exit 1; }
if [[ -z "${GAME_READINESS_SKIP_LISTENER_CONNECT:-}" ]]; then
  host="${listener%:*}"; port="${listener##*:}"
  timeout 5 bash -c "</dev/tcp/${host}/${port}" >/dev/null 2>&1 || { echo 'demo-game internal listener is unavailable' >&2; exit 1; }
fi

before="${CI_ARTIFACT_DIR}/non-game-restarts-before.tsv"
after="${CI_ARTIFACT_DIR}/non-game-restarts-after.tsv"
[[ -f "${before}" && -f "${after}" ]] || { echo 'non-game restart snapshots are required' >&2; exit 1; }
cmp -s "${before}" "${after}" || { echo 'non-game restart state changed' >&2; exit 1; }

wss_evidence="${CI_ARTIFACT_DIR}/public-wss.txt"
bash "${public_wss_verify_script}" --approval-evidence "${APPROVAL_EVIDENCE_FILE}" --output "${wss_evidence}"
grep -qx 'websocketUpgrade=PASS' "${wss_evidence}" || { echo 'external WebSocket verification failed' >&2; exit 1; }
grep -qx 'approvedAdmission=PASS' "${wss_evidence}" || { echo 'approved admission verification failed' >&2; exit 1; }

mkdir -p "$(dirname "${output}")"
"${python_bin}" - "${candidate_path}" "${output}" <<'PY'
import datetime, json, pathlib, sys
candidate = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
result = {key: candidate[key] for key in ('targetId', 'releaseId', 'contentId')}
result.update({'processRunning': 'PASS', 'internalListener': 'PASS', 'externalWebSocket': 'PASS', 'approvedAdmission': 'PASS', 'verifiedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')})
path = pathlib.Path(sys.argv[2]); temporary = path.with_suffix('.tmp')
temporary.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8'); temporary.replace(path)
PY
