def call(Map config = [:]) {
    String safeJob = env.JOB_NAME.replaceAll(/[^A-Za-z0-9_.-]/, '_')
    String safeRun = "${safeJob}-${env.BUILD_NUMBER}"
    String releaseId = "game-${env.GIT_COMMIT}-${env.BUILD_NUMBER}"
    String candidateStash = "dev-game-candidate-${safeRun}"
    String evidenceStash = "dev-game-evidence-${safeRun}"
    withEnv(["CI_COMPONENT=game", "CI_BRANCH=game", "CI_COMMIT_SHA=${env.GIT_COMMIT}", "CI_RUN_ID=${safeRun}",
             'DEPLOY_TARGET=dev-game', "RELEASE_ID=${releaseId}"]) {
        node('unity-6000.0.78f1') {
            [['webgl','WebGL'], ['linux-server','Linux Server']].each { target ->
                stage("Unity ${target[1]}") {
                    String persistentWorkspace = "/home/jenkins/agent/unity/workspaces/${safeJob}/${target[0]}"
                    ws(persistentWorkspace) {
                        checkout scm
                        withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game/${target[0]}"]) {
                            sh 'infra/jenkins/scripts/with-credentials.sh -- ci/validate'
                            sh 'infra/jenkins/scripts/with-credentials.sh -- festa-unity/ci/build --target ' + target[0]
                            if (target[0] == 'webgl') {
                                sh 'test -f festa-unity/Builds/webgl/index.html && test -d festa-unity/Builds/webgl/Build && test -d festa-unity/Builds/webgl/TemplateData'
                            }
                        }
                    }
                }
            }
            stage('Unity Test and Package') {
                ws("/home/jenkins/agent/unity/workspaces/${safeJob}/linux-server") {
                    withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game"]) {
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/test'
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/package'
                    }
                }
            }
            stage('Create release manifest') {
                ws("/home/jenkins/agent/unity/workspaces/${safeJob}/linux-server") {
                    String provider = env.SCM_PROVIDER?.trim() ?: (env.GIT_URL?.contains('gitlab') ? 'gitlab' : 'github')
                    withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game", "RELEASE_MANIFEST_PATH=${pwd()}/artifacts/game/release-manifest.json",
                             "SCM_PROVIDER=${provider}", "SCM_REPOSITORY=${env.GIT_URL}", 'SCM_BRANCH=game',
                             "JENKINS_JOB=${env.JOB_NAME}", "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}",
                             "JENKINS_BUILD_URL=${env.BUILD_URL}", 'RELEASE_COMPONENTS=game',
                             "COMPONENT_METADATA_DIR=${pwd()}/artifacts/game/components"]) {
                        sh '''mkdir -p "$COMPONENT_METADATA_DIR"
cp "$CI_ARTIFACT_DIR/image-metadata.json" "$COMPONENT_METADATA_DIR/$CI_COMPONENT.json"
infra/deploy/scripts/build-release-manifest.sh'''
                        stash name: candidateStash, includes: 'artifacts/game/image-metadata.json,artifacts/game/release-manifest.json'
                    }
                }
            }
        }
        stage('Deploy and verify game') {
            node('deploy') {
                ws("/home/jenkins/agent/deploy/workspaces/${safeRun}/game") {
                    checkout scm
                    unstash candidateStash
                    withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game", "RELEASE_MANIFEST_PATH=${pwd()}/artifacts/game/release-manifest.json"]) {
                        milestone ordinal: env.BUILD_NUMBER.toInteger()
                        lock(resource: 'deploy-dev-game') {
                            int fresh = sh(returnStatus: true, script: 'FRESHNESS_EXPECTED_SHA="$CI_COMMIT_SHA" infra/jenkins/scripts/freshness.sh')
                            if (fresh == 75) { currentBuild.result = 'NOT_BUILT'; echo 'SUPERSEDED: newer game head exists'; return }
                            if (fresh != 0) { error('game freshness check failed') }
                            sh 'infra/jenkins/scripts/with-credentials.sh CONNECTION_TOKEN_SECRET_FILE -- infra/jenkins/scripts/deploy-dev-component.sh'
                        }
                    }
                    stash name: evidenceStash, includes: 'artifacts/game/**'
                }
            }
        }
        unstash evidenceStash
    }
}
return this
