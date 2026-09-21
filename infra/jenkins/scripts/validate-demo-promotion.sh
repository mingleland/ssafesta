#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_MANIFEST_PATH:?}" "${DEV_VERIFICATION_RESULT_PATH:?}" "${DEMO_APPROVED_BY:?}"
[[ -f "${RELEASE_MANIFEST_PATH}" ]] || { echo 'release manifest is missing' >&2; exit 66; }
[[ -f "${DEV_VERIFICATION_RESULT_PATH}" ]] || { echo 'dev verification result is missing' >&2; exit 66; }
[[ "${DEMO_APPROVED_BY}" =~ ^[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}$ ]] || { echo 'invalid DEMO_APPROVED_BY' >&2; exit 64; }

# readiness 통과(=current)만으로는 프로덕션에 못 간다. 사람이 demo 에서 확인한 조합이어야 한다
# (spec §Session 2026-09-18 규칙 5).
known_good_env="${KNOWN_GOOD_ENVIRONMENT_PATH:-${ENVIRONMENT_STATE_DIR:-/var/lib/festa-environments}/dev/batches/known-good/environment.json}"
[[ -f "${known_good_env}" ]] || { echo 'promotion denied: no human-approved known-good environment' >&2; exit 66; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 이 스크립트의 stdout은 Jenkins가 releaseId로 직접 소비한다.
# contract validator의 진단 출력이 섞이면 DEMO_RELEASE_ID가 여러 줄이 되므로
# 성공 출력은 버리고 실패 stderr/exit code만 전달한다.
bash "${script_dir}/validate-contracts.sh" release "${RELEASE_MANIFEST_PATH}" >/dev/null
bash "${script_dir}/validate-contracts.sh" verification "${DEV_VERIFICATION_RESULT_PATH}" >/dev/null

python_bin="$(command -v python3 || command -v python || true)"
[[ -n "${python_bin}" ]] || { echo 'Python 3 is required' >&2; exit 69; }
"${python_bin}" - "${RELEASE_MANIFEST_PATH}" "${DEV_VERIFICATION_RESULT_PATH}" "${known_good_env}" <<'PY'
import json,pathlib,sys
release=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
verification=json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
known_good=json.loads(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8'))
names=[item['name'] for item in release['components']]
if release['scm']['branch'] != 'develop':
    raise SystemExit('promotion denied: release is not from develop')
if not release['jenkins']['job'].startswith('festa-gitlab-develop/develop'):
    raise SystemExit('promotion denied: release was not built by the develop pipeline')
if set(names) != {'ai','back','front','game'} or len(names) != 4:
    raise SystemExit('promotion denied: release must contain exactly ai, back, front and game')
if verification['releaseId'] != release['releaseId']:
    raise SystemExit('promotion denied: verification release ID does not match')
if not verification['targetId'].startswith('dev-'):
    raise SystemExit('promotion denied: verification target is not dev')
if verification['finalDecision'] != 'PASS' or not verification['requiredNonAiPassed'] or verification.get('aiPassed') is not True:
    raise SystemExit('promotion denied: dev verification did not fully pass')
if known_good.get('state') != 'KNOWN_GOOD':
    raise SystemExit('promotion denied: known-good environment is not approved')
approved={name: value.get('imageRef') for name, value in known_good.get('components', {}).items()}
proposed={item['name']: item['imageRef'] for item in release['components']}
if approved != proposed:
    raise SystemExit('promotion denied: release does not match the approved known-good environment')
print(release['releaseId'])
PY
