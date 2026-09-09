def call() {
    final String headSha = env.GIT_COMMIT
    if (!(headSha ==~ /^[0-9a-f]{40}$/)) { error('GIT_COMMIT must be a full lowercase SHA') }

    String range = "--branch develop --head '${headSha}'"
    final String baseSha = env.GIT_BEFORE_SHA ?: env.GIT_PREVIOUS_COMMIT ?: env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: ''
    if (baseSha) { range += " --base '${baseSha}'" }

    final String selectionText = sh(
        returnStdout: true,
        script: "infra/jenkins/scripts/detect-changed-components.sh ${range}"
    ).trim()
    final Map selection = readJSON text: selectionText, returnPojo: true
    sh 'mkdir -p artifacts/develop'
    writeFile file: 'artifacts/develop/selection.json', text: "${selectionText}\n"
    archiveArtifacts artifacts: 'artifacts/develop/selection.json', allowEmptyArchive: false, fingerprint: true

    final List components = selection.components as List
    if (components.isEmpty()) {
        echo "NO_OP: ${selection.reasons.join(', ')}"
        return
    }

    final String componentList = components.join(',')
    final String artifactRoot = 'artifacts/develop'
    final String metadataDir = "${artifactRoot}/release-metadata"
    final String releaseManifest = "${artifactRoot}/release-manifest.json"
    final String transferBundle = "develop-${headSha}-${env.BUILD_NUMBER}"
    final String transferDir = '/var/lib/festa-image-transfer'

    echo "SELECTED_COMPONENTS: ${components.join(', ')}; candidate transfer enabled, deploy disabled"
    components.each { component ->
        load('infra/jenkins/pipelines/component.groovy').call([
            component: component,
            sourceSha: selection.headSha,
            artifactDir: "artifacts/develop/build/${component}"
        ])
    }
    if (components.contains('game')) {
        stage('Collect Game Candidate Metadata') {
            unstash 'candidate-metadata-game'
        }
    }

    stage('Candidate Manifest') {
        sh """
            mkdir -p '${metadataDir}'
            rm -f '${metadataDir}'/*.json
            for component in ${componentList.replace(',', ' ')}; do
                cp '${artifactRoot}/build/'\${component}'/image-metadata.json' '${metadataDir}/'\${component}'.json'
            done
        """
        withEnv([
            "DEPLOY_COMPONENTS=${componentList}",
            "COMPONENT_METADATA_DIR=${metadataDir}",
            "RELEASE_MANIFEST_PATH=${releaseManifest}",
            "RELEASE_ID=develop-${headSha}-${env.BUILD_NUMBER}",
            'SCM_PROVIDER=gitlab',
            'SCM_REPOSITORY=s15-metaverse-game-sub1/S15P21A604',
            'SCM_BRANCH=develop',
            "CI_COMMIT_SHA=${headSha}",
            "JENKINS_JOB=${env.JOB_NAME}",
            "JENKINS_BUILD_NUMBER=${env.BUILD_NUMBER}",
            "JENKINS_BUILD_URL=${env.BUILD_URL}"
        ]) {
            sh 'infra/deploy/scripts/build-release-manifest.sh'
            sh "infra/jenkins/scripts/transfer-local-images.sh --export --bundle '${transferBundle}' --transfer-dir '${transferDir}' --manifest '${releaseManifest}'"
        }
    }

    stage('Deploy Candidate Receipt') {
        node('deploy') {
            ws('/home/jenkins/agent/deploy/workspaces/develop-candidate-receipt') {
                checkout scm
                sh "git checkout --detach '${headSha}'"
                sh "infra/jenkins/scripts/transfer-local-images.sh --import --bundle '${transferBundle}' --transfer-dir '${transferDir}'"
            }
        }
    }
}
return this
