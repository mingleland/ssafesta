String gitlabServerName = System.getenv('GITLAB_SERVER_NAME') ?: 'CONFIGURE_ME'
String gitlabApiCredentialsId = System.getenv('GITLAB_API_CREDENTIALS_ID') ?: 'gitlab-api'
String gitlabProjectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String gitlabProjectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'

multibranchPipelineJob('festa-gitlab-develop') {
    description('GitLab develop push CI dispatcher; merge requests are gated by GitLab CI, never Jenkins.')
    branchSources {
        branchSource {
            source {
                gitlab {
                    id('festa-gitlab-develop')
                    serverName(gitlabServerName)
                    credentialsId(gitlabApiCredentialsId)
                    projectOwner(gitlabProjectOwner)
                    projectPath(gitlabProjectPath)
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
