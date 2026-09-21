def call() {
    // SCM environment variables may be absent after a Controller restart;
    // the checked-out commit remains the authoritative build revision.
    final String headSha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    if (!(headSha ==~ /^[0-9a-f]{40}$/)) { error('GIT_COMMIT must be a full lowercase SHA') }

    // Deploy agent 는 Unity 원본을 빌드하지 않는다 (Consumer-only). checkout 이 LFS pointer 를 만나면
    // smudge 가 GitLab LFS endpoint 로 blob 을 받으러 가는데, 거기서 멈추면 checkout 이 600초
    // timeout 으로 죽는다 — BGM_ArcadeRush.mp3(4.4MB) 반입 후 build #535 가 그렇게 실패했다 (#259).
    // deploy 경로에 필요한 것은 infra 스크립트·pipeline 정의·설정뿐이라 blob 실체가 필요 없다.
    // 범위를 deploy checkout 으로 한정한다 — Unity 빌드/MR 검증 경로의 LFS 는 그대로 둔다.
    def deployCheckout = {
        withEnv(['GIT_LFS_SKIP_SMUDGE=1']) {
            checkout scm
            sh "git checkout --detach '${headSha}'"
        }
    }

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
        if (selection.sharedCiChanged) {
            stage('CI Static & Contract Tests') {
                sh 'for t in infra/jenkins/tests/*.sh; do bash "$t"; done'
                sh 'bash infra/unity-server/tests/run-static.sh'
            }
        }
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
                    deployCheckout()
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
                deployCheckout()
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

    // 여기서부터 game 이다 (Batch 2 Consumer-only). Jenkins 는 Unity Editor 를 돌리지 않는다 — WebGL zip 과 World image 는 Unity 담당자가
    // 자기 개발환경에서 만들어 Registry unity-release-bundle/<8sha> 로 올린 Unity Release Bundle 에서만 온다.
    //
    //   Resolve  (deploy)  canonical festa-webgl/festa-world 가 있으면 재사용, bundle 이 있으면 intake, 둘 다 없으면 WAITING 으로 정상 종료
    //   Intake   (deploy)  bundle 4 파일 download → zip/tar/metadata 검증 → docker image load
    //   Consumer (deploy)  SCM identity gate → publish(idempotent) → release-set 검증 → World candidate → readiness → WebGL current → promote
    final String gameDeployWs = '/home/jenkins/agent/deploy/workspaces/develop-game-deploy'
    final String gitlabApi = env.GITLAB_API_V4_URL ?: 'https://lab.ssafy.com/api/v4'
    final String gitlabProject = env.GITLAB_PROJECT_ID ?: '1443023'
    final String readCredentialId = env.GITLAB_PACKAGE_READ_CREDENTIAL_ID ?: 'gitlab-package-read'
    final String writeCredentialId = env.GITLAB_PACKAGE_WRITE_CREDENTIAL_ID ?: 'gitlab-package-write'
    final String checkoutCredentialId = env.GITLAB_CHECKOUT_CREDENTIALS_ID ?: ''
    if (checkoutCredentialId.trim().isEmpty()) { error('GITLAB_CHECKOUT_CREDENTIALS_ID is required for the game source-identity gate') }

    def onDeploy = { Closure body ->
        node('deploy') {
            ws(gameDeployWs) {
                deployCheckout()
                body()
            }
        }
    }

    Map resolution = null
    def withReadToken = { Closure body ->
        withCredentials([usernamePassword(credentialsId: readCredentialId, usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN')]) { body() }
    }
    final String candidateParam = (params.UNITY_ARTIFACT_CANDIDATE ?: '').trim()
    stage('Resolve Game Artifacts') {
        onDeploy {
            withReadToken {
                final String candidateArg = candidateParam ? " --candidate '${candidateParam}'" : ''
                final String text = sh(returnStdout: true, script: "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/jenkins/scripts/resolve-game-artifacts.sh --pipeline-commit '${headSha}'${candidateArg}").trim()
                resolution = readJSON text: text, returnPojo: true
                writeFile file: "${artifactRoot}/game-resolution.json", text: "${text}\n"
                archiveArtifacts artifacts: "${artifactRoot}/game-resolution.json", allowEmptyArchive: false, fingerprint: true
            }
        }
    }
    final String decision = resolution.decision as String
    final String artifactSourceCommit = (resolution.artifactSourceCommit ?: headSha) as String
    final String gameReleaseId = artifactSourceCommit.substring(0, 8)
    final String bundleDir = "${gameDeployWs}/unity-release-bundle/${gameReleaseId}"
    if (!(decision in ['REGISTRY_COMPLETE', 'BUNDLE_AVAILABLE', 'PUBLISH_WEBGL', 'PUBLISH_WORLD', 'WAITING_FOR_UNITY_ARTIFACT'])) { error("unknown game artifact resolution: ${decision}") }
    echo "GAME_ARTIFACTS: ${decision} (festa-webgl/${gameReleaseId}, festa-world/${gameReleaseId}, unity-release-bundle/${gameReleaseId}, matchedBy=${resolution.matchedBy ?: 'none'})"
    if (decision == 'WAITING_FOR_UNITY_ARTIFACT') {
        // Unity build 를 시작하지 않는다. 담당자가 bundle 을 올린 뒤 같은 SHA 를 다시 돌리면 여기서 이어진다.
        echo "WAITING_FOR_UNITY_ARTIFACT: no canonical game artifacts and no Unity Release Bundle for ${gameReleaseId} (unitySourceSha=${resolution.unitySourceSha ?: headSha}); upload unity-release-bundle/${gameReleaseId} and rebuild this commit"
        return
    }

    final String gameImageRef = "festa-game:${artifactSourceCommit}"
    final String webglZip = "${bundleDir}/festa-webgl-release-${gameReleaseId}.zip"
    String webglSha = resolution.registry?.webglSha256 ?: ''
    String gameContentId = resolution.registry?.worldContentId ?: ''
    final boolean needWebgl = decision in ['BUNDLE_AVAILABLE', 'PUBLISH_WEBGL']
    final boolean needWorld = decision in ['BUNDLE_AVAILABLE', 'PUBLISH_WORLD']

    stage('Publish Game Artifacts') {
        onDeploy {
            if (needWebgl || needWorld) {
                // Intake: bundle 4 파일을 받아 검증하고 World image 를 로컬 docker 에 올린다. BUNDLE_OK <commit> <zipSha> <contentId>
                withReadToken {
                    final String bundleLine = sh(returnStdout: true, script: "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/jenkins/scripts/intake-unity-release-bundle.sh --source-commit '${artifactSourceCommit}' --dest '${bundleDir}'").trim().readLines().last()
                    final List parts = bundleLine.tokenize(' ')
                    if (parts.size() != 4 || parts[0] != 'BUNDLE_OK') { error("Unity Release Bundle intake failed: ${bundleLine}") }
                    if (needWebgl) { webglSha = parts[2] }
                    gameContentId = parts[3]
                }
            } else if (!sh(returnStatus: true, script: "docker image inspect '${gameImageRef}' >/dev/null 2>&1").equals(0)) {
                // REGISTRY_COMPLETE 인데 deploy agent 에 image 가 없다(예: image prune) → canonical festa-world tar 로 되살린다. 재빌드가 아니다.
                withReadToken {
                    sh "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/deploy/scripts/load-production-world.sh --release-id '${gameReleaseId}' --sha256 '${resolution.registry.worldSha256}' --package-url '${gitlabApi}/projects/${gitlabProject}/packages/generic/festa-world/${gameReleaseId}/festa-world-release-${gameReleaseId}.tar' --image-ref '${gameImageRef}' --content-id '${gameContentId}'"
                }
            }
            // Gate: commit 이 origin 에 있고 develop 조상이며 zip/image 가 그 commit 을 가리킨다. 실패하면 publish 로 못 간다.
            withCredentials([gitUsernamePassword(credentialsId: checkoutCredentialId)]) {
                sh "infra/jenkins/scripts/check-game-source-identity.sh --head '${headSha}' --source-commit '${artifactSourceCommit}'" +
                   (needWebgl ? " --webgl-zip '${webglZip}'" : '') + " --image-ref '${gameImageRef}'"
            }
            final String localContentId = sh(returnStdout: true, script: "docker image inspect --format '{{.Id}}' '${gameImageRef}'").trim()
            if (localContentId != gameContentId) { error("game image ${gameImageRef} on the deploy agent is ${localContentId}, expected ${gameContentId}") }
            if (needWebgl || needWorld) {
                withCredentials([string(credentialsId: writeCredentialId, variable: 'GITLAB_PACKAGE_TOKEN')]) {
                    withEnv(["JENKINS_JOB=${env.JOB_NAME}", "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}", "JENKINS_BUILD_URL=${env.BUILD_URL}"]) {
                        if (needWebgl) {
                            sh "infra/jenkins/scripts/with-credentials.sh GITLAB_PACKAGE_TOKEN -- infra/jenkins/scripts/publish-webgl-release.sh '${webglZip}' '${gameReleaseId}' --no-trigger"
                        }
                        if (needWorld) {
                            sh "infra/jenkins/scripts/with-credentials.sh GITLAB_PACKAGE_TOKEN -- infra/jenkins/scripts/publish-world-release.sh --source-commit '${artifactSourceCommit}' --image-ref '${gameImageRef}' --content-id '${gameContentId}'"
                        }
                    }
                }
            }
            if (needWebgl) {
                sh "infra/jenkins/scripts/validate-game-release-set.sh --source-commit '${artifactSourceCommit}' --webgl-zip '${webglZip}' --webgl-sha256 '${webglSha}' --image-ref '${gameImageRef}' --content-id '${gameContentId}'"
                // bundle 사본은 게시 뒤 필요 없다 — Registry 가 정본이다.
                sh "rm -rf -- '${bundleDir}'"
            }
            if (!(webglSha ==~ /^[0-9a-f]{64}$/)) { error('WebGL artifact SHA-256 is unknown after publish/resolve') }
        }
    }

    if (!deployGame) {
        echo 'NO_OP: game artifacts published for CI only; deployment was not requested by the component detector'
        return
    }

    stage('Deploy Game Release Set') {
        onDeploy {
            // Demo readiness 는 Demo World host 만 검사한다 — 값이 없으면 추론하지 않고 멈춘다 (Batch 1).
            if (!env.DEMO_WORLD_HOST?.trim()) { error('DEMO_WORLD_HOST is required on the deploy agent') }
            // deploy-game.sh 는 release manifest 로 image identity 를 받는다. 재실행(SKIP/PUBLISH_*)에는 이번 run 의 manifest 가
            // 없으므로 deploy agent 의 image 에서 같은 형식으로 다시 만든다 — 출처는 어차피 docker image inspect 다.
            sh """
                mkdir -p '${gameMetadataDir}'
                printf '{"schemaVersion":"1.0.0","component":"game","sourceCommit":"%s","storageMode":"local-docker","imageRef":"%s","contentId":"%s"}\\n' '${artifactSourceCommit}' '${gameImageRef}' '${gameContentId}' >'${gameMetadataDir}/game.json'
                printf '{"sourceCommit":"%s"}\\n' '${artifactSourceCommit}' >'${artifactRoot}/webgl-candidate-manifest.json'
            """
            withEnv([
                'DEPLOY_COMPONENTS=game', "COMPONENT_METADATA_DIR=${gameMetadataDir}", "RELEASE_MANIFEST_PATH=${gameManifest}",
                "RELEASE_ID=${releaseId}", 'SCM_PROVIDER=gitlab', 'SCM_REPOSITORY=s15-metaverse-game-sub1/S15P21A604', 'SCM_BRANCH=develop',
                "CI_COMMIT_SHA=${headSha}", "JENKINS_JOB=${env.JOB_NAME}", "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}", "JENKINS_BUILD_URL=${env.BUILD_URL}"
            ]) { sh 'infra/deploy/scripts/build-release-manifest.sh' }

            final String webglPackageUrl = "${env.GITLAB_API_V4_URL ?: 'https://lab.ssafy.com/api/v4'}/projects/${env.GITLAB_PROJECT_ID ?: '1443023'}/packages/generic/festa-webgl/${gameReleaseId}/festa-webgl-release-${gameReleaseId}.zip"
            withEnv([
                "RELEASE_MANIFEST_PATH=${gameManifest}",
                // prefab guard 는 candidate WebGL(같은 commit) 과 대조한다 — 게시된 zip 의 sourceCommit 은 gate 가 이미 headSha 로 증명했다.
                "WEBGL_MANIFEST_PATH=${artifactRoot}/webgl-candidate-manifest.json",
                "WORLD_PUBLIC_HOST=${env.DEMO_WORLD_HOST}",
                "GAME_ENV_FILE=${env.GAME_ENV_FILE ?: '/srv/festa/config/game.env'}",
                "CONNECTION_TOKEN_SECRET_FILE=${env.CONNECTION_TOKEN_SECRET_FILE ?: '/opt/festa/secrets/dev-game-connection-token-secret'}",
                "GAME_DEPLOY_STATE_DIR=${env.GAME_DEPLOY_STATE_DIR ?: '/var/lib/festa-environments/demo/game'}",
                "CI_ARTIFACT_DIR=${artifactRoot}",
                "WEBGL_EVIDENCE_PATH=${pwd()}/${artifactRoot}/webgl-deployment.json"
            ]) {
                // 순서가 계약이다: World candidate runtime → readiness → WebGL current flip(자체 verify/rollback) → promote.
                // VERIFIED 전 실패는 canonical current(webgl current, batches/current/*.json)를 바꾸지 않는다; World runtime 만
                // 잠시 candidate 였다가 rollback-game.sh 로 돌아온다. 각 스크립트는 개별 sh 스텝이다(한 덩어리 sh 안의 error() 사고 재발 방지).
                int deployStatus = sh(returnStatus: true, script: 'bash infra/unity-server/scripts/deploy-game.sh')
                if (deployStatus == 75) {
                    currentBuild.result = 'NOT_BUILT'
                    echo 'SKIPPED: candidate WebGL client and this game candidate disagree on the NGO prefab set; the running demo world was left untouched'
                } else if (deployStatus != 0) {
                    error("Dedicated Server deployment failed with exit ${deployStatus}")
                } else if (sh(returnStatus: true, script: 'bash infra/unity-server/scripts/game-readiness.sh') != 0) {
                    sh 'bash infra/unity-server/scripts/rollback-game.sh'
                    error('Dedicated Server deployment verification failed')
                } else {
                    int webglStatus = -1
                    withCredentials([usernamePassword(credentialsId: readCredentialId, usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN')]) {
                        webglStatus = sh(returnStatus: true, script: "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/jenkins/scripts/deploy-webgl-release.sh --release-id '${gameReleaseId}' --sha256 '${webglSha}' --package-url '${webglPackageUrl}'")
                    }
                    archiveArtifacts artifacts: "${artifactRoot}/webgl-deployment.json", allowEmptyArchive: true, fingerprint: true
                    if (webglStatus != 0) {
                        // deploy-webgl-release.sh 는 자기 검증 실패 시 previous 로 스스로 되돌린다. World 도 같이 되돌린다.
                        sh 'bash infra/unity-server/scripts/rollback-game.sh'
                        error("WebGL release activation failed with exit ${webglStatus}")
                    }
                    sh 'bash infra/unity-server/scripts/promote-game.sh'
                }
            }
        }
    }
}
return this
