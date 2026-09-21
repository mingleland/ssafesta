String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"

pipelineJob('festa-world-package-publish') {
    description(
        'FALLBACK/HISTORICAL (Batch 2): manually preserve an already Demo-approved World Docker image as an immutable GitLab Generic Package. ' +
        'The normal path is festa-gitlab-develop/develop, which publishes festa-world/<8sha> automatically. ' +
        'This job never builds or rebuilds Unity/Docker artifacts.'
    )

    parameters {
        stringParam('SOURCE_COMMIT', '', 'Full 40-character source commit of the Demo-approved World image')
        stringParam('IMAGE_REF', '', 'Exact Demo-approved Docker image ref')
        stringParam('CONTENT_ID', '', 'Exact Demo-approved Docker image content ID (sha256:...)')
        stringParam('PUBLISHED_BY', '', 'Named human operator performing immutable package publication')
    }

    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(repositoryUrl)
                        credentials(checkoutCredential)
                    }
                    branches('*/develop')
                    extensions {
                        cloneOptions {
                            shallow(true)
                            depth(1)
                            noTags(true)
                        }
                    }
                }
            }

            scriptPath('infra/jenkins/pipelines/world-package-publish.groovy')
            lightweight(true)
        }
    }

    logRotator {
        numToKeep(20)
    }
}
