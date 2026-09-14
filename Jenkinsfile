pipeline {
    agent { label 'linux-docker' }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timeout(time: 60, unit: 'MINUTES')
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
    }
}
