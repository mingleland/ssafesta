#!/usr/bin/env bash
# 사람이 demo.ssafesta.world 에서 실제 서비스 검증을 마친 CURRENT 릴리스를 KNOWN_GOOD 으로 승격한다.
# 롤백 기준선(rollback baseline)은 KNOWN_GOOD 에서만 나온다 — CURRENT 는 자동 승격이라 사람이 확인하지 않았을 수 있다.
#
# 사용: approve-known-good.sh <target> [<expected-release-id>]
#   target: game | ai | back | front | webgl
#   expected-release-id 는 오타 방지용 이중 확인 — 지정하면 CURRENT 의 releaseId 와 일치해야 한다.
#
# 환경변수:
#   GAME_DEPLOY_STATE_DIR       game 대상일 때 필수 (예: /var/lib/festa-environments/demo/game)
#   ENVIRONMENT_STATE_DIR       ai/back/front/webgl 상태 루트 (기본 /var/lib/festa-environments)
set -euo pipefail

usage() {
  echo 'Usage: approve-known-good.sh <target> [<expected-release-id>]' >&2
  echo '  target: game | ai | back | front | webgl' >&2
  exit 64
}

[[ $# -ge 1 && $# -le 2 ]] || usage
target="$1"
expected_release="${2:-}"
[[ "${target}" =~ ^(game|ai|back|front|webgl)$ ]] || usage

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }

env_state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
dev_batches="${env_state_root}/dev/batches"

case "${target}" in
  game)
    : "${GAME_DEPLOY_STATE_DIR:?GAME_DEPLOY_STATE_DIR is required for game}"
    current_path="${GAME_DEPLOY_STATE_DIR}/current.json"
    known_good_path="${GAME_DEPLOY_STATE_DIR}/known-good.json"
    ;;
  ai|back|front|webgl)
    current_path="${dev_batches}/current/${target}.json"
    known_good_path="${dev_batches}/known-good/${target}.json"
    ;;
esac

[[ -f "${current_path}" ]] || { echo "CURRENT 상태 파일이 없다: ${current_path}" >&2; exit 66; }

mkdir -p "$(dirname "${known_good_path}")"
lock_path="$(dirname "${known_good_path}")/.approve.lock"
command -v flock >/dev/null 2>&1 || { echo 'flock is required' >&2; exit 69; }
exec {lock_fd}>"${lock_path}"
flock -w 30 "${lock_fd}" || { echo 'another approval owns the lock' >&2; exit 73; }
trap 'flock -u "${lock_fd}" || true' EXIT HUP INT TERM

CURRENT_PATH="${current_path}" KNOWN_GOOD_PATH="${known_good_path}" EXPECTED_RELEASE="${expected_release}" TARGET="${target}" "${python_bin}" <<'PY'
import datetime, json, os, pathlib

current_path = pathlib.Path(os.environ['CURRENT_PATH'])
known_good_path = pathlib.Path(os.environ['KNOWN_GOOD_PATH'])
expected = os.environ['EXPECTED_RELEASE']
target = os.environ['TARGET']

current = json.loads(current_path.read_text(encoding='utf-8'))

# game/webgl 은 스키마가 서로 다르지만 releaseId 필드만은 공통이다.
release_id = current.get('releaseId')
if not isinstance(release_id, str) or not release_id:
    raise SystemExit(f'{target} current.json 에 releaseId 가 없다')

if expected and expected != release_id:
    raise SystemExit(f'releaseId 불일치: expected={expected}, current={release_id}')

# game 은 state 필드가 있어 CURRENT 를 요구한다. dev-batch(ai/back/front) 및 webgl 은
# release manifest 를 그대로 저장하므로 state 필드가 없다 — 그 경우 확인을 건너뛴다.
if 'state' in current and current['state'] not in ('CURRENT', 'CURRENT/KNOWN_GOOD'):
    raise SystemExit(f"{target} current 상태가 승격 대상이 아니다: {current['state']}")

document = dict(current)
timestamp = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
# game 처럼 state 필드가 있으면 KNOWN_GOOD 로 갱신. 그 외 대상은 원본 매니페스트 스키마를 유지한다.
if 'state' in document:
    document['state'] = 'KNOWN_GOOD'
document['approvedAt'] = timestamp
document.setdefault('approvedFromCurrent', release_id)

temporary = known_good_path.with_suffix('.tmp')
temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
temporary.replace(known_good_path)

print(f'APPROVED_KNOWN_GOOD: target={target} releaseId={release_id}')
PY
