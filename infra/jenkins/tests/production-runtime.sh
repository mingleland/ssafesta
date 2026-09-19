#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
fail(){ echo "FAIL: $*" >&2; exit 1; }
compose="${repo_root}/infra/deploy/compose/production/compose.yaml"
stage="${repo_root}/infra/deploy/scripts/stage-production-webgl.sh"
deploy="${repo_root}/infra/deploy/scripts/deploy-production-candidate.sh"
verify="${repo_root}/infra/deploy/scripts/verify-production-candidate.sh"
bootstrap="${repo_root}/infra/deploy/scripts/bootstrap-production-data.sh"
ancestry="${repo_root}/infra/deploy/scripts/validate-production-main-ancestry.sh"
pipeline="${repo_root}/infra/jenkins/pipelines/production-promotion.groovy"
job="${repo_root}/infra/jenkins/jobs/gitlab-production-promotion.groovy"
agents="${repo_root}/infra/jenkins/agents/compose.yaml"
for file in "${compose}" "${stage}" "${deploy}" "${verify}" "${bootstrap}" "${ancestry}" "${pipeline}" "${job}" "${agents}"; do [[ -f "${file}" ]] || fail "missing file: ${file}"; done
for script in "${stage}" "${deploy}" "${verify}" "${bootstrap}" "${ancestry}"; do bash -n "${script}"; done
grep -Fq 'name: festa-production' "${compose}"
grep -Fq '127.0.0.1:${PROD_FRONT_HOST_PORT:-28080}:80' "${compose}"
grep -Fq '127.0.0.1:${PROD_BACK_HOST_PORT:-28081}:8080' "${compose}"
grep -Fq '127.0.0.1:${PROD_AI_HOST_PORT:-28082}:8000' "${compose}"
grep -Fq '127.0.0.1:${PROD_WORLD_HOST_PORT:-27777}:7777' "${compose}"
grep -Fq 'festa_prod_business' "${bootstrap}"
grep -Fq 'festa_prod_ai' "${bootstrap}"
grep -Fq 'host-acl-file-hashed' "${bootstrap}"
grep -Fq '~prod:*' "${bootstrap}"
grep -Fq '~prod:ai:*' "${bootstrap}"
grep -Fq "branches('*/main')" "${job}"
grep -Fq 'validate-production-main-ancestry.sh' "${pipeline}"
grep -Fq 'deploy-production-candidate.sh' "${pipeline}"
grep -Fq 'verify-production-candidate.sh' "${pipeline}"
grep -Fq 'PROD_CONNECTION_TOKEN_SECRET_FILE' "${agents}"
for forbidden in 'docker build' 'docker buildx' 'npm run build' 'gradlew build' 'Unity -batchmode' 'unity -batchmode' '--remove-orphans'; do
  if grep -R -Fq -- "${forbidden}" "${repo_root}/infra/deploy/compose/production" "${stage}" "${deploy}" "${verify}" "${job}" "${pipeline}"; then fail "forbidden command: ${forbidden}"; fi
done
if grep -Fq '/srv/festa/webgl/current' "${stage}"; then fail 'Production WebGL staging references Demo current'; fi
work="$(mktemp -d)"; trap 'rm -rf "${work}"' EXIT
touch "${work}/back.env" "${work}/ai.env"
printf 'ZmFrZS1maXh0dXJlLXNlY3JldC1mb3ItY29udHJhY3QtdGVzdC0wMTIzNDU2Nzg5\n' >"${work}/connection.secret"
AI_IMAGE_REF='fixture/ai:1' BACK_IMAGE_REF='fixture/back:1' FRONT_IMAGE_REF='fixture/front:1' WORLD_IMAGE_REF='fixture/world:1' \
AI_ENV_FILE="${work}/ai.env" BACK_ENV_FILE="${work}/back.env" CONNECTION_TOKEN_SECRET_FILE="${work}/connection.secret" \
INTERNAL_SPRING_TO_AI_TOKENS='fixture-a' INTERNAL_AI_TO_SPRING_TOKENS='fixture-b' INTERNAL_INFRA_TO_SPRING_TOKENS='fixture-c' \
PRODUCTION_WORLD_HOST='world-prod.example.invalid' ROOT_DOMAIN='example.invalid' \
docker compose --project-name festa-production-contract --file "${compose}" config --quiet
commit="$(git -C "${repo_root}" rev-parse HEAD)"; short="${commit:0:8}"; webgl_root="${work}/webgl"
mkdir -p "${webgl_root}/releases/${short}"
printf '%064d\n' 0 >"${webgl_root}/releases/${short}/.artifact-sha256"
cat >"${webgl_root}/releases/${short}/manifest.json" <<EOF_MANIFEST
{"schemaVersion":"1.0.0","sourceCommit":"${commit}","sourceBranch":"develop","dirty":false,"buildProfile":"release","apiEnvironment":"Prod","compression":"brotli+fallback"}
EOF_MANIFEST
cat >"${work}/receipt.json" <<EOF_RECEIPT
{"webgl":{"packageVersion":"${short}","artifactSha256":"0000000000000000000000000000000000000000000000000000000000000000","sourceCommit":"${commit}"}}
EOF_RECEIPT
WEBGL_RELEASE_ROOT="${webgl_root}" "${stage}" "${work}/receipt.json" >/dev/null
[[ -L "${webgl_root}/prod/candidate" ]] || fail 'candidate symlink missing'
[[ "$(readlink -f "${webgl_root}/prod/candidate")" == "$(readlink -f "${webgl_root}/releases/${short}")" ]] || fail 'candidate target mismatch'
ln -s "releases/${short}" "${webgl_root}/current"; before="$(readlink "${webgl_root}/current")"
WEBGL_RELEASE_ROOT="${webgl_root}" "${stage}" "${work}/receipt.json" >/dev/null
after="$(readlink "${webgl_root}/current")"; [[ "${before}" == "${after}" ]] || fail 'Demo current mutated'
acl="${work}/users.acl"
cat >"${acl}" <<'EOF_ACL'
user default off
user admin on #aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ~* +@all
user demo_back on #bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ~demo:* +@read
user prod_back on #cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc ~prod:* +@read
EOF_ACL
back_hash="$(printf '%s' fixture-back-password | sha256sum | awk '{print $1}')"
ai_hash="$(printf '%s' fixture-ai-password | sha256sum | awk '{print $1}')"
awk '!/^user prod_back / && !/^user prod_ai /' "${acl}" >"${acl}.tmp"
printf '%s\n' "user prod_back on #${back_hash} ~prod:* +@read +@write +@keyspace +@connection +@scripting -@dangerous +info" >>"${acl}.tmp"
printf '%s\n' "user prod_ai on #${ai_hash} ~prod:ai:* +@read +@write +@scripting -@dangerous" >>"${acl}.tmp"
mv "${acl}.tmp" "${acl}"
[[ "$(grep -c '^user prod_back ' "${acl}")" -eq 1 && "$(grep -c '^user prod_ai ' "${acl}")" -eq 1 ]]
grep -Fq 'user admin on' "${acl}"; grep -Fq 'user demo_back on' "${acl}"
grep -Eq '^user prod_back on #[0-9a-f]{64} ~prod:\* ' "${acl}"
grep -Eq '^user prod_ai on #[0-9a-f]{64} ~prod:ai:\* ' "${acl}"
! grep -Fq fixture-back-password "${acl}"; ! grep -Fq fixture-ai-password "${acl}"
echo 'PASS: Production exact-artifact runtime, WebGL isolation and Redis ACL persistence contracts'
