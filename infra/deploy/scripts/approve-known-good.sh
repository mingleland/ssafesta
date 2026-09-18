#!/usr/bin/env bash
# 사람이 demo.ssafesta.world 에서 실제 서비스 검증을 마친 CURRENT 릴리스를 KNOWN_GOOD 으로 승격한다.
# 롤백 기준선(rollback baseline)은 KNOWN_GOOD 에서만 나온다 — CURRENT 는 자동 승격이라 사람이 확인하지 않았을 수 있다.
#
# 사용: approve-known-good.sh <target> [<expected-release-id>]
#   target: environment | game | ai | back | front | webgl
#   environment 는 그 시점 demo 전체 조합(ai·back·front·game)을 한 번에 승격한다
#   — 사람이 확인한 것은 컴포넌트 하나가 아니라 통합 상태다 (spec §Session 2026-09-18 규칙 4·9).
#   expected-release-id 는 오타 방지용 이중 확인 — 지정하면 CURRENT 의 releaseId 와 일치해야 한다.
#
# 환경변수:
#   GAME_DEPLOY_STATE_DIR       game 대상일 때 필수 (예: /var/lib/festa-environments/demo/game)
#   ENVIRONMENT_STATE_DIR       ai/back/front/webgl 상태 루트 (기본 /var/lib/festa-environments)
set -euo pipefail

usage() {
  echo 'Usage: approve-known-good.sh <target> [<expected-release-id>]' >&2
  echo '  target: environment | game | ai | back | front | webgl' >&2
  exit 64
}

[[ $# -ge 1 && $# -le 2 ]] || usage
target="$1"
expected_release="${2:-}"
[[ "${target}" =~ ^(environment|game|ai|back|front|webgl)$ ]] || usage

python_bin="${PYTHON_BIN:-python3}"
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }

env_state_root="${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}"
dev_batches="${env_state_root}/dev/batches"

# 사람이 확인한 것은 컴포넌트 하나가 아니라 그 시점 demo 전체 조합이다 (규칙 4).
if [[ "${target}" == environment ]]; then
  current_dir="${dev_batches}/current"
  known_good_dir="${dev_batches}/known-good"
  game_state_dir="${GAME_DEPLOY_STATE_DIR:-${env_state_root}/demo/game}"
  [[ -d "${current_dir}" ]] || { echo "CURRENT 상태 디렉터리가 없다: ${current_dir}" >&2; exit 66; }
  mkdir -p "${known_good_dir}"
  command -v flock >/dev/null 2>&1 || { echo 'flock is required' >&2; exit 69; }
  exec {env_lock_fd}>"${known_good_dir}/.approve.lock"
  flock -w 30 "${env_lock_fd}" || { echo 'another approval owns the lock' >&2; exit 73; }
  trap 'flock -u "${env_lock_fd}" || true' EXIT HUP INT TERM

  # 컴포넌트별 known-good 은 batch 롤백이 읽는 자리라 함께 갱신한다.
  for component in ai back front; do
    [[ ! -f "${current_dir}/${component}.json" ]] \
      || cp "${current_dir}/${component}.json" "${known_good_dir}/${component}.json"
  done

  CURRENT_DIR="${current_dir}" GAME_CURRENT="${game_state_dir}/current.json" \
    KNOWN_GOOD_ENV="${known_good_dir}/environment.json" HISTORY_ROOT="${dev_batches}/history" \
    EXPECTED_RELEASE="${expected_release}" APPROVED_BY="${APPROVED_BY:-}" "${python_bin}" <<'PY'
import datetime, json, os, pathlib

current_dir = pathlib.Path(os.environ['CURRENT_DIR'])
game_current = pathlib.Path(os.environ['GAME_CURRENT'])
target_path = pathlib.Path(os.environ['KNOWN_GOOD_ENV'])
history_root = pathlib.Path(os.environ['HISTORY_ROOT'])
expected = os.environ['EXPECTED_RELEASE']
approved_by = os.environ['APPROVED_BY']

def identity(path, component):
    document = json.loads(path.read_text(encoding='utf-8'))
    items = document.get('components')
    if isinstance(items, list):
        for item in items:
            if isinstance(item, dict) and item.get('name') == component:
                return {
                    'releaseId': document.get('releaseId'),
                    'sourceCommit': item.get('sourceCommit'),
                    'imageRef': item.get('imageRef'),
                    'contentId': item.get('contentId'),
                }
    return {
        'releaseId': document.get('releaseId'),
        'sourceCommit': document.get('sourceCommit'),
        'imageRef': document.get('imageRef'),
        'contentId': document.get('contentId'),
    }

components = {}
for component in ('ai', 'back', 'front'):
    path = current_dir / f'{component}.json'
    if path.is_file():
        components[component] = identity(path, component)
if game_current.is_file():
    components['game'] = identity(game_current, 'game')
if not components:
    raise SystemExit('승격할 CURRENT 조합이 없다')

fingerprint = {name: value.get('imageRef') for name, value in components.items()}

# 같은 조합을 담은 이력 항목의 검증 상태를 올린다 (규칙 7). 이력은 retention 정리에서도 보호된다.
approved_from = None
if history_root.is_dir():
    def recorded_at(entry):
        meta = entry / 'meta.json'
        if not meta.is_file():
            return ''
        return json.loads(meta.read_text(encoding='utf-8')).get('recordedAt', '')

    for entry in sorted((item for item in history_root.iterdir() if item.is_dir()), key=lambda item: (recorded_at(item), item.name), reverse=True):
        meta_path = entry / 'meta.json'
        if not meta_path.is_file():
            continue
        meta = json.loads(meta_path.read_text(encoding='utf-8'))
        recorded = {name: value.get('imageRef') for name, value in meta.get('components', {}).items()}
        if recorded == fingerprint:
            meta['verification'] = 'verified'
            meta_path.write_text(json.dumps(meta, indent=2) + '\n', encoding='utf-8')
            approved_from = meta.get('releaseId') or entry.name
            break

if expected and approved_from and expected != approved_from:
    raise SystemExit(f'releaseId 불일치: expected={expected}, history={approved_from}')

document = {
    'schemaVersion': '1.0.0',
    'state': 'KNOWN_GOOD',
    'approvedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
    'approvedBy': approved_by or None,
    'approvedFromHistory': approved_from,
    'components': components,
}
temporary = target_path.with_suffix('.tmp')
temporary.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
temporary.replace(target_path)

summary = ' '.join(f'{name}={value.get("imageRef")}' for name, value in sorted(components.items()))
print(f'APPROVED_KNOWN_GOOD: target=environment {summary}')
PY
  exit 0
fi


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
