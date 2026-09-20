def archiveReleaseEvidence(String artifactRoot = 'artifacts/develop') {
    sh "infra/jenkins/scripts/secret-scan.sh --path '${artifactRoot}'"
    archiveArtifacts(
        artifacts: "${artifactRoot}/**/release-manifest.json,${artifactRoot}/**/verification-result.json,${artifactRoot}/**/deployment-record.json,${artifactRoot}/**/provenance.jsonl",
        allowEmptyArchive: false,
        fingerprint: true
    )
}
return this
