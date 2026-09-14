String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'

pipelineJob('festa-postgres-backup') {
    description('Manually create a checksum-protected PostgreSQL backup in the private R2 backup bucket.')
    parameters {
        choiceParam('ENVIRONMENT', ['dev', 'demo'], 'Source environment')
        choiceParam('TIER', ['manual-test', 'daily', 'weekly', 'pre-migration'], 'Backup retention tier')
        stringParam('DOCUMENT_INVENTORY_REF', '', 'Required inventory reference; document bodies are never copied to the backup bucket')
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
            scriptPath('infra/jenkins/pipelines/postgres-backup.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
