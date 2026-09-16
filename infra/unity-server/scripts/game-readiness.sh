#!/usr/bin/env bash
# Records the four mandatory readiness gates for a demo-game candidate.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
docker_bin="${DOCKER_BIN:-docker}"

: "${GAME_ENV_FILE:?GAME_ENV_FILE is required}"
: "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required}"
: "${CI_ARTIFACT_DIR:?CI_ARTIFACT_DIR is required}"
approval_evidence_file="${APPROVAL_EVIDENCE_FILE:-}"

compose_file="${GAME_COMPOSE_FILE:-${repo_root}/infra/unity-server/compose.yaml}"
compose_project="${GAME_COMPOSE_PROJECT:-festa-demo-world}"
compose_service="${GAME_COMPOSE_SERVICE:-demo-game}"
public_wss_verify_script="${PUBLIC_WSS_VERIFY_SCRIPT:-${script_dir}/verify-public-wss.sh}"
candidate_path="${GAME_DEPLOY_STATE_DIR}/candidate.json"
output="${GAME_READINESS_PATH:-${CI_ARTIFACT_DIR}/game-readiness.json}"

[[ -f "${GAME_ENV_FILE}" && -f "${candidate_path}" ]] || { echo 'candidate and game environment are required' >&2; exit 66; }
if [[ -n "${approval_evidence_file}" ]]; then
  [[ -f "${approval_evidence_file}" ]] || { echo 'approval evidence file is missing' >&2; exit 66; }
fi
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
  # compose 헬스체크는 `pgrep -f festa-unity.x86_64` 로 **프로세스 존재만** 본다 — 7777 바인딩이 아니다.
  # 그래서 `up -d --wait` 이 돌려주는 Healthy 는 "떴다" 지 "받을 준비가 됐다" 가 아니다.
  # develop #389 실측: 컨테이너 Started 10:23:13.4 → Healthy 10:23:19.9(6.6초) → probe 10:23:20.2 →
  # 0.25초 만에 거부. Unity 데디케이티드 서버가 월드 씬을 올려 포트를 열기 전이다.
  # 단발 probe 는 구조적으로 이르므로 유한 예산 안에서 폴링한다. 예산을 넘기면 그대로 실패시킨다 —
  # 게이트를 무르게 하지 않는다. 걸린 시간을 남기는 이유는 다음 배포에서 예산을 근거로 조정하기 위해서다.
  listener_budget_seconds="${GAME_READINESS_LISTENER_TIMEOUT_SECONDS:-120}"
  listener_started_at="${SECONDS}"
  listener_deadline=$(( listener_started_at + listener_budget_seconds ))
  listener_attempts=0
  listener_ready=
  while (( SECONDS <= listener_deadline )); do
    listener_attempts=$(( listener_attempts + 1 ))
    if timeout 5 bash -c "</dev/tcp/${host}/${port}" >/dev/null 2>&1; then listener_ready=1; break; fi
    sleep 3
  done
  if [[ -z "${listener_ready}" ]]; then
    echo "demo-game internal listener is unavailable — ${host}:${port} 에 ${listener_budget_seconds}초 동안 ${listener_attempts}회 시도했으나 모두 거부됐다" >&2
    exit 1
  fi
  echo "demo-game internal listener ready — ${host}:${port} ($(( SECONDS - listener_started_at ))초, ${listener_attempts}회 시도)"
fi

before="${CI_ARTIFACT_DIR}/non-game-restarts-before.tsv"
after="${CI_ARTIFACT_DIR}/non-game-restarts-after.tsv"
[[ -f "${before}" && -f "${after}" ]] || { echo 'non-game restart snapshots are required' >&2; exit 1; }
cmp -s "${before}" "${after}" || { echo 'non-game restart state changed' >&2; exit 1; }

wss_evidence="${CI_ARTIFACT_DIR}/public-wss.txt"
if [[ -n "${approval_evidence_file}" ]]; then
  bash "${public_wss_verify_script}" --approval-evidence "${approval_evidence_file}" --output "${wss_evidence}"
  grep -qx 'websocketUpgrade=PASS' "${wss_evidence}" || { echo 'external WebSocket verification failed' >&2; exit 1; }
  grep -qx 'approvedAdmission=PASS' "${wss_evidence}" || { echo 'approved admission verification failed' >&2; exit 1; }
  admission_result='PASS'
else
  bash "${public_wss_verify_script}" --output "${wss_evidence}"
  grep -qx 'websocketUpgrade=PASS' "${wss_evidence}" || { echo 'external WebSocket verification failed' >&2; exit 1; }
  admission_result='SKIPPED'
fi

mkdir -p "$(dirname "${output}")"
"${python_bin}" - "${candidate_path}" "${output}" "${admission_result}" <<'PY'
import datetime, json, pathlib, sys
candidate = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
result = {key: candidate[key] for key in ('targetId', 'releaseId', 'contentId')}
result.update({'processRunning': 'PASS', 'internalListener': 'PASS', 'externalWebSocket': 'PASS', 'approvedAdmission': sys.argv[3], 'verifiedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')})
path = pathlib.Path(sys.argv[2]); temporary = path.with_suffix('.tmp')
temporary.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8'); temporary.replace(path)
PY
