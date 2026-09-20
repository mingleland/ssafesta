#!/usr/bin/env bash
# stale develop run이 deploy lock 뒤에 도착해도 component 배포를 시작하지 않는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
expected='0123456789abcdef0123456789abcdef01234567'
newer='fedcba9876543210fedcba9876543210fedcba98'
content_id="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
cat >"${work}/candidate.json" <<JSON
{"scm":{"commit":"${expected}"},"components":[{"name":"back","sourceCommit":"${expected}","imageRef":"festa-back:${expected}","contentId":"${content_id}"}],"rollbackSafety":{"classification":"SAFE","dataChange":"none","dbSchemaChanged":false,"secretOrConfigChanged":false}}
JSON

cat >"${work}/deploy" <<'SH'
#!/usr/bin/env bash
touch "${DEPLOY_MARKER}"
SH
chmod +x "${work}/deploy"

set +e
DEV_BATCH_ID=stale DEPLOY_COMPONENTS=back RELEASE_MANIFEST_PATH="${work}/candidate.json" CI_ARTIFACT_DIR="${work}/artifacts" \
  DEV_BATCH_STATE_DIR="${work}/state" DEPLOY_COMPONENT_COMMAND="${work}/deploy" VERIFY_COMPONENT_COMMAND=true \
  FRESHNESS_EXPECTED_SHA="${expected}" FRESHNESS_ACTUAL_SHA="${newer}" DEPLOY_MARKER="${work}/deployed" \
  bash "${repo_root}/infra/jenkins/scripts/deploy-dev-batch.sh"
status=$?
set -e
[[ "${status}" == 75 ]] || { echo 'superseded run returned an unexpected exit code' >&2; exit 1; }

[[ ! -e "${work}/deployed" ]] || { echo 'superseded run started a deploy' >&2; exit 1; }
grep -q '"status": "SUPERSEDED"' "${work}/artifacts/dev-batch-result.json"
echo 'PASS: superseded develop push cannot deploy after freshness recheck'
