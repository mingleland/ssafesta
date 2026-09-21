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
        stage('Prepare Build Artifacts') {
            steps {
                // Jenkins workspace는 build 간 재사용된다. 이전 build의 artifacts를 남기면
                // docs-only build도 오래된 release manifest를 자기 산출물처럼 archive한다.
                sh 'rm -rf artifacts && mkdir -p artifacts'
            }
        }
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
    }
}
