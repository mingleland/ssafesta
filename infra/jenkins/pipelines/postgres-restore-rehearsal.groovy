pipeline {
    agent { label 'deploy' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {
        stage('Restore and Verify Disposable PostgreSQL Databases') {
            steps {
                script {
                    String manifestKey = params.MANIFEST_KEY?.trim()
                    String target = params.TARGET?.trim()
                    if (!manifestKey) { error('MANIFEST_KEY is required') }
                    if (!(target ==~ /disposable-[a-z0-9][a-z0-9-]{0,62}/)) { error('TARGET must start with disposable-') }
                    String artifact = "${pwd()}/artifacts/postgres-restore/result.json"
                    String readerCredentialId = env.R2_POSTGRES_BACKUP_READER_CREDENTIAL_ID ?: 'r2-postgres-backup-reader'
                    String postgresCredentialId = env.POSTGRES_BACKUP_ADMIN_PASSWORD_CREDENTIAL_ID ?: 'postgres-backup-admin-password'
                    withCredentials([
                        usernamePassword(credentialsId: readerCredentialId,
                            usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY'),
                        string(credentialsId: postgresCredentialId, variable: 'PGPASSWORD')
                    ]) {
                        withEnv(["BACKUP_MANIFEST_KEY=${manifestKey}", "RESTORE_TARGET=${target}", "POSTGRES_RESTORE_RESULT=${artifact}"]) {
                            sh '''mkdir -p "$(dirname "$POSTGRES_RESTORE_RESULT")"
                                infra/jenkins/scripts/with-credentials.sh AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY PGPASSWORD -- \
                                  bash -c 'infra/environments/postgres/backup/restore.sh --target "$RESTORE_TARGET" --manifest-key "$BACKUP_MANIFEST_KEY" && infra/environments/postgres/backup/verify.sh --target "$RESTORE_TARGET"' \
                                  >"$POSTGRES_RESTORE_RESULT"'''
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path artifacts'
            archiveArtifacts artifacts: 'artifacts/postgres-restore/result.json', allowEmptyArchive: true, fingerprint: true
        }
    }
}
