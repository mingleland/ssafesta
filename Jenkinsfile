pipeline {
    agent { label 'linux-docker' }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Security Preflight') {
            steps {
                sh 'infra/jenkins/scripts/secret-scan.sh --path .'
            }
        }
        stage('Dispatch') {
            steps {
                script {
                    final String branch = (env.BRANCH_NAME ?: env.GIT_BRANCH ?: '')
                        .replaceFirst(/^origin\//, '')
                    final Set componentBranches = ['ai', 'back', 'front', 'game'] as Set
                    final String pipelinePath

                    if (componentBranches.contains(branch)) {
                        pipelinePath = 'infra/jenkins/pipelines/component.groovy'
                    } else if (branch == 'develop') {
                        pipelinePath = 'infra/jenkins/pipelines/develop.groovy'
                    } else {
                        error("지원하지 않는 브랜치 '${branch}'. 허용: ai, back, front, game, develop")
                    }

                    if (!fileExists(pipelinePath)) {
                        error("파이프라인 구현이 아직 준비되지 않음: ${pipelinePath}")
                    }

                    echo "SCM 제공자와 무관하게 branch=${branch}, pipeline=${pipelinePath}로 실행합니다."
                    def selectedPipeline = load(pipelinePath)
                    if (env.COMPONENT_SECRET_TEXT_CREDENTIAL_ID?.trim()) {
                        withCredentials([string(credentialsId: env.COMPONENT_SECRET_TEXT_CREDENTIAL_ID, variable: 'CI_RUNTIME_SECRET')]) {
                            withEnv(['CI_BOUND_CREDENTIAL_NAMES=CI_RUNTIME_SECRET']) { selectedPipeline.call([scope: branch]) }
                        }
                    } else {
                        selectedPipeline.call([scope: branch])
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path .'
            archiveArtifacts artifacts: 'artifacts/**/*', allowEmptyArchive: true, fingerprint: true
        }
    }
}
