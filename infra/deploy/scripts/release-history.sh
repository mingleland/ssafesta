#!/usr/bin/env bash
# current 이 된 릴리스의 환경 전체 조합을 이력으로 남기고, 특정 과거 릴리스로 되돌린다.
# candidate 에서 죽어 current 가 되지 못한 릴리스는 남기지 않는다 (spec §Session 2026-09-18).
#
# 사용:
#   release-history.sh record <release-id>           현재 current 조합을 이력으로 적재
#   release-history.sh list                          보관 중인 이력
#   release-history.sh show <release-id>             이력 한 건의 메타
#   release-history.sh restore <release-id> [comp…]  이력의 릴리스를 다시 배포 (ai|back|front)
#
# 환경변수:
#   ENVIRONMENT_STATE_DIR      상태 루트 (기본 /var/lib/festa-environments)
#   RELEASE_HISTORY_RETENTION  보관 개수 (기본 10). known-good 이 가리키는 이력은 개수와 무관하게 보호한다
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

state_root="${DEV_BATCH_STATE_DIR:-${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}/dev/batches}"
game_state_dir="${GAME_DEPLOY_STATE_DIR:-${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}/demo/game}"
history_root="${state_root}/history"
known_good_env="${state_root}/known-good/environment.json"
retention="${RELEASE_HISTORY_RETENTION:-10}"
python_bin="${PYTHON_BIN:-python3}"

usage() {
  echo 'Usage: release-history.sh record <release-id> | list | show <release-id> | restore <release-id> [component...]' >&2
  exit 64
}

[[ $# -ge 1 ]] || usage
action="$1"; shift
command -v "${python_bin}" >/dev/null 2>&1 || { echo 'Python 3 is required' >&2; exit 69; }
[[ "${retention}" =~ ^[1-9][0-9]*$ ]] || { echo 'RELEASE_HISTORY_RETENTION must be a positive integer' >&2; exit 64; }

safe_id() {
  [[ "$1" =~ ^[A-Za-z0-9_.-]+$ && "$1" != '.' && "$1" != '..' ]] \
    || { echo "invalid release id: $1" >&2; exit 64; }
}

write_meta() {
  ENTRY_DIR="$1" RELEASE_ID="$2" "${python_bin}" <<'PY'
import datetime, json, os, pathlib

entry = pathlib.Path(os.environ['ENTRY_DIR'])

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
    # game current.json 은 단일 릴리스 문서라 components 목록이 없다.
    return {
        'releaseId': document.get('releaseId'),
        'sourceCommit': document.get('sourceCommit'),
        'imageRef': document.get('imageRef'),
        'contentId': document.get('contentId'),
    }

components = {}
for component in ('ai', 'back', 'front', 'game'):
    path = entry / f'{component}.json'
    if path.is_file():
        components[component] = identity(path, component)

meta = {
    'schemaVersion': '1.0.0',
    'releaseId': os.environ['RELEASE_ID'],
    'recordedAt': datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
    'readiness': 'PASS',
    'verification': 'unverified',
    'components': components,
}
(entry / 'meta.json').write_text(json.dumps(meta, indent=2) + '\n', encoding='utf-8')
PY
}

prune_history() {
  HISTORY_ROOT="${history_root}" RETENTION="${retention}" KNOWN_GOOD_ENV="${known_good_env}" "${python_bin}" <<'PY'
import json, os, pathlib, shutil

root = pathlib.Path(os.environ['HISTORY_ROOT'])
retention = int(os.environ['RETENTION'])

protected = set()
known_good = pathlib.Path(os.environ['KNOWN_GOOD_ENV'])
if known_good.is_file():
    reference = json.loads(known_good.read_text(encoding='utf-8')).get('approvedFromHistory')
    if isinstance(reference, str) and reference:
        protected.add(reference)

# mtime 만으로 정렬하면 같은 초에 적재된 항목의 순서가 흔들려 엉뚱한 이력이 지워진다.
entries = sorted((item for item in root.iterdir() if item.is_dir()), key=lambda item: (item.stat().st_mtime, item.name), reverse=True)
for index, entry in enumerate(entries):
    if index < retention or entry.name in protected:
        continue
    shutil.rmtree(entry)
    print(f'PRUNED {entry.name}')
PY
}

case "${action}" in
  record)
    [[ $# -eq 1 ]] || usage
    release_id="$1"; safe_id "${release_id}"
    entry="${history_root}/${release_id}"
    rm -rf "${entry}"; mkdir -p "${entry}"
    recorded=0
    for component in ai back front; do
      source_path="${state_root}/current/${component}.json"
      [[ -f "${source_path}" ]] || continue
      cp "${source_path}" "${entry}/${component}.json"; recorded=1
    done
    # game 은 infra-003 이 소유한 별도 상태다. 있으면 같은 조합에 묶고, 없으면 조합에서 빠진다.
    [[ ! -f "${game_state_dir}/current.json" ]] || cp "${game_state_dir}/current.json" "${entry}/game.json"
    (( recorded == 1 )) || { rm -rf "${entry}"; echo 'no current release to record' >&2; exit 66; }
    write_meta "${entry}" "${release_id}"
    prune_history
    echo "RECORDED ${release_id}"
    ;;
  list)
    [[ $# -eq 0 ]] || usage
    [[ -d "${history_root}" ]] || { echo 'no release history yet' >&2; exit 66; }
    HISTORY_ROOT="${history_root}" "${python_bin}" <<'PY'
import json, os, pathlib

root = pathlib.Path(os.environ['HISTORY_ROOT'])
rows = []
for entry in root.iterdir():
    meta = entry / 'meta.json'
    if not meta.is_file():
        continue
    document = json.loads(meta.read_text(encoding='utf-8'))
    rows.append((document.get('recordedAt', ''), document.get('releaseId', entry.name), document.get('verification', '?'), sorted(document.get('components', {}))))
for recorded_at, release_id, verification, components in sorted(rows, reverse=True):
    print(f"{recorded_at}  {release_id}  {verification}  {','.join(components)}")
PY
    ;;
  show)
    [[ $# -eq 1 ]] || usage
    safe_id "$1"
    meta="${history_root}/$1/meta.json"
    [[ -f "${meta}" ]] || { echo "release is not in history: $1" >&2; exit 66; }
    cat "${meta}"
    ;;
  restore)
    # 실서비스 복구는 known-good 을 쓰고, 이 경로는 장애 구간 조사를 위한 과거 릴리스 재배포다.
    [[ $# -ge 1 ]] || usage
    release_id="$1"; shift; safe_id "${release_id}"
    entry="${history_root}/${release_id}"
    [[ -d "${entry}" ]] || { echo "release is not in history: ${release_id}" >&2; exit 66; }
    components=("$@")
    (( ${#components[@]} )) || components=(ai back front)
    batch_id="restore-${release_id}"
    snapshot_dir="${state_root}/${batch_id}/before"
    mkdir -p "${snapshot_dir}" "${state_root}/current"
    for component in "${components[@]}"; do
      # game 되돌리기는 infra-003 의 rollback-game.sh 가 소유한다.
      [[ "${component}" =~ ^(ai|back|front)$ ]] || { echo "restore covers ai, back and front only: ${component}" >&2; exit 64; }
      [[ -f "${entry}/${component}.json" ]] || { echo "history entry has no ${component}: ${release_id}" >&2; exit 66; }
      cp "${entry}/${component}.json" "${snapshot_dir}/${component}.json"
      DEV_BATCH_ROLLBACK=1 DEV_BATCH_STATE_ROOT="${state_root}" DEV_BATCH_ID="${batch_id}" CI_COMPONENT="${component}" \
        bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" \
          --environment "${FESTA_DEPLOY_ENVIRONMENT:-demo}" --component "${component}" --release-manifest "${snapshot_dir}/${component}.json"
      # 되돌린 릴리스가 곧 현재 실행본이다 (spec §Session 2026-09-18 규칙 6).
      cp "${snapshot_dir}/${component}.json" "${state_root}/current/${component}.json"
      echo "RESTORED ${component} ${release_id}"
    done
    ;;
  *)
    usage
    ;;
esac
