// GitHub jobs와 동일한 Jenkinsfile/stage 계약을 사용한다. 실제 전환 전 test folder에서 생성한다.
String gitlabServerName = System.getenv('GITLAB_SERVER_NAME') ?: 'CONFIGURE_ME'
String gitlabApiCredentialsId = System.getenv('GITLAB_API_CREDENTIALS_ID') ?: 'gitlab-api'
String gitlabProjectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String gitlabProjectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String gitlabProjectFullPath = "${gitlabProjectOwner}/${gitlabProjectPath}"

['ai', 'back', 'front', 'game', 'develop'].each { branchName ->
    multibranchPipelineJob("festa-gitlab-rehearsal/${branchName}") {
        description("GitLab migration rehearsal for ${branchName}; delete rehearsal only after evidence is approved")
        branchSources {
            branchSource {
                source {
                    gitlab {
                        id("festa-gitlab-${branchName}")
                        serverName(gitlabServerName)
                        credentialsId(gitlabApiCredentialsId)
                        projectOwner(gitlabProjectOwner)
                        projectPath(gitlabProjectFullPath)
                        traits {
                            gitLabBranchDiscovery { strategyId(1) }
                            headWildcardFilter { includes(branchName); excludes('') }
                        }
                    }
                }
            }
        }
        factory { workflowBranchProjectFactory { scriptPath('Jenkinsfile') } }
        orphanedItemStrategy { discardOldItems { numToKeep(10) } }
    }
}

// 비교 계약: validate/test/build/package/deploy/verify stage, failure code, manifest schema,
// artifact 이름은 GitHub 기준 실행과 동일해야 한다. 바꾸는 항목은 Branch Source/plugin,
// server/repository, API·checkout credential 및 webhook뿐이다.
