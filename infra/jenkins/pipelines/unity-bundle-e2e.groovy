// Unity Release Bundle Consumer E2E (Batch 2).
//
// Registry 에 번들이 올라오면 publish-unity-release-bundle.sh 가 이 job 을 깨운다. 여기서부터는 사람 손이 없다:
// download → 검증 → Demo World candidate → readiness → WebGL current → promote → 그리고 항상 직전 Demo current 로 복귀.
//
// Unity Editor 도 라이선스도 필요 없다. canonical festa-webgl/festa-world 는 건드리지 않는다 — WebGL zip 은 E2E 전용
// namespace(festa-webgl-e2e)로만 게시한다. Production 경로는 이 job 에 없다.
pipeline {
    agent { label 'deploy' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 40, unit: 'MINUTES')
    }

    stages {
        stage('Unity Bundle Demo E2E') {
            steps {
                script {
                    final String bundleVersion = (params.BUNDLE_VERSION ?: '').trim()
                    final boolean fixtureMode = (params.FIXTURE_MODE as boolean)
                    final String target = (params.TARGET ?: 'demo').trim()
                    if (!(bundleVersion ==~ /[0-9a-f]{8}/)) { error('BUNDLE_VERSION must be the 8-char source SHA') }
                    // fixture 는 Demo 에서만, 그리고 이 job 에서만 허용한다. Production 은 이 경로 자체가 없다.
                    if (target != 'demo') { error('only the demo target is supported') }

                    final String api = env.GITLAB_API_V4_URL ?: 'https://lab.ssafy.com/api/v4'
                    final String projectId = env.GITLAB_PROJECT_ID ?: '1443023'
                    final String readCredentialId = env.GITLAB_PACKAGE_READ_CREDENTIAL_ID ?: 'gitlab-package-read'
                    final String writeCredentialId = env.GITLAB_PACKAGE_WRITE_CREDENTIAL_ID ?: 'gitlab-package-write'
                    final String stateDir = env.GAME_DEPLOY_STATE_DIR ?: '/var/lib/festa-environments/demo/game'
                    final String artifactRoot = 'artifacts/bundle-e2e'
                    final String bundleDir = "${pwd()}/${artifactRoot}/bundle"
                    final String metadataDir = "${pwd()}/${artifactRoot}/release-metadata-game"
                    final String manifestPath = "${pwd()}/${artifactRoot}/release-manifest-game.json"
                    final String headSha = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    if (!env.DEMO_WORLD_HOST?.trim()) { error('DEMO_WORLD_HOST is required on the deploy agent') }
                    sh "mkdir -p '${artifactRoot}' '${bundleDir}' '${metadataDir}'"

                    def withReadToken = { Closure body ->
                        withCredentials([usernamePassword(credentialsId: readCredentialId,
                            usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN')]) { body() }
                    }

                    // ── 되돌릴 자리를 먼저 적어 둔다. 이 값이 없으면 시작하지 않는다.
                    final String previousGame = sh(returnStdout: true, script: "cat '${stateDir}/current.json'").trim()
                    final Map previousGameState = readJSON(text: previousGame, returnPojo: true)
                    final String previousWebgl = sh(returnStdout: true, script: "readlink '${env.WEBGL_RELEASE_ROOT ?: '/srv/festa/webgl'}/current'").trim().tokenize('/').last()
                    writeFile file: "${artifactRoot}/demo-current-before.json",
                        text: "{\"game\":${previousGame},\"webglReleaseId\":\"${previousWebgl}\"}\n"
                    echo "DEMO_CURRENT_BEFORE: game=${previousGameState.sourceCommit} webgl=${previousWebgl}"

                    // ── 번들 반입: sourceCommit 은 번들 자신이 말한다.
                    String sourceCommit = ''
                    withReadToken {
                        withEnv(["BUNDLE_META_URL=${api}/projects/${projectId}/packages/generic/unity-release-bundle/${bundleVersion}/image-metadata.json",
                                 "BUNDLE_META_PATH=${pwd()}/${artifactRoot}/bundle-image-metadata.json"]) {
                            sh '''infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- \
                                curl --silent --show-error --fail --header "DEPLOY-TOKEN: $GITLAB_DEPLOY_TOKEN" \
                                --output "$BUNDLE_META_PATH" "$BUNDLE_META_URL"'''
                        }
                        sourceCommit = (readJSON(file: "${artifactRoot}/bundle-image-metadata.json", returnPojo: true).sourceCommit ?: '') as String
                        if (!(sourceCommit ==~ /[0-9a-f]{40}/) || !sourceCommit.startsWith(bundleVersion)) {
                            error("bundle image-metadata sourceCommit ${sourceCommit} does not match unity-release-bundle/${bundleVersion}")
                        }
                        sh "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/jenkins/scripts/intake-unity-release-bundle.sh --source-commit '${sourceCommit}' --dest '${bundleDir}'"
                    }

                    // ── provenance: 커밋이 저장소에 있으면 평소 gate 를 그대로 건다. 없으면 fixture 일 때만 낮춘다.
                    final String imageRef = "festa-game:${sourceCommit}"
                    final String webglZip = "${bundleDir}/festa-webgl-release-${bundleVersion}.zip"
                    final boolean commitPresent = sh(returnStatus: true, script: "git cat-file -e '${sourceCommit}^{commit}' 2>/dev/null") == 0
                    if (commitPresent) {
                        sh "infra/jenkins/scripts/check-game-source-identity.sh --head '${headSha}' --source-commit '${sourceCommit}' --webgl-zip '${webglZip}' --image-ref '${imageRef}'"
                    } else if (fixtureMode) {
                        echo "UNVERIFIED_FIXTURE: ${sourceCommit} is not in this repository; source provenance is not proven. Artifact contract checks still apply and nothing is promoted to canonical packages."
                    } else {
                        error("SOURCE_NOT_IN_REPOSITORY: ${sourceCommit}; rerun with FIXTURE_MODE=true on demo to exercise the consumer path")
                    }
                    // fixture 든 아니든 artifact contract 는 그대로다. apiEnvironment 는 validator 밖이라 여기서 본다.
                    sh """python3 -c 'import json,sys
doc = json.load(open(sys.argv[1]))
assert doc["apiEnvironment"] == "Prod", doc.get("apiEnvironment")
assert doc["dirty"] is False, doc.get("dirty")
assert doc["buildProfile"] == "release", doc.get("buildProfile")' '${bundleDir}/webgl-manifest.json'"""

                    final String contentId = sh(returnStdout: true, script: "docker image inspect --format '{{.Id}}' '${imageRef}'").trim()
                    final String webglSha = sh(returnStdout: true, script: "sha256sum '${webglZip}' | awk '{print \$1}'").trim()
                    sh "infra/jenkins/scripts/validate-game-release-set.sh --source-commit '${sourceCommit}' --webgl-zip '${webglZip}' --webgl-sha256 '${webglSha}' --image-ref '${imageRef}' --content-id '${contentId}'"

                    // ── WebGL zip 은 E2E namespace 로만 올린다. canonical festa-webgl/festa-world 는 이 job 이 만들지 않는다.
                    final String e2ePackage = env.WEBGL_E2E_PACKAGE_NAME ?: 'festa-webgl-e2e'
                    // package-write 는 Secret text 다 (develop 파이프라인과 같은 바인딩).
                    withCredentials([string(credentialsId: writeCredentialId, variable: 'GITLAB_PACKAGE_TOKEN')]) {
                        withEnv(["WEBGL_PACKAGE_NAME=${e2ePackage}"]) {
                            sh "infra/jenkins/scripts/with-credentials.sh GITLAB_PACKAGE_TOKEN -- infra/jenkins/scripts/publish-webgl-release.sh '${webglZip}' '${bundleVersion}' --no-trigger"
                        }
                    }
                    final String webglPackageUrl = "${api}/projects/${projectId}/packages/generic/${e2ePackage}/${bundleVersion}/festa-webgl-release-${bundleVersion}.zip"

                    // ── Demo 배포: develop 과 같은 스크립트, 같은 순서.
                    deployGame(artifactRoot, metadataDir, manifestPath, sourceCommit, imageRef, contentId,
                               "e2e-${bundleVersion}-${env.BUILD_NUMBER}", headSha, stateDir)
                    lock(resource: 'deploy-dev-game') {
                        if (sh(returnStatus: true, script: 'bash infra/unity-server/scripts/game-readiness.sh') != 0) {
                            sh 'bash infra/unity-server/scripts/rollback-game.sh'
                            error('demo world readiness failed for the bundle candidate')
                        }
                    }
                    withReadToken {
                        withEnv(["WEBGL_EVIDENCE_PATH=${pwd()}/${artifactRoot}/webgl-deployment.json"]) {
                            sh "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/jenkins/scripts/deploy-webgl-release.sh --release-id '${bundleVersion}' --sha256 '${webglSha}' --package-url '${webglPackageUrl}'"
                        }
                    }
                    withEnv(["GAME_DEPLOY_STATE_DIR=${stateDir}", "CI_ARTIFACT_DIR=${pwd()}/${artifactRoot}"]) {
                        sh 'bash infra/unity-server/scripts/promote-game.sh'
                    }
                    echo "BUNDLE_E2E_PROMOTED: demo now runs ${imageRef} with WebGL ${bundleVersion}"

                    // ── 그리고 되돌린다. E2E 는 Demo 를 빌려 쓰는 것이지 바꾸는 것이 아니다.
                    restoreDemo(artifactRoot, metadataDir, manifestPath, previousGameState, previousWebgl,
                                headSha, stateDir, api, projectId, readCredentialId, env.BUILD_NUMBER as String)
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'artifacts/bundle-e2e/*.json', allowEmptyArchive: true, fingerprint: true
        }
    }
}

// develop 파이프라인과 같은 계약으로 game candidate 를 올린다: metadata → manifest → deploy-game.
void deployGame(String artifactRoot, String metadataDir, String manifestPath, String sourceCommit,
                String imageRef, String contentId, String releaseId, String headSha, String stateDir) {
    sh """
        printf '{"schemaVersion":"1.0.0","component":"game","sourceCommit":"%s","storageMode":"local-docker","imageRef":"%s","contentId":"%s"}\n' '${sourceCommit}' '${imageRef}' '${contentId}' >'${metadataDir}/game.json'
        printf '{"sourceCommit":"%s"}\n' '${sourceCommit}' >'${artifactRoot}/webgl-candidate-manifest.json'
    """
    withEnv([
        'DEPLOY_COMPONENTS=game', "COMPONENT_METADATA_DIR=${metadataDir}", "RELEASE_MANIFEST_PATH=${manifestPath}",
        "RELEASE_ID=${releaseId}", 'SCM_PROVIDER=gitlab', 'SCM_REPOSITORY=s15-metaverse-game-sub1/S15P21A604', 'SCM_BRANCH=develop',
        "CI_COMMIT_SHA=${headSha}", "JENKINS_JOB=${env.JOB_NAME}", "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}", "JENKINS_BUILD_URL=${env.BUILD_URL}"
    ]) { sh 'infra/deploy/scripts/build-release-manifest.sh' }
    withEnv([
        "RELEASE_MANIFEST_PATH=${manifestPath}",
        "WEBGL_MANIFEST_PATH=${pwd()}/${artifactRoot}/webgl-candidate-manifest.json",
        "WORLD_PUBLIC_HOST=${env.DEMO_WORLD_HOST}",
        "GAME_ENV_FILE=${env.GAME_ENV_FILE ?: '/srv/festa/config/game.env'}",
        "CONNECTION_TOKEN_SECRET_FILE=${env.CONNECTION_TOKEN_SECRET_FILE ?: '/opt/festa/secrets/dev-game-connection-token-secret'}",
        "GAME_DEPLOY_STATE_DIR=${stateDir}",
        "CI_ARTIFACT_DIR=${pwd()}/${artifactRoot}"
    ]) {
        int status = sh(returnStatus: true, script: 'bash infra/unity-server/scripts/deploy-game.sh')
        if (status != 0) { error("demo world deployment failed with exit ${status}") }
    }
}

// 직전 Demo current 로 되돌린다 — 같은 배포 경로를 그대로 쓴다(별도 복구 기계를 만들지 않는다).
void restoreDemo(String artifactRoot, String metadataDir, String manifestPath, Map previousGame, String previousWebgl,
                 String headSha, String stateDir, String api, String projectId, String readCredentialId, String buildNumber) {
    stage('Restore Demo Current') {
        deployGame(artifactRoot, metadataDir, manifestPath, previousGame.sourceCommit as String,
                   previousGame.imageRef as String, previousGame.contentId as String,
                   "e2e-restore-${previousWebgl}-${buildNumber}", headSha, stateDir)
        withEnv(["GAME_DEPLOY_STATE_DIR=${stateDir}", "CI_ARTIFACT_DIR=${pwd()}/${artifactRoot}"]) {
            sh 'bash infra/unity-server/scripts/game-readiness.sh'
            sh 'bash infra/unity-server/scripts/promote-game.sh'
        }
        withCredentials([usernamePassword(credentialsId: readCredentialId,
            usernameVariable: 'GITLAB_DEPLOY_USER', passwordVariable: 'GITLAB_DEPLOY_TOKEN')]) {
            final String base = "${api}/projects/${projectId}/packages/generic/festa-webgl/${previousWebgl}/festa-webgl-release-${previousWebgl}.zip"
            withEnv(["PREVIOUS_SHA_URL=${base}.sha256", "PREVIOUS_SHA_PATH=${pwd()}/${artifactRoot}/webgl-previous.sha256"]) {
                sh '''infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- \
                    curl --silent --show-error --fail --header "DEPLOY-TOKEN: $GITLAB_DEPLOY_TOKEN" \
                    --output "$PREVIOUS_SHA_PATH" "$PREVIOUS_SHA_URL"'''
            }
            final String previousSha = sh(returnStdout: true, script: "awk '{print \$1}' '${artifactRoot}/webgl-previous.sha256'").trim()
            withEnv(["WEBGL_EVIDENCE_PATH=${pwd()}/${artifactRoot}/webgl-restore.json"]) {
                sh "infra/jenkins/scripts/with-credentials.sh GITLAB_DEPLOY_TOKEN -- infra/jenkins/scripts/deploy-webgl-release.sh --release-id '${previousWebgl}' --sha256 '${previousSha}' --package-url '${base}'"
            }
        }
        echo "DEMO_CURRENT_RESTORED: game=${previousGame.sourceCommit} webgl=${previousWebgl}"
    }
}
