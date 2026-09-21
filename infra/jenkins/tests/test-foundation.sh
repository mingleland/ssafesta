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
unity_mr_job="${repo_root}/infra/jenkins/jobs/gitlab-unity-mr-validation.groovy"
unity_mr_pipeline="${repo_root}/infra/jenkins/pipelines/unity-mr-validation.groovy"
webgl_job="${repo_root}/infra/jenkins/jobs/gitlab-webgl-package-deploy.groovy"
webgl_pipeline="${repo_root}/infra/jenkins/pipelines/webgl-package-deploy.groovy"
webgl_deploy="${repo_root}/infra/jenkins/scripts/deploy-webgl-release.sh"
webgl_publish="${repo_root}/infra/jenkins/scripts/publish-webgl-release.sh"
jobs_casc="${repo_root}/infra/jenkins/casc/jobs.yaml"
demo_promotion_job="${repo_root}/infra/jenkins/jobs/gitlab-demo-promotion.groovy"
demo_promotion_pipeline="${repo_root}/infra/jenkins/pipelines/demo-promotion.groovy"
demo_promotion_validator="${repo_root}/infra/jenkins/scripts/validate-demo-promotion.sh"
active_dev_release_builder="${repo_root}/infra/jenkins/scripts/build-active-dev-release.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

grep -q '127.0.0.1:8080:8080' "${controller_compose}" || fail "Jenkins 8080 is not loopback-only"
! grep -q '/var/run/docker.sock' "${controller_compose}" || fail "controller mounts Docker socket"
! grep -Eq '(^|[^0-9])(3000|50000):' "${controller_compose}" || fail "controller publishes a forbidden port"
pass "controller port and mount policy"

grep -q 'network_mode: host' "${agent_compose}" || fail "linux Docker agent must use host networking"
grep -q 'http://127.0.0.1:8080' "${agent_compose}" || fail "linux Docker agent must reach Jenkins through loopback"
grep -q 'TESTCONTAINERS_HOST_OVERRIDE: 127.0.0.1' "${agent_compose}" || fail "linux Docker agent lacks Testcontainers loopback override"
# rootless Docker 에서 Ryuk 가 마운트할 host 소켓 경로 — 없으면 back: Test 가 Ryuk 기동 실패로 죽는다 (build #544).
grep -q 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: ${DOCKER_SOCKET_PATH:-/run/user/1000/docker.sock}' "${agent_compose}" \
  || fail "linux Docker agent lacks Testcontainers rootless socket override for Ryuk"
pass "rootless Docker Testcontainers network policy"

grep -Fq '${WEBGL_RELEASE_ROOT:-/srv/festa/webgl}:/srv/festa/webgl' "${agent_compose}" \
  || fail "deploy agent cannot mutate the host WebGL release root"
grep -Fq '../jobs:/var/jenkins_home/job_dsl:ro' "${controller_compose}" \
  || fail "controller does not mount repository Job DSL definitions"
for job_dsl in gitlab-develop-multibranch.groovy gitlab-unity-mr-validation.groovy gitlab-webgl-package-deploy.groovy gitlab-demo-promotion.groovy; do
  grep -Fq "/var/jenkins_home/job_dsl/${job_dsl}" "${jobs_casc}" \
    || fail "JCasC does not apply ${job_dsl}"
done
grep -q 'WEBGL_PUBLIC_BASE_URL:' "${agent_compose}" || fail "deploy agent lacks the public WebGL verification URL"
grep -q '^[[:space:]]*curl[[:space:]\\]*$' "${agent_dockerfile}" || fail "agent image omits curl"
grep -q '^[[:space:]]*util-linux[[:space:]\\]*$' "${agent_dockerfile}" || fail "agent image omits flock"
# public-wss.sh 등 unity-server 테스트가 envsubst 로 nginx 템플릿을 렌더한다 — 이미지에 없으면 CI Static 이 127 로 죽는다 (build #543).
grep -q '^[[:space:]]*gettext-base[[:space:]\\]*$' "${agent_dockerfile}" || fail "agent image omits envsubst (gettext-base)"
pass "WebGL deploy agent host path and runtime tools"

for entrypoint in "${repo_root}"/infra/jenkins/scripts/*.sh "${repo_root}"/infra/deploy/scripts/*.sh; do
  [[ -x "${entrypoint}" ]] || fail "Shell entrypoint is not executable: ${entrypoint#"${repo_root}/"}"
done
pass "Shell entrypoint executable policy"

grep -qx 'timestamper:1.30' "${plugins}" || fail "Timestamper plugin is not pinned"
grep -q 'check-agent-capabilities.sh' "${jenkinsfile}" || fail "Agent capability gate is not wired"
grep -q 'jsonschema==' "${agent_dockerfile}" || fail "Agent image does not pin jsonschema"
grep -q 'PYTHON_JSONSCHEMA_VERSION' "${agent_compose}" || fail "Agent Compose omits the jsonschema version pin"
grep -q 'PYTHON_PYYAML_VERSION' "${agent_compose}" || fail "Agent Compose omits the PyYAML version pin"
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
users = {item["id"]: item for item in jenkins["securityRealm"]["local"]["users"]}
assert "webgl-publisher" in users
assert users["webgl-publisher"]["password"] == "${JENKINS_WEBGL_PUBLISHER_PASSWORD}"
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
entries=authorization['jenkins']['authorizationStrategy']['projectMatrix']['entries']
assert any('Credentials/ManageDomains' in item.get('group',{}).get('permissions',[]) for item in entries)
publisher=next(item['user'] for item in entries if item.get('user',{}).get('name') == 'webgl-publisher')
assert set(publisher['permissions']) == {'Overall/Read','Job/Discover','Job/Read','Job/Build'}
server=gitlab['unclassified']['gitLabServers']['servers'][0]
assert server['manageWebHooks'] is True and server['manageSystemHooks'] is False
assert server['webhookSecretCredentialsId'] == '${GITLAB_WEBHOOK_SECRET_CREDENTIALS_ID}'
assert 'secretToken' not in server
PY
pass "JCasC credential persistence, GitLab migration and least-privilege matrix"

grep -q 'JENKINS_WEBGL_PUBLISHER_PASSWORD: \${JENKINS_WEBGL_PUBLISHER_PASSWORD:?set in infra/.env}' "${controller_compose}" \
  || fail "controller does not receive the WebGL publisher password"
pass "WebGL publisher service account configuration"

for name in GITLAB_PACKAGE_READ_CREDENTIAL_ID DEV_BACK_ENV_CREDENTIAL_ID DEV_AI_ENV_CREDENTIAL_ID DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID \
  DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID DEV_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID DEMO_BACK_ENV_CREDENTIAL_ID DEMO_AI_ENV_CREDENTIAL_ID \
  DEMO_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID \
  DEMO_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID; do
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
# Batch 2 Consumer-only: Jenkins 는 Unity Editor 를 돌리지 않는다. game 산출물은 Unity Release Bundle 에서만 온다.
! grep -q "node('unity-6000.0.78f1')" "${component_pipeline}" \
  || fail "component CI must not run on the Unity agent (Jenkins does not build Unity)"
grep -q "Jenkins does not build Unity" "${component_pipeline}" \
  || fail "component CI must refuse game build/package stages"
! grep -q -E 'preflight-license|hostname: festa-unity-agent|mac_address' "${agent_compose}" "${repo_root}/ci/validate" "${repo_root}/festa-unity/ci/build" \
  || fail "Unity license handling must not live in CI infrastructure"
for script in intake-unity-release-bundle.sh resolve-game-artifacts.sh check-game-source-identity.sh validate-game-release-set.sh; do
  [[ -x "${repo_root}/infra/jenkins/scripts/${script}" ]] || fail "Unity consumer script missing: ${script}"
done
# 로컬 producer 어댑터(Unity 담당자 PC)는 한 checkout 에서 WebGL → Linux Server 를 두 세션으로 만든다.
grep -q 'bash festa-unity/ci/build --target webgl' "${repo_root}/ci/build" && grep -q 'bash festa-unity/ci/build --target linux-server' "${repo_root}/ci/build" \
  || fail "local game producer adapter must build WebGL and Linux Server"
for line in 'festa-unity/**/*.fbx filter=lfs' 'festa-unity/**/*.tga filter=lfs' 'docs/LJH/skills/** text eol=lf'; do
  grep -Fq "${line}" "${repo_root}/.gitattributes" || fail ".gitattributes lost rule: ${line}"
done
! grep -q 'deploy-component.sh' "${component_pipeline}" \
  || fail "Phase 2 component CI must not deploy"
grep -q 'with-credentials.sh CONNECTION_TOKEN_SECRET_FILE -- infra/deploy/scripts/deploy-component.sh' "${repo_root}/infra/jenkins/pipelines/unity.groovy" \
  || fail "dev game pipeline does not require the connection token Secret file reference"
grep -q 'detect-changed-components.sh' "${develop_pipeline}" \
  || fail "develop pipeline does not detect the pushed range"
grep -q "script: 'git rev-parse HEAD'" "${develop_pipeline}" \
  || fail "develop pipeline does not derive its head SHA from the checkout"
! grep -q 'final String headSha = env.GIT_COMMIT' "${develop_pipeline}" \
  || fail "develop pipeline relies on a restart-volatile GIT_COMMIT value"
grep -q "mkdir -p artifacts/develop" "${develop_pipeline}" \
  || fail "develop pipeline does not create its selection artifact directory"
! grep -q 'deploy-release.sh' "${develop_pipeline}" \
  || fail "Phase 2 develop pipeline must not deploy demo"
grep -q 'transfer-local-images.sh --export' "${develop_pipeline}" \
  || fail "develop pipeline does not export selected candidate images"
grep -q 'transfer-local-images.sh --import' "${develop_pipeline}" \
  || fail "deploy node does not verify candidate image receipt"
grep -q 'PUBLIC_API_BASE_URL: \${PUBLIC_API_BASE_URL:-}' "${agent_compose}" \
  || fail "deploy agent does not preserve the same-origin dev API fallback"
grep -q 'PUBLIC_AI_API_BASE_URL: \${PUBLIC_AI_API_BASE_URL:-}' "${agent_compose}" \
  || fail "deploy agent does not preserve the same-origin dev AI fallback"
grep -Fq 'PUBLIC_AUTH_BASE_URL: ${PUBLIC_AUTH_BASE_URL:-https://api.${ROOT_DOMAIN:?set in infra/.env}}' "${agent_compose}" \
  || fail "deploy agent does not preserve the public OAuth origin"
grep -q 'ENVIRONMENT_STATE_DIR: /var/lib/festa-environments' "${agent_compose}" \
  || fail "deploy agent does not persist dev batch state outside its container filesystem"
grep -q 'deploy_state:/var/lib/festa-environments' "${agent_compose}" \
  || fail "deploy agent does not mount persistent dev batch state"
grep -q "final List deployComponents = selectedDeploy.findAll { it in \['ai', 'back', 'front'\] }" "${develop_pipeline}" \
  || fail "dev batch must use the detector deployComponents contract and keep game Dedicated Server deployment outside it"
grep -q "final List buildComponents = (selection.buildComponents" "${develop_pipeline}" \
  || fail "develop pipeline must build only the detector buildComponents"
grep -q "final boolean hasGame = buildComponents.contains('game')" "${develop_pipeline}" \
  || fail "Unity build must be gated by buildComponents (gameBuildRequired), not by validation scope"
grep -q "component == 'game' ? \['validate'\] : \['validate', 'test'\]" "${develop_pipeline}" \
  || fail "validation-only components must run validate/test without image or Unity builds"
grep -q "component == 'game' && stages.any { it in \['build', 'package'\] }" "${component_pipeline}" \
  || fail "game component CI must reject build/package stages (Consumer-only)"
grep -q 'withCredentials(credentialBindings)' "${develop_pipeline}" \
  || fail "dev batch does not bind selected component credentials"
grep -q "credentialsId: env.DEMO_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_INFRA_TO_SPRING_TOKENS'" "${develop_pipeline}" \
  || fail "dev backend does not bind the Infra-to-Spring token"
grep -q 'gitUsernamePassword(credentialsId: checkoutCredentialId)' "${develop_pipeline}" \
  || fail "deploy freshness check does not bind the GitLab checkout credential"
grep -q 'FRESHNESS_EXPECTED_SHA=' "${develop_pipeline}" \
  || fail "dev batch does not recheck the develop head before deployment"
# Git LFS smudge 건너뛰기의 정본은 deploy agent compose env 하나다 (!1285). 파이프라인 withEnv 는
# 선언형 암묵 checkout 에 닿지 않는 중복이라 두지 않는다 (#259, build #535).
if grep -q 'GIT_LFS_SKIP_SMUDGE' "${develop_pipeline}"; then
  fail "develop pipeline must not carry its own Git LFS smudge skip — the deploy agent env is the single source"
fi
[ "$(grep -c 'checkout scm' "${develop_pipeline}")" = 1 ] \
  || fail "deploy checkout must route through the single deployCheckout helper"
# catch-up: 실패한 push 의 변경을 다시 판정하도록 base 를 덮어쓰는 파라미터 (#259, build #536·#538)
grep -q "string(name: 'CHANGE_BASE_SHA'" "${repo_root}/Jenkinsfile" \
  || fail "Jenkinsfile must declare CHANGE_BASE_SHA for deploy catch-up"
grep -q 'params.CHANGE_BASE_SHA' "${develop_pipeline}" \
  || fail "develop pipeline must honour CHANGE_BASE_SHA as the detector base"
grep -q "baseOverride ==~ /^\[0-9a-f\]{40}\$/" "${develop_pipeline}" \
  || fail "CHANGE_BASE_SHA must be validated as a full lowercase SHA"
# 선언형 파이프라인(unity-bundle-e2e 등)의 암묵적 checkout 은 withEnv 로 감쌀 수 없다. deploy agent 범위로 건다.
grep -q "GIT_LFS_SKIP_SMUDGE: '1'" "${agent_compose}" \
  || fail "deploy agent does not skip Git LFS smudge for implicit declarative checkouts"
[ "$(grep -c 'GIT_LFS_SKIP_SMUDGE' "${agent_compose}")" = 1 ] \
  || fail "Git LFS smudge skip must stay scoped to the deploy agent"
grep -q 'deploy-dev-batch.sh' "${develop_pipeline}" \
  || fail "candidate transfer does not activate the Phase 3 dev batch"
# game candidate identity 는 Unity workspace 가 아니라 Unity Release Bundle 의 image-metadata.json 에서 온다 (Batch 2 Consumer-only).
grep -q 'intake-unity-release-bundle.sh' "${develop_pipeline}" \
  || fail "develop pipeline does not take game artifacts from the Unity Release Bundle"
grep -q 'WAITING_FOR_UNITY_ARTIFACT' "${develop_pipeline}" \
  || fail "develop pipeline must wait (not build) when no game artifact exists"
# 자동 탐색이 번들을 못 찾을 때 운영자가 source commit 을 지정하는 경로. 읽기만 하고 선언이 없으면 항상 빈 값이다 (#259).
grep -q "params.UNITY_ARTIFACT_CANDIDATE" "${develop_pipeline}" \
  || fail "develop pipeline does not accept an explicit Unity artifact candidate"
grep -q "string(name: 'UNITY_ARTIFACT_CANDIDATE'" "${jenkinsfile}" \
  || fail "Jenkinsfile does not declare the Unity artifact candidate parameter"
# 배포를 유발한 push 가 실패하고 develop 이 그 앞으로 지나가면 game 배포 트리거가 사라진다 (#259).
grep -q "booleanParam(name: 'DEPLOY_GAME_TO_DEMO'" "${jenkinsfile}" \
  || fail "Jenkinsfile does not declare the explicit game deploy parameter"
grep -q 'final boolean forceGameDeploy' "${develop_pipeline}" \
  || fail "develop pipeline cannot open the game path without a fresh game diff"
grep -q "final boolean deployGame = selectedDeploy.contains('game') || forceGameDeploy" "${develop_pipeline}" \
  || fail "explicit game deploy must go through the same deployGame gate"
grep -q 'image-transfer-init' "${agent_compose}" \
  || fail "shared image transfer volume has no ownership initializer"
grep -q "branch != 'develop'" "${jenkinsfile}" \
  || fail "Jenkinsfile accepts non-develop branches"
! grep -q 'componentBranches' "${jenkinsfile}" \
  || fail "Jenkinsfile retains legacy component branch dispatch"
grep -q "multibranchPipelineJob('festa-gitlab-develop')" "${develop_job}" \
  || fail "GitLab develop-only multibranch job is missing"
# T-168: strategy 1 ("MR source 브랜치 제외") 는 develop→main MR 이 열리는 순간 develop child 를 Dead 로 만든다.
grep -q 'gitLabBranchDiscovery { strategyId(3) }' "${develop_job}" \
  || fail "GitLab develop discovery must use strategy 3 (all branches) — T-168"
! grep -q 'strategyId(1)' "${develop_job}" \
  || fail "GitLab develop discovery must not exclude MR source branches — T-168"
grep -q "headWildcardFilter { includes('develop')" "${develop_job}" \
  || fail "GitLab develop discovery must still be restricted to develop"
grep -q 'serverName(gitlabServerName)' "${develop_job}" \
  || fail "GitLab develop Job DSL shadows the serverName method"
grep -q "String gitlabApiCredentialsId = System.getenv('GITLAB_API_CREDENTIALS_ID')" "${develop_job}" \
  || fail "GitLab develop Job DSL must read the API credential"
grep -q 'credentialsId(gitlabApiCredentialsId)' "${develop_job}" \
  || fail "GitLab develop Job DSL must use the API credential for project discovery"
grep -q 'projectOwner(gitlabProjectOwner)' "${develop_job}" \
  || fail "GitLab develop Job DSL shadows the projectOwner method"
grep -q 'String gitlabProjectFullPath = "${gitlabProjectOwner}/${gitlabProjectPath}"' "${develop_job}" \
  || fail "GitLab develop Job DSL must compose the full GitLab project path"
grep -q 'projectPath(gitlabProjectFullPath)' "${develop_job}" \
  || fail "GitLab develop Job DSL must pass the full GitLab project path"
grep -Fq 'gitlabAvatar { disableProjectAvatar(true) }' "${develop_job}" \
  || fail "GitLab develop Job DSL must disable private project avatar retrieval"
! grep -q '^String projectOwner[[:space:]]*=' "${develop_job}" \
  || fail "GitLab develop Job DSL declares a projectOwner variable that shadows the method"
! grep -q '^String projectPath[[:space:]]*=' "${develop_job}" \
  || fail "GitLab develop Job DSL declares a projectPath variable that shadows the method"
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
grep -q "pipelineJob('festa-unity-mr-validation')" "${unity_mr_job}" \
  || fail "Unity MR validation job is missing"
grep -q "permission('hudson.model.Item.Build', 'unity-mr-validator')" "${unity_mr_job}" \
  || fail "Unity MR validator cannot build its own job"
grep -q "node('unity-6000.0.78f1')" "${unity_mr_pipeline}" \
  || fail "Unity MR validation does not use Unity agent"

"${python_bin}" - "${repo_root}/.gitlab-ci.yml" <<'PY_GAME_RULE'
import pathlib
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
start = text.index(".game-changes:")
end = text.index("unity-mr-validation-dispatch:", start)
block = text[start:end]

assert "festa-unity/**/*" in block
assert "ci/test" in block
assert "ci/lib.sh" in block
assert "infra/unity-server/" not in block
PY_GAME_RULE

grep -q '"gameBuildRequired": game_build_required'   "${repo_root}/infra/jenkins/scripts/detect-changed-components.sh"   || fail "component detector omits the explicit Unity game-build decision"

grep -q 'infra/unity-server/tests/'   "${repo_root}/infra/jenkins/scripts/detect-changed-components.sh"   || fail "Unity Server tests are not classified as validation-only"

grep -q 'selection.gameBuildRequired' "${develop_pipeline}"   || fail "develop pipeline ignores the detector Unity build decision"

grep -q "detector selected a game build without gameBuildRequired" "${develop_pipeline}"   || fail "develop pipeline must refuse a Unity build that the detector did not require"
grep -q "gitlabCommitStatus(" "${unity_mr_pipeline}" \
  || fail "Unity MR validation does not publish the required GitLab status context"
grep -Fq "connection: gitLabConnection(gitlabConnectionName)" "${unity_mr_pipeline}" \
  || fail "Unity MR validation does not bind the configured GitLab connection"
grep -Fq "builds: [[projectId: gitlabProjectId, revisionHash: sourceSha]]" "${unity_mr_pipeline}" \
  || fail "Unity MR validation does not bind the exact MR commit to its GitLab status"
grep -q 'with-credentials.sh -- bash ci/test' "${unity_mr_pipeline}" \
  || fail "Unity MR validation does not use the credential-masking command wrapper"
grep -q '^set +x$' "${repo_root}/infra/jenkins/scripts/with-credentials.sh" \
  || fail "credential wrapper does not disable shell command echoing"
for forbidden in 'ci/build' 'ci/package' 'docker compose' 'deploy-component.sh' 'deploy-dev-batch.sh' 'deploy-release.sh' 'promote-release.sh'; do
  ! grep -Fq "${forbidden}" "${unity_mr_pipeline}" \
    || fail "Unity MR validation contains forbidden ${forbidden}"
done
grep -q "pipelineJob('festa-demo-promotion')" "${demo_promotion_job}" \
  || fail "manual demo promotion job is missing"
grep -q "scriptPath('infra/jenkins/pipelines/demo-promotion.groovy')" "${demo_promotion_job}" \
  || fail "demo promotion job does not use the repository pipeline"
grep -q 'validate-demo-promotion.sh' "${demo_promotion_pipeline}" \
  || fail "demo promotion does not validate its dev release"
grep -q 'deploy-release.sh' "${demo_promotion_pipeline}" \
  || fail "demo promotion does not reuse demo deployment"
grep -q 'verify-release.sh' "${demo_promotion_pipeline}" \
  || fail "demo promotion does not reuse integration verification"
grep -q 'decide-recovery.sh' "${demo_promotion_pipeline}" \
  || fail "demo promotion does not apply recovery policy"
grep -q 'rollback-release.sh' "${demo_promotion_pipeline}" \
  || fail "demo promotion does not reuse rollback"
grep -q 'promote-release.sh' "${demo_promotion_pipeline}" \
  || fail "demo promotion does not atomically promote a verified release"
grep -q 'DEMO_VERIFY_WEB_COMMAND:' "${agent_compose}" \
  || fail "deploy agent lacks demo verification commands"
[[ -x "${demo_promotion_validator}" ]] || fail "demo promotion validator is not executable"
[[ -x "${active_dev_release_builder}" ]] || fail "active dev release builder is not executable"
grep -q 'fingerprint: true' "${component_pipeline}" \
  || fail "component CI does not fingerprint selected artifacts"
grep -q 'SPRING_PROFILES_ACTIVE: infra' "${integration_compose}" || fail "demo backend does not use infra profile"
[[ "$(grep -c 'INTERNAL_AI_TO_SPRING_TOKENS:' "${integration_compose}")" -eq 2 ]] \
  || fail "shared AI-to-Spring token must reach exactly AI and backend"
[[ "$(grep -c 'INTERNAL_SPRING_TO_AI_TOKENS:' "${integration_compose}")" -eq 2 ]] \
  || fail "shared Spring-to-AI token must reach exactly AI and backend"
grep -q 'AI_INTERNAL_BASE_URL: http://ai:8000' "${integration_compose}" || fail "demo backend lacks AI service DNS"
grep -q 'SPRING_INTERNAL_BASE_URL: http://back:8080' "${integration_compose}" || fail "demo AI lacks backend service DNS"
grep -Fq '127.0.0.1:${AI_LOOPBACK_PORT:-18082}:8000' "${integration_compose}" \
  || fail "demo AI lacks the loopback ingress the same-origin /ai/v1 route proxies to"
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
export PYTHON_PYYAML_VERSION="6.0.2"
export JENKINS_ADMIN_ID="foundation-admin"
export JENKINS_ADMIN_PASSWORD="foundation-only-value"
export JENKINS_UNITY_MR_VALIDATOR_PASSWORD="foundation-unity-mr-validator-value"
export JENKINS_UNITY_MR_PIPELINE_BRANCH=develop
export JENKINS_WEBGL_PUBLISHER_PASSWORD="foundation-webgl-publisher-value"
export JENKINS_PUBLIC_URL="https://ci.example.invalid/"
export JENKINS_AGENT_SECRET_LINUX_DOCKER="foundation-linux-agent-value"
export JENKINS_AGENT_SECRET_DEPLOY="foundation-deploy-agent-value"
export JENKINS_AGENT_SECRET_UNITY="foundation-unity-agent-value"
export ROOT_DOMAIN="example.invalid"
export DEV_BACK_ENV_CREDENTIAL_ID="foundation-dev-back-env"
export DEV_AI_ENV_CREDENTIAL_ID="foundation-dev-ai-env"
export DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID="foundation-dev-spring-to-ai"
export DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID="foundation-dev-ai-to-spring"
export DEV_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID="foundation-dev-infra-to-spring"
export DEMO_BACK_ENV_CREDENTIAL_ID="foundation-demo-back-env"
export DEMO_AI_ENV_CREDENTIAL_ID="foundation-demo-ai-env"
export DEMO_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID="foundation-demo-spring-to-ai"
export DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID="foundation-demo-ai-to-spring"
export DEMO_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID="foundation-demo-infra-to-spring"

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
  export INTERNAL_INFRA_TO_SPRING_TOKENS=foundation-infra-to-spring-token
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
