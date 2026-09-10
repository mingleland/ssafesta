pipeline {
    agent { label 'deploy' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {
        stage('Validate Approved Dev Release') {
            steps {
                script {
                    String manifest = params.DEV_RELEASE_MANIFEST?.trim()
                    String verification = params.DEV_VERIFICATION_RESULT?.trim()
                    String approver = params.APPROVED_BY?.trim()
                    if (!manifest || !verification || !approver) { error('DEV_RELEASE_MANIFEST, DEV_VERIFICATION_RESULT and APPROVED_BY are required') }
                    // The input is the immutable artifact made by build-release-manifest.sh on develop.
                    // Rebuilding it at approval time would weaken its image provenance.
                    String releaseId = withEnv(["RELEASE_MANIFEST_PATH=${manifest}", "DEV_VERIFICATION_RESULT_PATH=${verification}", "DEMO_APPROVED_BY=${approver}"]) {
                        sh(returnStdout: true, script: 'infra/jenkins/scripts/validate-demo-promotion.sh').trim()
                    }
                    env.DEMO_RELEASE_MANIFEST = manifest
                    env.DEMO_VERIFICATION_RESULT = verification
                    env.DEMO_APPROVED_BY = approver
                    env.DEMO_RELEASE_ID = releaseId
                }
            }
        }

        stage('Deploy and Verify Demo') {
            steps {
                lock(resource: 'deploy-demo') {
                    script {
                        final String artifactDir = "${pwd()}/artifacts/demo-promotion"
                        final String stateDir = env.DEMO_PROMOTION_STATE_DIR ?: '/var/lib/festa-environments/demo'
                        final Map verificationCommands = [
                            VERIFY_WEB_COMMAND: env.DEMO_VERIFY_WEB_COMMAND ?: '',
                            VERIFY_LOGIN_COMMAND: env.DEMO_VERIFY_LOGIN_COMMAND ?: '',
                            VERIFY_WORLD_COMMAND: env.DEMO_VERIFY_WORLD_COMMAND ?: '',
                            VERIFY_AI_COMMAND: env.DEMO_VERIFY_AI_COMMAND ?: ''
                        ]
                        if (verificationCommands.any { _, value -> value.trim().isEmpty() }) { error('All DEMO_VERIFY_*_COMMAND values must be configured on the deploy agent') }

                        withCredentials([
                            file(credentialsId: env.DEMO_BACK_ENV_CREDENTIAL_ID, variable: 'DEMO_BACK_ENV_FILE'),
                            file(credentialsId: env.DEMO_AI_ENV_CREDENTIAL_ID, variable: 'DEMO_AI_ENV_FILE'),
                            string(credentialsId: env.DEMO_INTERNAL_SPRING_TO_AI_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_SPRING_TO_AI_TOKENS'),
                            string(credentialsId: env.DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID, variable: 'INTERNAL_AI_TO_SPRING_TOKENS')
                        ]) {
                            withEnv([
                                "RELEASE_MANIFEST_PATH=${env.DEMO_RELEASE_MANIFEST}",
                                "RELEASE_ID=${env.DEMO_RELEASE_ID}",
                                "DEPLOY_TARGET=demo-integration",
                                "CI_ARTIFACT_DIR=${artifactDir}",
                                "STATE_DIR=${stateDir}",
                                "RECOVERY_DECISION_PATH=${artifactDir}/recovery-decision.json",
                                "BACK_ENV_FILE=${env.DEMO_BACK_ENV_FILE}",
                                "AI_ENV_FILE=${env.DEMO_AI_ENV_FILE}",
                                'FESTA_ENVIRONMENT=demo'
                            ] + verificationCommands.collect { key, value -> "${key}=${value}" }) {
                                int deployStatus = sh(returnStatus: true, script: 'infra/jenkins/scripts/with-credentials.sh DEMO_BACK_ENV_FILE DEMO_AI_ENV_FILE INTERNAL_SPRING_TO_AI_TOKENS INTERNAL_AI_TO_SPRING_TOKENS -- infra/deploy/scripts/deploy-release.sh')
                                if (deployStatus != 0) {
                                    sh '''python3 - "$CI_ARTIFACT_DIR/verification-result.json" <<'PY'
import datetime,json,os,pathlib,sys
now=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')
path=pathlib.Path(sys.argv[1]); path.parent.mkdir(parents=True,exist_ok=True)
path.write_text(json.dumps({'schemaVersion':'1.0.0','verificationId':'verify-'+os.environ['RELEASE_ID'],'releaseId':os.environ['RELEASE_ID'],'targetId':os.environ['DEPLOY_TARGET'],'checks':[{'name':'web','status':'FAILED'}],'requiredNonAiPassed':False,'aiPassed':None,'failureCode':'CONTAINER_START','evidenceRefs':[],'finalDecision':'ROLLBACK','startedAt':now,'finishedAt':now},indent=2)+'\\n',encoding='utf-8')
PY'''
                                } else {
                                    sh(returnStatus: true, script: 'infra/deploy/scripts/verify-release.sh')
                                }
                                sh 'infra/deploy/scripts/decide-recovery.sh'
                                Map decision = readJSON file: "${artifactDir}/recovery-decision.json", returnPojo: true
                                if (decision.decision == 'AUTO_ROLLBACK') {
                                    String knownGood = sh(returnStdout: true, script: '''python3 - "$STATE_DIR/target-state.json" "$STATE_DIR/releases" <<'PY'
import json,pathlib,sys
state=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
release=state.get('knownGoodReleaseId')
if not release: raise SystemExit('no known-good demo release exists')
path=pathlib.Path(sys.argv[2])/(release+'.json')
if not path.is_file(): raise SystemExit('known-good demo manifest is missing')
print(path)
PY''').trim()
                                    withEnv(["KNOWN_GOOD_MANIFEST_PATH=${knownGood}"]) { sh 'infra/deploy/scripts/rollback-release.sh' }
                                    error('candidate failed; known-good demo release was restored')
                                }
                                if (decision.decision != 'NONE') { error("demo promotion requires manual action: ${decision.reason}") }
                                sh 'infra/deploy/scripts/promote-release.sh'
                                sh '''python3 - "$CI_ARTIFACT_DIR/demo-promotion.json" <<'PY'
import datetime,json,os,pathlib,sys
path=pathlib.Path(sys.argv[1]); path.write_text(json.dumps({'promotionId':'demo-'+os.environ['RELEASE_ID'],'approvedBy':os.environ['DEMO_APPROVED_BY'],'releaseId':os.environ['RELEASE_ID'],'status':'ACTIVE','verificationEvidence':'verification-result.json','finishedAt':datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace('+00:00','Z')},indent=2)+'\\n',encoding='utf-8')
PY'''
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'infra/jenkins/scripts/secret-scan.sh --path .'
            archiveArtifacts artifacts: 'artifacts/demo-promotion/**/*', allowEmptyArchive: true, fingerprint: true
        }
    }
}
