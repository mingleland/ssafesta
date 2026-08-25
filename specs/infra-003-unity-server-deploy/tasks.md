# Tasks: Unity Dedicated Server 외부 배포

**Input**: `specs/infra-003-unity-server-deploy/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Tests**: 명세의 독립 시험, FR-025~FR-027과 SC-001~SC-009가 필수이므로 각 스토리에서 실패 우선 자동 테스트와 외부 실측 작업을 포함한다.

**Organization**: Setup과 공통 기반 이후 US1~US5를 명세 순서로 배치한다. 동결 기준선 `v0.0.1-poc`의 이동·스폰·Dockerfile은 재구현하거나 리팩터링하지 않는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업 완료 후 다른 파일에서 병렬 진행 가능
- **[USn]**: `spec.md`의 사용자 스토리 번호
- 모든 작업은 구현 또는 검증 대상의 정확한 파일 경로를 포함한다.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: infra-003가 소유하는 코드·테스트·배포 경계와 실행 규약을 준비한다.

- [ ] T001 infra-001/002 재사용 경계, 동결 기준선 금지 사항과 로컬·외부 검증 명령을 `infra/unity-server/README.md`에 작성한다
- [ ] T002 [P] 실제 값을 포함하지 않는 game image·도메인·토큰 Secret·TLS·network·volume 환경 변수 예시를 `infra/unity-server/.env.example`에 작성한다
- [ ] T003 [P] ShellCheck 대상 엄격 모드, 정리 trap, 민감정보 제거와 공통 assertion을 `infra/unity-server/tests/lib/assert.sh`에 작성한다
- [ ] T004 [P] Unity 보안 코드와 EditMode 테스트를 분리하는 assembly definition을 `festa-unity/Assets/_Project/Scripts/Network/Security/Festa.Network.Security.asmdef`와 `festa-unity/Assets/_Project/Tests/EditMode/Festa.Network.Security.Tests.asmdef`에 구성한다
- [ ] T005 [P] 릴리스·DNS/TLS·WSS·무입력·재접속·수용량 결과만 기록하고 token/Secret/개인정보 원문을 금지하는 양식을 `infra/unity-server/evidence/template.md`에 작성한다

**Checkpoint**: infra-003 전용 경로와 테스트 실행 기반이 준비된다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 공유하는 계약, 설정과 테스트 도구를 먼저 고정한다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 시작하지 않는다.

- [ ] T006 `world-session.openapi.yaml`의 구조화 endpoint·인증·필수 필드·오류 응답을 검사하는 계약 테스트를 `backend/src/test/java/com/example/ssafesta/world/WorldSessionContractTest.java`에 작성한다
- [ ] T007 [P] HS256 only, TTL 120초, issuer/audience, `11F/11F-01`과 전용 Base64 Secret을 바인딩·검증하는 설정 모델을 `backend/src/main/java/com/example/ssafesta/world/WorldSessionProperties.java`에 작성한다
- [ ] T008 [P] World Entry Grant의 검증된 신원·target·시간·JTI를 표현하는 내부 모델을 `backend/src/main/java/com/example/ssafesta/world/WorldEntryGrant.java`와 `festa-unity/Assets/_Project/Scripts/Network/Security/VerifiedWorldEntryGrant.cs`에 작성한다
- [ ] T009 [P] game runtime·world entry token 계약의 고정값과 필수 환경 변수를 검사하는 정적 테스트를 `infra/unity-server/tests/static/contracts.sh`에 작성한다
- [ ] T010 [P] token·Secret·개인정보 원문을 제거하면서 release/client/channel/failure-layer만 남기는 로그 규약을 `infra/unity-server/contracts/logging.md`에 작성한다
- [ ] T011 [P] immutable image ref, current/known-good, game-only lock과 검증 상태를 infra-001 스키마에 매핑하는 배포 상태 계약을 `infra/unity-server/contracts/release-state.md`에 작성한다
- [ ] T012 Backend·Unity가 동일한 issuer/audience/world/channel/ledger 경로를 소비하도록 환경 변수 매핑을 `infra/unity-server/contracts/runtime-env.md`에 작성한다
- [ ] T013 OpenAPI YAML, Compose config, Nginx 구문과 shell strict-mode를 한 번에 검사하는 로컬 진입점을 `infra/unity-server/tests/run-static.sh`에 작성한다
- [ ] T014 Setup·Foundational 산출물이 계획 계약과 헌법 6·8·13~16·27조를 만족하는지 `specs/infra-003-unity-server-deploy/checklists/implementation.md`에 검증 항목으로 작성한다

**Checkpoint**: API·token·runtime·release·로그 경계가 고정되어 스토리별 실패 우선 테스트를 작성할 수 있다.

---

## Phase 3: User Story 1 — 브라우저에서 안전하게 월드에 입장한다 (Priority: P0) 🎯

**Goal**: 회원·게스트가 구조화 endpoint와 1회용 권한을 받아 `wss://world.${ROOT_DOMAIN}:443`으로 입장하고 내부 7777에는 직접 접근하지 못하게 한다.

**Independent Test**: 외부 브라우저 2개가 같은 `11F-01`에 입장해 상호 생성·이동을 확인하고, 인증서 오류와 public 7777 직접 접근은 실패해야 한다.

### Tests for User Story 1 ⚠️

- [ ] T015 [P] [US1] 회원·게스트 인증, `wss/world/443`, `11F/11F-01`, 120초 token 응답과 비인가 거부를 검증하는 API 통합 테스트를 `backend/src/test/java/com/example/ssafesta/world/WorldSessionApiIntegrationTest.java`에 작성한다
- [ ] T016 [P] [US1] `WorldSessionDto.endpoint`의 ws/wss 매핑, 호스트 검증과 하드코딩 주소 부재를 검사하는 Unity EditMode 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/WorldSessionEndpointTests.cs`에 작성한다
- [ ] T017 [P] [US1] host 443·Full(strict)·Upgrade·cache bypass와 public 7777 차단을 실패 우선 검증하는 테스트를 `infra/unity-server/tests/integration/public-wss.sh`에 작성한다
- [ ] T018 [P] [US1] 인증서 만료·호스트 불일치·신뢰 실패를 우회하지 않는 원본 TLS 검사를 `infra/unity-server/tests/security/tls-strict.sh`에 작성한다

### Implementation for User Story 1

- [ ] T019 [US1] 인증된 회원·게스트 신원을 조회하고 `WorldSessionResponse`를 생성하는 controller/service 경계를 `backend/src/main/java/com/example/ssafesta/world/WorldSessionController.java`와 `backend/src/main/java/com/example/ssafesta/world/WorldSessionService.java`에 구현한다
- [ ] T020 [US1] 전용 HS256 Secret으로 120초 connection JWT를 발급하고 `world-session.openapi.yaml` 응답을 채우는 issuer를 `backend/src/main/java/com/example/ssafesta/world/WorldEntryTokenIssuer.java`에 구현한다
- [ ] T021 [US1] local/demo별 구조화 endpoint와 Secret Reference를 바인딩하고 잘못된 scheme·host·port·Secret에서 조기 실패하도록 `backend/src/main/resources/application-local.yml`과 `backend/src/main/resources/application-infra.yml`을 구성한다
- [ ] T022 [P] [US1] Access Token을 Authorization header로 사용해 로딩 완료 뒤 `POST /api/v1/world-sessions`를 호출하는 adapter를 `festa-unity/Assets/_Project/Scripts/Integration/Spring/HttpUserApiClient.cs`에 구현한다
- [ ] T023 [US1] mock/real 환경에서 올바른 user API adapter를 선택하고 응답 endpoint로만 접속하도록 `festa-unity/Assets/_Project/Scripts/Integration/ApiServices.cs`와 `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs`를 연결한다
- [ ] T024 [P] [US1] `world.${ROOT_DOMAIN}`의 TLS·Upgrade·Host·cache bypass와 내부 `demo-game:7777` upstream을 `infra/unity-server/nginx/world.conf.template`에 작성한다
- [ ] T025 [US1] DNS→TLS→Cloudflare→Nginx→내부 listener→승인 접속과 public 7777 차단 결과를 수집하는 외부 실행기를 `infra/unity-server/scripts/verify-public-wss.sh`에 구현한다

**Checkpoint**: US3의 실제 승인 검증과 결합하면 외부 브라우저의 안전한 월드 입장을 독립 검증할 수 있다.

---

## Phase 4: User Story 2 — 운영자가 단일 월드 서버를 안전하게 배포한다 (Priority: P0)

**Goal**: 검증된 불변 Unity 릴리스를 game 대상으로만 배포하고 다른 demo 서비스를 재시작하지 않으며 실패 후보를 known-good으로 복구한다.

**Independent Test**: game image만 변경한 뒤 비관리자 실행·내부 listener·외부 WSS·릴리스 ID를 확인하고 비대상 서비스 restart delta가 0인지 검증한다.

### Tests for User Story 2 ⚠️

- [ ] T026 [P] [US2] 단일 `demo-game`, maxPlayers 40, 비관리자, 내부 expose-only 7777, replay volume과 Secret mount를 검사하는 Compose 테스트를 `infra/unity-server/tests/integration/game-compose.sh`에 작성한다
- [ ] T027 [P] [US2] game-only `--no-deps` 배포 전후 Backend·AI·web restart count 0과 image ref 변경 범위를 검사하는 테스트를 `infra/unity-server/tests/integration/game-only-deploy.sh`에 작성한다
- [ ] T028 [P] [US2] 내부 listener 실패·외부 승인 실패 후보가 current/known-good으로 승격되지 않고 이전 ref로 복구되는 장애 테스트를 `infra/unity-server/tests/failure/deploy-rollback.sh`에 작성한다
- [ ] T029 [P] [US2] process running·internal listening·external handshake·approved admission을 서로 다른 상태로 판정하는 테스트를 `infra/unity-server/tests/integration/game-readiness.sh`에 작성한다

### Implementation for User Story 2

- [ ] T030 [US2] 불변 game image, `11F-01`, maxPlayers 40, 비관리자·cap drop, 내부 7777, replay volume과 Secret mount를 `infra/unity-server/compose.yaml`에 구성한다
- [ ] T031 [P] [US2] image digest/full SHA, Secret 파일, demo network, volume과 7777 비공개를 배포 전에 검사하는 `infra/unity-server/scripts/preflight.sh`를 구현한다
- [ ] T032 [US2] infra-001 target lock과 release state를 재사용해 candidate를 `--no-deps`로 올리고 비대상 restart count를 보존하는 `infra/unity-server/scripts/deploy-game.sh`를 구현한다
- [ ] T033 [US2] 내부 listener와 실제 승인 WSS를 모두 통과해야 current/known-good을 갱신하는 `infra/unity-server/scripts/promote-game.sh`를 구현한다
- [ ] T034 [US2] 검증 실패 시 실패 ref를 기록하고 마지막 known-good image로 game만 복구하는 `infra/unity-server/scripts/rollback-game.sh`를 구현한다
- [ ] T035 [P] [US2] release/client 호환 ref, 단계별 readiness와 비대상 restart delta를 민감정보 없이 수집하는 `infra/unity-server/scripts/collect-deploy-evidence.sh`를 구현한다
- [ ] T036 [US2] game 배포·검증·승격·복구와 단일 EC2 전체 장애 한계를 `infra/unity-server/runbooks/deploy-and-rollback.md`에 작성한다

**Checkpoint**: game-only 배포가 다른 demo 서비스와 분리되고 실패 후보는 known-good으로 복구된다.

---

## Phase 5: User Story 3 — 유효한 사용자만 월드에 입장한다 (Priority: P0)

**Goal**: Unity 서버가 Backend 조회 없이 signed grant를 검증하고 동시·재시작 후 재사용과 클라이언트 신원 위조를 플레이어 생성 전에 차단한다.

**Independent Test**: 정상 grant는 한 번만 승인되고 변조·만료·대상 불일치·동시 재사용·재시작 후 재사용은 모두 거부되며 Backend 중단 중 이미 발급된 grant의 최초 입장은 성공해야 한다.

### Tests for User Story 3 ⚠️

- [ ] T037 [P] [US3] HS256 이외 알고리즘, 잘못된 서명·issuer·audience·시간·target·필수 claim을 거부하는 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/WorldEntryTokenValidatorTests.cs`에 작성한다
- [ ] T038 [P] [US3] 동일 JTI 동시 소비 1건만 성공, append/flush 실패 fail-closed, 만료 정리와 재기동 복원을 검증하는 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/UsedGrantLedgerTests.cs`에 작성한다
- [ ] T039 [P] [US3] 클라이언트 userId·nickname·avatar 변조를 무시하고 token claim만 SessionDataStore에 저장하는 approval 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/ConnectionApprovalSecurityTests.cs`에 작성한다
- [ ] T040 [P] [US3] member/guest claim, JTI 고유성, TTL 120초, 전용 key와 token 원문 비로그를 검증하는 Backend 테스트를 `backend/src/test/java/com/example/ssafesta/world/WorldEntryTokenIssuerTest.java`에 작성한다
- [ ] T041 [P] [US3] 사용 grant를 소비한 뒤 game container를 교체해 만료 전 재사용이 계속 거부되는 통합 테스트를 `infra/unity-server/tests/security/replay-after-restart.sh`에 작성한다

### Implementation for User Story 3

- [ ] T042 [P] [US3] compact JWS Base64URL decode, HS256 constant-time 검증과 claim parsing을 `festa-unity/Assets/_Project/Scripts/Network/Security/WorldEntryTokenValidator.cs`에 구현한다
- [ ] T043 [P] [US3] `SHA-256(jti)|exp`만 원자 append·flush하고 부팅 복원·만료 정리·동시 소비를 직렬화하는 ledger를 `festa-unity/Assets/_Project/Scripts/Network/Security/UsedGrantLedger.cs`에 구현한다
- [ ] T044 [US3] token 검증 성공 뒤 ledger 소비까지 완료된 claim으로만 승인하고 모든 보안 실패를 제한된 reason으로 거부하도록 `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs`를 수정한다
- [ ] T045 [US3] 검증된 신원만 player spawn에 전달하고 연결 종료 시 제거하도록 `festa-unity/Assets/_Project/Scripts/Network/Session/SessionDataStore.cs`의 lifecycle 경계를 보강한다
- [ ] T046 [US3] 전용 Secret 파일, issuer/audience, world/channel과 ledger 경로를 server process에 전달하도록 `infra/unity-server/compose.yaml`의 game 환경과 mount를 연결한다
- [ ] T047 [P] [US3] Secret·token·JTI·nickname 원문 없이 승인/거부 범주와 client ID만 남기도록 `backend/src/main/java/com/example/ssafesta/world/`와 `festa-unity/Assets/_Project/Scripts/Network/` 로그를 정리한다
- [ ] T048 [US3] Backend network를 차단한 상태에서 미사용 정상 grant 최초 입장과 잘못된 grant 거부를 실행하는 `infra/unity-server/tests/failure/backend-unavailable-admission.sh`를 구현한다
- [ ] T049 [US3] token 생성·검증·소비·만료 정리·Secret 회전과 ledger 장애 시 fail-closed 절차를 `infra/unity-server/runbooks/world-entry-token.md`에 작성한다

**Checkpoint**: 안전한 월드 입장 MVP(US1+US3)가 완성되고 Backend 일시 장애와 토큰 재사용이 분리된다.

---

## Phase 6: User Story 4 — 무입력 연결과 재접속을 검증한다 (Priority: P0)

**Goal**: 회원·게스트 브라우저가 10분 무입력 상태에서도 유지되고 종료 정리 뒤 사용자가 새 grant로 30초 안에 수동 재접속하게 한다.

**Independent Test**: 외부 브라우저 2개를 10분 방치한 뒤 한 연결을 종료·정리하고 새 grant로 재접속해 중복 player 없이 상호 이동을 재개해야 한다.

### Tests for User Story 4 ⚠️

- [ ] T050 [P] [US4] client disconnect에서 player와 SessionDataStore가 제거되고 이전 grant를 다시 쓰지 않는 Unity 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/DisconnectCleanupTests.cs`에 작성한다
- [ ] T051 [P] [US4] 자동 재접속 없이 종료 안내→사용자 선택→새 session 발급→새 연결 상태 전이를 검증하는 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/ManualReconnectFlowTests.cs`에 작성한다
- [ ] T052 [P] [US4] Nginx 180초 초기 timeout, Upgrade 유지와 cache/buffering off를 검사하는 테스트를 `infra/unity-server/tests/integration/idle-timeout.sh`에 작성한다
- [ ] T053 [P] [US4] 회원·게스트 브라우저 2개의 10분 무입력·종료·30초 재접속 결과 필드를 검사하는 증거 테스트를 `infra/unity-server/tests/evidence/p0-evidence.sh`에 작성한다

### Implementation for User Story 4

- [ ] T054 [US4] 연결 종료 reason을 표시하고 사용자가 누를 때만 새 world-session을 요청하도록 `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionStatusHud.cs`를 구현한다
- [ ] T055 [US4] disconnect callback에서 session/player를 한 번만 정리하고 수동 재접속 시 이전 payload를 폐기하도록 `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs`를 보강한다
- [ ] T056 [P] [US4] `proxy_read_timeout`·`proxy_send_timeout` 초기값 180초와 장시간 Upgrade 설정을 `infra/unity-server/nginx/world.conf.template`에 반영한다
- [ ] T057 [US4] 회원·게스트 두 브라우저의 상호 이동·10분 무입력·종료 정리·새 grant 재접속을 안내하고 시간 측정하는 `infra/unity-server/scripts/verify-p0-browser.sh`를 구현한다
- [ ] T058 [P] [US4] Cloudflare/Nginx/Unity 중 종료 계층과 관찰 시각을 분리해 기록하는 `infra/unity-server/scripts/collect-idle-evidence.sh`를 구현한다
- [ ] T059 [US4] 10분 동안 예상 밖 종료 0이면 180초를 확정하고 실패 시 계층 진단·조정·동일 시험 반복을 요구하는 `infra/unity-server/runbooks/idle-and-reconnect.md`를 작성한다
- [ ] T060 [US4] P0 결과의 release/server/client ref, 10분 유지, 종료 정리, 30초 재접속과 비대상 restart delta를 `infra/unity-server/evidence/p0-result.md`에 기록한다

**Checkpoint**: US1~US4 P0 외부 시연 기준이 모두 검증되고 최종 Nginx idle timeout이 증거에 남는다.

---

## Phase 7: User Story 5 — 한 채널의 시연 수용량을 확인한다 (Priority: P1)

**Goal**: 실제 외부 WSS 계약으로 `1→10→20→30→40`명을 단계적으로 시험하고 반복 성공한 최대값 이하만 안전 수용량으로 공개한다.

**Independent Test**: 각 단계를 10분 유지하며 승인·종료·EC2 자원·다른 demo 서비스 상태를 기록하고 최고 성공 단계를 두 번 반복한다.

### Tests for User Story 5 ⚠️

- [ ] T061 [P] [US5] client별 별도 grant, 목표 client 수, 10분 유지와 결과 집계를 검증하는 runner 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/CapacityRunnerTests.cs`에 작성한다
- [ ] T062 [P] [US5] 1·10·20·30·40 단계, unexpected disconnect, restart count와 CPU·memory·network 필수값을 검사하는 증거 테스트를 `infra/unity-server/tests/evidence/capacity-evidence.sh`에 작성한다
- [ ] T063 [P] [US5] 40명 초과의 41번째 접속이 `SERVER_FULL`로 player 생성 전에 거부되는 Unity 테스트를 `festa-unity/Assets/_Project/Tests/EditMode/ServerCapacityLimitTests.cs`에 작성한다

### Implementation for User Story 5

- [ ] T064 [US5] 외부 WSS endpoint와 개별 grant를 사용해 자동 입장·최소 이동·연결 유지를 수행하는 Unity load client를 `festa-unity/Assets/_Project/Scripts/LoadTest/WorldCapacityClient.cs`에 구현한다
- [ ] T065 [US5] Unity load client를 headless runner로 빌드하는 Editor 진입점을 `festa-unity/Assets/_Project/Editor/BuildWorldCapacityRunner.cs`에 구현한다
- [ ] T066 [US5] runner를 `1→10→20→30→40`명으로 늘려 단계당 10분 실행하고 최고 성공 단계를 두 번 반복하는 `infra/unity-server/scripts/run-capacity-test.sh`를 구현한다
- [ ] T067 [P] [US5] 단계별 game/host CPU·memory·network와 Backend·AI·web health/restart delta를 수집하는 `infra/unity-server/scripts/collect-capacity-metrics.sh`를 구현한다
- [ ] T068 [US5] 단계별 승인·종료·자원·비대상 상태와 반복 결과를 `infra/unity-server/evidence/capacity-result.md`에 기록한다
- [ ] T069 [US5] 반복 성공 최대값 이하만 시연 허용 인원으로 채택하고 40명 미달을 완료로 표시하지 않는 운영 절차를 `infra/unity-server/runbooks/capacity.md`에 작성한다

**Checkpoint**: 단일 채널의 검증된 안전 수용량이 수치와 반복 증거로 확정된다.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 전체 계약 정합성, 보안, 문서와 실행 가능성을 최종 검증한다.

- [ ] T070 [P] `docs/08_Backend_API_명세서.md`와 `docs/16_Realtime_통신_명세서.md`의 문자열 `serverEndpoint` 초안을 구조화 endpoint 계약으로 정합화한다
- [ ] T071 [P] `festa-unity/Docs/deployment-handoff.md`의 ALB/ECS 설명을 현재 Cloudflare/Nginx 경계로 정리하고 동결 기준선 재사용 범위를 명시한다
- [ ] T072 [P] 저장소·Compose config·container env·Backend/Unity 로그·evidence에서 token/Secret/private key 원문을 검사하는 `infra/unity-server/tests/security/secret-scan.sh`를 작성한다
- [ ] T073 모든 Backend 테스트, Unity EditMode 테스트와 `infra/unity-server/tests/run-static.sh`를 실행하고 실패를 해당 코드 또는 테스트에서 해결한다
- [ ] T074 `specs/infra-003-unity-server-deploy/quickstart.md`의 local, P0, P1 절차를 순서대로 실행하고 실제 결과 링크를 추가한다
- [ ] T075 FR-001~FR-027과 SC-001~SC-009를 테스트·증거·운영 문서에 매핑해 `specs/infra-003-unity-server-deploy/checklists/implementation.md`를 완료한다
- [ ] T076 INFRA 작업 결과와 발생한 문제의 `INFRA-T-번호` 링크를 `docs/JSW/24_작업일지.md` 및 `docs/JSW/25_트러블슈팅.md`에 기록한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음
- **Foundational (Phase 2)**: Setup 완료 후 시작하며 모든 사용자 스토리를 차단한다
- **US1 (Phase 3)**: Foundational 이후 시작 가능하지만 실제 안전한 입장 완료 판정은 US3 검증과 결합한다
- **US2 (Phase 4)**: Foundational 이후 시작 가능하며 US1/US3의 외부 승인 smoke가 있어야 최종 승격 시험을 완료한다
- **US3 (Phase 5)**: Foundational 이후 US1 Backend issuer와 연결해 완료한다
- **US4 (Phase 6)**: US1+US2+US3 완료 후 실제 외부 경로에서 수행한다
- **US5 (Phase 7)**: US1~US4 P0 통과 후 수행한다
- **Polish (Phase 8)**: 목표 스토리 전체 완료 후 수행한다

### User Story Dependency Graph

```text
Setup → Foundational
                 ├─→ US1 ─┐
                 ├─→ US2 ─┼─→ US4 → US5 → Polish
                 └─→ US3 ─┘
```

### Within Each User Story

- 해당 스토리의 테스트를 먼저 작성하고 요구 동작이 없어 실패하는지 확인한다.
- 모델·설정·계약을 구현한 뒤 service/validator를 구현한다.
- Backend/Unity 구현 뒤 Compose/Nginx와 외부 검증을 연결한다.
- 자동 테스트와 독립 시험이 모두 통과해야 다음 의존 스토리로 이동한다.
- 동일 파일을 수정하는 작업은 번호 순서대로 수행한다.

### Parallel Opportunities

- T002~T005는 서로 다른 파일에서 병렬 가능하다.
- T007~T011은 T006 계약 테스트 작성 뒤 서로 다른 경계에서 병렬 가능하다.
- US1의 T015~T018, US2의 T026~T029, US3의 T037~T041, US4의 T050~T053, US5의 T061~T063은 각 스토리 안에서 병렬로 작성 가능하다.
- Foundational 뒤 US1 Backend/API, US2 배포 골격, US3 Unity 보안 테스트는 파일 소유자가 다르면 병렬 진행할 수 있다.
- T070~T072는 서로 다른 문서·검사 파일에서 병렬 가능하다.

## Parallel Examples

### US1 + US2 + US3 초기 테스트

```text
Task: T015 회원·게스트 world-session API 통합 테스트
Task: T026 game Compose 계약 테스트
Task: T037 Unity token validator 테스트
Task: T038 used-grant ledger 동시성·복원 테스트
```

### US4 외부 실측 준비

```text
Task: T050 disconnect cleanup 테스트
Task: T051 수동 재접속 상태 전이 테스트
Task: T052 Nginx idle timeout 테스트
Task: T053 P0 evidence schema 테스트
```

## Implementation Strategy

### Secure MVP First

1. Phase 1 Setup 완료
2. Phase 2 Foundational 완료
3. US1의 구조화 endpoint·공개 WSS 구현
4. US3의 signed grant 자체 검증·replay 차단 구현
5. US1+US3 독립 시험으로 안전한 외부 입장 MVP 검증

### P0 Release Readiness

1. Secure MVP에 US2 game-only 배포·rollback 추가
2. US4의 회원·게스트 10분 무입력과 수동 재접속 실측
3. US1~US4 전체 통과 뒤 demo P0 완료 판정

### P1 Capacity

1. P0 기준선을 고정한다.
2. 외부 Unity runner를 1·10·20·30·40명으로 단계별 실행한다.
3. 최고 성공 단계를 두 번 반복하고 그 이하만 안전 수용량으로 기록한다.

## Notes

- `[P]`는 다른 파일에서 병렬 가능한 작업이며 같은 파일 수정은 순차 실행한다.
- 실제 도메인·EC2·상위 80/443 권한은 infra-002 C-01/C-02 입력을 기다리되 로컬 코드·정적 테스트를 먼저 완료할 수 있다.
- 외부 WSS·10분 idle·40명 결과는 실제 실측 없이 완료 처리하지 않는다.
- `festa-unity/Docker/Dockerfile`, 이동·스폰·Booth Runtime 기준선은 재구현하거나 리팩터링하지 않는다.
- 자동 재접속, 다중 채널, 고가용성, ALB/NLB/ACM/ECS는 이 작업 목록에 추가하지 않는다.
