String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"

pipelineJob('festa-webgl-package-deploy') {
    description('Deploy an immutable, QA-complete Unity WebGL Generic Package to the EC2 Nginx release path.')
    parameters {
        stringParam('RELEASE_ID', '', 'Immutable Generic Package version/release ID')
        stringParam('ARTIFACT_SHA256', '', 'Expected lowercase SHA-256 of festa-webgl-release-<RELEASE_ID>.zip')
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
                    extensions { cloneOptions { shallow(true); depth(1); noTags(true) } }
                }
            }
            scriptPath('infra/jenkins/pipelines/webgl-package-deploy.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
