def owner = System.getenv('GITHUB_REPOSITORY_OWNER') ?: 'SET_IN_SEED_JOB'
def repository = System.getenv('GITHUB_REPOSITORY_NAME') ?: 'SET_IN_SEED_JOB'
def credentialsId = System.getenv('GITHUB_CREDENTIALS_ID') ?: 'github-scm'
multibranchPipelineJob('festa-components') {
  branchSources {
    github {
      id('festa-github-components')
      repoOwner(owner)
      repository(repository)
      scanCredentialsId(credentialsId)
      buildOriginBranchWithPR(false)
      buildOriginPRMerge(false)
      buildForkPRMerge(false)
      traits { headWildcardFilter { includes('ai back front game'); excludes('develop main master') } }
    }
  }
  factory { workflowBranchProjectFactory { scriptPath('Jenkinsfile') } }
  orphanedItemStrategy { discardOldItems { numToKeep(20) } }
  triggers { periodicFolderTrigger { interval('1d') } }
}
