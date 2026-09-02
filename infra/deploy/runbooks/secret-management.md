# Jenkins Secret 운영 절차

## 원칙

- 실제 값은 SCM, `.env.example`, JCasC, console, cache, manifest, stage summary와 artifact에 기록하지 않는다.
- Jenkins에는 용도별 credential ID만 저장한다. GitHub/GitLab/Mattermost는 hostname domain을 분리하고 job에는 필요한 ID만 전달한다.
- Unity ID, 비밀번호, Hub session과 license 파일은 Jenkins Credentials에 넣지 않는다. 운영자가 영속 Unity agent에서 대화형 활성화한다.

## 생성과 사용

1. `credential-managers` 그룹 구성원이 Jenkins Credentials에서 올바른 hostname domain을 선택한다.
2. ID는 `github-scm`, `gitlab-scm`, `deploy-host`, `mattermost-webhook`처럼 용도를 표현하고 값은 이름에 포함하지 않는다.
3. Pipeline은 Jenkins `withCredentials`로 최소 stage에만 주입하고 `with-credentials.sh`로 child process 범위를 제한한다.
4. shell tracing은 켜지 않으며 URL query, command argument, filename에 값을 넣지 않는다.
5. 보관 전 `secret-scan.sh`를 통과한 artifact만 archive한다.

## 애플리케이션 런타임 credential

Jenkins에 아래 ID를 등록하고 `infra/.env`에는 ID만 적는다.

| ID 변수 | Jenkins 종류 | 내용 |
|---|---|---|
| `DEV_BACK_ENV_CREDENTIAL_ID` | Secret file | dev Spring 전용 dotenv |
| `DEV_AI_ENV_CREDENTIAL_ID` | Secret file | dev FastAPI 전용 dotenv |
| `DEV_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID` | Secret text | dev FastAPI→Spring 토큰 1~2개 |
| `DEMO_BACK_ENV_CREDENTIAL_ID` | Secret file | demo Spring 전용 dotenv |
| `DEMO_AI_ENV_CREDENTIAL_ID` | Secret file | demo FastAPI 전용 dotenv |
| `DEMO_INTERNAL_AI_TO_SPRING_TOKENS_CREDENTIAL_ID` | Secret text | demo FastAPI→Spring 토큰 1~2개 |

Spring과 FastAPI dotenv에는 각 서비스가 소비하는 값만 둔다. 공통 토큰은 dotenv에 복제하지 않고
환경별 Secret text 하나를 두 컨테이너에 주입한다. dev와 demo credential은 공유하지 않는다.

## 회전

1. 제공 시스템에서 새 값을 발급하되 기존 값을 즉시 폐기하지 않는다.
2. 같은 credential ID의 값을 Jenkins UI에서 교체한다.
3. 승인된 smoke job으로 checkout/배포 또는 Mattermost test를 검증한다.
4. 성공 후 이전 값을 제공 시스템에서 폐기하고 회전 시각·담당자·대상 ID만 기록한다.

Mattermost webhook은 URL 전체가 Secret이다. Grafana contact point에는 credential 참조로만 주입하고 일반 CI 성공/실패 알림에는 사용하지 않는다.

## 긴급 폐기

1. 유출 가능성이 있으면 파이프라인을 중지하고 제공 시스템에서 값을 먼저 폐기한다.
2. Jenkins credential을 비활성화/삭제하고 영향 job·build·artifact·workspace·cache를 `secret-scan.sh`와 canary로 검사한다.
3. 유출 값이 있는 artifact는 접근을 차단하고 보존/삭제 여부를 보안 담당자와 결정한다.
4. 새 값을 발급해 최소 smoke만 실행한다.
5. 값 자체가 아닌 incident ID, 영향 범위, 폐기·회전 시각을 트러블슈팅에 기록한다.
