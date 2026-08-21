# Tasks: 파트별 CI/CD 파이프라인

**Input**: Design documents from `specs/infra-001-ci-cd-pipelines/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Tests**: 명세의 Independent Test, Acceptance Scenario, SC-001~SC-015를 자동 검증 가능한 작업으로 포함한다. 테스트 작업은 해당 구현보다 먼저 작성해 실패를 확인한다.

**Organization**: 작업은 사용자 스토리별로 묶는다. P0 스토리는 US1→US4→US2 순서로 안전한 개발환경과 통합환경을 완성하고, P1 스토리는 US3→US5 순서로 이력과 관측을 추가한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업 완료 후 다른 파일에서 병렬 실행 가능
- **[Story]**: `spec.md`의 User Story 번호
- **SERVER-GATE**: 설명이 `SERVER-GATE:`로 시작하는 작업은 EC2·DNS·Unity 라이선스 등 외부 자원이 제공된 뒤 실행

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 저장소 구조와 버전·비밀값 제외 규칙을 먼저 고정한다.

- [x] T001 Infra 디렉터리 구조와 담당 범위를 `infra/README.md`에 작성한다
- [x] T002 [P] 생성된 상태 파일, 실제 `.env`, Unity 빌드 결과물, Jenkins 런타임 데이터와 인증서를 `.gitignore`의 제외 대상으로 추가한다
- [x] T003 [P] Jenkins·배포·reverse proxy·관측에 필요한 변수 이름만 `infra/.env.example`에 정의한다
- [x] T004 [P] Jenkins LTS 이미지, Docker/Compose 호환 정책, Unity 버전과 관측 이미지 버전을 `infra/versions.env`에 고정한다
- [x] T005 [P] JCasC·Multibranch·GitHub Branch Source·Lockable Resources·Pipeline Utility Steps·credential binding에 필요한 최소 Jenkins plugin과 버전을 `infra/jenkins/plugins.txt`에 선언한다

**Checkpoint**: 모든 후속 파일의 위치와 버전 정책이 고정되고 실제 Secret·runtime state가 커밋 대상에서 제외된다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 사용자 스토리가 공유하는 Jenkins 실행 경계, 계약 검증, release state 기반을 구현한다.

**⚠️ CRITICAL**: 이 Phase가 끝나기 전에는 사용자 스토리 구현을 시작하지 않는다.

- [x] T006 영속 `JENKINS_HOME`, loopback 전용 `8080`, healthcheck를 사용하고 Docker socket을 연결하지 않는 Jenkins controller Compose 서비스를 `infra/jenkins/controller/compose.yaml`에 작성한다
- [x] T007 내장 executor `0`, controller 사용 제한, credential ID 참조와 agent label을 JCasC 파일 `infra/jenkins/casc/jenkins.yaml`에 설정한다
- [x] T008 서로 다른 workspace를 사용하는 `linux-docker`, `deploy`, 영속 단일-executor Unity agent를 논리적으로 분리해 `infra/jenkins/agents/compose.yaml`에 정의한다
- [x] T009 [P] `8080`·`3000`·`50000`을 공개하지 않고 loopback Jenkins로 전달하는 TLS reverse proxy를 `infra/jenkins/reverse-proxy/nginx.conf`에 설정한다
- [x] T010 [P] SSH 세션 유지, UFW `22/80/443`, DNS/TLS 확인과 공개 금지 포트를 포함한 서버 수령 절차를 `infra/deploy/runbooks/server-bootstrap.md`에 작성한다
- [x] T011 release·verification·detection-rule 문서의 JSON Schema 검증기를 `infra/jenkins/scripts/validate-contracts.sh`에 구현한다
- [x] T012 [P] 정상·오류 release, verification, detection-rule fixture를 `infra/tests/contract/fixtures/`에 추가한다
- [x] T013 커밋하지 않는 런타임 상태 구조, 원자적 pointer 규칙과 current/known-good/candidate 소유 관계를 `infra/deploy/state/README.md`에 정의한다
- [x] T014 SCM 제공자별 로직 없이 component 또는 develop 흐름을 선택하는 root dispatcher를 `Jenkinsfile`에 작성한다
- [x] T015 [P] 표준 stage-summary JSON 생성과 구조화된 실패 코드를 `infra/jenkins/scripts/write-stage-summary.sh`에 구현한다
- [x] T016 Compose 렌더링, JCasC 로딩, controller executor `0`, 금지 mount, schema fixture와 공개 포트 정책 검사를 `infra/jenkins/tests/test-foundation.sh`에 추가한다

**Checkpoint**: Jenkins controller가 빌드하지 않고 세 agent 경계와 계약 validator가 재현 가능하며, 모든 스토리가 같은 release/state 규칙을 사용할 수 있다.

---

## Phase 3: User Story 1 - 파트 변경을 독립적으로 검증하고 배포한다 (Priority: P0) 🎯 Developer MVP

**Goal**: `ai`/`back`/`front`/`game` 변경이 자기 파트만 test·build·package·deploy·verify하고 오래된 실행이 최신 환경을 덮어쓰지 못하게 한다.

**Independent Test**: 한 파트의 정상 변경은 그 파트 container만 갱신하고, test 실패 변경은 미배포되며, 연속 실행 중 오래된 실행은 `SUPERSEDED`가 된다.

### Tests for User Story 1

- [x] T017 [P] [US1] adapter 필수 환경변수, summary 출력, 실패 시 non-zero 종료, full commit SHA를 검증하는 계약 테스트를 `infra/tests/contract/test-component-adapter.sh`에 추가한다
- [x] T018 [P] [US1] 전체 component container ID를 기록하고 한 파트 배포 후 나머지 세 파트가 유지되는지 검증하는 통합 테스트를 `infra/tests/integration/test-component-isolation.sh`에 추가한다
- [x] T019 [P] [US1] 두 실행의 경쟁 상황에서 오래된 실행이 lock 획득 후 `SUPERSEDED`가 되는지 검증하는 fixture를 `infra/tests/integration/test-deploy-freshness.sh`에 추가한다
- [x] T020 [P] [US1] commit별 고유 tag, `contentId` 일치, tag 재사용 거부와 known-good 이미지 보호를 검증하는 테스트를 `infra/tests/integration/test-local-image-store.sh`에 추가한다

### Implementation for User Story 1

- [x] T021 [US1] validate→test→build→package→lock/freshness→deploy→verify 순서와 배포 전 실패 차단을 `infra/jenkins/pipelines/component.groovy`에 구현한다
- [x] T022 [US1] milestone, 배포 대상 lock, branch head 비교와 `SUPERSEDED` 출력을 `infra/jenkins/scripts/freshness.sh`에 구현한다
- [x] T023 [US1] `<component>:<full-commit-sha>` 이미지를 만들고 기존 tag 변경을 거부하며 Docker image ID와 산출물 metadata를 출력하도록 `infra/deploy/scripts/package-local-image.sh`를 구현한다
- [x] T024 [US1] `imageRef`와 `contentId`를 검증한 후 요청받은 Compose project/service만 배포하도록 `infra/deploy/scripts/deploy-component.sh`를 구현한다
- [x] T025 [US1] 파트가 소유한 readiness 검사를 실행하고 `verification-result.schema.json` 형식으로 출력하도록 `infra/deploy/scripts/verify-component.sh`를 구현한다
- [x] T026 [P] [US1] 격리된 AI 개발 project·network·volume·자원 placeholder·service healthcheck를 `infra/deploy/compose/dev/ai.compose.yaml`에 정의한다
- [x] T027 [P] [US1] 격리된 Back 개발 project·network·volume·자원 placeholder·service healthcheck를 `infra/deploy/compose/dev/back.compose.yaml`에 정의한다
- [x] T028 [P] [US1] 격리된 Front 개발 project·network·volume·자원 placeholder·service healthcheck를 `infra/deploy/compose/dev/front.compose.yaml`에 정의한다
- [x] T029 [P] [US1] 기존 Unity Docker build context를 유지하면서 격리된 Game 개발 project를 `infra/deploy/compose/dev/game.compose.yaml`에 정의한다
- [x] T030 [P] [US1] AI 저장소의 `ci/validate`, `ci/test`, `ci/build`, `ci/package`, `ci/verify` adapter를 구현한다
- [x] T031 [P] [US1] Back 저장소의 `ci/validate`, `ci/test`, `ci/build`, `ci/package`, `ci/verify` adapter를 구현한다
- [x] T032 [P] [US1] Front 저장소의 `ci/validate`, `ci/test`, `ci/build`, `ci/package`, `ci/verify` adapter를 구현한다
- [x] T033 [P] [US1] 실패 시 명확한 non-zero로 종료하는 Unity WebGL·Linux Dedicated Server batch 진입점을 `festa-unity/Assets/_Project/Editor/CI/CIBuild.cs`에 구현한다
- [x] T034 [P] [US1] target 검증, 출력 정리, 빌드 실패 전파와 동결 경로 보호 EditMode 테스트를 `festa-unity/Assets/_Project/Editor/CI/Tests/CIBuildTests.cs`에 추가한다
- [x] T035 [US1] Unity target별 별도 process, 정확한 `6000.0.78f1` 사전 검사, target별 log와 기존 `Builds/linux-server` Docker context를 사용하는 Game adapter를 `festa-unity/ci/`에 구현한다
- [x] T036 [US1] target별 영속 Unity workspace, executor `1`, cache 재사용, `CleanBuildCache`와 queue 동작을 `infra/jenkins/pipelines/unity.groovy`에 설정한다
- [x] T037 [US1] `checkout scm`과 credential ID를 사용해 `ai`·`back`·`front`·`game`을 발견하는 GitHub Multibranch job을 `infra/jenkins/jobs/github-component-multibranch.groovy`에 정의한다
- [x] T038 [US1] US1 정상·test 실패·파트 격리·오래된 실행·Unity 순차 빌드 인수 증거 생성을 `infra/tests/acceptance/us1-part-pipelines.sh`에 자동화한다

**Checkpoint**: 각 파트가 독립 배포되며 최신 실행 우선권, local Docker artifact identity, Unity 순차 빌드가 독립적으로 검증된다.

---

## Phase 4: User Story 4 - 비밀정보를 노출하지 않고 배포한다 (Priority: P0) 🔒 Secure MVP

**Goal**: 실제 값은 Jenkins Credentials에서 실행 시점에만 최소 범위로 주입되고 SCM·console·cache·report·artifact·release record에 원문이 남지 않게 한다.

**Independent Test**: 고유 canary Secret으로 성공·실패 파이프라인을 실행한 후 모든 저장·출력 위치를 검색해 원문 노출 0건을 확인한다.

### Tests for User Story 4

- [x] T039 [P] [US4] console·파일·cache·manifest·URL·보관 artifact 누출의 정상·오류 canary fixture를 `infra/tests/security/fixtures/`에 추가한다
- [x] T040 [P] [US4] canary와 승인된 민감 key 패턴을 발견하면 실패하는 저장소·생성물 Secret 검사를 `infra/tests/security/test-secret-leak.sh`에 추가한다

### Implementation for User Story 4

- [x] T041 [US4] shell tracing을 끄고 종료 시 값을 반드시 정리하는 범위 제한 Jenkins credential binding wrapper를 `infra/jenkins/scripts/with-credentials.sh`에 구현한다
- [x] T042 [US4] 보관 전과 실행 후 workspace·허용 cache·stage summary·manifest·log·report를 검사하도록 `infra/jenkins/scripts/secret-scan.sh`를 구현한다
- [x] T043 [P] [US4] console masking, 정제된 실패 summary, artifact 보존과 credential domain 제한을 `infra/jenkins/casc/security.yaml`에 설정한다
- [x] T044 [P] [US4] pipeline 조회·파트 실행·develop 배포·복구 승인·credential 관리의 최소 권한 role을 `infra/jenkins/casc/authorization.yaml`에 정의한다
- [x] T045 [US4] credential wrapper와 실행 전후 Secret scan gate를 component·develop pipeline 진입점인 `Jenkinsfile`에 연결한다
- [x] T046 [US4] 실제 값 없이 credential 생성·회전·Mattermost webhook 처리·Unity credential 금지·긴급 폐기 절차를 `infra/deploy/runbooks/secret-management.md`에 작성한다
- [x] T047 [US4] US4 정상 주입·명령 실패·archive·cache·정리 인수 증거 생성을 `infra/tests/acceptance/us4-secret-safety.sh`에 자동화한다

**Checkpoint**: US1을 실제 credential과 함께 실행해도 Secret 원문이 어떤 보존 위치에도 남지 않고 권한이 역할별로 제한된다.

---

## Phase 5: User Story 2 - develop을 통합 시연 가능한 상태로 배포한다 (Priority: P0) 🚀 P0 Release

**Goal**: `develop`의 네 component를 하나의 candidate release로 배포하고 웹→로그인→월드→AI 순차 검증 후에만 승격하며 실패 유형에 맞게 자동복구·수동대기·AI재시도를 선택한다.

**Independent Test**: 정상, 가역 비AI 실패, DB/Secret/config 위험 실패, AI-only 실패를 각각 주입해 `ACTIVE`, `ROLLED_BACK`, `MANUAL_ACTION_REQUIRED`, `AWAITING_AI_RETRY_OR_APPROVAL`로 정확히 분기되는지 확인한다.

### Tests for User Story 2

- [x] T048 [P] [US2] 네 component identity와 웹→로그인→월드→AI 순차 검증을 모두 요구하는 develop 정상 경로 테스트를 `infra/tests/integration/test-develop-release.sh`에 추가한다
- [x] T049 [P] [US2] 가역·DB·Secret/config·비가역·AI-only·unknown·rollback 실패 복구 정책을 표 기반으로 검증하는 테스트를 `infra/tests/integration/test-recovery-policy.sh`에 추가한다
- [x] T050 [P] [US2] 일부 배포만 성공한 경우 통합 current pointer가 갱신되지 않음을 검증하는 원자적 승격 실패 테스트를 `infra/tests/integration/test-atomic-promotion.sh`에 추가한다

### Implementation for User Story 2

- [x] T051 [US2] rollback 안전성 metadata를 포함한 네 component candidate를 생성·검증하도록 `infra/deploy/scripts/build-release-manifest.sh`를 구현한다
- [x] T052 [P] [US2] 설정으로 endpoint를 주입하고 world-session 주소를 하드코딩하지 않는 통합 Compose project를 `infra/deploy/compose/integration/compose.yaml`에 정의한다
- [x] T053 [US2] component별 current pointer를 중간 승격하지 않고 전체 candidate를 배포하도록 `infra/deploy/scripts/deploy-release.sh`를 구현한다
- [x] T054 [US2] 웹→로그인→월드→AI 검증을 순서대로 실행하고 구조화된 증거와 실패 코드를 출력하도록 `infra/deploy/scripts/verify-release.sh`를 구현한다
- [x] T055 [US2] 무조건 rollback하지 않고 rollback 안전성, data/config flag, AI-only 격리와 수동 대응을 판정하도록 `infra/deploy/scripts/decide-recovery.sh`를 구현한다
- [x] T056 [US2] 보호된 known-good local image를 한 번만 복구하고 비AI 검증을 재실행하며 rollback 실패 시 중단하도록 `infra/deploy/scripts/rollback-release.sh`를 구현한다
- [x] T057 [US2] 정책상 성공한 경우에만 candidate를 current/known-good으로 원자적으로 승격하도록 `infra/deploy/scripts/promote-release.sh`를 구현한다
- [x] T058 [US2] candidate 생성, 통합 lock/freshness, 배포, 순차 검증, 복구 판단과 최종 상태를 `infra/jenkins/pipelines/develop.groovy`에서 오케스트레이션한다
- [x] T059 [US2] Squash commit checkout과 통합 대상 lock을 사용하는 `develop` GitHub Multibranch job을 `infra/jenkins/jobs/github-develop-multibranch.groovy`에 정의한다
- [x] T060 [US2] 정상·자동 rollback·수동 조치·AI-only·rollback 실패 인수 증거 생성을 `infra/tests/acceptance/us2-develop-release.sh`에 자동화한다

**Checkpoint**: P0 전체가 완료되어 파트 독립 배포, Secret 안전성, 통합 릴리스와 조건부 복구를 각각 재현할 수 있다.

---

## Phase 6: User Story 3 - 릴리스·배포·복구 이력을 추적한다 (Priority: P1)

**Goal**: console 전체를 읽지 않고도 commit→pipeline→local image→target→verification→recovery→current release를 재구성한다.

**Independent Test**: 성공 배포와 복구 배포를 하나씩 선택해 연결 이력만으로 원본 변경, 실패 지점, 복구 전후와 현재 실행 버전을 찾는다.

### Tests for User Story 3

- [x] T061 [P] [US3] 배포·복구 기록의 schema와 상태 전이를 검증하는 테스트를 `infra/tests/contract/test-deployment-record.sh`에 추가한다
- [x] T062 [P] [US3] 활성 release 하나와 복구 release 하나의 provenance를 끝까지 재구성하는 테스트를 `infra/tests/integration/test-provenance-chain.sh`에 추가한다

### Implementation for User Story 3

- [x] T063 [US3] candidate·stage·실패·복구·최종 상태·current release 필드를 `infra/contracts/deployment-record.schema.json`에 정의한다
- [x] T064 [US3] run ID와 release ID로 연결되고 민감정보가 제거된 append-only pipeline·배포·복구 기록을 `infra/jenkins/scripts/provenance.sh`에서 생성한다
- [x] T065 [US3] current/known-good pointer를 원자적으로 읽고 사람이 확인할 수 있는 현재 버전을 출력하도록 `infra/deploy/scripts/show-release.sh`를 구현한다
- [x] T066 [US3] 두 pipeline의 manifest·verification result·deployment record를 archive하고 fingerprint하도록 `infra/jenkins/pipelines/provenance.groovy`를 구현한다
- [x] T067 [US3] US3 이력 조회와 현재 버전 확인 인수 증거 생성을 `infra/tests/acceptance/us3-provenance.sh`에 자동화한다

**Checkpoint**: 성공·실패·복구 상태를 원본 로그 재분석 없이 구조화된 기록으로 재구성할 수 있다.

---

## Phase 7: User Story 5 - 운영 로그의 특이사항과 서버 사용량을 관측한다 (Priority: P1)

**Goal**: Alloy가 로그와 host/container 지표를 수집하고 Grafana에서 조회하며 Infra가 승인한 비CI 탐지 규칙만 Mattermost 알림을 생성한다.

**Independent Test**: 승인 anomaly는 firing/resolved로 알림되고 일반 CI·비승인·불일치 이벤트는 0건이며, 관측 stack을 중단해도 app/CI가 계속된다.

### Tests for User Story 5

- [x] T068 [P] [US5] 승인 정보, `domain!=ci`, 제한된 label, runbook, 만료와 알림 필드를 검증하는 detection-rule 계약 테스트를 `infra/tests/contract/test-detection-rule.sh`에 추가한다
- [x] T069 [P] [US5] authorization·bearer·password·token·cookie·webhook URL·email·phone 마스킹 fixture를 `infra/tests/security/test-observability-redaction.sh`에 추가한다
- [x] T070 [P] [US5] Alloy·Loki·Prometheus·Grafana를 각각 중단해도 app·CI probe가 성공하는 장애 격리 테스트를 `infra/tests/integration/test-observability-isolation.sh`에 추가한다

### Implementation for User Story 5

- [x] T071 [US5] 영속 volume·private network·healthcheck·restart 정책·자원 placeholder를 가진 별도 observability Compose project를 `infra/observability/compose.yaml`에 작성한다
- [x] T072 [US5] Alloy unix metric, Docker discovery, 제한된 label, Prometheus remote write와 Loki write를 `infra/observability/alloy/config.alloy`에 설정한다
- [x] T073 [US5] 원문을 보존하지 않고 저장 전에 RE2 마스킹을 적용하도록 `infra/observability/alloy/redaction.alloy`을 작성한다
- [x] T074 [P] [US5] 단일 Loki의 영속화, retention placeholder, compactor와 private 전용 listen을 `infra/observability/loki/config.yaml`에 설정한다
- [x] T075 [P] [US5] Prometheus remote-write receiver, TSDB retention placeholder와 private 전용 listen을 `infra/observability/prometheus/prometheus.yaml`에 설정한다
- [x] T076 [US5] credential을 직접 넣지 않은 Prometheus·Loki datasource를 `infra/observability/grafana/provisioning/datasources/datasources.yaml`에 provisioning한다
- [x] T077 [P] [US5] EC2 CPU·memory·disk·network dashboard를 `infra/observability/grafana/dashboards/host-overview.json`에 provisioning한다
- [x] T078 [P] [US5] component별 container 자원과 log 탐색 dashboard를 `infra/observability/grafana/dashboards/component-overview.json`에 provisioning한다
- [x] T079 [US5] detection-rule schema, 정책 label, 만료, 고카디널리티와 Secret/PII 검증을 `infra/observability/scripts/validate-rules.sh`에 구현한다
- [x] T080 [US5] 승인된 규칙 전용 alert group, NoData/Error 격리, grouping, 반복 억제와 resolved 알림을 `infra/observability/grafana/provisioning/alerting/rules.yaml`에 provisioning한다
- [x] T081 [US5] Secret 참조 방식의 Mattermost 호환 contact point를 만들고 CI·비승인 경로를 제외하도록 `infra/observability/grafana/provisioning/alerting/contact-points.yaml`에 설정한다
- [x] T082 [US5] 정제된 문맥과 runbook/dashboard link를 포함한 알림 정책·mute timing·message template을 `infra/observability/grafana/provisioning/alerting/policies.yaml`에 provisioning한다
- [x] T083 [US5] 승인 anomaly, 정상·CI·비승인 제외, dedupe, firing/resolved와 canary 마스킹 인수 증거 생성을 `infra/tests/acceptance/us5-observability.sh`에 자동화한다
- [x] T084 [US5] retention·자원 상한·scrape/evaluation 주기·임계치·dashboard refresh 승인을 위한 24~72시간 sizing 기준선을 `infra/observability/BASELINE.md`에 작성한다

**Checkpoint**: P1 관측 stack이 P0 경로와 독립적으로 동작하고 승인된 운영 anomaly만 Mattermost에 전달된다.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 운영 수명주기, migration, 실서버 gate, 전체 인수 증거를 마무리한다.

- [x] T085 [P] current/known-good/candidate 이미지를 보호하고 승인된 disk 임계치 아래에서 참조되지 않는 local image만 정리하도록 `infra/deploy/scripts/prune-images.sh`를 구현한다
- [x] T086 [P] credential 값을 내보내지 않고 Jenkins JCasC·job·plugin·build record를 백업·복구하는 절차와 검증 방법을 `infra/deploy/runbooks/jenkins-backup-restore.md`에 작성한다
- [x] T087 [P] agent 분리, rootless Docker 검토 결과, 수용한 Docker socket 위험과 향후 agent EC2 이전 절차를 `infra/deploy/runbooks/agent-migration.md`에 작성한다
- [x] T088 [P] Unity node offline, 라이선스 반환 확인, cache 처리와 disk 폐기 절차를 `infra/deploy/runbooks/agent-retirement.md`에 작성한다
- [x] T089 GitLab Branch Source migration 설정과 provider 중립 비교 절차를 `infra/jenkins/jobs/gitlab-migration.groovy`에 작성한다
- [ ] T090 SERVER-GATE: EC2 사양, Docker/rootless 결과, UFW/DNS/TLS/webhook 도달성, 닫힌 관리 포트와 disk 기준선을 `infra/evidence/server-preflight.md`에 기록한다
- [ ] T091 SERVER-GATE: Unity Personal 적격성 승인, GUI 활성화 경로, 정확한 Editor/module, credential 없는 batch smoke와 라이선스 반환 담당자를 `infra/evidence/unity-agent-preflight.md`에 기록한다
- [ ] T092 SERVER-GATE: GitHub·GitLab test project에서 같은 Jenkinsfile 동작을 재현하고 stage·계약 차이를 `infra/evidence/scm-migration-rehearsal.md`에 기록한다
- [ ] T093 `specs/infra-001-ci-cd-pipelines/quickstart.md`의 모든 시나리오를 실행하고 연결된 증거를 `infra/evidence/quickstart-results.md`에 기록한다
- [ ] T094 구현 결과, 새 T-number 참조와 미해결 운영 gate를 `docs/24_작업일지.md`, `docs/25_트러블슈팅.md`, `docs/26_팀_결정_필요사항.md`에 반영한다

---

## Dependencies & Execution Order

### Phase Dependencies

```text
Phase 1 Setup
    ↓
Phase 2 Foundation
    ├─→ Phase 3 US1 ───────┐
    └─→ Phase 4 US4 ───┐   │
                        └─→ Phase 5 US2
Phase 3 + Phase 4 + Phase 5 ─→ Phase 6 US3
Phase 2 + Phase 4 ───────────→ Phase 7 US5
All selected stories ─────────→ Phase 8 Polish/Server Gates
```

### User Story Dependencies

- **US1 (P0)**: Foundation 후 독립 시작 가능. fixture credential로 US4와 병렬 개발할 수 있다.
- **US4 (P0)**: Foundation 후 독립 시작 가능. 실제 Secret이 필요한 US1/US2 운영 적용 전 완료해야 한다.
- **US2 (P0)**: US1의 image/deploy/verify primitive와 US4의 안전한 credential 경계가 필요하다.
- **US3 (P1)**: US1·US2가 생성하는 배포/복구 결과와 US4의 sanitization을 소비한다.
- **US5 (P1)**: Foundation과 US4 이후 시작 가능하며 US2/US3과 기능적으로 독립적이다.
- **Phase 8 SERVER-GATE**: EC2, DNS, Unity GUI/라이선스, GitLab test project가 제공된 항목만 실행한다.

### Within Each User Story

- Test/fixture를 먼저 작성하고 구현 전 실패를 확인한다.
- 계약/상태 모델을 구현한 뒤 orchestration에 연결한다.
- pipeline script 전에 leaf deploy/verify script를 직접 검증한다.
- 독립 인수 스크립트가 통과해야 다음 의존 스토리로 넘어간다.
- 실패를 성공·빈 값·기본값으로 조용히 변환하지 않는다.

### Parallel Opportunities

- Setup의 T002~T005는 T001과 병렬 수행 가능하다.
- Foundation의 T009, T010, T012, T015는 서로 다른 파일에서 병렬 수행 가능하다.
- US1 test T017~T020, Compose T026~T029, part adapter T030~T034는 각 묶음 내 병렬 가능하다.
- US1과 US4는 Foundation 이후 병렬 개발 가능하지만 실환경 배포는 US4 완료 뒤 수행한다.
- US2 test T048~T050과 integration Compose T052는 병렬 가능하다.
- US3 test T061~T062는 병렬 가능하다.
- US5 test T068~T070, storage T074~T075, dashboard T077~T078은 각 묶음 내 병렬 가능하다.
- Polish의 T085~T088은 병렬 가능하며 SERVER-GATE는 외부 자원 제공 순서에 따라 독립 실행한다.

---

## Parallel Execution Examples

### User Story 1

```text
Track A: T017 → T021 → T022 → T023 → T024 → T025
Track B: T018 + T019 + T020
Track C: T026 + T027 + T028 + T029
Track D: T030 + T031 + T032
Track E: T033 + T034 → T035 → T036
Join: T037 → T038
```

### User Story 4

```text
Track A: T039 → T041 → T042
Track B: T040
Track C: T043 + T044 + T046
Join: T045 → T047
```

### User Story 2

```text
Track A: T048 → T051 → T053 → T054
Track B: T049 → T055 → T056
Track C: T050 + T052 → T057
Join: T058 → T059 → T060
```

### User Story 3

```text
Track A: T061 → T063 → T064
Track B: T062
Join: T065 → T066 → T067
```

### User Story 5

```text
Track A: T068 → T079 → T080 → T081 → T082
Track B: T069 → T072 → T073
Track C: T070 → T071
Track D: T074 + T075 → T076
Track E: T077 + T078
Join: T083 → T084
```

---

## Implementation Strategy

### Pre-server Priority Queue — 지금 바로 수행

서버가 없어도 저장소 안에서 작성·정적 검증·fixture 검증할 수 있는 작업을 다음 순서로 수행한다.

```text
Pre-1 공통 뼈대       T001~T016
  → Infra 구조, 버전, Jenkins/JCasC/Agent/Nginx 선언, 계약 validator와 fixture

Pre-2 파트 배포 코어  T017~T029, T037~T038
  → component pipeline, freshness, local image, 파트 격리 Compose, GitHub job, 인수 스크립트

Pre-3 Secret 안전성   T039~T047
  → canary test, credential wrapper, scan/masking/RBAC, 인수 스크립트

Pre-4 develop 배포    T048~T060
  → release/verify/recovery/rollback/promotion, develop pipeline, failure fixture

Pre-5 P1 코드·설정    T061~T089
  → provenance, observability provisioning, local image 정리, backup/migration runbook
```

다음 작업은 서버 외의 별도 입력이 필요하므로 해당 입력이 올 때까지 건너뛴다.

- **파트 저장소/명령 필요**: T030~T032 — AI/Back/Front 소유 `ci/*` adapter
- **Unity 실행환경 필요**: T033~T036 — 코드는 미리 작성할 수 있으나 성공 판정은 Editor·module·Personal 활성화 후 가능
- **실제 Mattermost 필요**: T081~T083 — provisioning과 fixture는 미리 작성하고 실제 delivery 판정은 webhook 제공 후 수행
- **EC2/GitLab/실환경 필요**: T090~T093 — SERVER-GATE
- **최종 기록**: T094 — 선택한 scope의 인수 결과가 나온 뒤 수행

**Pre-server stop line**: P0 우선이면 Pre-1~Pre-4를 완료한 뒤 중단한다. 이 시점에는 모든 config/script/test가 저장소에 존재하지만 실제 EC2 성공을 주장하지 않으며, server receipt 후 T090→T091→T093으로 검증한다.

### Milestone A — Server-independent foundation

1. Complete Phase 1 and Phase 2.
2. Validate JCasC, Compose, schema fixtures, agent boundaries locally.
3. Do not wait for EC2 to implement repository-controlled configuration.

### Milestone B — Secure Developer MVP

1. Complete US1 and US4 in parallel.
2. Stop and run `us1-part-pipelines.sh` and `us4-secret-safety.sh`.
3. This is the smallest safe MVP: independent part delivery without Secret leakage.

### Milestone C — P0 Integrated Release

1. Complete US2 after US1 and US4.
2. Rehearse success, automatic rollback, manual action, AI-only isolation, and rollback failure.
3. P0 is complete when US1+US4+US2 acceptance evidence passes.

### Milestone D — P1 Operations

1. Add US3 provenance.
2. Add US5 observability without coupling it to app/CI success.
3. Measure before fixing retention, resource, and threshold values.

### Milestone E — Provided-server acceptance

1. Execute T090 and T091 as soon as EC2 and Unity activation path are available.
2. Execute T092 when GitLab test access is available.
3. Finish T093 and T094 only after all in-scope story evidence is linked.

---

## Notes

- `[P]` tasks only mean file-level parallelism; shared EC2/Unity executor constraints still apply at runtime.
- `festa-unity/Docker/`, `NetworkPlayer.cs`, `ConnectionManager.cs`, and `BoothRuntime.cs` are never modified.
- Real `.env`, Jenkins runtime state, certificate/private key, Unity credential/session/license data are never committed.
- Local image cleanup must never remove current, known-good, or in-progress candidate references.
- `infra-002` owns domain/certificate detail and `infra-003` owns final Unity network/LB deployment; this task list only validates their consumed configuration.
- Every completed task or logical group updates the worklog; every problem receives a T-number even if unresolved.
