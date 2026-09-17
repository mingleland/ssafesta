#!/usr/bin/env bash
# 선택된 dev component를 하나의 batch로 배포하고, 실패 시 이 batch의 known-good snapshot만 복구한다.
# 성공 시 CURRENT 만 자동 갱신하며, KNOWN_GOOD 승격은 사람이 approve-known-good.sh 로 별도 수행한다 (spec §Session 2026-09-17).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"

: "${DEV_BATCH_ID:?}" "${DEPLOY_COMPONENTS:?}" "${RELEASE_MANIFEST_PATH:?}" "${CI_ARTIFACT_DIR:?}"
[[ -f "${RELEASE_MANIFEST_PATH}" ]] || { echo 'release manifest is missing' >&2; exit 66; }
[[ "${DEV_BATCH_ID}" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo 'invalid DEV_BATCH_ID' >&2; exit 64; }

python_bin=''
for candidate in "${PYTHON_BIN:-}" python3 python; do
  [[ -n "${candidate}" ]] || continue
  if command -v "${candidate}" >/dev/null 2>&1 && "${candidate}" -c 'import sys; assert sys.version_info.major == 3' >/dev/null 2>&1; then
    python_bin="${candidate}"
    break
  fi
done
[[ -n "${python_bin}" ]] || { echo 'Python 3 is required' >&2; exit 69; }

state_root="${DEV_BATCH_STATE_DIR:-${ENVIRONMENT_STATE_DIR:-/tmp/festa-environments}/dev/batches}"
batch_dir="${state_root}/${DEV_BATCH_ID}"
snapshot_dir="${batch_dir}/before"
status_path="${CI_ARTIFACT_DIR}/dev-batch-result.json"
lock_path="${state_root}/.deploy.lock"
lock_timeout_seconds="${DEV_BATCH_LOCK_TIMEOUT_SECONDS:-300}"
IFS=',' read -r -a components <<<"${DEPLOY_COMPONENTS}"
(( ${#components[@]} > 0 )) || { echo 'DEPLOY_COMPONENTS must not be empty' >&2; exit 64; }
[[ "${lock_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || { echo 'DEV_BATCH_LOCK_TIMEOUT_SECONDS must be a positive integer' >&2; exit 64; }

declare -A seen=()
for component in "${components[@]}"; do
  [[ "${component}" =~ ^(ai|back|front|game)$ ]] || { echo "invalid deploy component: ${component}" >&2; exit 64; }
  [[ -z "${seen[${component}]:-}" ]] || { echo "duplicate deploy component: ${component}" >&2; exit 64; }
  seen["${component}"]=1
done

write_status() {
  local status="$1" reason="$2"
  mkdir -p "${CI_ARTIFACT_DIR}"
  "${python_bin}" - "${status_path}" "${DEV_BATCH_ID}" "${status}" "${reason}" "${DEPLOY_COMPONENTS}" <<'PY'
import datetime,json,pathlib,sys
path,batch_id,status,reason,components=sys.argv[1:]
document={
    'schemaVersion':'1.0.0',
    'batchId':batch_id,
    'status':status,
    'components':components.split(','),
    'reason':reason,
    'finishedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z'),
}
target=pathlib.Path(path); target.parent.mkdir(parents=True,exist_ok=True)
tmp=target.with_suffix(target.suffix+'.tmp'); tmp.write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8'); tmp.replace(target)
PY
}

is_safe_rollback() {
  "${python_bin}" - "${RELEASE_MANIFEST_PATH}" <<'PY'
import json,pathlib,sys
safety=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')).get('rollbackSafety',{})
raise SystemExit(0 if safety.get('classification') == 'SAFE' and safety.get('dataChange') in ('none','reversible') and not safety.get('dbSchemaChanged') and not safety.get('secretOrConfigChanged') else 1)
PY
}

validate_candidates() {
  local identities image_ref content_id actual
  identities="$("${python_bin}" - "${RELEASE_MANIFEST_PATH}" "${components[@]}" <<'PY'
import json, pathlib, re, sys

document = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
commit = document.get('scm', {}).get('commit', '')
if not re.fullmatch(r'[0-9a-f]{40}', commit):
    raise SystemExit('release manifest has an invalid scm commit')
items = document.get('components')
if not isinstance(items, list):
    raise SystemExit('release manifest has no component list')
by_name = {}
for item in items:
    if not isinstance(item, dict) or item.get('name') in by_name:
        raise SystemExit('release manifest has invalid or duplicate component metadata')
    by_name[item['name']] = item
for name in sys.argv[2:]:
    item = by_name.get(name)
    if not item:
        raise SystemExit(f'release manifest does not contain component: {name}')
    if item.get('sourceCommit') != commit:
        raise SystemExit(f'release manifest component has a different source commit: {name}')
    image_ref, content_id = item.get('imageRef'), item.get('contentId')
    if not isinstance(image_ref, str) or not image_ref or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(content_id)):
        raise SystemExit(f'release manifest has invalid candidate identity: {name}')
    print(f'{image_ref}\t{content_id}')
PY
)" || return
  while IFS=$'\t' read -r image_ref content_id; do
    actual="$(${DOCKER_BIN:-docker} image inspect --format '{{.Id}}' "${image_ref}")"
    [[ "${actual}" == "${content_id}" ]] || {
      echo "candidate image content ID mismatch: ${image_ref}" >&2
      return 65
    }
  done <<<"${identities}"
}

run_component() {
  local action="$1" component="$2" manifest="$3"
  local override='' component_env=''
  case "${action}" in
    deploy)
      override="${DEPLOY_COMPONENT_COMMAND:-}"
      ;;
    verify)
      override="${VERIFY_COMPONENT_COMMAND:-}"
      ;;
    rollback)
      override="${ROLLBACK_COMPONENT_COMMAND:-}"
      ;;
  esac
  if [[ -n "${override}" ]]; then
    CI_COMPONENT="${component}" RELEASE_MANIFEST_PATH="${manifest}" bash -o pipefail -c "${override}"
    return $?
  fi
  case "${component}" in
    ai) component_env="${DEV_AI_ENV_FILE:-${COMPONENT_ENV_FILE:-}}" ;;
    back) component_env="${DEV_BACK_ENV_FILE:-${COMPONENT_ENV_FILE:-}}" ;;
  esac
  if [[ "${component}" =~ ^(ai|back)$ && -z "${component_env}" ]]; then
    echo "missing component environment file for ${component}" >&2
    return 64
  fi
  case "${action}" in
    deploy)
      COMPONENT_ENV_FILE="${component_env}" CI_COMPONENT="${component}" RELEASE_MANIFEST_PATH="${manifest}" \
        bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" --environment dev --component "${component}" --release-manifest "${manifest}"
      ;;
    verify)
      COMPONENT_ENV_FILE="${component_env}" CI_COMPONENT="${component}" RELEASE_MANIFEST_PATH="${manifest}" \
        bash "${repo_root}/infra/environments/scripts/verify-environment.sh" --environment dev --component "${component}"
      ;;
    rollback)
      COMPONENT_ENV_FILE="${component_env}" DEV_BATCH_ROLLBACK=1 DEV_BATCH_STATE_ROOT="${state_root}" CI_COMPONENT="${component}" RELEASE_MANIFEST_PATH="${manifest}" \
        bash "${repo_root}/infra/environments/scripts/deploy-environment.sh" --environment dev --component "${component}" --release-manifest "${manifest}"
      ;;
  esac
}

mkdir -p "${state_root}"
command -v flock >/dev/null 2>&1 || { write_status MANUAL_ACTION_REQUIRED 'flock is required for the dev deployment lock'; exit 69; }
exec {batch_lock_fd}>"${lock_path}"
if ! flock -w "${lock_timeout_seconds}" "${batch_lock_fd}"; then
  write_status LOCKED 'another dev deployment batch still owns the lock'
  exit 73
fi
release_lock() { flock -u "${batch_lock_fd}" || true; }
trap release_lock EXIT HUP INT TERM

if [[ -n "${FRESHNESS_EXPECTED_SHA:-}" ]]; then
  set +e
  bash "${script_dir}/freshness.sh"
  freshness_status=$?
  set -e
  if [[ "${freshness_status}" != 0 ]]; then
    if [[ "${freshness_status}" == 75 ]]; then
      write_status SUPERSEDED 'newer develop head exists before batch deployment'
      exit 75
    fi
    write_status MANUAL_ACTION_REQUIRED 'freshness check failed'
    exit "${freshness_status}"
  fi
fi

mkdir -p "${snapshot_dir}" "${state_root}/known-good"
validate_candidates
changed=()
for component in "${components[@]}"; do
  known_good="${state_root}/known-good/${component}.json"
  [[ -f "${known_good}" ]] && cp "${known_good}" "${snapshot_dir}/${component}.json" || : >"${snapshot_dir}/${component}.missing"
done

rollback() {
  local manual=false component snapshot
  is_safe_rollback || manual=true
  for (( index=${#changed[@]}-1; index>=0; index-- )); do
  component="${changed[index]}"; snapshot="${snapshot_dir}/${component}.json"
    if [[ ! -f "${snapshot}" ]] || ! run_component rollback "${component}" "${snapshot}" || ! run_component verify "${component}" "${snapshot}"; then
      manual=true
    fi
  done
  if [[ "${manual}" == true ]]; then
    write_status MANUAL_ACTION_REQUIRED 'rollback is unsafe, has no known-good snapshot, or restoration failed'
    return 1
  fi
  write_status ROLLED_BACK 'candidate deploy or verification failed; affected components restored'
  return 0
}

for component in "${components[@]}"; do
  # A failed Compose apply may have created a partial target, so restore this component too.
  changed+=("${component}")
  if ! run_component deploy "${component}" "${RELEASE_MANIFEST_PATH}"; then
    rollback || true
    exit 1
  fi
  if ! run_component verify "${component}" "${RELEASE_MANIFEST_PATH}"; then
    rollback || true
    exit 1
  fi
done

for component in "${components[@]}"; do
  # 자동 승격은 CURRENT 까지만. KNOWN_GOOD 은 사람이 approve-known-good.sh 로 승인해야 갱신된다.
  mkdir -p "${state_root}/current"
  target="${state_root}/current/${component}.json"
  temp="${target}.tmp"
  cp "${RELEASE_MANIFEST_PATH}" "${temp}"
  mv -f "${temp}" "${target}"
done
write_status ACTIVE 'all selected component deployments and verifications passed'
echo "${status_path}"
