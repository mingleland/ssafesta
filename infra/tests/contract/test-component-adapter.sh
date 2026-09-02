#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
sha=0123456789abcdef0123456789abcdef01234567
tmp="$(mktemp -d)"; trap 'rm -rf "${tmp}"' EXIT

source "${repo_root}/ci/lib.sh"
declare -A expected_dirs=([ai]=festa-ai [back]=backend [front]=festa-frontend [game]=festa-unity)
for component in "${!expected_dirs[@]}"; do
  CI_COMPONENT="${component}"
  [[ "$(ci_component_dir)" == "${repo_root}/${expected_dirs[${component}]}" ]] \
    || { echo "${component}: wrong component directory" >&2; exit 1; }
done

grep -Fq 'pytest tests/unit tests/contract' "${repo_root}/ci/test" \
  || { echo 'AI default CI must exclude opt-in integration tests' >&2; exit 1; }

for adapter in validate test build package verify; do
  [[ -f "${repo_root}/ci/${adapter}" ]] || { echo "missing ci/${adapter}" >&2; exit 1; }
  [[ "$(git -C "${repo_root}" ls-files --stage "ci/${adapter}" | awk '{print $1}')" == 100755 ]] \
    || { echo "ci/${adapter}: Git mode must be 100755" >&2; exit 1; }
  run_dir="${tmp}/${adapter}"; mkdir -p "${run_dir}"
  CI_DRY_RUN=1 CI_COMPONENT=back CI_BRANCH=back CI_COMMIT_SHA="${sha}" \
    CI_RUN_ID=test-1 CI_ARTIFACT_DIR="${run_dir}" DEPLOY_TARGET=dev-back \
    RELEASE_ID=test-release RELEASE_MANIFEST_PATH="${repo_root}/infra/tests/contract/fixtures/release-valid.json" \
    bash "${repo_root}/ci/${adapter}"
  summary="${run_dir}/stage-summary.json"
  [[ -f "${summary}" ]] || { echo "${adapter}: missing summary" >&2; exit 1; }
  python - "${summary}" "${adapter}" "${sha}" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
assert d['stage']==sys.argv[2] and d['commit']==sys.argv[3] and d['status']=='SUCCEEDED'
PY
done

if CI_DRY_RUN=1 CI_COMPONENT=back CI_BRANCH=back CI_COMMIT_SHA=short CI_RUN_ID=x \
  CI_ARTIFACT_DIR="${tmp}/invalid" bash "${repo_root}/ci/validate" >/dev/null 2>&1; then
  echo "short SHA was accepted" >&2; exit 1
fi
if CI_DRY_RUN=1 CI_COMPONENT=back CI_BRANCH=back CI_COMMIT_SHA="${sha}" CI_RUN_ID=x \
  CI_ARTIFACT_DIR="${tmp}/forced" CI_FORCE_FAILURE=test bash "${repo_root}/ci/test" >/dev/null 2>&1; then
  echo "forced adapter failure returned zero" >&2; exit 1
fi
echo "PASS: component adapter contract"
