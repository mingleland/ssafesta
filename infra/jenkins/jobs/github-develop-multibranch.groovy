multibranchPipelineJob('festa/develop') {
    displayName('FESTA develop integration')
    branchSources {
        github {
            id('festa-github-develop')
            repoOwner(System.getenv('GITHUB_REPOSITORY_OWNER') ?: 'CONFIGURE_ME')
            repository(System.getenv('GITHUB_REPOSITORY_NAME') ?: 'CONFIGURE_ME')
            credentialsId(System.getenv('GITHUB_CREDENTIALS_ID') ?: 'github-scm')
            traits { headWildcardFilter { includes('develop'); excludes('') } }
        }
    }
    factory { workflowBranchProjectFactory { scriptPath('Jenkinsfile') } }
    orphanedItemStrategy { discardOldItems { numToKeep(10) } }
}
