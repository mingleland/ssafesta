#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../../.." && pwd)"
controller_compose="${repo_root}/infra/jenkins/controller/compose.yaml"
agent_compose="${repo_root}/infra/jenkins/agents/compose.yaml"
agent_dockerfile="${repo_root}/infra/jenkins/agents/Dockerfile"
jcasc="${repo_root}/infra/jenkins/casc/jenkins.yaml"
nginx="${repo_root}/infra/jenkins/reverse-proxy/nginx.conf"
fixtures="${repo_root}/infra/tests/contract/fixtures"
validator="${repo_root}/infra/jenkins/scripts/validate-contracts.sh"
plugins="${repo_root}/infra/jenkins/plugins.txt"
jenkinsfile="${repo_root}/Jenkinsfile"
integration_compose="${repo_root}/infra/deploy/compose/integration/compose.yaml"
component_pipeline="${repo_root}/infra/jenkins/pipelines/component.groovy"
develop_pipeline="${repo_root}/infra/jenkins/pipelines/develop.groovy"
develop_job="${repo_root}/infra/jenkins/jobs/gitlab-develop-multibranch.groovy"
webgl_job="${repo_root}/infra/jenkins/jobs/gitlab-webgl-package-deploy.groovy"
webgl_pipeline="${repo_root}/infra/jenkins/pipelines/webgl-package-deploy.groovy"
webgl_deploy="${repo_root}/infra/jenkins/scripts/deploy-webgl-release.sh"
webgl_publish="${repo_root}/infra/jenkins/scripts/publish-webgl-release.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

grep -q '127.0.0.1:8080:8080' "${controller_compose}" || fail "Jenkins 8080 is not loopback-only"
! grep -q '/var/run/docker.sock' "${controller_compose}" || fail "controller mounts Docker socket"
! grep -Eq '(^|[^0-9])(3000|50000):' "${controller_compose}" || fail "controller publishes a forbidden port"
pass "controller port and mount policy"

grep -q 'network_mode: host' "${agent_compose}" || fail "linux Docker agent must use host networking"
grep -q 'http://127.0.0.1:8080' "${agent_compose}" || fail "linux Docker agent must reach Jenkins through loopback"
grep -q 'TESTCONTAINERS_HOST_OVERRIDE: 127.0.0.1' "${agent_compose}" || fail "linux Docker agent lacks Testcontainers loopback override"
pass "rootless Docker Testcontainers network policy"

grep -Fq '${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}:/srv/festa/webgl' "${agent_compose}" \
  || fail "deploy agent cannot mutate the host WebGL release root"
grep -q 'WEBGL_PUBLIC_BASE_URL:' "${agent_compose}" || fail "deploy agent lacks the public WebGL verification URL"
grep -q '^[[:space:]]*curl[[:space:]\\]*$' "${agent_dockerfile}" || fail "agent image omits curl"
grep -q '^[[:space:]]*util-linux[[:space:]\\]*$' "${agent_dockerfile}" || fail "agent image omits flock"
pass "WebGL deploy agent host path and runtime tools"

for entrypoint in "${repo_root}"/infra/jenkins/scripts/*.sh "${repo_root}"/infra/deploy/scripts/*.sh; do
  [[ -x "${entrypoint}" ]] || fail "Shell entrypoint is not executable: ${entrypoint#"${repo_root}/"}"
done
pass "Shell entrypoint executable policy"

grep -qx 'timestamper:1.30' "${plugins}" || fail "Timestamper plugin is not pinned"
grep -q 'check-agent-capabilities.sh' "${jenkinsfile}" || fail "Agent capability gate is not wired"
grep -q 'jsonschema==' "${agent_dockerfile}" || fail "Agent image does not pin jsonschema"
grep -q 'PYTHON_JSONSCHEMA_VERSION' "${agent_compose}" || fail "Agent Compose omits the jsonschema version pin"
for package in libasound2t64 libgl1 libglu1-mesa libgtk-3-0t64 libicu76 libnss3 libxss1 libxtst6; do
  grep -q "^[[:space:]]*${package}[[:space:]\\]*$" "${agent_dockerfile}" || fail "Agent image omits Unity runtime package: ${package}"
done
pass "controller plugin, agent capability gate and Unity runtime packages"

python_bin="${PYTHON_BIN:-}"
if [[ -z "${python_bin}" ]]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
    python_bin=python3
  elif command -v python >/dev/null 2>&1 && python -c 'import sys' >/dev/null 2>&1; then
    python_bin=python
  else
    fail "Python 3 is unavailable"
  fi
fi

"${python_bin}" - "${jcasc}" <<'PY'
import pathlib
import sys
import yaml

document = yaml.safe_load(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
jenkins = document["jenkins"]
assert jenkins["numExecutors"] == 0
assert jenkins["mode"] == "EXCLUSIVE"
assert jenkins["slaveAgentPort"] == -1
nodes = {item["permanent"]["name"]: item["permanent"] for item in jenkins["nodes"]}
assert set(nodes) == {"linux-docker", "deploy", "unity"}
assert len({node["remoteFS"] for node in nodes.values()}) == 3
assert all(node["numExecutors"] == 1 for node in nodes.values())
assert "unity-6000.0.78f1" in nodes["unity"]["labelString"]
PY
pass "JCasC syntax, controller executor and separated agent nodes"

"${python_bin}" - "${repo_root}/infra/jenkins/casc/security.yaml" "${repo_root}/infra/jenkins/casc/authorization.yaml" "${repo_root}/infra/jenkins/casc/gitlab.yaml" <<'PY'
import pathlib,sys,yaml
security=yaml.safe_load(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
authorization=yaml.safe_load(pathlib.Path(sys.argv[2]).read_text(encoding='utf-8'))
gitlab=yaml.safe_load(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8'))
assert 'credentials' not in security, 'JCasC must not overwrite UI-managed credentials'
entries=authorization['jenkins']['authorizationStrategy']['globalMatrix']['entries']
assert any('Credentials/ManageDomains' in item.get('group',{}).get('permissions',[]) for item in entries)
server=gitlab['unclassified']['gitLabServers']['servers'][0]
assert server['manageWebHooks'] is True and server['manageSystemHooks'] is False
assert server['webhookSecretCredentialsId'] == '${GITLAB_WEBHOOK_SECRET_CREDENTIALS_ID}'
assert 'secretToken' not in server
PY
pass "JCasC credential persistence, GitLab migration and least-privilege matrix"

for name in GITLAB_PACKAGE_READ_CREDENTIAL_ID DEV_BACK_ENV_CREDENTIAL_ID DEV_AI_ENV_CREDENTIAL_ID DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID \
  DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID DEMO_BACK_ENV_CREDENTIAL_ID DEMO_AI_ENV_CREDENTIAL_ID \
  DEMO_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID; do
  grep -q "key: ${name}" "${repo_root}/infra/jenkins/casc/security.yaml" || fail "JCasC omits ${name}"
  grep -q "^[[:space:]]*${name}:.*\${${name}" "${controller_compose}" || fail "controller does not receive ${name}"
done
grep -q 'final String sourceSha = config.sourceSha as String' "${component_pipeline}" \
  || fail "component CI does not accept its selected source SHA"
grep -q 'final String artifactDir = config.artifactDir as String' "${component_pipeline}" \
  || fail "component CI does not accept its selected artifact path"
grep -Fq 'final String artifactRoot = "${pwd()}/${artifactDir}"' "${component_pipeline}" \
  || fail "component CI does not root artifacts in the Jenkins workspace"
grep -Fq '"CI_ARTIFACT_DIR=${artifactRoot}"' "${component_pipeline}" \
  || fail "component CI does not pass the rooted artifact directory to adapters"
grep -Fq '"CI_STAGE_SUMMARY_PATH=${artifactRoot}/stage-summaries/${name}.json"' "${component_pipeline}" \
  || fail "component CI does not root stage summaries in the Jenkins workspace"
grep -Fq 'bash "${ci_root}/infra/deploy/scripts/verify-component.sh"' "${repo_root}/ci/verify" \
  || fail "component verification must remain valid after adapter directory dispatch"
grep -q "ws('/home/jenkins/agent/unity/workspaces/develop-game')" "${component_pipeline}" \
  || fail "game component CI does not reuse its Unity workspace"
grep -q 'game) bash festa-unity/ci/build --target linux-server' "${repo_root}/ci/build" \
  || fail "general game CI must build Linux Server only"
grep -q 'ci_dispatch_or build build_project --target linux-server' "${repo_root}/ci/build" \
  || fail "general game CI must pass the Linux Server target to its component adapter"
! grep -q 'game) bash festa-unity/ci/build --target all' "${repo_root}/ci/build" \
  || fail "general game CI must not build WebGL"
! grep -q 'deploy-component.sh' "${component_pipeline}" \
  || fail "Phase 2 component CI must not deploy"
grep -q 'with-credentials.sh CONNECTION_TOKEN_SECRET_FILE -- infra/deploy/scripts/deploy-component.sh' "${repo_root}/infra/jenkins/pipelines/unity.groovy" \
  || fail "dev game pipeline does not require the connection token Secret file reference"
grep -q 'detect-changed-components.sh' "${develop_pipeline}" \
  || fail "develop pipeline does not detect the pushed range"
grep -q "mkdir -p artifacts/develop" "${develop_pipeline}" \
  || fail "develop pipeline does not create its selection artifact directory"
! grep -q 'deploy-release.sh' "${develop_pipeline}" \
  || fail "Phase 2 develop pipeline must not deploy demo"
grep -q 'transfer-local-images.sh --export' "${develop_pipeline}" \
  || fail "develop pipeline does not export selected candidate images"
grep -q 'transfer-local-images.sh --import' "${develop_pipeline}" \
  || fail "deploy node does not verify candidate image receipt"
grep -q 'PUBLIC_API_BASE_URL: \${PUBLIC_API_BASE_URL:-/__dev/api}' "${agent_compose}" \
  || fail "deploy agent does not receive the approved dev API base"
grep -q 'ENVIRONMENT_STATE_DIR: /var/lib/festa-environments' "${agent_compose}" \
  || fail "deploy agent does not persist dev batch state outside its container filesystem"
grep -q 'deploy_state:/var/lib/festa-environments' "${agent_compose}" \
  || fail "deploy agent does not mount persistent dev batch state"
grep -q "final List deployComponents = (selection.deployComponents as List).findAll { it in \['ai', 'back', 'front'\] }" "${develop_pipeline}" \
  || fail "dev batch must use the detector deployComponents contract and keep game Dedicated Server deployment outside it"
grep -q 'withCredentials(credentialBindings)' "${develop_pipeline}" \
  || fail "dev batch does not bind selected component credentials"
grep -q 'gitUsernamePassword(credentialsId: checkoutCredentialId)' "${develop_pipeline}" \
  || fail "deploy freshness check does not bind the GitLab checkout credential"
grep -q 'FRESHNESS_EXPECTED_SHA=' "${develop_pipeline}" \
  || fail "dev batch does not recheck the develop head before deployment"
grep -q 'deploy-dev-batch.sh' "${develop_pipeline}" \
  || fail "candidate transfer does not activate the Phase 3 dev batch"
grep -q "stash name: 'candidate-metadata-game'" "${component_pipeline}" \
  || fail "game candidate metadata cannot leave the Unity workspace"
grep -q "unstash 'candidate-metadata-game'" "${develop_pipeline}" \
  || fail "develop pipeline does not collect game candidate metadata"
grep -q 'image-transfer-init' "${agent_compose}" \
  || fail "shared image transfer volume has no ownership initializer"
grep -q "branch != 'develop'" "${jenkinsfile}" \
  || fail "Jenkinsfile accepts non-develop branches"
! grep -q 'componentBranches' "${jenkinsfile}" \
  || fail "Jenkinsfile retains legacy component branch dispatch"
grep -q "multibranchPipelineJob('festa-gitlab-develop')" "${develop_job}" \
  || fail "GitLab develop-only multibranch job is missing"
grep -q "pipelineJob('festa-webgl-package-deploy')" "${webgl_job}" \
  || fail "GitLab WebGL package deployment job is missing"
grep -q "scriptPath('infra/jenkins/pipelines/webgl-package-deploy.groovy')" "${webgl_job}" \
  || fail "WebGL package job does not use the repository pipeline"
grep -q "usernamePassword(credentialsId: credentialId" "${webgl_pipeline}" \
  || fail "WebGL package pipeline does not bind its Registry deploy token"
grep -q "lock(resource: 'deploy-dev-game')" "${webgl_pipeline}" \
  || fail "WebGL package deployment does not reuse the game deployment lock"
grep -q 'deploy-webgl-release.sh' "${webgl_pipeline}" || fail "WebGL deployment script is not wired"
grep -q 'DEPLOY-TOKEN:' "${webgl_deploy}" || fail "Registry download does not use a Deploy Token"
grep -q 'buildWithParameters' "${webgl_publish}" || fail "publisher does not trigger Jenkins after upload"
! grep -q 'unity-webgl-builder' "${webgl_job}" "${webgl_pipeline}" \
  || fail "obsolete Windows WebGL agent remains wired"
grep -q 'fingerprint: true' "${component_pipeline}" \
  || fail "component CI does not fingerprint selected artifacts"
grep -q 'SPRING_PROFILES_ACTIVE: infra' "${integration_compose}" || fail "demo backend does not use infra profile"
[[ "$(grep -c 'INTERNAL_AI_TO_SPRING_TOKENS:' "${integration_compose}")" -eq 2 ]] \
  || fail "shared AI-to-Spring token must reach exactly AI and backend"
[[ "$(grep -c 'INTERNAL_SPRING_TO_AI_TOKENS:' "${integration_compose}")" -eq 2 ]] \
  || fail "shared Spring-to-AI token must reach exactly AI and backend"
grep -q 'AI_INTERNAL_BASE_URL: http://ai:8000' "${integration_compose}" || fail "demo backend lacks AI service DNS"
grep -q 'SPRING_INTERNAL_BASE_URL: http://back:8080' "${integration_compose}" || fail "demo AI lacks backend service DNS"
pass "runtime credential binding and least-privilege Compose wiring"

stage_summary_dir="$(mktemp -d)"
CI_ARTIFACT_DIR="${stage_summary_dir}" \
CI_STAGE_SUMMARY_PATH="${stage_summary_dir}/stage-summaries/validate.json" \
CI_COMPONENT=ai \
CI_COMMIT_SHA=0123456789abcdef0123456789abcdef01234567 \
CI_STAGE=validate \
CI_STAGE_STATUS=SUCCEEDED \
CI_STARTED_AT=2026-09-09T03:35:42Z \
CI_FINISHED_AT=2026-09-09T03:35:43Z \
"${repo_root}/infra/jenkins/scripts/write-stage-summary.sh" >/dev/null
[[ -f "${stage_summary_dir}/stage-summaries/validate.json" ]] \
  || fail "stage summary does not create its nested output directory"
rm -rf "${stage_summary_dir}"
pass "nested stage summary artifact path"

grep -q 'proxy_pass http://127.0.0.1:8080' "${nginx}" || fail "Nginx does not proxy to loopback Jenkins"
! grep -Eq 'listen[[:space:]]+(8080|3000|50000)' "${nginx}" || fail "Nginx publicly listens on a forbidden port"
pass "reverse proxy public port policy"

"${validator}" release "${fixtures}/release-valid.json"
if "${validator}" release "${fixtures}/release-invalid.json" >/dev/null 2>&1; then fail "invalid release fixture passed"; fi
"${validator}" verification "${fixtures}/verification-valid.json"
if "${validator}" verification "${fixtures}/verification-invalid.json" >/dev/null 2>&1; then fail "invalid verification fixture passed"; fi
"${validator}" detection-rule "${fixtures}/detection-rule-valid.json"
if "${validator}" detection-rule "${fixtures}/detection-rule-invalid.json" >/dev/null 2>&1; then fail "invalid detection rule fixture passed"; fi
pass "contract schema fixtures"

export JENKINS_IMAGE="jenkins/jenkins:foundation-test"
export JENKINS_INBOUND_AGENT_IMAGE="jenkins/inbound-agent:foundation-test-jdk21"
export DOCKER_CLI_IMAGE="docker:foundation-test-cli"
export NODE_RUNTIME_IMAGE="node:foundation-test"
export PYTHON_JSONSCHEMA_VERSION="4.26.0"
export JENKINS_ADMIN_ID="foundation-admin"
export JENKINS_ADMIN_PASSWORD="foundation-only-value"
export JENKINS_PUBLIC_URL="https://ci.example.invalid/"
export JENKINS_AGENT_SECRET_LINUX_DOCKER="foundation-linux-agent-value"
export JENKINS_AGENT_SECRET_DEPLOY="foundation-deploy-agent-value"
export JENKINS_AGENT_SECRET_UNITY="foundation-unity-agent-value"
export ROOT_DOMAIN="example.invalid"
export DEV_BACK_ENV_CREDENTIAL_ID="foundation-dev-back-env"
export DEV_AI_ENV_CREDENTIAL_ID="foundation-dev-ai-env"
export DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID="foundation-dev-spring-to-ai"
export DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID="foundation-dev-ai-to-spring"
export DEMO_BACK_ENV_CREDENTIAL_ID="foundation-demo-back-env"
export DEMO_AI_ENV_CREDENTIAL_ID="foundation-demo-ai-env"
export DEMO_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID="foundation-demo-spring-to-ai"
export DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID="foundation-demo-ai-to-spring"

if command -v docker >/dev/null 2>&1; then
  runtime_env_dir="$(mktemp -d)"
  trap 'rm -rf "${runtime_env_dir}"' EXIT
  : >"${runtime_env_dir}/component.env"
  : >"${runtime_env_dir}/back.env"
  : >"${runtime_env_dir}/ai.env"
  docker compose -f "${controller_compose}" config --quiet
  docker compose --profile linux-docker --profile deploy --profile unity -f "${agent_compose}" config --quiet
  export COMPONENT_IMAGE_REF="festa-test:0123456789abcdef0123456789abcdef01234567"
  export COMPONENT_ENV_FILE="${runtime_env_dir}/component.env"
  export CONNECTION_TOKEN_SECRET_FILE="${runtime_env_dir}/connection-token-secret"
  printf '%s\n' 'Zm91bmRhdGlvbi1vbmx5LWNvbm5lY3Rpb24tdG9rZW4tc2VjcmV0' >"${CONNECTION_TOKEN_SECRET_FILE}"
  export INTERNAL_AI_TO_SPRING_TOKENS=foundation-ai-to-spring-token
  export INTERNAL_SPRING_TO_AI_TOKENS=foundation-spring-to-ai-token
  for component in ai back front game; do
    docker compose -f "${repo_root}/infra/deploy/compose/dev/${component}.compose.yaml" config --quiet
  done
  docker compose -f "${repo_root}/infra/deploy/compose/dev/back.compose.yaml" config | grep -q 'FESTA_ENVIRONMENT: dev' || fail "dev backend lacks Redis environment namespace"
  docker compose -f "${repo_root}/infra/deploy/compose/dev/ai.compose.yaml" config | grep -q 'FESTA_ENVIRONMENT: dev' || fail "dev FastAPI lacks environment namespace"
  export AI_IMAGE_REF=festa-ai:test BACK_IMAGE_REF=festa-back:test FRONT_IMAGE_REF=festa-front:test GAME_IMAGE_REF=festa-game:test
  export PUBLIC_API_BASE_URL=http://front.invalid PUBLIC_UNITY_BUILD_BASE=/unity/
  export BACK_ENV_FILE="${runtime_env_dir}/back.env" AI_ENV_FILE="${runtime_env_dir}/ai.env" FESTA_ENVIRONMENT=demo
  docker compose -f "${integration_compose}" config | grep -c 'FESTA_ENVIRONMENT: demo' | grep -qx '4' || fail "demo Compose lacks four environment namespaces"
  if (unset BACK_ENV_FILE; docker compose -f "${integration_compose}" config --quiet >/dev/null 2>&1); then
    fail "demo Compose accepted missing backend credential file"
  fi
  export GRAFANA_ADMIN_USER=foundation GRAFANA_ADMIN_PASSWORD=foundation-only-value
  export LOKI_RETENTION_PERIOD=24h PROMETHEUS_RETENTION_TIME=24h PROMETHEUS_RETENTION_SIZE=1GB
  export ALLOY_CPU_LIMIT=0.5 ALLOY_MEMORY_LIMIT=256M LOKI_CPU_LIMIT=0.5 LOKI_MEMORY_LIMIT=256M
  export PROMETHEUS_CPU_LIMIT=0.5 PROMETHEUS_MEMORY_LIMIT=256M GRAFANA_CPU_LIMIT=0.5 GRAFANA_MEMORY_LIMIT=256M
  export ALLOY_CADVISOR_CPU_LIMIT=0.5 ALLOY_CADVISOR_MEMORY_LIMIT=256M
  docker compose -f "${repo_root}/infra/observability/compose.yaml" config --quiet
  pass "Compose rendering"
else
  echo "SKIP: Docker CLI is unavailable; run Compose rendering on the EC2 gate."
fi

if [[ "${FOUNDATION_START_JENKINS:-0}" == "1" ]]; then
  cleanup() { docker compose -f "${controller_compose}" down --remove-orphans; }
  trap cleanup EXIT
  docker compose -f "${controller_compose}" up -d --build --wait
  docker compose -f "${controller_compose}" exec -T jenkins-controller test -f /var/jenkins_home/casc_configs/jenkins.yaml
  docker compose -f "${controller_compose}" logs jenkins-controller | grep -q 'Jenkins is fully up and running' \
    || fail "Jenkins/JCasC integration startup failed"
  pass "Jenkins JCasC integration startup"
fi

echo "Foundation checks completed."
