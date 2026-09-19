pipeline {
    agent { label 'deploy' }
    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 45, unit: 'MINUTES')
    }
    stages {
        stage('Resolve Approved Demo Receipt') {
            steps {
                script {
                    String receiptId = params.RECEIPT_ID?.trim()
                    String worldHost = params.PRODUCTION_WORLD_HOST?.trim()
                    if (!(receiptId ==~ /[A-Za-z0-9][A-Za-z0-9_.-]{0,127}/)) { error('Invalid RECEIPT_ID') }
                    if (!worldHost) { error('PRODUCTION_WORLD_HOST is required') }
                    String stateRoot = env.ENVIRONMENT_STATE_DIR ?: '/var/lib/festa-environments'
                    String receipt = "${stateRoot}/production/receipts/${receiptId}.json"
                    if (!fileExists(receipt)) { error("Approved Demo receipt does not exist: ${receipt}") }
                    env.PRODUCTION_RECEIPT_PATH = receipt
                    env.PRODUCTION_RECEIPT_ID = receiptId
                    env.PRODUCTION_WORLD_HOST_VALUE = worldHost
                    env.PRODUCTION_DATA_EVIDENCE_PATH = "${stateRoot}/production/data/bootstrap.json"
                }
            }
        }
        stage('Validate Receipt and Main Ancestry') {
            steps {
                sh '''
                    PRODUCTION_PROMOTION_RECEIPT_PATH="$PRODUCTION_RECEIPT_PATH" \
                      infra/jenkins/scripts/validate-production-promotion.sh >/dev/null
                    infra/deploy/scripts/validate-production-main-ancestry.sh \
                      "$PRODUCTION_RECEIPT_PATH" HEAD
                '''
            }
        }
        stage('Deploy Exact Production Candidate') {
            steps {
                lock(resource: 'deploy-production') {
                    script {
                        String packageReadCredential = env.GITLAB_PACKAGE_READ_CREDENTIAL_ID ?: 'gitlab-package-read'
                        String backEnvCredential = env.PROD_BACK_ENV_CREDENTIAL_ID ?: 'festa-prod-back-env'
                        String aiEnvCredential = env.PROD_AI_ENV_CREDENTIAL_ID ?: 'festa-prod-ai-env'
                        String springToAiCredential = env.PROD_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID ?: 'festa-prod-internal-spring-to-ai-tokens'
                        String aiToSpringCredential = env.PROD_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID ?: 'festa-prod-internal-ai-to-spring-tokens'
                        String infraToSpringCredential = env.PROD_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID ?: 'festa-prod-internal-infra-to-spring-tokens'
                        String worldSecretFile = env.PROD_CONNECTION_TOKEN_SECRET_FILE ?: '/opt/festa/secrets/prod-game-connection-token-secret'
                        withCredentials([
                            usernamePassword(credentialsId: packageReadCredential, usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN'),
                            file(credentialsId: backEnvCredential, variable: 'BACK_ENV_FILE'),
                            file(credentialsId: aiEnvCredential, variable: 'AI_ENV_FILE'),
                            string(credentialsId: springToAiCredential, variable: 'INTERNAL_SPRING_TO_AI_TOKENS'),
                            string(credentialsId: aiToSpringCredential, variable: 'INTERNAL_AI_TO_SPRING_TOKENS'),
                            string(credentialsId: infraToSpringCredential, variable: 'INTERNAL_INFRA_TO_SPRING_TOKENS')
                        ]) {
                            withEnv([
                                "PRODUCTION_WORLD_HOST=${env.PRODUCTION_WORLD_HOST_VALUE}",
                                "PRODUCTION_DATA_EVIDENCE_PATH=${env.PRODUCTION_DATA_EVIDENCE_PATH}",
                                "CONNECTION_TOKEN_SECRET_FILE=${worldSecretFile}"
                            ]) {
                                sh '''
                                    infra/jenkins/scripts/with-credentials.sh \
                                      GITLAB_DEPLOY_TOKEN BACK_ENV_FILE AI_ENV_FILE \
                                      INTERNAL_SPRING_TO_AI_TOKENS INTERNAL_AI_TO_SPRING_TOKENS INTERNAL_INFRA_TO_SPRING_TOKENS \
                                      -- infra/deploy/scripts/deploy-production-candidate.sh "$PRODUCTION_RECEIPT_PATH"
                                '''
                            }
                        }
                    }
                }
            }
        }
        stage('Verify Candidate') {
            steps {
                sh 'infra/deploy/scripts/verify-production-candidate.sh "$PRODUCTION_RECEIPT_PATH"'
            }
        }
        stage('Archive Evidence') {
            steps {
                sh '''
                    mkdir -p artifacts/production-promotion
                    cp "$PRODUCTION_RECEIPT_PATH" artifacts/production-promotion/receipt.json
                    cp "$PRODUCTION_DATA_EVIDENCE_PATH" artifacts/production-promotion/data-bootstrap.json
                    cp "$ENVIRONMENT_STATE_DIR/production/candidates/$PRODUCTION_RECEIPT_ID.json" artifacts/production-promotion/candidate.json
                    cp "$ENVIRONMENT_STATE_DIR/production/candidates/$PRODUCTION_RECEIPT_ID.verification.json" artifacts/production-promotion/verification.json
                '''
            }
        }
    }
    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path artifacts'
            archiveArtifacts artifacts: 'artifacts/production-promotion/**/*', allowEmptyArchive: true, fingerprint: true
        }
    }
}
