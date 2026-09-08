def call(Map config = [:]) {
    final String component = config.component as String
    final String sourceSha = config.sourceSha as String
    final String artifactDir = config.artifactDir as String
    if (!(component in ['ai', 'back', 'front', 'game'])) { error("invalid component: ${component}") }
    if (!(sourceSha ==~ /^[0-9a-f]{40}$/)) { error('sourceSha must be a full lowercase SHA') }
    if (!(artifactDir ==~ /^artifacts\/[A-Za-z0-9_.\/-]+$/) || artifactDir.contains('..')) { error('artifactDir must be a safe artifacts path') }

    def runCi = {
        sh "test \"\$(git rev-parse HEAD)\" = '${sourceSha}'"
        withEnv([
            "CI_COMPONENT=${component}",
            'CI_BRANCH=develop',
            "CI_COMMIT_SHA=${sourceSha}",
            "CI_RUN_ID=${env.JOB_NAME.replaceAll(/[^A-Za-z0-9_.-]/, '_')}-${env.BUILD_NUMBER}",
            "CI_ARTIFACT_DIR=${artifactDir}"
        ]) {
            ['validate', 'test', 'build', 'package'].each { name ->
                stage("${component}: ${name.capitalize()}") {
                    withEnv(["CI_STAGE_SUMMARY_PATH=${artifactDir}/stage-summaries/${name}.json"]) {
                        sh "infra/jenkins/scripts/with-credentials.sh -- ci/${name}"
                    }
                }
            }
            stage("${component}: Evidence") {
                writeJSON file: "${artifactDir}/selection.json", json: [
                    schemaVersion: '1.0.0', component: component, sourceSha: sourceSha, artifactDir: artifactDir
                ], pretty: 2
                archiveArtifacts artifacts: "${artifactDir}/**", allowEmptyArchive: false, fingerprint: true
            }
        }
    }

    if (component == 'game') {
        node('unity-6000.0.78f1') {
            ws('/home/jenkins/agent/unity/workspaces/develop-game') {
                checkout scm
                sh "git checkout --detach '${sourceSha}'"
                runCi()
            }
        }
    } else {
        runCi()
    }
}
return this
