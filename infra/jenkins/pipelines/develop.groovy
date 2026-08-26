def call(Map config = [:]) {
    final String commit = env.GIT_COMMIT
    final String runId = "${env.JOB_NAME}-${env.BUILD_NUMBER}".replaceAll(/[^A-Za-z0-9_.-]/, '_')
    final String artifactRoot = "${env.WORKSPACE}/artifacts/develop"
    final String metadataDir = "${artifactRoot}/components"
    final String releaseId = "develop-${commit}-${env.BUILD_NUMBER}"
    final List components = ['ai', 'back', 'front']

    withEnv(["CI_BRANCH=develop", "CI_COMMIT_SHA=${commit}", "CI_RUN_ID=${runId}", "RELEASE_ID=${releaseId}",
             "DEPLOY_TARGET=integration-develop", "CI_ARTIFACT_DIR=${artifactRoot}", "COMPONENT_METADATA_DIR=${metadataDir}",
             "RELEASE_MANIFEST_PATH=${artifactRoot}/release-manifest.json", "STATE_DIR=${env.WORKSPACE}/infra/deploy/state/runtime/integration-develop"]) {
        stage('Build candidate components') {
            components.each { component ->
                withEnv(["CI_COMPONENT=${component}", "CI_ARTIFACT_DIR=${artifactRoot}/build/${component}"]) {
                    sh "infra/jenkins/scripts/with-credentials.sh -- ci/validate"
                    sh "infra/jenkins/scripts/with-credentials.sh -- ci/test"
                    sh "infra/jenkins/scripts/with-credentials.sh -- ci/build"
                    sh "infra/jenkins/scripts/with-credentials.sh -- ci/package"
                    sh "mkdir -p '${metadataDir}' && cp '${artifactRoot}/build/${component}/image-metadata.json' '${metadataDir}/${component}.json'"
                }
            }
        }
        stage('Build candidate game') {
            node('unity-6000.0.78f1') {
                ws("/home/jenkins/agent/unity/workspaces/${runId}/develop") {
                    checkout scm
                    withEnv(["CI_COMPONENT=game", "CI_BRANCH=develop", "CI_COMMIT_SHA=${commit}", "CI_RUN_ID=${runId}",
                             "CI_ARTIFACT_DIR=${pwd()}/artifacts/develop/build/game"]) {
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/validate'
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/test'
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/build'
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/package'
                        stash name: "develop-game-${runId}", includes: 'artifacts/develop/build/game/image-metadata.json', useDefaultExcludes: false
                    }
                }
            }
            unstash "develop-game-${runId}"
            sh "mkdir -p '${metadataDir}' && cp 'artifacts/develop/build/game/image-metadata.json' '${metadataDir}/game.json'"
        }
        stage('Create candidate manifest') {
            withEnv(["SCM_PROVIDER=${env.SCM_PROVIDER ?: 'github'}", "SCM_REPOSITORY=${env.SCM_REPOSITORY ?: env.GIT_URL}", 'SCM_BRANCH=develop',
                     "JENKINS_JOB=${env.JOB_NAME}", "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}", "JENKINS_BUILD_URL=${env.BUILD_URL}"]) {
                sh 'infra/deploy/scripts/build-release-manifest.sh'
                sh 'mkdir -p "$STATE_DIR/candidates" && cp "$RELEASE_MANIFEST_PATH" "$STATE_DIR/candidates/$RELEASE_ID.json"'
            }
        }
        stage('Deploy and verify candidate') {
            milestone ordinal: env.BUILD_NUMBER.toInteger()
            lock(resource: 'deploy-integration-develop') {
                int fresh = sh(returnStatus: true, script: 'FRESHNESS_EXPECTED_SHA="$CI_COMMIT_SHA" infra/jenkins/scripts/freshness.sh')
                if (fresh == 75) { currentBuild.result = 'NOT_BUILT'; error('SUPERSEDED: newer develop head exists') }
                if (fresh != 0) { error('develop freshness check failed') }
                sh 'infra/jenkins/scripts/with-credentials.sh -- infra/deploy/scripts/deploy-release.sh'
                int verified = sh(returnStatus: true, script: 'infra/jenkins/scripts/with-credentials.sh -- infra/deploy/scripts/verify-release.sh')
                sh 'RECOVERY_DECISION_PATH="$CI_ARTIFACT_DIR/recovery-decision.json" VERIFICATION_RESULT_PATH="$CI_ARTIFACT_DIR/verification-result.json" infra/deploy/scripts/decide-recovery.sh'
                def decision = readJSON file: 'artifacts/develop/recovery-decision.json'
                if (verified == 0 && decision.decision == 'NONE') {
                    sh 'VERIFICATION_RESULT_PATH="$CI_ARTIFACT_DIR/verification-result.json" infra/deploy/scripts/promote-release.sh'
                } else if (decision.decision == 'AUTO_ROLLBACK') {
                    if (!fileExists("${env.STATE_DIR}/target-state.json")) {
                        sh '''python -c "import json,os; p=os.environ['CI_ARTIFACT_DIR']+'/recovery-decision.json'; d=json.load(open(p)); d.update(decision='MANUAL',reason='no known-good release exists'); open(p,'w').write(json.dumps(d,indent=2))"'''
                        echo 'MANUAL_ACTION_REQUIRED: no known-good release exists'
                    } else {
                        def targetState = readJSON file: "${env.STATE_DIR}/target-state.json"
                        withEnv(["KNOWN_GOOD_MANIFEST_PATH=${env.STATE_DIR}/releases/${targetState.knownGoodReleaseId}.json", "CI_ARTIFACT_DIR=${artifactRoot}/rollback"]) {
                            int rollbackStatus = sh(returnStatus: true, script: 'infra/deploy/scripts/rollback-release.sh')
                            if (rollbackStatus != 0) { echo 'ROLLBACK_FAILED: automatic retry is forbidden' }
                        }
                    }
                    currentBuild.result = 'FAILURE'
                } else if (decision.decision == 'AI_RETRY') {
                    currentBuild.result = 'UNSTABLE'; echo 'AWAITING_AI_RETRY_OR_APPROVAL'
                } else {
                    currentBuild.result = 'FAILURE'; echo 'MANUAL_ACTION_REQUIRED'
                }
                sh 'rm -f "$STATE_DIR/candidates/$RELEASE_ID.json"'
            }
        }
        stage('Provenance') {
            sh 'VERIFICATION_RESULT_PATH="$CI_ARTIFACT_DIR/verification-result.json" RECOVERY_DECISION_PATH="$CI_ARTIFACT_DIR/recovery-decision.json" ROLLBACK_DECISION_PATH="$CI_ARTIFACT_DIR/rollback/rollback-decision.json" DEPLOYMENT_RECORD_PATH="$CI_ARTIFACT_DIR/deployment-record.json" RUN_ID="$CI_RUN_ID" infra/deploy/scripts/write-deployment-record.sh'
            sh 'PROVENANCE_LOG="$CI_ARTIFACT_DIR/provenance.jsonl" RUN_ID="$CI_RUN_ID" VERIFICATION_RESULT_PATH="$CI_ARTIFACT_DIR/verification-result.json" DEPLOYMENT_RECORD_PATH="$CI_ARTIFACT_DIR/deployment-record.json" infra/jenkins/scripts/provenance.sh'
            load('infra/jenkins/pipelines/provenance.groovy').archiveReleaseEvidence('artifacts/develop')
        }
    }
}
return this
