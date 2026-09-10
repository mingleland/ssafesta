pipeline {
    agent { label 'deploy' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
    }

    stages {
        stage('Deploy WebGL Package') {
            steps {
                script {
                    String releaseId = params.RELEASE_ID?.trim()
                    String artifactSha = params.ARTIFACT_SHA256?.trim()?.toLowerCase()
                    if (!(releaseId ==~ /[A-Za-z0-9][A-Za-z0-9._-]{0,63}/)) { error('Invalid RELEASE_ID') }
                    if (!(artifactSha ==~ /[0-9a-f]{64}/)) { error('Invalid ARTIFACT_SHA256') }
                    String api = env.GITLAB_API_V4_URL ?: 'https://lab.ssafy.com/api/v4'
                    String projectId = env.GITLAB_PROJECT_ID ?: '1443023'
                    String credentialId = env.GITLAB_PACKAGE_READ_CREDENTIAL_ID ?: 'gitlab-package-read'
                    String filename = "festa-webgl-release-${releaseId}.zip"
                    String packageUrl = "${api}/projects/${projectId}/packages/generic/festa-webgl/${releaseId}/${filename}"
                    lock(resource: 'deploy-dev-game') {
                        withCredentials([usernamePassword(credentialsId: credentialId,
                            usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN')]) {
                            withEnv(["WEBGL_RELEASE_ID=${releaseId}", "WEBGL_ARTIFACT_SHA256=${artifactSha}",
                                     "WEBGL_PACKAGE_URL=${packageUrl}",
                                     "WEBGL_EVIDENCE_PATH=${pwd()}/artifacts/webgl-deployment.json"]) {
                                sh '''infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- \
                                    infra/jenkins/scripts/deploy-webgl-release.sh \
                                    --release-id "$WEBGL_RELEASE_ID" \
                                    --sha256 "$WEBGL_ARTIFACT_SHA256" \
                                    --package-url "$WEBGL_PACKAGE_URL"'''
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'artifacts/webgl-deployment.json', allowEmptyArchive: true, fingerprint: true
        }
    }
}
