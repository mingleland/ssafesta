def call() {
    // SCM environment variables may be absent after a Controller restart;
    // the checked-out commit remains the authoritative build revision.
    final String headSha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    if (!(headSha ==~ /^[0-9a-f]{40}$/)) { error('GIT_COMMIT must be a full lowercase SHA') }

    String range = "--branch develop --head '${headSha}'"
    final String baseSha = env.GIT_BEFORE_SHA ?: env.GIT_PREVIOUS_COMMIT ?: env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: ''
    if (baseSha) { range += " --base '${baseSha}'" }

    final String selectionText = sh(
        returnStdout: true,
        script: "infra/jenkins/scripts/detect-changed-components.sh ${range}"
    ).trim()
    final Map selection = readJSON text: selectionText, returnPojo: true
    sh 'mkdir -p artifacts/develop'
    writeFile file: 'artifacts/develop/selection.json', text: "${selectionText}\n"
    archiveArtifacts artifacts: 'artifacts/develop/selection.json', allowEmptyArchive: false, fingerprint: true

    final List components = selection.components as List
    if (components.isEmpty()) {
        echo "NO_OP: ${selection.reasons.join(', ')}"
        return
    }

    final String componentList = components.join(',')
    final String artifactRoot = 'artifacts/develop'
    final String metadataDir = "${artifactRoot}/release-metadata"
    final String releaseManifest = "${artifactRoot}/release-manifest.json"
    final String transferBundle = "develop-${headSha}-${env.BUILD_NUMBER}"
    final String transferDir = '/var/lib/festa-image-transfer'
    // `components` may be all components for a shared CI change. Only the
    // detector's deployComponents contract is allowed to mutate dev.
    final List deployComponents = (selection.deployComponents as List).findAll { it in ['ai', 'back', 'front'] }
    final String deployComponentList = deployComponents.join(',')

    echo "SELECTED_COMPONENTS: ${components.join(', ')}; dev batch targets: ${deployComponentList ?: 'none'}"
    components.each { component ->
        load('infra/jenkins/pipelines/component.groovy').call([
            component: component,
            sourceSha: selection.headSha,
            artifactDir: "artifacts/develop/build/${component}"
        ])
    }
    if (components.contains('game')) {
        stage('Collect Game Candidate Metadata') {
            unstash 'candidate-metadata-game'
        }
    }

    stage('Candidate Manifest') {
        sh """
            mkdir -p '${metadataDir}'
            rm -f '${metadataDir}'/*.json
            for component in ${componentList.replace(',', ' ')}; do
                cp '${artifactRoot}/build/'\${component}'/image-metadata.json' '${metadataDir}/'\${component}'.json'
            done
        """
        withEnv([
            "DEPLOY_COMPONENTS=${componentList}",
            "COMPONENT_METADATA_DIR=${metadataDir}",
            "RELEASE_MANIFEST_PATH=${releaseManifest}",
            "RELEASE_ID=develop-${headSha}-${env.BUILD_NUMBER}",
            'SCM_PROVIDER=gitlab',
            'SCM_REPOSITORY=s15-metaverse-game-sub1/S15P21A604',
            'SCM_BRANCH=develop',
            "CI_COMMIT_SHA=${headSha}",
            "JENKINS_JOB=${env.JOB_NAME}",
            "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}",
            "JENKINS_BUILD_URL=${env.BUILD_URL}"
        ]) {
            sh 'infra/deploy/scripts/build-release-manifest.sh'
            sh "infra/jenkins/scripts/transfer-local-images.sh --export --bundle '${transferBundle}' --transfer-dir '${transferDir}' --manifest '${releaseManifest}'"
            stash name: 'candidate-release-manifest', includes: releaseManifest, useDefaultExcludes: false
        }
    }

    stage('Deploy Candidate Receipt') {
        node('deploy') {
            ws('/home/jenkins/agent/deploy/workspaces/develop-candidate-receipt') {
                checkout scm
                sh "git checkout --detach '${headSha}'"
                unstash 'candidate-release-manifest'
                sh "infra/jenkins/scripts/transfer-local-images.sh --import --bundle '${transferBundle}' --transfer-dir '${transferDir}'"
            }
        }
    }

    if (deployComponents.isEmpty() && !components.contains('game')) {
        echo 'NO_OP: game deployment remains on the Phase 3 WebGL path; Dedicated Server deployment is infra-003'
        return
    }

    if (!deployComponents.isEmpty()) {
        stage('Deploy Selected Components') {
        node('deploy') {
            ws('/home/jenkins/agent/deploy/workspaces/develop-dev-batch') {
                checkout scm
                sh "git checkout --detach '${headSha}'"
                unstash 'candidate-release-manifest'

                final List credentialBindings = []
                final List credentialNames = []
                final String checkoutCredentialId = env.GITLAB_CHECKOUT_CREDENTIALS_ID ?: ''
                if (checkoutCredentialId.trim().isEmpty()) {
                    error('GITLAB_CHECKOUT_CREDENTIALS_ID is required for the deploy freshness check')
                }
                credentialBindings << gitUsernamePassword(credentialsId: checkoutCredentialId)
                if (deployComponents.contains('ai')) {
                    credentialBindings << file(credentialsId: env.DEMO_AI_ENV_CREDENTIAL_ID, variable: 'DEMO_AI_ENV_FILE')
                    credentialNames << 'DEMO_AI_ENV_FILE'
                }
                if (deployComponents.contains('back')) {
                    credentialBindings << file(credentialsId: env.DEMO_BACK_ENV_CREDENTIAL_ID, variable: 'DEMO_BACK_ENV_FILE')
                    credentialBindings << string(credentialsId: env.DEMO_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_INFRA_TO_SPRING_TOKENS')
                    credentialNames << 'DEMO_BACK_ENV_FILE'
                    credentialNames << 'INTERNAL_INFRA_TO_SPRING_TOKENS'
                }
                if (deployComponents.any { it in ['ai', 'back'] }) {
                    credentialBindings << string(credentialsId: env.DEMO_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_SPRING_TO_AI_TOKENS')
                    credentialBindings << string(credentialsId: env.DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_AI_TO_SPRING_TOKENS')
                    credentialNames.addAll(['INTERNAL_SPRING_TO_AI_TOKENS', 'INTERNAL_AI_TO_SPRING_TOKENS'])
                }

                def deployBatch = {
                    int deployStatus = sh(
                        returnStatus: true,
                        script: "infra/jenkins/scripts/with-credentials.sh ${credentialNames.join(' ')} -- infra/jenkins/scripts/deploy-dev-batch.sh"
                    )
                    archiveArtifacts artifacts: 'artifacts/develop/dev-batch-result.json', allowEmptyArchive: true, fingerprint: true
                    if (deployStatus == 75) {
                        currentBuild.result = 'NOT_BUILT'
                        echo 'SUPERSEDED: newer develop head exists before dev deployment'
                        return
                    }
                    if (deployStatus != 0) { error("dev batch failed with exit ${deployStatus}") }
                }

                withEnv([
                    "DEV_BATCH_ID=develop-${headSha}-${env.BUILD_NUMBER}",
                    "DEPLOY_COMPONENTS=${deployComponentList}",
                    "RELEASE_MANIFEST_PATH=${releaseManifest}",
                    "CI_ARTIFACT_DIR=${artifactRoot}",
                    "FRESHNESS_EXPECTED_SHA=${headSha}",
                    'CI_BRANCH=develop',
                    'FESTA_DEPLOY_ENVIRONMENT=demo',
                    'PUBLIC_UNITY_BUILD_BASE=/unity/',
                    // 이 값이 비면 프론트는 멀쩡히 뜨는데 구글 로그인만 404 로 죽는다 (2026-09-17 실측).
                    // agent 가 값을 주지 않으면 ROOT_DOMAIN 으로 만들어 준다 — 빈 값으로 조용히 배포되지 않게.
                    "PUBLIC_API_BASE_URL=${env.PUBLIC_API_BASE_URL?.trim() ?: 'https://api.' + (env.ROOT_DOMAIN ?: '')}"
                ]) {
                    if (credentialBindings.isEmpty()) { deployBatch() } else { withCredentials(credentialBindings) { deployBatch() } }
                }
            }
        }
    }
    }

    if (components.contains('game')) {
        stage('Deploy Dedicated Server') {
            node('deploy') {
                ws('/home/jenkins/agent/deploy/workspaces/develop-game-deploy') {
                    checkout scm
                    sh "git checkout --detach '${headSha}'"
                    unstash 'candidate-release-manifest'
                    withEnv([
                        "RELEASE_MANIFEST_PATH=${releaseManifest}",
                        "GAME_ENV_FILE=${env.GAME_ENV_FILE ?: '/srv/festa/config/game.env'}",
                        "CONNECTION_TOKEN_SECRET_FILE=${env.CONNECTION_TOKEN_SECRET_FILE ?: '/opt/festa/secrets/dev-game-connection-token-secret'}",
                        "GAME_DEPLOY_STATE_DIR=${env.GAME_DEPLOY_STATE_DIR ?: '/var/lib/festa-environments/demo/game'}",
                        "CI_ARTIFACT_DIR=${artifactRoot}"
                    ]) {
                        // deploy-game.sh 의 75 는 "배포된 WebGL 과 NGO 프리팹이 어긋나 교체하지 않았다" 는 뜻이다.
                        // 실패가 아니라 건너뜀이다 — 돌고 있는 월드는 그대로이고, 무관한 커밋마다 빨간 빌드가 쌓이면
                        // 사람이 검사를 꺼 버린다. deploy-dev-batch.sh 의 superseded 와 같은 관례를 쓴다.
                        //
                        // 각 스크립트를 개별 sh 스텝으로 부른다. 이전에는 한 덩어리 sh 문자열 안에 Groovy 의
                        // error(...) 가 들어 있어 셸이 그것을 명령으로 실행했다 — 단계는 우연히 실패했지만
                        // 의도한 메시지는 한 번도 나온 적이 없다.
                        int deployStatus = sh(returnStatus: true, script: 'bash infra/unity-server/scripts/deploy-game.sh')
                        if (deployStatus == 75) {
                            currentBuild.result = 'NOT_BUILT'
                            echo 'SKIPPED: deployed WebGL client and this game candidate disagree on the NGO prefab set; the running demo world was left untouched'
                        } else if (deployStatus != 0) {
                            error("Dedicated Server deployment failed with exit ${deployStatus}")
                        } else if (sh(returnStatus: true, script: 'bash infra/unity-server/scripts/game-readiness.sh') == 0) {
                            sh 'bash infra/unity-server/scripts/promote-game.sh'
                        } else {
                            sh 'bash infra/unity-server/scripts/rollback-game.sh'
                            error('Dedicated Server deployment verification failed')
                        }
                    }
                }
            }
        }
    }
}
return this
