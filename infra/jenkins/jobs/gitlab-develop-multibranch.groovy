String gitlabServerName = System.getenv('GITLAB_SERVER_NAME') ?: 'CONFIGURE_ME'
String credentialsId = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'

multibranchPipelineJob('festa-gitlab-develop') {
    description('GitLab develop push CI dispatcher; merge requests are gated by GitLab CI, never Jenkins.')
    branchSources {
        branchSource {
            source {
                gitlab {
                    id('festa-gitlab-develop')
                    serverName(gitlabServerName)
                    credentialsId(credentialsId)
                    projectOwner(projectOwner)
                    projectPath(projectPath)
                    traits {
                        gitLabBranchDiscovery { strategyId(1) }
                        headWildcardFilter { includes('develop'); excludes('') }
                    }
                }
            }
        }
    }
    factory { workflowBranchProjectFactory { scriptPath('Jenkinsfile') } }
    orphanedItemStrategy { discardOldItems { numToKeep(10) } }
}
