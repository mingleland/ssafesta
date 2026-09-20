String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"

pipelineJob('festa-production-promotion') {
    description('Consume one human-approved Demo Promotion Receipt from main, activate the exact artifacts, verify public Production, and require human approval before known-good. No application, Docker, Unity, or WebGL build is permitted.')
    parameters {
        stringParam('RECEIPT_ID', '', 'Approved receipt under production/receipts')
        stringParam('PRODUCTION_WORLD_HOST', '', 'Production World hostname reserved for public cutover')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote { url(repositoryUrl); credentials(checkoutCredential) }
                    branches('*/main')
                    extensions { cloneOptions { shallow(false); noTags(false) } }
                }
            }
            scriptPath('infra/jenkins/pipelines/production-promotion.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
