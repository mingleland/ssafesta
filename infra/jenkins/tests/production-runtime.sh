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
prepare="${repo_root}/infra/deploy/scripts/prepare-production-cutover.sh"
activate="${repo_root}/infra/deploy/scripts/activate-production-release.sh"
verify_public="${repo_root}/infra/deploy/scripts/verify-production-public.sh"
approve_production="${repo_root}/infra/deploy/scripts/approve-production-known-good.sh"
rollback_production="${repo_root}/infra/deploy/scripts/rollback-production-release.sh"
pipeline="${repo_root}/infra/jenkins/pipelines/production-promotion.groovy"
job="${repo_root}/infra/jenkins/jobs/gitlab-production-promotion.groovy"
agents="${repo_root}/infra/jenkins/agents/compose.yaml"
maintenance_template="${repo_root}/infra/environments/nginx/sites/prod-maintenance.conf.template"
demo_template="${repo_root}/infra/environments/nginx/sites/demo.conf.template"
prod_template="${repo_root}/infra/environments/nginx/sites/prod.conf.template"
world_template="${repo_root}/infra/environments/nginx/sites/world-prod.conf.template"
for file in "${compose}" "${stage}" "${deploy}" "${verify}" "${bootstrap}" "${ancestry}" "${prepare}" "${activate}" "${verify_public}" "${approve_production}" "${rollback_production}" "${pipeline}" "${job}" "${agents}" "${maintenance_template}"; do [[ -f "${file}" ]] || fail "missing file: ${file}"; done
for script in "${stage}" "${deploy}" "${verify}" "${bootstrap}" "${ancestry}" "${prepare}" "${activate}" "${verify_public}" "${approve_production}" "${rollback_production}"; do bash -n "${script}"; done
grep -Fq 'name: festa-production' "${compose}"
grep -Fq '127.0.0.1:${PROD_FRONT_HOST_PORT:-28080}:80' "${compose}"
grep -Fq '127.0.0.1:${PROD_BACK_HOST_PORT:-28081}:8080' "${compose}"
grep -Fq '127.0.0.1:${PROD_AI_HOST_PORT:-28082}:8000' "${compose}"
grep -Fq '127.0.0.1:${PROD_WORLD_HOST_PORT:-27777}:7777' "${compose}"
grep -Fq 'festa_prod_business' "${bootstrap}"
grep -Fq 'festa_prod_ai' "${bootstrap}"
# R2 는 Postgres 역할·Redis 네임스페이스와 달리 저장소 설정이 호스트 env 파일에 있어 정적으로 볼 수 없다.
# 공유 상태는 배포를 막지 않지만(2026-09-21 운영 결정) 사실은 반드시 드러나야 한다 — 경고 문구와
# 증거의 r2BucketIsolation 값이 사라지면 공유 여부를 나중에 알 수 없게 된다.
grep -Fq 'WARN: Production shares the demo R2 bucket' "${verify}"
grep -Fq 'WARN: Production back and ai disagree on R2_BUCKET' "${verify}"
grep -Fq "'r2BucketIsolation':os.environ.get('R2_ISOLATION')" "${verify}"
grep -Fq 'r2_isolation=SHARED_WITH_DEMO' "${verify}"
grep -Fq 'host-acl-file-hashed' "${bootstrap}"
grep -Fq '~prod:*' "${bootstrap}"
grep -Fq '~prod:ai:*' "${bootstrap}"
grep -Fq '~conversation:*' "${bootstrap}"
grep -Fq "'conversationKeyPattern':'conversation:*'" "${bootstrap}"
grep -Fq "r.get('conversationKeyPattern')!='conversation:*'" "${deploy}"
grep -Fq 'SET prod:backend:bootstrap:probe ok' "${bootstrap}"
grep -Fq 'SET conversation:bootstrap:probe ok' "${bootstrap}"
grep -Fq 'SET conversation:bootstrap:forbidden nope' "${bootstrap}"
grep -Fq 'SET prod:backend:forbidden nope' "${bootstrap}"
grep -Fq 'cleanup_probe_keys' "${bootstrap}"

if grep -Fq 'REDIS_ADMIN_' "${bootstrap}"; then
  fail 'Production bootstrap must not require a nonexistent Redis ACL admin user'
fi

grep -Fq 'restart_redis' "${bootstrap}"
grep -Fq 'wait_redis_healthy' "${bootstrap}"
grep -Fq '"${docker_bin}" restart "${redis_container}"' "${bootstrap}"
grep -Fq "branches('*/main')" "${job}"
grep -Fq 'validate-production-main-ancestry.sh' "${pipeline}"
grep -Fq 'deploy-production-candidate.sh' "${pipeline}"
grep -Fq 'verify-production-candidate.sh' "${pipeline}"
grep -Fq 'prepare-production-cutover.sh' "${pipeline}"
grep -Fq 'activate-production-release.sh' "${pipeline}"
grep -Fq 'verify-production-public.sh' "${pipeline}"
grep -Fq 'approve-production-known-good.sh' "${pipeline}"
grep -Fq 'Human Verification Gate' "${pipeline}"
grep -Fq 'PROD_CONNECTION_TOKEN_SECRET_FILE' "${agents}"
for forbidden in 'docker build' 'docker buildx' 'npm run build' 'gradlew build' 'Unity -batchmode' 'unity -batchmode' '--remove-orphans'; do
  if grep -R -Fq -- "${forbidden}" "${repo_root}/infra/deploy/compose/production" "${stage}" "${deploy}" "${verify}" "${job}" "${pipeline}"; then fail "forbidden command: ${forbidden}"; fi
done
if grep -Fq '/srv/festa/webgl/current' "${stage}"; then fail 'Production WebGL staging references Demo current'; fi
grep -Fq 'server_name demo.${ROOT_DOMAIN};' "${demo_template}"
if grep -Fq 'demo.${ROOT_DOMAIN} ${ROOT_DOMAIN}' "${demo_template}"; then fail 'Demo still claims the root domain'; fi
grep -Fq 'proxy_pass http://127.0.0.1:28081' "${prod_template}"
grep -Fq 'location /oauth2/' "${prod_template}"
# Game Studio 초안 저장(최대 2,000,000 bytes)이 nginx 기본 1m 한도에 걸려 413 이 되는 것을 막는다 (GitLab #207).
grep -Fq 'location /api/ { client_max_body_size 4m;' "${prod_template}"
grep -Fq 'location /login/oauth2/' "${prod_template}"
grep -Fq 'alias /srv/festa/webgl/prod/current/' "${prod_template}"
# Build 산출물은 파일명이 content hash 다. 포괄 location 이 없으면 loader 같은 비압축 파일이
# location /unity/ 로 떨어져 no-cache 로 나가고 엣지가 매번 오리진을 때린다 (S15P21A604-968).
grep -Fq 'location /unity/Build/ { alias /srv/festa/webgl/prod/current/Build/; add_header Cache-Control "public, max-age=31536000, immutable" always; }' "${prod_template}"
grep -Fq 'proxy_pass http://127.0.0.1:27777' "${world_template}"
# Demo World 는 demo.<root> 의 루트 WebSocket Upgrade 로 17777 에 들어간다. world.<root> 는 Production world-prod.conf 만 가진다 (Batch 1).
demo_world_template="${repo_root}/infra/unity-server/nginx/world.conf.template"
if grep -Fq 'world.${ROOT_DOMAIN}' "${demo_world_template}"; then fail 'dedicated Demo world vhost template still claims the Production World host'; fi
if grep -Fq 'server_name world.${ROOT_DOMAIN}' "${demo_template}"; then fail 'demo site must not claim the Production World host'; fi
grep -Fq 'websocket http://127.0.0.1:17777;' "${demo_template}" || fail 'demo site must route root WebSocket Upgrade to the Demo World port'
grep -Fq 'default   http://127.0.0.1:18080;' "${demo_template}" || fail 'demo site must keep plain root traffic on the Demo Front'
if grep -Fq 'websocket http://127.0.0.1:27777' "${demo_template}"; then fail 'demo site must not route to the Production World'; fi
grep -Fq 'proxy_pass $festa_demo_root_upstream;' "${demo_template}" || fail 'demo root location must dispatch by Upgrade header'
grep -Fq 'DEMO_WORLD_HOST: ${DEMO_WORLD_HOST:-demo.${ROOT_DOMAIN' "${agents}" || fail 'deploy agent must inject DEMO_WORLD_HOST'
grep -Fq 'WORLD_HOST: demo.${ROOT_DOMAIN' "${repo_root}/infra/environments/compose/demo/back.yaml" || fail 'Demo back must issue demo.<root> world tokens'
grep -Fq 'WORLD_PUBLIC_HOST=${env.DEMO_WORLD_HOST}' "${repo_root}/infra/jenkins/pipelines/develop.groovy" || fail 'develop pipeline must pass the Demo World host to readiness'
if grep -Eq 'proxy_pass[[:space:]]+http://127\.0\.0\.1:28(080|081|082)' "${maintenance_template}"; then fail 'maintenance config exposes candidate ports'; fi
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
user demo_back on #bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb ~demo:* +@read
user prod_back on #cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc ~prod:* +@read
EOF_ACL
back_hash="$(printf '%s' fixture-back-password | sha256sum | awk '{print $1}')"
ai_hash="$(printf '%s' fixture-ai-password | sha256sum | awk '{print $1}')"
awk '!/^user prod_back / && !/^user prod_ai /' "${acl}" >"${acl}.tmp"
printf '%s\n' "user prod_back on #${back_hash} ~prod:* +@read +@write +@keyspace +@connection +@scripting -@dangerous +info" >>"${acl}.tmp"
printf '%s\n' "user prod_ai on #${ai_hash} ~prod:ai:* ~conversation:* +@read +@write +@scripting -@dangerous" >>"${acl}.tmp"
mv "${acl}.tmp" "${acl}"
[[ "$(grep -c '^user prod_back ' "${acl}")" -eq 1 && "$(grep -c '^user prod_ai ' "${acl}")" -eq 1 ]]
grep -Fq 'user demo_back on' "${acl}"
grep -Eq '^user prod_back on #[0-9a-f]{64} ~prod:\* ' "${acl}"
grep -Eq '^user prod_ai on #[0-9a-f]{64} ~prod:ai:\* ~conversation:\* ' "${acl}"
! grep -Eq '^user prod_back .*~conversation:\*' "${acl}"
! grep -Fq fixture-back-password "${acl}"; ! grep -Fq fixture-ai-password "${acl}"
# Approved festa-ai ConversationRepository stores raw Conversation state under
# conversation:<conversationId>. Production prod_ai must retain access to that
# key family without widening prod_back.
grep -Fq '~conversation:*' \
  infra/deploy/scripts/bootstrap-production-data.sh

python3 - <<'PY_ACL_CONTRACT'
from pathlib import Path

text = Path(
    "infra/deploy/scripts/bootstrap-production-data.sh"
).read_text(encoding="utf-8")

prod_ai_lines = [
    line
    for line in text.splitlines()
    if '"user prod_ai on #${ai_hash}' in line
]

prod_back_lines = [
    line
    for line in text.splitlines()
    if '"user prod_back on #${back_hash}' in line
]

if len(prod_ai_lines) != 1:
    raise SystemExit(
        "FAIL: expected exactly one prod_ai ACL definition, "
        f"actual={len(prod_ai_lines)}"
    )

if len(prod_back_lines) != 1:
    raise SystemExit(
        "FAIL: expected exactly one prod_back ACL definition, "
        f"actual={len(prod_back_lines)}"
    )

prod_ai = prod_ai_lines[0]
prod_back = prod_back_lines[0]

if "~prod:ai:*" not in prod_ai:
    raise SystemExit(
        "FAIL: prod_ai missing prod:ai:*"
    )

if "~conversation:*" not in prod_ai:
    raise SystemExit(
        "FAIL: prod_ai missing conversation:*"
    )

if "~prod:*" not in prod_back:
    raise SystemExit(
        "FAIL: prod_back missing prod:*"
    )

if "~conversation:*" in prod_back:
    raise SystemExit(
        "FAIL: prod_back unexpectedly permits conversation:*"
    )

print(
    "PRODUCTION_AI_CONVERSATION_REDIS_ACL=PASS"
)
PY_ACL_CONTRACT

echo 'PASS: Production exact-artifact runtime, WebGL isolation and Redis ACL persistence contracts'
