def call(Map config = [:]) {
    String safeJob = env.JOB_NAME.replaceAll(/[^A-Za-z0-9_.-]/, '_')
    withEnv(["CI_COMPONENT=game", "CI_BRANCH=game", "CI_COMMIT_SHA=${env.GIT_COMMIT}", "CI_RUN_ID=${safeJob}-${env.BUILD_NUMBER}"]) {
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
            def meta
            stage('Unity Test and Package') {
                ws("/home/jenkins/agent/unity/workspaces/${safeJob}/linux-server") {
                    withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game"]) {
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/test'
                        sh 'infra/jenkins/scripts/with-credentials.sh -- ci/package'
                        meta = readJSON file: 'artifacts/game/image-metadata.json'
                    }
                }
            }
            stage('Deploy Game') {
                ws("/home/jenkins/agent/unity/workspaces/${safeJob}/linux-server") {
                    withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game"]) {
                            milestone ordinal: env.BUILD_NUMBER.toInteger()
                            lock(resource: 'deploy-dev-game') {
                                int fresh = sh(returnStatus: true, script: 'FRESHNESS_EXPECTED_SHA="$CI_COMMIT_SHA" infra/jenkins/scripts/freshness.sh')
                                if (fresh == 75) { currentBuild.result = 'NOT_BUILT'; echo 'SUPERSEDED: newer game head exists'; return }
                                if (fresh != 0) { error('game freshness check failed') }
                                withEnv(["COMPOSE_FILE=infra/deploy/compose/dev/game.compose.yaml", 'COMPOSE_PROJECT=festa-dev-game', 'COMPOSE_SERVICE=game', "IMAGE_REF=${meta.imageRef}", "CONTENT_ID=${meta.contentId}"]) {
                                    sh 'infra/jenkins/scripts/with-credentials.sh CONNECTION_TOKEN_SECRET_FILE -- infra/deploy/scripts/deploy-component.sh'
                                }
                            }
                    }
                }
            }
            stage('Verify Game') {
                ws("/home/jenkins/agent/unity/workspaces/${safeJob}/linux-server") {
                    withEnv(["CI_ARTIFACT_DIR=${pwd()}/artifacts/game"]) {
                            withEnv(["DEPLOY_TARGET=dev-game", "RELEASE_ID=game-${env.GIT_COMMIT}-${env.BUILD_NUMBER}", 'COMPONENT_VERIFY_COMMAND=docker compose --project-name festa-dev-game --file infra/deploy/compose/dev/game.compose.yaml ps --status running --services | grep -qx game']) {
                                sh 'infra/jenkins/scripts/with-credentials.sh -- infra/deploy/scripts/verify-component.sh'
                            }
                    }
                }
            }
        }
    }
}
return this
