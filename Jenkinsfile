pipeline {
    agent { label 'linux-docker' }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // Unity 빌드는 단독으로 45분을 넘고 실행기가 하나다. 60분이면 큐가 조금만 붐벼도
        // 이미 끝난 앱 배포까지 ABORT 로 묶여 버린다 (2026-09-18 #468 실측).
        timeout(time: 180, unit: 'MINUTES')
    }

    stages {
        stage('Agent Preflight') {
            steps { sh 'infra/jenkins/scripts/check-agent-capabilities.sh' }
        }
        stage('Security Preflight') {
            steps { sh 'infra/jenkins/scripts/secret-scan.sh --tracked --path .' }
        }
        stage('Develop Push Dispatch') {
            steps {
                script {
                    final String branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '').replaceFirst(/^origin\//, '')
                    if (branch != 'develop') { error("Only develop push builds are supported: ${branch}") }
                    load('infra/jenkins/pipelines/develop.groovy').call()
                }
            }
        }
    }

    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path artifacts'
            archiveArtifacts artifacts: 'artifacts/**/*', allowEmptyArchive: true, fingerprint: true
        }
        // 실패가 Jenkins 안에만 남으면 인프라 담당 한 사람만 본다. 파트 CI 실패도 여기서 터진다.
        unsuccessful {
            script {
                final Closure notify = {
                    withEnv(["BUILD_RESULT=${currentBuild.currentResult}"]) {
                        sh 'infra/jenkins/scripts/notify-build-failure.sh'
                    }
                }
                final String hookCredential = (env.MATTERMOST_CREDENTIALS_ID ?: '').trim()
                try {
                    if (hookCredential.isEmpty()) { notify() }
                    else { withCredentials([string(credentialsId: hookCredential, variable: 'MATTERMOST_WEBHOOK_URL')]) { notify() } }
                } catch (ignored) {
                    // credential 미등록이 아직 기본 상태다. 알림이 이미 실패한 빌드를 한 번 더 죽이면 안 된다.
                    try { notify() } catch (ignoredAgain) { echo 'NOTIFY_UNSENT: 실패 알림을 실행하지 못했다' }
                }
            }
        }
    }
}
