# Quickstart: 구현 후 인수 검증

이 문서는 구현 명령 자체가 아니라 `$speckit-implement` 완료 후 계획의 핵심 요구사항을 재현하는 인수 시나리오다. 실제 endpoint, credential ID, EC2 사양은 환경 설정에서 주입하며 문서에 Secret 값을 적지 않는다.

## 0. 사전 조건

- 저장소 root에서 실행하고 `.specify/feature.json`이 `specs/infra-001-ci-cd-pipelines`를 가리킨다.
- Jenkins controller built-in executor가 0이고 controller에 Docker socket/Unity/deploy credential이 없다.
- `linux && docker`, `deploy`, `unity-6000.0.78f1` label의 agent가 각각 의도한 workspace/권한으로 등록돼 있다.
- 단일 EC2 Docker daemon에 local image를 저장할 disk 공간이 있고 current/known-good image 보호 규칙이 설정돼 있다.
- 실제 Secret이 아닌 canary 값을 만든 뒤 console, archived artifact, cache, release manifest에서 원문이 발견되지 않는지 검사할 준비를 한다.
- Unity agent는 담당자가 Personal 적격성을 확인·기록하고 Hub에서 1회 활성화했다. Jenkins에는 Unity credential/session/license 파일이 없다.
- UFW 변경 전 SSH 세션을 2~3개 유지하고 현재 rule을 기록한다. 기존 UFW를 임의로 disable/reset하지 않는다.
- UFW 기본 inbound는 22/80/443 TCP이며 Jenkins 8080, Grafana 3000, Jenkins Agent 50000은 외부 허용 rule이 없어야 한다.
- Jenkins는 `127.0.0.1:8080`에 bind되고 reverse proxy의 443을 통해서만 접근한다.

### 네트워크 사전 검증

1. `sudo ufw status numbered`로 22/80/443 rule과 불필요한 공개 port 부재를 확인한다.
2. 새 터미널에서 SSH 재접속을 검증한 뒤 기존 여분 세션을 종료한다.
3. EC2 내부에서 Jenkins loopback 응답을 확인하고 외부에서는 8080 직접 접속이 차단되는지 확인한다.
4. 외부 네트워크에서 `https://<team-domain>` TLS 접속과 GitHub test webhook delivery를 확인한다.
5. UFW가 허용됐는데도 실패하면 SSAFY/AWS 상위 방화벽→DNS→reverse proxy→Jenkins 순으로 진단한다.
6. Unity 7777은 Transport protocol이 확정되기 전 rule을 추가하지 않는다.

**통과 기준**: SSH가 유지되고 443 webhook은 도달하며 관리 원본 포트는 외부에서 닫혀 있다.

## 1. 구성과 계약 검증

1. JCasC와 Compose 파일을 parse/validate한다.
2. 예시 release manifest를 `contracts/release-manifest.schema.json`으로 검증한다.
3. 예시 verification result를 `contracts/verification-result.schema.json`으로 검증한다.
4. 승인된 예시 detection rule을 schema로 검증한다.
5. 다음 규칙 위반 fixture가 반드시 실패하는지 확인한다.

   - local image ref만 있고 `contentId`(Docker image ID)가 없는 release
   - 40자리 full SHA가 아닌 commit
   - `domain=ci`인 알림 규칙
   - approval 정보가 비어 있는 알림 규칙
   - Secret/token/webhook URL이 포함된 report 또는 manifest

**통과 기준**: 유효 fixture만 성공하며 실패 원인이 field 단위로 보인다.

## 2. 파트별 독립 파이프라인

각각 `ai`, `back`, `front`, `game` 브랜치에서 다음을 반복한다.

1. test 가능한 정상 변경을 push한다.
2. checkout commit과 Jenkinsfile commit이 같은지 확인한다.
3. validate→test→build→package→deploy→verify 순서를 확인한다.
4. `<component>:<full-commit-sha>` local tag와 Docker image ID가 release record에 남고, deploy 직전 실제 image ID 일치 검사를 수행하는지 확인한다.
5. 대상 파트 container만 변경됐고 나머지 파트 container ID/start time이 유지되는지 확인한다.
6. test 실패 변경을 push해 package/deploy가 실행되지 않는지 확인한다.

**통과 기준**: 다른 파트 재시작 없이 대상 파트만 갱신되고, 사전 검증 실패에는 rollback이 아니라 “미배포”가 기록된다.

## 3. 최신 실행 우선권

1. 같은 파트에 느린 실행 A와 더 최신 commit 실행 B를 연속 시작한다.
2. A가 먼저 build를 끝내도 deploy lock/freshness 경계에서 최신 여부를 재검사하게 한다.
3. B가 target을 갱신한 뒤 A가 target을 덮지 못하는지 확인한다.

**통과 기준**: target의 current release는 B이며 A는 `SUPERSEDED`로 기록된다. 배포 도중 프로세스를 무조건 abort해 반쪽 상태를 만들지 않는다.

## 4. develop 통합 배포

### 정상 경로

1. 네 component의 local image ref와 image ID를 포함한 하나의 release manifest를 만든다.
2. 통합 target에 candidate를 배포한다.
3. 웹 접속→로그인→월드 입장→AI 응답 순으로 verify한다.
4. 모든 필수 검증 후에만 current/known-good pointer가 동시에 새 release로 승격되는지 확인한다.

### 가역 실패와 자동 복구

1. `rollbackSafety=SAFE`, data/config 변경 없음인 candidate에 비AI readiness 실패를 주입한다.
2. local store에 보호된 previous known-good image로 rollback이 1회만 실행되는지 확인한다.
3. rollback 뒤 비AI verify가 재실행되고 결과가 기록되는지 확인한다.

### 수동 판단 경로

1. DB schema 변경 또는 Secret/config 변경 metadata가 있는 candidate를 실패시킨다.
2. 자동 rollback하지 않고 `MANUAL_ACTION_REQUIRED`로 전환되는지 확인한다.
3. candidate/previous manifest와 sanitized evidence가 보존되는지 확인한다.

### AI-only 실패

1. 웹·로그인·월드 검증은 성공하고 외부 AI 응답만 실패하게 한다.
2. release가 성공으로 승격되지 않되 비AI/월드 container를 자동 rollback하지 않는지 확인한다.
3. `AWAITING_AI_RETRY_OR_APPROVAL`과 재시도/승인 경로가 보이는지 확인한다.

**통과 기준**: 실패 유형별 정책이 정확히 다르고 rollback 실패를 자동 반복하지 않는다.

## 5. Release provenance

성공 배포와 rollback 배포를 각각 하나 선택해 다음 연결을 UI/artifact/record만으로 확인한다.

```text
SCM provider/repository/branch/full commit
→ Jenkins job/build/stage 결과
→ component local image tag/image ID
→ deployment target/candidate release
→ verification evidence
→ recovery decision/from-to release/result
→ current release
```

**통과 기준**: 원본 console log를 전부 읽지 않고도 위 연결과 최종 상태를 재구성할 수 있고, evidence에 Secret 원문이 없다.

## 6. GitHub에서 GitLab migration rehearsal

1. test project에서 GitHub Branch Source job의 pipeline을 실행해 기준 결과를 저장한다.
2. Jenkinsfile은 수정하지 않은 채 GitLab Branch Source/plugin, repository/API URL, checkout/API credential, webhook을 test GitLab project로 교체한다.
3. 동일한 branch 이름과 adapter fixture로 pipeline을 실행한다.
4. stage, schema, failure code, artifact naming이 provider 전후 동일한지 비교한다.

**통과 기준**: provider 설정만 바뀌며 Jenkinsfile/adapter/deploy contract 복제가 없다.

## 7. Unity build와 license 운영

1. agent에서 Editor version/revision과 Web Build Support/Linux Dedicated Server module을 확인한다.
2. credential 입력 없이 batchmode smoke/import가 성공하는지 확인한다. 라이선스 실패는 명확한 agent-configuration 오류로 종료돼야 한다.
3. WebGL과 Linux Dedicated Server를 별도 프로세스/target workspace에서 순차 빌드한다.
4. WebGL의 `index.html`, `Build/`, `TemplateData/`와 HTTP load를 검증한다.
5. Linux server의 `festa-unity.x86_64`와 필수 data/runtime 파일을 검증한다.
6. 기존 Dockerfile로 build context를 정확히 `Builds/linux-server`로 지정해 image를 만들고 7777 readiness를 확인한다.
7. 같은 commit을 두 번 빌드해 target별 Library cache가 재사용되는지 확인하고, 이어 `CleanBuildCache` 실행도 성공하는지 확인한다.
8. 동시 Unity job 2개를 요청해 두 번째가 queue되고 같은 project/Library를 동시에 열지 않는지 확인한다.
9. agent retirement runbook을 review한다: node offline→진행 build 없음→Hub logout/Return→반환 확인→node 제거/디스크 폐기. 정상 job post에는 return이 없어야 한다.

**통과 기준**: Unity 계정 정보가 Jenkins 어디에도 없고, 캐시는 target별로 재사용되며 출력만 매 실행 정리된다. `festa-unity/Docker/` 변경이 없다.

## 8. P1 관측과 선택 알림

1. observability Compose project만 기동하고 Alloy→Prometheus/Loki→Grafana 연결을 확인한다.
2. Grafana에서 EC2 CPU/메모리/disk/network와 파트별 container 상태·로그를 조회한다.
3. 승인된 test anomaly rule에 맞는 sanitized 이벤트를 발생시켜 Mattermost에 firing과 resolved 알림이 한 그룹으로 오는지 확인한다.
4. 같은 fingerprint 이벤트를 반복해 group/repeat 정책이 중복 폭주를 막는지 확인한다.
5. 일반 Jenkins 성공/실패와 승인되지 않은 rule, 정상 log가 Mattermost에 오지 않는지 확인한다.
6. token/email/authorization canary가 Loki label/body와 Mattermost에 원문으로 남지 않는지 확인한다.
7. Alloy/Loki/Prometheus/Grafana를 각각 중단해도 app/CI build/deploy/readiness가 계속되는지 확인한다.
8. 24–72시간 baseline으로 ingest, active series, CPU/RAM/disk를 측정한 뒤 retention/resource cap/threshold/dashboard refresh 값을 운영 승인으로 확정한다.

**통과 기준**: 승인된 anomaly만 알림되고 일반 CI 상태는 알림되지 않는다. 관측 장애가 서비스와 CI/CD를 멈추지 않는다.

## 9. 알려진 단일 EC2 한계 확인

- 같은 host의 논리적 controller/agent 분리는 완전한 보안 격리가 아니다.
- Docker socket을 쓰면 agent가 host 제어 권한을 갖는다. rootless 검증 결과와 임시 위험 수용 여부를 기록한다.
- EC2 전체 장애는 같은 EC2의 Grafana가 Mattermost로 알릴 수 없다. 외부 heartbeat/CloudWatch 등 두 번째 실패영역 도입 여부를 팀 결정 목록에 올린다.
- EC2 사양이 제공되기 전 executor/resource/retention 수치를 확정하지 않는다.
- EC2가 유실되면 local Docker image와 rollback 대상도 함께 유실된다. current/known-good/candidate image는 자동 정리에서 보호한다.
- 추후 AWS IAM role과 ECR repository가 제공되면 `storageMode=registry`로 migration rehearsal을 수행한다. 그전에는 ECR을 필수 조건으로 삼지 않는다.

## 10. 완료 증거

- 파트 4개와 develop run URL/manifest/verification record
- stale run, auto rollback, manual, AI-only failure fixture 결과
- GitHub→GitLab rehearsal 비교표
- Unity version/module/license gate 및 cache 검증 결과
- Secret canary scan 결과
- P1 승인/비승인/CI 제외 Mattermost 결과와 Grafana dashboard 캡처
- 작업일지 및 새 troubleshooting 번호(문제 발생 시)
