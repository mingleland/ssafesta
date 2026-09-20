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

    // 네 축 계약 (Batch 1): validation ⊇ build ⊇ (deploy ∩ apps). shared-ci 는 검증만 넓힌다.
    final List validationComponents = (selection.validationComponents ?: selection.components) as List
    final List buildComponents = (selection.buildComponents ?: []) as List
    final List selectedDeploy = (selection.deployComponents ?: []) as List
    if (!(selection.gameBuildRequired instanceof Boolean)) {
        error('component detector must return boolean gameBuildRequired')
    }
    final boolean gameBuildRequired = selection.gameBuildRequired as boolean
    if (buildComponents.contains('game') && !gameBuildRequired) { error('detector selected a game build without gameBuildRequired') }

    if (validationComponents.isEmpty()) {
        echo "NO_OP: ${selection.reasons.join(', ')}"
        return
    }

    final String artifactRoot = 'artifacts/develop'
    final String releaseId = "develop-${headSha}-${env.BUILD_NUMBER}"
    final String transferDir = '/var/lib/festa-image-transfer'
    // Only the detector's deployComponents contract is allowed to mutate demo.
    final List deployComponents = selectedDeploy.findAll { it in ['ai', 'back', 'front'] }
    final String deployComponentList = deployComponents.join(',')

    echo "SELECTED_COMPONENTS: validate=${validationComponents.join(', ')}; build=${buildComponents.join(', ') ?: 'none'}; dev batch targets: ${deployComponentList ?: 'none'}"

    // game 은 Unity 실행기 하나를 독점하고 빌드가 45분을 넘는다. 그 빌드를 ai·back·front 배포보다
    // 앞에 두면 unity 가 붐빌 때 무관한 파트 배포까지 함께 죽는다 — #468 은 unity 대기 50분 뒤
    // 전체 타임아웃으로 ABORT 되며 이미 만들어 둔 back·front 이미지를 배포하지 못했다 (2026-09-18 실측).
    // 그래서 앱 컴포넌트를 먼저 빌드·배포하고 game 은 뒤에 둔다. 배포 단위가 서로 다르므로
    // release manifest 도 각자 만든다 (schema 는 minItems 1 을 허용한다).
    final List appComponents = buildComponents.findAll { it != 'game' }
    final String appComponentList = appComponents.join(',')
    // game 은 buildComponents 에 있을 때만(= gameBuildRequired) Unity 를 돌린다. 배포는 deployComponents 가 결정한다.
    final boolean hasGame = buildComponents.contains('game')
    final boolean deployGame = selectedDeploy.contains('game')

    final String appMetadataDir = "${artifactRoot}/release-metadata"
    final String appManifest = "${artifactRoot}/release-manifest.json"
    final String appBundle = "${releaseId}-app"
    final String gameMetadataDir = "${artifactRoot}/release-metadata-game"
    final String gameManifest = "${artifactRoot}/release-manifest-game.json"
    final String gameBundle = "${releaseId}-game"

    def buildComponent = { String component, List stages = null ->
        load('infra/jenkins/pipelines/component.groovy').call([
            component: component,
            sourceSha: selection.headSha,
            artifactDir: "artifacts/develop/build/${component}",
            stages: stages
        ])
    }

    // 검증만 할 컴포넌트: app 은 validate+test, game 은 validate(정적, Unity 없음).
    final List validateOnly = validationComponents.findAll { !(it in buildComponents) }
    validateOnly.each { component ->
        buildComponent(component, component == 'game' ? ['validate'] : ['validate', 'test'])
    }

    if (buildComponents.isEmpty()) {
        echo "NO_OP: validation-only change (${selection.reasons.join(', ')})"
        return
    }

    def candidateManifest = { String label, String comps, String metadataDir, String manifestPath, String bundle, String stashName ->
        stage("Candidate Manifest (${label})") {
            sh """
                mkdir -p '${metadataDir}'
                rm -f '${metadataDir}'/*.json
                for component in ${comps.replace(',', ' ')}; do
                    cp '${artifactRoot}/build/'\${component}'/image-metadata.json' '${metadataDir}/'\${component}'.json'
                done
            """
            withEnv([
                "DEPLOY_COMPONENTS=${comps}",
                "COMPONENT_METADATA_DIR=${metadataDir}",
                "RELEASE_MANIFEST_PATH=${manifestPath}",
                "RELEASE_ID=${releaseId}",
                'SCM_PROVIDER=gitlab',
                'SCM_REPOSITORY=s15-metaverse-game-sub1/S15P21A604',
                'SCM_BRANCH=develop',
                "CI_COMMIT_SHA=${headSha}",
                "JENKINS_JOB=${env.JOB_NAME}",
                "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}",
                "JENKINS_BUILD_URL=${env.BUILD_URL}"
            ]) {
                sh 'infra/deploy/scripts/build-release-manifest.sh'
                sh "infra/jenkins/scripts/transfer-local-images.sh --export --bundle '${bundle}' --transfer-dir '${transferDir}' --manifest '${manifestPath}'"
                stash name: stashName, includes: manifestPath, useDefaultExcludes: false
            }
        }
        stage("Deploy Candidate Receipt (${label})") {
            node('deploy') {
                ws("/home/jenkins/agent/deploy/workspaces/develop-candidate-receipt-${label}") {
                    checkout scm
                    sh "git checkout --detach '${headSha}'"
                    unstash stashName
                    sh "infra/jenkins/scripts/transfer-local-images.sh --import --bundle '${bundle}' --transfer-dir '${transferDir}'"
                }
            }
        }
    }

    if (!appComponents.isEmpty()) {
        appComponents.each { component -> buildComponent(component) }
        candidateManifest('app', appComponentList, appMetadataDir, appManifest, appBundle, 'candidate-release-manifest')
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
                    "DEV_BATCH_ID=${releaseId}",
                    "DEPLOY_COMPONENTS=${deployComponentList}",
                    "RELEASE_MANIFEST_PATH=${appManifest}",
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

    if (!hasGame) { return }

    // 여기서부터 game 이다. 위의 앱 배포가 이미 끝났으므로 Unity 대기가 그 배포를 막지 않는다.
    buildComponent('game')
    stage('Collect Game Candidate Metadata') {
        unstash 'candidate-metadata-game'
    }
    candidateManifest('game', 'game', gameMetadataDir, gameManifest, gameBundle, 'candidate-release-manifest-game')

    if (!deployGame) {
        echo 'NO_OP: game candidate built for CI only; deployment was not requested by the component detector'
        return
    }

    stage('Deploy Dedicated Server') {
        node('deploy') {
            ws('/home/jenkins/agent/deploy/workspaces/develop-game-deploy') {
                checkout scm
                sh "git checkout --detach '${headSha}'"
                unstash 'candidate-release-manifest-game'
                withEnv([
                    "RELEASE_MANIFEST_PATH=${gameManifest}",
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
return this
