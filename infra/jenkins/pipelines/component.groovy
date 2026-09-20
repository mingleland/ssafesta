def call(Map config = [:]) {
    final String component = config.component as String
    final String sourceSha = config.sourceSha as String
    final String artifactDir = config.artifactDir as String
    // stages 를 줄이면 검증만 한다 — validate-only game 은 Unity 실행기 없이 linux-docker 에서 ci/validate 만 돈다 (Batch 1).
    final List stages = (config.stages ?: ['validate', 'test', 'build', 'package']) as List
    final boolean fullBuild = stages.containsAll(['build', 'package'])
    if (!(component in ['ai', 'back', 'front', 'game'])) { error("invalid component: ${component}") }
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
                if (component == 'game' && fullBuild) {
                    // WebGL zip 은 컨트롤러가 아니라 transfer 볼륨으로 간다 (Batch 2): deploy agent 가 거기서 publish 하고,
                    // 같은 SHA 를 다시 돌리면 resolve-game-artifacts.sh 가 그 zip 을 찾아 Unity 를 다시 돌리지 않는다.
                    final String webglTransferDir = "${config.webglTransferDir ?: '/var/lib/festa-image-transfer/webgl'}/${sourceSha.substring(0, 8)}"
                    sh """
                        mkdir -p '${webglTransferDir}'
                        cp -f '${artifactDir}'/festa-webgl-release-*.zip '${artifactDir}'/festa-webgl-release-*.zip.sha256 '${artifactDir}/webgl-metadata.json' '${webglTransferDir}/'
                    """
                    archiveArtifacts artifacts: "${artifactDir}/**", excludes: "${artifactDir}/**/*.zip", allowEmptyArchive: false, fingerprint: true
                    stash name: 'candidate-metadata-game', includes: "${artifactDir}/image-metadata.json", useDefaultExcludes: false
                } else {
                    archiveArtifacts artifacts: "${artifactDir}/**", allowEmptyArchive: false, fingerprint: true
                }
            }
        }
    }

    if (component == 'game' && fullBuild) {
        node('unity-6000.0.78f1') {
            ws('/home/jenkins/agent/unity/workspaces/develop-game') {
                checkout scm
                sh "git checkout --detach '${sourceSha}'"
                // LFS pointer 위에서 Unity 를 돌리면 모델·텍스처가 빠진 채 "성공" 한다. 풀고, 남은 pointer 가 있으면 78 로 멈춘다 (Batch 2).
                // `git lfs install` 은 부르지 않는다: Jenkins checkout 은 core.hooksPath=/dev/null 이라 hook 설치가 "mkdir /dev/null" 로
                // 죽고(#507), filter 설정은 기존 plain-blob fbx/tga 를 status 에서 modified 로 보이게 해 dirty 검사를 오탐시킨다.
                // `git lfs pull` 은 filter/hook 없이도 pointer 를 실제 파일로 바꾼다.
                sh '''
                    command -v git-lfs >/dev/null 2>&1 || { echo 'git-lfs is required on the unity agent' >&2; exit 69; }
                    git lfs pull
                    if git lfs ls-files | grep -q ' - '; then echo 'LFS_POINTER_UNRESOLVED: pointer files remain after git lfs pull' >&2; git lfs ls-files | grep ' - ' >&2; exit 78; fi
                    test -z "$(git status --porcelain)" || { echo 'checkout is dirty; Unity builds must come from a clean tree' >&2; git status --porcelain >&2; exit 65; }
                '''
                runCi()
            }
        }
    } else {
        runCi()
    }
}
return this
