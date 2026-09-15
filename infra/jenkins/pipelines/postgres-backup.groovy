pipeline {
    agent { label 'deploy' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {
        stage('Create PostgreSQL R2 Backup') {
            steps {
                script {
                    String environment = params.ENVIRONMENT?.trim()
                    String tier = params.TIER?.trim()
                    String inventoryRef = params.DOCUMENT_INVENTORY_REF?.trim()
                    if (!(environment in ['dev', 'demo'])) { error('ENVIRONMENT must be dev or demo') }
                    if (!(tier in ['manual-test', 'daily', 'weekly', 'pre-migration'])) { error('Invalid TIER') }
                    if (!inventoryRef) { error('DOCUMENT_INVENTORY_REF is required') }
                    String releaseId = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    String artifact = "${pwd()}/artifacts/postgres-backup/result.json"
                    String writerCredentialId = env.R2_POSTGRES_BACKUP_WRITER_CREDENTIAL_ID ?: 'r2-postgres-backup-writer'
                    String postgresCredentialId = env.POSTGRES_BACKUP_ADMIN_PASSWORD_CREDENTIAL_ID ?: 'postgres-backup-admin-password'
                    withCredentials([
                        usernamePassword(credentialsId: writerCredentialId,
                            usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY'),
                        string(credentialsId: postgresCredentialId, variable: 'PGPASSWORD')
                    ]) {
                        withEnv(["RELEASE_ID=${releaseId}", "DOCUMENT_INVENTORY_REF=${inventoryRef}", "POSTGRES_BACKUP_RESULT=${artifact}"]) {
                            sh '''mkdir -p "$(dirname "$POSTGRES_BACKUP_RESULT")"
                                infra/jenkins/scripts/with-credentials.sh AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY PGPASSWORD -- \
                                  bash infra/environments/postgres/backup/dump.sh --environment "$ENVIRONMENT" --tier "$TIER" \
                                  >"$POSTGRES_BACKUP_RESULT"'''
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path artifacts'
            archiveArtifacts artifacts: 'artifacts/postgres-backup/result.json', allowEmptyArchive: true, fingerprint: true
        }
    }
}
