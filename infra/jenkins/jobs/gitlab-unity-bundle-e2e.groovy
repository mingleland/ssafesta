String serverUrl = System.getenv('GITLAB_SERVER_URL') ?: 'https://lab.ssafy.com'
String projectOwner = System.getenv('GITLAB_PROJECT_OWNER') ?: 'CONFIGURE_ME'
String projectPath = System.getenv('GITLAB_PROJECT_PATH') ?: 'CONFIGURE_ME'
String checkoutCredential = System.getenv('GITLAB_CHECKOUT_CREDENTIALS_ID') ?: 'gitlab-checkout'
String repositoryUrl = "${serverUrl}/${projectOwner}/${projectPath}.git"

pipelineJob('festa-unity-bundle-e2e') {
    description(
        'Unity Release Bundle Consumer E2E: Registry(unity-release-bundle/<8sha>) 에 올라온 번들을 받아 검증하고 ' +
        'Demo candidate 로 배포한 뒤, 끝나면 직전 Demo current 로 되돌린다. Unity Editor 는 돌지 않는다. ' +
        'FIXTURE_MODE 는 source provenance(커밋 존재·develop ancestry)만 UNVERIFIED_FIXTURE 로 낮추며 demo 에서만 허용된다.'
    )
    parameters {
        stringParam('BUNDLE_VERSION', '', 'unity-release-bundle 의 version (8-char source SHA)')
        booleanParam('FIXTURE_MODE', false, 'source provenance 를 UNVERIFIED_FIXTURE 로 낮춘다 (demo 전용)')
        choiceParam('TARGET', ['demo'], '배포 대상 — demo 만 허용한다')
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url(repositoryUrl)
                        credentials(checkoutCredential)
                    }
                    branches('*/develop')
                    extensions { cloneOptions { shallow(true); depth(1); noTags(true) } }
                }
            }
            scriptPath('infra/jenkins/pipelines/unity-bundle-e2e.groovy')
            lightweight(true)
        }
    }
    logRotator { numToKeep(20) }
}
