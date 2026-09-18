def sourceSha = (params.SOURCE_SHA ?: '').trim()
if (!(sourceSha ==~ /^[0-9a-f]{40}$/)) { error('SOURCE_SHA must be a full lowercase SHA') }

def sourceBranch = (params.SOURCE_BRANCH ?: '').trim()
if (!(sourceBranch ==~ /^(feat|feature|fix|refactor|test|docs|chore|build|ci|hotfix|perf)\/.+/)) {
    error('SOURCE_BRANCH must be an allowed feature branch')
}

def safeJob = env.JOB_NAME.replaceAll(/[^A-Za-z0-9_.-]/, '_')
def artifactDir = "artifacts/unity-mr/${env.BUILD_NUMBER}"
def serverUrl = env.GITLAB_SERVER_URL ?: 'https://lab.ssafy.com'
def projectOwner = env.GITLAB_PROJECT_OWNER ?: 's15-metaverse-game-sub1'
def projectPath = env.GITLAB_PROJECT_PATH ?: 'S15P21A604'
def repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"
def checkoutCredential = env.GITLAB_CHECKOUT_CREDENTIALS_ID ?: 'gitlab-checkout'

gitlabCommitStatus(name: 'unity-mr-validation') {
    node('unity-6000.0.78f1') {
        ws("/home/jenkins/agent/unity/workspaces/${safeJob}") {
            stage('Checkout MR Head') {
                checkout([$class: 'GitSCM',
                    branches: [[name: sourceSha]],
                    doGenerateSubmoduleConfigurations: false,
                    extensions: [[$class: 'CloneOption', shallow: false, noTags: true, honorRefspec: false]],
                    userRemoteConfigs: [[credentialsId: checkoutCredential, url: repositoryUrl]]
                ])
                sh "test \"\$(git rev-parse HEAD)\" = '${sourceSha}'"
            }
            stage('Unity EditMode') {
                withEnv([
                    'CI_COMPONENT=game',
                    "CI_BRANCH=${sourceBranch}",
                    "CI_COMMIT_SHA=${sourceSha}",
                    "CI_RUN_ID=${safeJob}-${env.BUILD_NUMBER}",
                    "CI_ARTIFACT_DIR=${pwd()}/${artifactDir}"
                ]) {
                    sh 'infra/jenkins/scripts/with-credentials.sh -- bash ci/test'
                }
            }
            junit allowEmptyResults: true, testResults: "${artifactDir}/unity-editmode-results.xml"
            archiveArtifacts artifacts: "${artifactDir}/**", allowEmptyArchive: true, fingerprint: true
        }
    }
}
