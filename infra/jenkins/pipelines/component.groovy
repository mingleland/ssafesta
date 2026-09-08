def call(Map config = [:]) {
    String component = config.scope as String
    if (!(component in ['ai', 'back', 'front', 'game'])) { error("invalid component: ${component}") }
    if (component == 'game') { load('infra/jenkins/pipelines/unity.groovy').call(config); return }

    String safeRun = "${env.JOB_NAME}-${env.BUILD_NUMBER}".replaceAll(/[^A-Za-z0-9_.-]/, '_')
    String candidateStash = "dev-${component}-candidate-${safeRun}"
    String evidenceStash = "dev-${component}-evidence-${safeRun}"
    String releaseId = "${component}-${env.GIT_COMMIT}-${env.BUILD_NUMBER}"
    withEnv([
        "CI_COMPONENT=${component}", "CI_BRANCH=${component}", "CI_COMMIT_SHA=${env.GIT_COMMIT}",
        "CI_RUN_ID=${safeRun}", "CI_ARTIFACT_DIR=${env.WORKSPACE}/artifacts/${component}",
        "DEPLOY_TARGET=dev-${component}", "RELEASE_ID=${releaseId}",
        "RELEASE_MANIFEST_PATH=${env.WORKSPACE}/artifacts/${component}/release-manifest.json"
    ]) {
        ['validate', 'test', 'build', 'package'].each { name -> stage(name.capitalize()) { sh "infra/jenkins/scripts/with-credentials.sh -- ci/${name}" } }
        stage('Create release manifest') {
            String provider = env.SCM_PROVIDER?.trim() ?: (env.GIT_URL?.contains('gitlab') ? 'gitlab' : 'github')
            withEnv(["SCM_PROVIDER=${provider}", "SCM_REPOSITORY=${env.GIT_URL}", "SCM_BRANCH=${component}",
                     "JENKINS_JOB=${env.JOB_NAME}", "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}",
                     "JENKINS_BUILD_URL=${env.BUILD_URL}", "RELEASE_COMPONENTS=${component}",
                     "COMPONENT_METADATA_DIR=${env.WORKSPACE}/artifacts/${component}/components"]) {
                sh '''mkdir -p "$COMPONENT_METADATA_DIR"
cp "$CI_ARTIFACT_DIR/image-metadata.json" "$COMPONENT_METADATA_DIR/$CI_COMPONENT.json"
infra/deploy/scripts/build-release-manifest.sh'''
            }
            stash name: candidateStash, includes: "artifacts/${component}/image-metadata.json,artifacts/${component}/release-manifest.json"
        }
        stage('Deploy and verify') {
            node('deploy') {
                ws("/home/jenkins/agent/deploy/workspaces/${safeRun}/${component}") {
                    checkout scm
                    unstash candidateStash
                    withEnv(["CI_COMPONENT=${component}", "CI_BRANCH=${component}", "CI_COMMIT_SHA=${env.GIT_COMMIT}",
                             "CI_RUN_ID=${safeRun}", "CI_ARTIFACT_DIR=${pwd()}/artifacts/${component}",
                             "DEPLOY_TARGET=dev-${component}", "RELEASE_ID=${releaseId}",
                             "RELEASE_MANIFEST_PATH=${pwd()}/artifacts/${component}/release-manifest.json",
                             'PUBLIC_API_BASE_URL=/__dev/api', 'PUBLIC_UNITY_BUILD_BASE=/unity/']) {
                        milestone ordinal: env.BUILD_NUMBER.toInteger()
                        lock(resource: "deploy-dev-${component}") {
                            int fresh = sh(returnStatus: true, script: 'FRESHNESS_EXPECTED_SHA="$CI_COMMIT_SHA" infra/jenkins/scripts/freshness.sh')
                            if (fresh == 75) { currentBuild.result = 'NOT_BUILT'; echo 'SUPERSEDED: newer branch head exists'; return }
                            if (fresh != 0) { error('freshness check failed') }
                            if (component in ['ai', 'back']) {
                                String envCredentialName = component == 'back' ? 'DEV_BACK_ENV_CREDENTIAL_ID' : 'DEV_AI_ENV_CREDENTIAL_ID'
                                String envCredentialId = env[envCredentialName]?.trim()
                                String springToAiCredentialId = env.DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID?.trim()
                                String aiToSpringCredentialId = env.DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID?.trim()
                                if (!envCredentialId) { error("필수 Jenkins credential ID 누락: ${envCredentialName}") }
                                if (!springToAiCredentialId) { error('필수 Jenkins credential ID 누락: DEV_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID') }
                                if (!aiToSpringCredentialId) { error('필수 Jenkins credential ID 누락: DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID') }
                                withCredentials([
                                    file(credentialsId: envCredentialId, variable: 'COMPONENT_ENV_FILE'),
                                    string(credentialsId: springToAiCredentialId, variable: 'INTERNAL_SPRING_TO_AI_TOKENS'),
                                    string(credentialsId: aiToSpringCredentialId, variable: 'INTERNAL_AI_TO_SPRING_TOKENS')
                                ]) {
                                    sh 'infra/jenkins/scripts/with-credentials.sh COMPONENT_ENV_FILE INTERNAL_SPRING_TO_AI_TOKENS INTERNAL_AI_TO_SPRING_TOKENS -- infra/jenkins/scripts/deploy-dev-component.sh'
                                }
                            } else {
                                sh 'infra/jenkins/scripts/with-credentials.sh -- infra/jenkins/scripts/deploy-dev-component.sh'
                            }
                        }
                    }
                    stash name: evidenceStash, includes: "artifacts/${component}/**"
                }
            }
        }
        unstash evidenceStash
    }
}
return this
