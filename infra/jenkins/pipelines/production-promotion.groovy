def withProductionCredentials(Closure body) {
    String packageReadCredential = env.GITLAB_PACKAGE_READ_CREDENTIAL_ID ?: 'gitlab-package-read'
    String backEnvCredential = env.PROD_BACK_ENV_CREDENTIAL_ID ?: 'festa-prod-back-env'
    String aiEnvCredential = env.PROD_AI_ENV_CREDENTIAL_ID ?: 'festa-prod-ai-env'
    String springToAiCredential = env.PROD_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID ?: 'festa-prod-internal-spring-to-ai-tokens'
    String aiToSpringCredential = env.PROD_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID ?: 'festa-prod-internal-ai-to-spring-tokens'
    String infraToSpringCredential = env.PROD_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID ?: 'festa-prod-internal-infra-to-spring-tokens'
    withCredentials([
        usernamePassword(credentialsId: packageReadCredential, usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN'),
        file(credentialsId: backEnvCredential, variable: 'BACK_ENV_FILE'),
        file(credentialsId: aiEnvCredential, variable: 'AI_ENV_FILE'),
        string(credentialsId: springToAiCredential, variable: 'INTERNAL_SPRING_TO_AI_TOKENS'),
        string(credentialsId: aiToSpringCredential, variable: 'INTERNAL_AI_TO_SPRING_TOKENS'),
        string(credentialsId: infraToSpringCredential, variable: 'INTERNAL_INFRA_TO_SPRING_TOKENS')
    ]) { body() }
}

pipeline {
    agent { label 'deploy' }
    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Resolve Approved Demo Receipt') {
            steps {
                script {
                    String receiptId = params.RECEIPT_ID?.trim()
                    String worldHost = params.PRODUCTION_WORLD_HOST?.trim()
                    if (!(receiptId ==~ /[A-Za-z0-9][A-Za-z0-9_.-]{0,127}/)) { error('Invalid RECEIPT_ID') }
                    if (!worldHost) { error('PRODUCTION_WORLD_HOST is required') }
                    if (!env.ROOT_DOMAIN?.trim()) { error('ROOT_DOMAIN is required') }
                    String stateRoot = env.ENVIRONMENT_STATE_DIR ?: '/var/lib/festa-environments'
                    env.ENVIRONMENT_STATE_DIR = stateRoot
                    String receipt = "${stateRoot}/production/receipts/${receiptId}.json"
                    if (!fileExists(receipt)) { error("Approved Demo receipt does not exist: ${receipt}") }
                    env.PRODUCTION_RECEIPT_PATH = receipt
                    env.PRODUCTION_RECEIPT_ID = receiptId
                    env.PRODUCTION_WORLD_HOST_VALUE = worldHost
                    env.NGINX_ORIGIN_CERTIFICATE_FILE = env.NGINX_ORIGIN_CERTIFICATE_FILE ?: '/etc/nginx/tls/world-dev-origin.pem'
                    env.NGINX_ORIGIN_PRIVATE_KEY_FILE = env.NGINX_ORIGIN_PRIVATE_KEY_FILE ?: '/etc/nginx/tls/world-dev-origin.key'
                    env.PRODUCTION_DATA_EVIDENCE_PATH = "${stateRoot}/production/data/bootstrap.json"
                    env.PRODUCTION_PUBLIC_BASE_URL = "https://${env.ROOT_DOMAIN}"
                    env.PRODUCTION_WORLD_PUBLIC_URL = "wss://${worldHost}/"
                }
            }
        }
        stage('Validate Receipt, Bootstrap and Main Ancestry') {
            steps {
                sh '''
                    PRODUCTION_PROMOTION_RECEIPT_PATH="$PRODUCTION_RECEIPT_PATH" \
                      infra/jenkins/scripts/validate-production-promotion.sh >/dev/null
                    infra/deploy/scripts/validate-production-main-ancestry.sh \
                      "$PRODUCTION_RECEIPT_PATH" HEAD
                    python3 - "$PRODUCTION_DATA_EVIDENCE_PATH" <<'PY'
import json,pathlib,sys
d=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
r=d.get('redis',{})
if d.get('schemaVersion')!='1.1.0' or d.get('state')!='READY': raise SystemExit('Production bootstrap evidence is not ready')
if r.get('aiKeyPattern')!='prod:ai:*' or r.get('conversationKeyPattern')!='conversation:*' or r.get('backKeyPattern')!='prod:*': raise SystemExit('Production Redis evidence mismatch')
PY
                '''
            }
        }
        stage('Detect Idempotent Receipt') {
            steps {
                script {
                    env.PRODUCTION_ALREADY_APPROVED = sh(returnStdout: true, script: '''
                        python3 - "$ENVIRONMENT_STATE_DIR/production/current.json" \
                          "$ENVIRONMENT_STATE_DIR/production/known-good.json" "$PRODUCTION_RECEIPT_ID" <<'PY'
import json,pathlib,sys
current,known_good=map(pathlib.Path,sys.argv[1:3]); receipt_id=sys.argv[3]
if current.exists() != known_good.exists(): raise SystemExit('Production current/known-good state is inconsistent')
if not current.exists(): print('0'); raise SystemExit(0)
c=json.loads(current.read_text(encoding='utf-8')); k=json.loads(known_good.read_text(encoding='utf-8'))
print('1' if c.get('receiptId')==receipt_id and k.get('receiptId')==receipt_id and k.get('state')=='KNOWN_GOOD' else '0')
PY
                    ''').trim()
                }
            }
        }
        stage('Cutover Readiness Gate') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                script {
                    env.CUTOVER_APPROVED_BY = input(message: "Enter maintenance and replace legacy Production for ${env.PRODUCTION_RECEIPT_ID}?", ok: 'Begin cutover', submitterParameter: 'CUTOVER_APPROVED_BY') as String
                }
            }
        }
        stage('Maintenance Fence') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                lock(resource: 'deploy-production') {
                    sh 'infra/deploy/scripts/prepare-production-cutover.sh "$PRODUCTION_RECEIPT_PATH"'
                }
            }
        }
        stage('Replace Legacy with Canonical Candidate') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                lock(resource: 'deploy-production') {
                    script {
                        String worldSecretFile = env.PROD_CONNECTION_TOKEN_SECRET_FILE ?: '/opt/festa/secrets/prod-game-connection-token-secret'
                        withProductionCredentials {
                            withEnv(["PRODUCTION_WORLD_HOST=${env.PRODUCTION_WORLD_HOST_VALUE}", "PRODUCTION_DATA_EVIDENCE_PATH=${env.PRODUCTION_DATA_EVIDENCE_PATH}", "CONNECTION_TOKEN_SECRET_FILE=${worldSecretFile}"]) {
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
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps { sh 'infra/deploy/scripts/verify-production-candidate.sh "$PRODUCTION_RECEIPT_PATH"' }
        }
        stage('Activate Public Production') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                lock(resource: 'deploy-production') {
                    withEnv(["PRODUCTION_WORLD_HOST=${env.PRODUCTION_WORLD_HOST_VALUE}"]) {
                        sh 'infra/deploy/scripts/activate-production-release.sh "$PRODUCTION_RECEIPT_PATH"'
                    }
                }
            }
        }
        stage('Verify Public Production') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                withEnv(["PRODUCTION_WORLD_PUBLIC_URL=${env.PRODUCTION_WORLD_PUBLIC_URL}"]) {
                    sh 'infra/deploy/scripts/verify-production-public.sh "$PRODUCTION_RECEIPT_PATH"'
                }
            }
        }
        stage('Human Verification Gate') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                script {
                    env.PRODUCTION_APPROVED_BY = input(message: "Approve public Production ${env.PRODUCTION_RECEIPT_ID} as known-good?", ok: 'Approve known-good', submitterParameter: 'PRODUCTION_APPROVED_BY') as String
                }
            }
        }
        stage('Approve Known Good') {
            when { expression { env.PRODUCTION_ALREADY_APPROVED != '1' } }
            steps {
                script {
                    sh 'APPROVED_BY="$PRODUCTION_APPROVED_BY" infra/deploy/scripts/approve-production-known-good.sh "$PRODUCTION_RECEIPT_ID"'
                    env.PRODUCTION_ALREADY_APPROVED = '1'
                }
            }
        }
        stage('Archive Evidence') {
            steps {
                sh '''
                    mkdir -p artifacts/production-promotion
                    cp "$PRODUCTION_RECEIPT_PATH" artifacts/production-promotion/receipt.json
                    cp "$PRODUCTION_DATA_EVIDENCE_PATH" artifacts/production-promotion/data-bootstrap.json
                    find "$ENVIRONMENT_STATE_DIR/production" -type f \
                      \\( -name "$PRODUCTION_RECEIPT_ID*.json" -o -name 'current.json' -o -name 'known-good.json' -o -name 'previous.json' \\) \
                      -exec cp {} artifacts/production-promotion/ \\;
                '''
            }
        }
    }
    post {
        failure {
            script {
                String prepare = "${env.ENVIRONMENT_STATE_DIR ?: '/var/lib/festa-environments'}/production/cutovers/${env.PRODUCTION_RECEIPT_ID}.prepare.json"
                if (env.PRODUCTION_ALREADY_APPROVED != '1' && env.PRODUCTION_RECEIPT_PATH && fileExists(prepare)) {
                    String stateRoot = env.ENVIRONMENT_STATE_DIR ?: '/var/lib/festa-environments'
                    if (fileExists("${stateRoot}/production/previous.json")) {
                        String worldSecretFile = env.PROD_CONNECTION_TOKEN_SECRET_FILE ?: '/opt/festa/secrets/prod-game-connection-token-secret'
                        withProductionCredentials {
                            withEnv(["PRODUCTION_WORLD_HOST=${env.PRODUCTION_WORLD_HOST_VALUE}", "CONNECTION_TOKEN_SECRET_FILE=${worldSecretFile}"]) {
                                sh 'infra/deploy/scripts/rollback-production-release.sh "$PRODUCTION_RECEIPT_PATH" pipeline-failure'
                            }
                        }
                    } else {
                        withEnv(["PRODUCTION_WORLD_HOST=${env.PRODUCTION_WORLD_HOST_VALUE}"]) {
                            sh 'infra/deploy/scripts/rollback-production-release.sh "$PRODUCTION_RECEIPT_PATH" pipeline-failure'
                        }
                    }
                }
            }
        }
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path artifacts'
            archiveArtifacts artifacts: 'artifacts/production-promotion/**/*', allowEmptyArchive: true, fingerprint: true
        }
    }
}
