def call(Map config = [:]) {
    final String component = config.component as String
    final String sourceSha = config.sourceSha as String
    final String artifactDir = config.artifactDir as String
    // stages 를 줄이면 검증만 한다 — validate-only game 은 Unity 실행기 없이 linux-docker 에서 ci/validate 만 돈다 (Batch 1).
    // Jenkins 는 Unity Editor 를 돌리지 않는다 (Batch 2 Consumer-only): game 의 build/package 산출물은 Unity 담당자가 올린
    // Unity Release Bundle(Registry unity-release-bundle/<8sha>)에서 온다. game 은 여기서 validate(+test) 만 허용한다.
    final List stages = (config.stages ?: ['validate', 'test', 'build', 'package']) as List
    if (!(component in ['ai', 'back', 'front', 'game'])) { error("invalid component: ${component}") }
    if (component == 'game' && stages.any { it in ['build', 'package'] }) { error('Jenkins does not build Unity; game artifacts come from the Unity Release Bundle') }
    if (!(sourceSha ==~ /^[0-9a-f]{40}$/)) { error('sourceSha must be a full lowercase SHA') }
    if (!(artifactDir ==~ /^artifacts\/[A-Za-z0-9_.\/-]+$/) || artifactDir.contains('..')) { error('artifactDir must be a safe artifacts path') }
    if (!stages.every { it in ['validate', 'test', 'build', 'package'] } || stages.isEmpty()) { error("invalid stages: ${stages}") }

    def runCi = {
        sh "test \"\$(git rev-parse HEAD)\" = '${sourceSha}'"
        final String artifactRoot = "${pwd()}/${artifactDir}"
        withEnv([
            "CI_COMPONENT=${component}",
            'CI_BRANCH=develop',
            "CI_COMMIT_SHA=${sourceSha}",
            "CI_RUN_ID=${env.JOB_NAME.replaceAll(/[^A-Za-z0-9_.-]/, '_')}-${env.BUILD_NUMBER}",
            "CI_ARTIFACT_DIR=${artifactRoot}"
        ]) {
            stages.each { name ->
                stage("${component}: ${name.capitalize()}") {
                    withEnv(["CI_STAGE_SUMMARY_PATH=${artifactRoot}/stage-summaries/${name}.json"]) {
                        sh "infra/jenkins/scripts/with-credentials.sh -- ci/${name}"
                    }
                }
            }
            stage("${component}: Evidence") {
                writeJSON file: "${artifactDir}/selection.json", json: [
                    schemaVersion: '1.0.0', component: component, sourceSha: sourceSha, artifactDir: artifactDir, stages: stages
                ], pretty: 2
                archiveArtifacts artifacts: "${artifactDir}/**", allowEmptyArchive: false, fingerprint: true
            }
        }
    }

    runCi()
}
return this
