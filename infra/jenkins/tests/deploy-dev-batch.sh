#!/usr/bin/env bash
# dev batch coordinator가 selected component만 승격하고 실패 시 같은 batch의 변경만 복구하는지 검증한다.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
sha='0123456789abcdef0123456789abcdef01234567'

content_id="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
cat >"${work}/candidate.json" <<JSON
{"schemaVersion":"1.0.0","scm":{"commit":"${sha}"},"components":[{"name":"ai","sourceCommit":"${sha}","imageRef":"festa-ai:${sha}","contentId":"${content_id}"},{"name":"back","sourceCommit":"${sha}","imageRef":"festa-back:${sha}","contentId":"${content_id}"},{"name":"front","sourceCommit":"${sha}","imageRef":"festa-front:${sha}","contentId":"${content_id}"},{"name":"game","sourceCommit":"${sha}","imageRef":"festa-game:${sha}","contentId":"${content_id}"}],"rollbackSafety":{"classification":"SAFE","dataChange":"none","dbSchemaChanged":false,"secretOrConfigChanged":false}}
JSON

cat >"${work}/docker" <<SH
#!/usr/bin/env bash
[[ "\$1 \$2" == 'image inspect' ]] || exit 64
printf '%s\\n' "${content_id}"
SH
chmod +x "${work}/docker"

cat >"${work}/deploy" <<'SH'
#!/usr/bin/env bash
printf 'deploy:%s\n' "${CI_COMPONENT}" >>"${BATCH_LOG}"
SH
cat >"${work}/verify" <<'SH'
#!/usr/bin/env bash
printf 'verify:%s\n' "${CI_COMPONENT}" >>"${BATCH_LOG}"
if [[ "${CI_COMPONENT}" == "${VERIFY_FAIL_COMPONENT:-}" && ! -e "${BATCH_LOG}.failed-once" ]]; then
  : >"${BATCH_LOG}.failed-once"
  exit 1
fi
SH
cat >"${work}/rollback" <<'SH'
#!/usr/bin/env bash
printf 'rollback:%s\n' "${CI_COMPONENT}" >>"${BATCH_LOG}"
SH
chmod +x "${work}/deploy" "${work}/verify" "${work}/rollback"

run_batch() {
  local id="$1" components="$2"
  DEV_BATCH_ID="${id}" DEPLOY_COMPONENTS="${components}" RELEASE_MANIFEST_PATH="${work}/candidate.json" \
    CI_ARTIFACT_DIR="${work}/artifacts/${id}" DEV_BATCH_STATE_DIR="${work}/state" BATCH_LOG="${work}/${id}.log" \
    DOCKER_BIN="${work}/docker" DEPLOY_COMPONENT_COMMAND="${work}/deploy" VERIFY_COMPONENT_COMMAND="${work}/verify" ROLLBACK_COMPONENT_COMMAND="${work}/rollback" \
    bash "${repo_root}/infra/jenkins/scripts/deploy-dev-batch.sh"
}

mkdir -p "${work}/state/known-good"
for component in ai back front game; do cp "${work}/candidate.json" "${work}/state/known-good/${component}.json"; done
run_batch single back
grep -Fqx 'deploy:back' "${work}/single.log"
grep -Fqx 'verify:back' "${work}/single.log"
! grep -qE '(ai|front|game)' "${work}/single.log"
grep -q '"status": "ACTIVE"' "${work}/artifacts/single/dev-batch-result.json"

run_batch multi back,front
grep -Fqx 'deploy:back' "${work}/multi.log"
grep -Fqx 'deploy:front' "${work}/multi.log"
grep -q '"status": "ACTIVE"' "${work}/artifacts/multi/dev-batch-result.json"

if VERIFY_FAIL_COMPONENT=front run_batch failed back,front; then
  echo 'verification failure unexpectedly passed' >&2
  exit 1
fi
grep -Fqx 'rollback:front' "${work}/failed.log"
grep -Fqx 'rollback:back' "${work}/failed.log"
grep -Fqx 'verify:front' "${work}/failed.log"
grep -Fqx 'verify:back' "${work}/failed.log"
grep -q '"status": "ROLLED_BACK"' "${work}/artifacts/failed/dev-batch-result.json"

unsafe="${work}/unsafe.json"
sed 's/"classification":"SAFE"/"classification":"UNSAFE"/' "${work}/candidate.json" >"${unsafe}"
if DEV_BATCH_ID=unsafe DEPLOY_COMPONENTS=back RELEASE_MANIFEST_PATH="${unsafe}" CI_ARTIFACT_DIR="${work}/artifacts/unsafe" \
  DEV_BATCH_STATE_DIR="${work}/state" BATCH_LOG="${work}/unsafe.log" DOCKER_BIN="${work}/docker" \
  DEPLOY_COMPONENT_COMMAND="${work}/deploy" VERIFY_COMPONENT_COMMAND="${work}/verify" ROLLBACK_COMPONENT_COMMAND="${work}/rollback" \
  VERIFY_FAIL_COMPONENT=back bash "${repo_root}/infra/jenkins/scripts/deploy-dev-batch.sh"; then
  echo 'unsafe rollback unexpectedly passed' >&2
  exit 1
fi
! grep -q '^rollback:' "${work}/unsafe.log"
grep -q '"status": "MANUAL_ACTION_REQUIRED"' "${work}/artifacts/unsafe/dev-batch-result.json"
echo 'PASS: dev batch activates only after all selected components verify and rolls back its own changes'
