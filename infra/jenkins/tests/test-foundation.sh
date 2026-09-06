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

for entrypoint in "${repo_root}"/infra/jenkins/scripts/*.sh "${repo_root}"/infra/deploy/scripts/*.sh; do
  [[ -x "${entrypoint}" ]] || fail "Shell entrypoint is not executable: ${entrypoint#"${repo_root}/"}"
done
pass "Shell entrypoint executable policy"

grep -qx 'timestamper:1.30' "${plugins}" || fail "Timestamper plugin is not pinned"
grep -q 'check-agent-capabilities.sh' "${jenkinsfile}" || fail "Agent capability gate is not wired"
grep -q 'jsonschema==' "${agent_dockerfile}" || fail "Agent image does not pin jsonschema"
grep -q 'PYTHON_JSONSCHEMA_VERSION' "${agent_compose}" || fail "Agent Compose omits the jsonschema version pin"
pass "controller plugin and agent capability gate"

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

for name in DEV_BACK_ENV_CREDENTIAL_ID DEV_AI_ENV_CREDENTIAL_ID DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID \
  DEMO_BACK_ENV_CREDENTIAL_ID DEMO_AI_ENV_CREDENTIAL_ID DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID; do
  grep -q "key: ${name}" "${repo_root}/infra/jenkins/casc/security.yaml" || fail "JCasC omits ${name}"
done
grep -q "file(credentialsId: envCredentialId, variable: 'COMPONENT_ENV_FILE')" "${component_pipeline}" \
  || fail "dev component pipeline does not bind runtime env file"
grep -q "string(credentialsId: tokenCredentialId, variable: 'INTERNAL_AI_TO_SPRING_TOKENS')" "${component_pipeline}" \
  || fail "dev component pipeline does not bind shared AI-to-Spring token"
grep -q 'with-credentials.sh CONNECTION_TOKEN_SECRET_FILE -- infra/deploy/scripts/deploy-component.sh' "${repo_root}/infra/jenkins/pipelines/unity.groovy" \
  || fail "dev game pipeline does not require the connection token Secret file reference"
grep -q "file(credentialsId: env.DEMO_BACK_ENV_CREDENTIAL_ID, variable: 'BACK_ENV_FILE')" "${develop_pipeline}" \
  || fail "demo pipeline does not bind backend runtime env file"
grep -q "file(credentialsId: env.DEMO_AI_ENV_CREDENTIAL_ID, variable: 'AI_ENV_FILE')" "${develop_pipeline}" \
  || fail "demo pipeline does not bind AI runtime env file"
grep -q "string(credentialsId: env.DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_AI_TO_SPRING_TOKENS')" "${develop_pipeline}" \
  || fail "demo pipeline does not bind shared AI-to-Spring token"
grep -q 'SPRING_PROFILES_ACTIVE: infra' "${integration_compose}" || fail "demo backend does not use infra profile"
[[ "$(grep -c 'INTERNAL_AI_TO_SPRING_TOKENS:' "${integration_compose}")" -eq 2 ]] \
  || fail "shared AI-to-Spring token must reach exactly AI and backend"
pass "runtime credential binding and least-privilege Compose wiring"

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
  for component in ai back front game; do
    docker compose -f "${repo_root}/infra/deploy/compose/dev/${component}.compose.yaml" config --quiet
  done
  docker compose -f "${repo_root}/infra/deploy/compose/dev/back.compose.yaml" config | grep -q 'FESTA_ENVIRONMENT: dev' || fail "dev backend lacks Redis environment namespace"
  docker compose -f "${repo_root}/infra/deploy/compose/dev/ai.compose.yaml" config | grep -q 'FESTA_ENVIRONMENT: dev' || fail "dev FastAPI lacks environment namespace"
  export AI_IMAGE_REF=festa-ai:test BACK_IMAGE_REF=festa-back:test FRONT_IMAGE_REF=festa-front:test GAME_IMAGE_REF=festa-game:test
  export BACK_BASE_URL=http://back:8080 AI_BASE_URL=http://ai:8000 PUBLIC_API_BASE_URL=http://front.invalid/api
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
