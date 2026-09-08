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
    writeFile file: 'artifacts/develop/selection.json', text: "${selectionText}\n"
    archiveArtifacts artifacts: 'artifacts/develop/selection.json', allowEmptyArchive: false, fingerprint: true

    final List components = selection.components as List
    if (components.isEmpty()) {
        echo "NO_OP: ${selection.reasons.join(', ')}"
        return
    }

    echo "SELECTED_COMPONENTS: ${components.join(', ')}; deploy disabled in Phase 2"
    components.each { component ->
        load('infra/jenkins/pipelines/component.groovy').call([
            component: component,
            sourceSha: selection.headSha,
            artifactDir: "artifacts/develop/build/${component}"
        ])
    }
}
return this
