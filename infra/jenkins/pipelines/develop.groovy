def call() {
    final String headSha = env.GIT_COMMIT
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

    if (deployComponents.isEmpty()) {
        echo 'NO_OP: game deployment remains on the Phase 3 WebGL path; Dedicated Server deployment is infra-003'
        return
    }

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
                    credentialBindings << file(credentialsId: env.DEV_AI_ENV_CREDENTIAL_ID, variable: 'DEV_AI_ENV_FILE')
                    credentialNames << 'DEV_AI_ENV_FILE'
                }
                if (deployComponents.contains('back')) {
                    credentialBindings << file(credentialsId: env.DEV_BACK_ENV_CREDENTIAL_ID, variable: 'DEV_BACK_ENV_FILE')
                    credentialBindings << string(credentialsId: env.DEV_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_INFRA_TO_SPRING_TOKENS')
                    credentialNames << 'DEV_BACK_ENV_FILE'
                    credentialNames << 'INTERNAL_INFRA_TO_SPRING_TOKENS'
                }
                if (deployComponents.any { it in ['ai', 'back'] }) {
                    credentialBindings << string(credentialsId: env.DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_SPRING_TO_AI_TOKENS')
                    credentialBindings << string(credentialsId: env.DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_AI_TO_SPRING_TOKENS')
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
                    'PUBLIC_UNITY_BUILD_BASE=/unity/'
                ]) {
                    if (credentialBindings.isEmpty()) { deployBatch() } else { withCredentials(credentialBindings) { deployBatch() } }
                }
            }
        }
    }
}
return this
