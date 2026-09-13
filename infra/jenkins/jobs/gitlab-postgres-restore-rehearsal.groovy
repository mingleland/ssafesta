String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'

pipelineJob('festa-postgres-restore-rehearsal') {
    description('Manually restore and verify a PostgreSQL R2 backup only into disposable databases.')
    parameters {
        stringParam('MANIFEST_KEY', '', 'Required R2 manifest object key')
        stringParam('TARGET', '', 'Required disposable- restore target suffix')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote { url("${serverUrl}/${projectOwner}/${projectPath}.git"); credentials(checkoutCredential) }
                    branches('*/develop')
                    extensions { cloneOptions { shallow(true); depth(1); noTags(true) } }
                }
            }
            scriptPath('infra/jenkins/pipelines/postgres-restore-rehearsal.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
