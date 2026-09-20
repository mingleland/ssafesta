String gitlabServerName = System.getenv('GITLAB_SERVER_NAME') ?: 'CONFIGURE_ME'
String gitlabApiCredentialsId = System.getenv('GITLAB_API_CREDENTIALS_ID') ?: 'gitlab-api'
String gitlabProjectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String gitlabProjectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String gitlabProjectFullPath = "${gitlabProjectOwner}/${gitlabProjectPath}"

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
                    projectPath(gitlabProjectFullPath)
                    traits {
                        // strategyId 3 = 모든 브랜치. 1("MR 로도 올라온 브랜치 제외") 은 develop→main promotion MR 이
                        // 열리는 순간 develop 을 discovery 에서 빼 child 를 Dead 로 만든다 (T-168, 2026-09-20 !1231 재현).
                        // develop 만 보는 것은 아래 wildcard filter 가 보장한다.
                        gitLabBranchDiscovery { strategyId(3) }
                        gitlabAvatar { disableProjectAvatar(true) }
                        headWildcardFilter { includes('develop'); excludes('') }
                    }
                }
            }
        }
    }
    factory { workflowBranchProjectFactory { scriptPath('Jenkinsfile') } }
    orphanedItemStrategy { discardOldItems { numToKeep(10) } }
}
