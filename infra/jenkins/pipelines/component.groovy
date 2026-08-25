def call(Map config = [:]) {
    String component = config.scope as String
    if (!(component in ['ai', 'back', 'front', 'game'])) { error("invalid component: ${component}") }
    if (component == 'game') { load('infra/jenkins/pipelines/unity.groovy').call(config); return }
    withEnv([
        "CI_COMPONENT=${component}", "CI_BRANCH=${component}", "CI_COMMIT_SHA=${env.GIT_COMMIT}",
        "CI_RUN_ID=${env.JOB_NAME}-${env.BUILD_NUMBER}", "CI_ARTIFACT_DIR=${env.WORKSPACE}/artifacts/${component}",
        "DEPLOY_TARGET=dev-${component}", "RELEASE_ID=${component}-${env.GIT_COMMIT}-${env.BUILD_NUMBER}",
        "RELEASE_MANIFEST_PATH=${env.WORKSPACE}/artifacts/${component}/release-manifest.json"
    ]) {
        ['validate', 'test', 'build', 'package'].each { name -> stage(name.capitalize()) { sh "infra/jenkins/scripts/with-credentials.sh -- ci/${name}" } }
        stage('Deploy') {
            milestone ordinal: env.BUILD_NUMBER.toInteger()
            lock(resource: "deploy-dev-${component}") {
                int fresh = sh(returnStatus: true, script: 'FRESHNESS_EXPECTED_SHA="$CI_COMMIT_SHA" infra/jenkins/scripts/freshness.sh')
                if (fresh == 75) { currentBuild.result = 'NOT_BUILT'; echo 'SUPERSEDED: newer branch head exists'; return }
                if (fresh != 0) { error('freshness check failed') }
                def meta = readJSON file: "artifacts/${component}/image-metadata.json"
                withEnv(["COMPOSE_FILE=infra/deploy/compose/dev/${component}.compose.yaml", "COMPOSE_PROJECT=festa-dev-${component}", "COMPOSE_SERVICE=${component}", "IMAGE_REF=${meta.imageRef}", "CONTENT_ID=${meta.contentId}"]) {
                    sh 'infra/jenkins/scripts/with-credentials.sh -- infra/deploy/scripts/deploy-component.sh'
                }
            }
        }
        stage('Verify') { sh 'infra/jenkins/scripts/with-credentials.sh -- ci/verify' }
    }
}
return this
