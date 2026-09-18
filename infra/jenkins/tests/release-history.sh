#!/usr/bin/env bash
# current 이 된 릴리스만 이력에 남고, retention 정리가 known-good 을 보호하는지 검증한다
# (spec §Session 2026-09-18 규칙 1·4·7·8).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
history_script="${repo_root}/infra/deploy/scripts/release-history.sh"
approve_script="${repo_root}/infra/deploy/scripts/approve-known-good.sh"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

state_root="${work}/state"
sha='0123456789abcdef0123456789abcdef01234567'
mkdir -p "${state_root}/current"

write_current() {
  local component="$1" release_id="$2"
  cat >"${state_root}/current/${component}.json" <<JSON
{"schemaVersion":"1.0.0","releaseId":"${release_id}","scm":{"commit":"${sha}"},"components":[{"name":"${component}","sourceCommit":"${sha}","imageRef":"festa-${component}:${release_id}","contentId":"sha256:$(printf '%064d' 1)"}]}
JSON
}

record() {
  local release_id="$1"
  for component in ai back front; do write_current "${component}" "${release_id}"; done
  DEV_BATCH_STATE_DIR="${state_root}" RELEASE_HISTORY_RETENTION=10 bash "${history_script}" record "${release_id}" >/dev/null
}

for index in $(seq -w 1 12); do record "r${index}"; done

kept="$(find "${state_root}/history" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[[ "${kept}" == 10 ]] || { echo "retention did not hold: ${kept}" >&2; exit 1; }
[[ ! -d "${state_root}/history/r01" ]] || { echo 'oldest history entry was not pruned' >&2; exit 1; }
[[ -d "${state_root}/history/r12" ]] || { echo 'newest history entry is missing' >&2; exit 1; }
grep -q '"verification": "unverified"' "${state_root}/history/r12/meta.json"
grep -q '"readiness": "PASS"' "${state_root}/history/r12/meta.json"

# 사람 승인은 컴포넌트 하나가 아니라 전체 조합을 굳힌다 (규칙 4).
ENVIRONMENT_STATE_DIR="${work}/env" bash -c 'true'
mkdir -p "${work}/env/dev"
ln -s "${state_root}" "${work}/env/dev/batches"
ENVIRONMENT_STATE_DIR="${work}/env" bash "${approve_script}" environment >/dev/null

known_good="${state_root}/known-good/environment.json"
[[ -f "${known_good}" ]] || { echo 'known-good environment snapshot is missing' >&2; exit 1; }
grep -q '"state": "KNOWN_GOOD"' "${known_good}"
grep -q '"approvedFromHistory": "r12"' "${known_good}"
for component in ai back front; do
  grep -q "festa-${component}:r12" "${known_good}"
  [[ -f "${state_root}/known-good/${component}.json" ]] || { echo "batch rollback baseline is missing: ${component}" >&2; exit 1; }
done
grep -q '"verification": "verified"' "${state_root}/history/r12/meta.json"

# retention 을 채워도 known-good 이 가리키는 이력은 남아야 한다 (규칙 8).
for index in $(seq -w 13 24); do record "r${index}"; done
[[ -d "${state_root}/history/r12" ]] || { echo 'known-good history entry was pruned' >&2; exit 1; }
[[ ! -d "${state_root}/history/r13" ]] || { echo 'unprotected history entry survived retention' >&2; exit 1; }

# game 되돌리기는 infra-003 소유다 — 여기서 받아주면 월드를 두 곳에서 건드린다.
if DEV_BATCH_STATE_DIR="${state_root}" bash "${history_script}" restore r12 game >/dev/null 2>&1; then
  echo 'restore accepted game' >&2
  exit 1
fi

DEV_BATCH_STATE_DIR="${state_root}" bash "${history_script}" list | grep -q 'r24'
DEV_BATCH_STATE_DIR="${state_root}" bash "${history_script}" show r12 | grep -q '"releaseId": "r12"'

echo 'PASS: release history keeps promoted releases, protects known-good, and approves whole environments'

