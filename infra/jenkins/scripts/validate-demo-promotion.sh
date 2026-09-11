#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_MANIFEST_PATH:?}" "${DEV_VERIFICATION_RESULT_PATH:?}" "${DEMO_APPROVED_BY:?}"
[[ -f "${RELEASE_MANIFEST_PATH}" ]] || { echo 'release manifest is missing' >&2; exit 66; }
[[ -f "${DEV_VERIFICATION_RESULT_PATH}" ]] || { echo 'dev verification result is missing' >&2; exit 66; }
[[ "${DEMO_APPROVED_BY}" =~ ^[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}$ ]] || { echo 'invalid DEMO_APPROVED_BY' >&2; exit 64; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${script_dir}/validate-contracts.sh" release "${RELEASE_MANIFEST_PATH}"
bash "${script_dir}/validate-contracts.sh" verification "${DEV_VERIFICATION_RESULT_PATH}"

python_bin="$(command -v python3 || command -v python || true)"
[[ -n "${python_bin}" ]] || { echo 'Python 3 is required' >&2; exit 69; }
"${python_bin}" - "${RELEASE_MANIFEST_PATH}" "${DEV_VERIFICATION_RESULT_PATH}" <<'PY'
import json,pathlib,sys
release=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
verification=json.loads(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
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
print(release['releaseId'])
PY
