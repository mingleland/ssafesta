# Component Pipeline Contract v1

중앙 Jenkins pipeline과 `ai`/`back`/`front`/`game` 파트 저장소 사이의 최소 계약이다. SCM provider와 무관하며 각 명령은 저장소 root에서 실행한다.

## Required adapters

| Adapter | 입력 | 성공 출력 | 실패 규칙 |
|---|---|---|---|
| `ci/validate` | `CI_COMMIT_SHA`, `CI_COMPONENT` | 계약/설정 유효 | non-zero + 원인 요약 |
| `ci/test` | 동일 | machine-readable test report 경로 | 필수 test 하나라도 실패하면 non-zero |
| `ci/build` | 동일 | build output | 오류를 기본값으로 삼키지 않고 non-zero |
| `ci/package` | `IMAGE_NAME`, `IMAGE_TAG` | local image ref와 image ID | Secret을 command output에 노출 금지 |
| `ci/verify` | `DEPLOY_TARGET`, `RELEASE_ID` | verification JSON | readiness 실패 시 구조화 failure code + non-zero |

실제 파일 형식은 각 파트 언어에 맞는 executable script 또는 동일 이름의 task runner target일 수 있다. 중앙 pipeline은 파트 내부 build tool 명령을 하드코딩하지 않는다.

## Standard environment

필수:

- `CI_COMPONENT`: `ai|back|front|game`
- `CI_BRANCH`
- `CI_COMMIT_SHA`: full SHA
- `CI_RUN_ID`
- `CI_ARTIFACT_DIR`: 실행별 쓰기 가능 디렉터리

배포/검증 시 추가:

- `DEPLOY_TARGET`
- `RELEASE_ID`
- `RELEASE_MANIFEST_PATH`

초기 값 주입은 Jenkins credential binding을 사용한다. AWS 권한이 제공되면 instance role/Secret adapter로 교체할 수 있다. adapter는 credential 이름을 요구할 수 있지만 Secret 값을 파일·로그·report에 반환해서는 안 된다.

## Standard output

각 adapter는 stdout에 사람용 진행 메시지를 쓸 수 있으나 마지막에 다음 summary 파일을 생성한다.

```json
{
  "schemaVersion": "1.0.0",
  "component": "back",
  "commit": "0123456789abcdef0123456789abcdef01234567",
  "stage": "test",
  "status": "SUCCEEDED",
  "startedAt": "2026-08-18T00:00:00Z",
  "finishedAt": "2026-08-18T00:01:00Z",
  "evidenceRefs": ["reports/tests.xml"]
}
```

summary에는 raw log, token, password, cookie, signed URL, webhook URL을 넣지 않는다.

## Deployment isolation

- 파트 adapter는 자신의 `CI_COMPONENT` service, network alias, volume만 변경할 수 있다.
- 다른 파트의 `docker compose down`, container recreate/restart, volume delete를 해서는 안 된다.
- game의 Linux server image context는 `festa-unity/Builds/linux-server`이며 `festa-unity/Docker/`는 수정하지 않는다.
- endpoint는 환경 설정 또는 서비스 discovery 계약으로 받아야 하며 하드코딩하지 않는다.

## Failure codes

최소 공통 code:

- `VALIDATION_FAILED`
- `TEST_FAILED`
- `BUILD_FAILED`
- `PACKAGE_FAILED`
- `CONTAINER_START`
- `VERIFY_NON_AI`
- `VERIFY_AI_ONLY`
- `DB_CHANGE`
- `SECRET_CONFIG`
- `IRREVERSIBLE_CHANGE`
- `UNKNOWN`

unknown exception을 성공 또는 빈 결과로 바꾸지 않는다.

## Unity extension

`game` adapter는 WebGL과 Linux Dedicated Server를 별도 Unity 프로세스로 실행하고 같은 Library를 동시에 열지 않는다. Unity agent label/version, output root, log path를 명시하며 계정/세션/license 파일을 입력으로 받지 않는다.
