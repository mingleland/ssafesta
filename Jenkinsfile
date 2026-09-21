pipeline {
    agent { label 'linux-docker' }

    parameters {
        // resolve-game-artifacts.sh 는 unitySourceSha 로 먼저 찾고, 없으면 unityInputId 가 같은
        // 최근 Registry 패키지를 뒤진다. 그 탐색이 비면 번들이 있는데도 WAITING_FOR_UNITY_ARTIFACT
        // 로 끝난다 — build #542 가 그랬다 (unitySourceSha=7a49835c, 번들은 a8f898e8). 그때
        // 운영자가 번들의 source commit 을 직접 지정한다. 비워 두면 기존 자동 탐색 그대로다 (#259).
        string(name: 'UNITY_ARTIFACT_CANDIDATE', defaultValue: '', description: 'Unity Release Bundle 의 full 40자 source commit. 비우면 자동 탐색.')
        // detector 는 이번 push 의 diff 로만 game 배포를 고른다. 배포를 유발한 push 가 실패로 끝나고
        // develop 이 그 앞으로 지나가 버리면(build #535 → #542) 그 번들을 Demo 로 올릴 트리거가 사라진다.
        // 그때 운영자가 이 값으로 game 구간을 연다. 기본값은 false 라 평소 동작은 그대로다.
        // 주의: true 로 돌리면 Demo World 컨테이너가 재생성되어 접속자가 끊긴다 (#228).
        booleanParam(name: 'DEPLOY_GAME_TO_DEMO', defaultValue: false, description: 'Demo World/WebGL 을 이번 실행에서 교체한다. 접속자가 끊긴다.')
    }

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
