String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"

pipelineJob('festa-demo-promotion') {
    description('Manually promote the current fully dev-verified release to demo.')
    parameters {
        stringParam('APPROVED_BY', '', 'Release approver identity recorded with this promotion')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote { url(repositoryUrl); credentials(checkoutCredential) }
                    branches('*/develop')
                    extensions { cloneOptions { shallow(true); depth(1); noTags(true) } }
                }
            }
            scriptPath('infra/jenkins/pipelines/demo-promotion.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
