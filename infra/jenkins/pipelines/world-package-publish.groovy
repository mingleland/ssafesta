pipeline {
    agent { label 'deploy' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 20, unit: 'MINUTES')
    }

    stages {
        stage('Validate Demo World Identity') {
            steps {
                script {
                    String sourceCommit = params.SOURCE_COMMIT?.trim()
                    String imageRef = params.IMAGE_REF?.trim()
                    String contentId = params.CONTENT_ID?.trim()?.toLowerCase()
                    String publishedBy = params.PUBLISHED_BY?.trim()

                    if (!(sourceCommit ==~ /[0-9a-f]{40}/)) {
                        error('SOURCE_COMMIT must be a full lowercase SHA')
                    }

                    if (!imageRef) {
                        error('IMAGE_REF is required')
                    }

                    if (!(contentId ==~ /sha256:[0-9a-f]{64}/)) {
                        error('CONTENT_ID must be a lowercase sha256 content ID')
                    }

                    if (!(publishedBy ==~ /[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}/)) {
                        error('PUBLISHED_BY is invalid')
                    }

                    env.WORLD_SOURCE_COMMIT = sourceCommit
                    env.WORLD_IMAGE_REF = imageRef
                    env.WORLD_CONTENT_ID = contentId
                    env.WORLD_PUBLISHED_BY = publishedBy
                    env.WORLD_RELEASE_ID = sourceCommit.substring(0, 8)

                    sh '''
                        PYTHON_BIN=python3 \
                          infra/jenkins/scripts/validate-world-package-source.sh
                    '''
                }
            }
        }

        stage('Publish Immutable World Package') {
            steps {
                lock(resource: 'deploy-dev-game') {
                    script {
                        String credentialId =
                            env.GITLAB_PACKAGE_WRITE_CREDENTIAL_ID ?: 'gitlab-package-write'

                        String api =
                            env.GITLAB_API_V4_URL ?: 'https://lab.ssafy.com/api/v4'

                        String projectId =
                            env.GITLAB_PROJECT_ID ?: '1443023'

                        String filename =
                            "festa-world-release-${env.WORLD_RELEASE_ID}.tar"

                        String packageUrl =
                            "${api}/projects/${projectId}/packages/generic/festa-world/" +
                            "${env.WORLD_RELEASE_ID}/${filename}"

                        sh 'mkdir -p artifacts/world-package'

                        withCredentials([
                            string(
                                credentialsId: credentialId,
                                variable: 'GITLAB_PACKAGE_TOKEN'
                            )
                        ]) {
                            String output = sh(
                                returnStdout: true,
                                script: '''
                                    infra/jenkins/scripts/with-credentials.sh \
                                      GITLAB_PACKAGE_TOKEN -- \
                                      infra/jenkins/scripts/publish-world-release.sh \
                                        --source-commit "$WORLD_SOURCE_COMMIT" \
                                        --image-ref "$WORLD_IMAGE_REF" \
                                        --content-id "$WORLD_CONTENT_ID"
                                '''
                            ).trim()

                            List<String> resultLines =
                                output.readLines().findAll {
                                    it.startsWith('PUBLISHED_WORLD_RELEASE:') ||
                                    it.startsWith('WORLD_RELEASE_EXISTS:')
                                }

                            if (resultLines.size() != 1) {
                                error(
                                    'World publisher did not return exactly one publication result'
                                )
                            }

                            String resultLine = resultLines[0]
                            List<String> parts = resultLine.tokenize(' ')

                            if (parts.size() != 4) {
                                error('Invalid World publication result')
                            }

                            String archiveSha = parts[2]
                            String returnedContentId = parts[3]

                            if (!(archiveSha ==~ /[0-9a-f]{64}/)) {
                                error('World publisher returned an invalid archive SHA-256')
                            }

                            if (returnedContentId != env.WORLD_CONTENT_ID) {
                                error('World publisher returned another image content ID')
                            }

                            writeJSON(
                                file: 'artifacts/world-package/world-package.json',
                                pretty: 2,
                                json: [
                                    schemaVersion: '1.0.0',
                                    packageName: 'festa-world',
                                    packageVersion: env.WORLD_RELEASE_ID,
                                    packageUrl: packageUrl,
                                    archiveSha256: archiveSha,
                                    sourceCommit: env.WORLD_SOURCE_COMMIT,
                                    sourceBranch: 'develop',
                                    imageRef: env.WORLD_IMAGE_REF,
                                    imageContentId: env.WORLD_CONTENT_ID,
                                    publishedBy: env.WORLD_PUBLISHED_BY,
                                    publicationResult: parts[0].replace(':', '')
                                ]
                            )

                            echo resultLine
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path .'

            archiveArtifacts(
                artifacts: 'artifacts/world-package/world-package.json',
                allowEmptyArchive: true,
                fingerprint: true
            )
        }
    }
}
