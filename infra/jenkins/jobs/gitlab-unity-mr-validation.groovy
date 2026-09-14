String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String pipelineBranch = System.getenv('JENKINS_UNITY_MR_PIPELINE_BRANCH') ?: 'develop'
String repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"

pipelineJob('festa-unity-mr-validation') {
    description('Game MR Unity compile and EditMode validation only; never builds, packages, or deploys.')
    parameters {
        stringParam('SOURCE_SHA', '', 'Required full GitLab MR head SHA')
        stringParam('SOURCE_BRANCH', '', 'Required GitLab MR source branch')
        stringParam('MR_IID', '', 'Required GitLab merge request IID for traceability')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote { url(repositoryUrl); credentials(checkoutCredential) }
                    branches("*/${pipelineBranch}")
                    extensions { cloneOptions { shallow(true); depth(1); noTags(true) } }
                }
            }
            scriptPath('infra/jenkins/pipelines/unity-mr-validation.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
