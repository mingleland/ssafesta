#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
commit=0123456789abcdef0123456789abcdef01234567

bash "${repo_root}/infra/jenkins/tests/demo-promotion.sh"

write_release() {
  local path="$1" classification="$2" config_changed="$3"
  python - "${path}" "${commit}" "${classification}" "${config_changed}" <<'PY'
import json,pathlib,sys
path,commit,classification,config=sys.argv[1:]
components=[{'name':name,'storageMode':'local-docker','imageRef':f'festa-{name}:{commit}','contentId':'sha256:'+char*64,'sourceCommit':commit} for name,char in zip(('ai','back','front','game'),'abcd')]
pathlib.Path(path).write_text(json.dumps({'schemaVersion':'1.0.0','releaseId':'develop-'+commit+'-9','scm':{'provider':'gitlab','repository':'festa/test','branch':'develop','commit':commit},'jenkins':{'job':'festa-gitlab-develop/develop','buildNumber':9},'components':components,'rollbackSafety':{'classification':classification,'dataChange':'none','dbSchemaChanged':False,'secretOrConfigChanged':config=='true'},'createdAt':'2026-09-11T00:00:00Z'}),encoding='utf-8')
PY
}

write_release "${work}/release-safe.json" SAFE false
RELEASE_ID="develop-${commit}-9" DEPLOY_TARGET=demo-integration CI_ARTIFACT_DIR="${work}/success" \
  VERIFY_WEB_COMMAND=true VERIFY_LOGIN_COMMAND=true VERIFY_WORLD_COMMAND=true VERIFY_AI_COMMAND=true \
  bash "${repo_root}/infra/deploy/scripts/verify-release.sh" >/dev/null
STATE_DIR="${work}/state" RELEASE_MANIFEST_PATH="${work}/release-safe.json" \
  VERIFICATION_RESULT_PATH="${work}/success/verification-result.json" DEPLOY_TARGET=demo-integration \
  bash "${repo_root}/infra/deploy/scripts/promote-release.sh" >/dev/null
grep -q '"currentReleaseId": "develop-' "${work}/state/target-state.json"

RELEASE_ID="develop-${commit}-9" DEPLOY_TARGET=demo-integration CI_ARTIFACT_DIR="${work}/non-ai" \
  VERIFY_WEB_COMMAND=false VERIFY_LOGIN_COMMAND=true VERIFY_WORLD_COMMAND=true VERIFY_AI_COMMAND=true \
  bash "${repo_root}/infra/deploy/scripts/verify-release.sh" >/dev/null 2>&1 && { echo 'FAIL: non-AI failure passed' >&2; exit 1; }
RELEASE_MANIFEST_PATH="${work}/release-safe.json" VERIFICATION_RESULT_PATH="${work}/non-ai/verification-result.json" \
  RECOVERY_DECISION_PATH="${work}/non-ai/decision.json" bash "${repo_root}/infra/deploy/scripts/decide-recovery.sh" >/dev/null
grep -q '"decision": "AUTO_ROLLBACK"' "${work}/non-ai/decision.json"
STATE_DIR="${work}/rollback-state" KNOWN_GOOD_MANIFEST_PATH="${work}/release-safe.json" CI_ARTIFACT_DIR="${work}/rollback" \
  RELEASE_ID="candidate-non-ai" DEPLOY_TARGET=demo-integration ROLLBACK_DEPLOY_COMMAND=true ROLLBACK_VERIFY_COMMAND=true \
  bash "${repo_root}/infra/deploy/scripts/rollback-release.sh" >/dev/null

write_release "${work}/release-config.json" SAFE true
RELEASE_MANIFEST_PATH="${work}/release-config.json" VERIFICATION_RESULT_PATH="${work}/non-ai/verification-result.json" \
  RECOVERY_DECISION_PATH="${work}/manual.json" bash "${repo_root}/infra/deploy/scripts/decide-recovery.sh" >/dev/null
grep -q '"decision": "MANUAL"' "${work}/manual.json"

RELEASE_ID="develop-${commit}-9" DEPLOY_TARGET=demo-integration CI_ARTIFACT_DIR="${work}/ai-only" \
  VERIFY_WEB_COMMAND=true VERIFY_LOGIN_COMMAND=true VERIFY_WORLD_COMMAND=true VERIFY_AI_COMMAND=false \
  bash "${repo_root}/infra/deploy/scripts/verify-release.sh" >/dev/null 2>&1 && { echo 'FAIL: AI-only failure passed' >&2; exit 1; }
RELEASE_MANIFEST_PATH="${work}/release-safe.json" VERIFICATION_RESULT_PATH="${work}/ai-only/verification-result.json" \
  RECOVERY_DECISION_PATH="${work}/ai-only/decision.json" bash "${repo_root}/infra/deploy/scripts/decide-recovery.sh" >/dev/null
grep -q '"decision": "AI_RETRY"' "${work}/ai-only/decision.json"

echo 'PASS: demo promotion covers success, non-AI rollback, config manual wait and AI-only retry wait'
