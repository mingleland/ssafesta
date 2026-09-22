# Tasks: 변경 컴포넌트 CI와 dev 배포

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`
**Branch policy**: GitLab CI/Runner는 `feature/* → develop` MR의 Front·Back merge gate를 수행한다. Game MR은 Jenkins Unity agent의 무배포 컴파일·EditMode gate가 검증한다. Jenkins `develop` job은 Squash Merge된 변경 컴포넌트 CI·dev 배포와 수동 demo promotion을 수행한다.

## Phase 1: GitLab MR merge gate

**Purpose**: GitLab native `rules:changes`로 Front·Back의 merge 전 build·test를 최소 경로로 강제한다.

- [X] T001 `infra/tests/acceptance/us1-gitlab-mr-gate.sh`에 Front-only, Back-only, Front·Back shared-path MR gate fixture를 작성한다
- [X] T002 `.gitlab-ci.yml`에 MR pipeline workflow와 `jira-*` job 정의 보존·비실행 규칙을 구현한다
- [X] T003 `.gitlab-ci.yml`에 `rules:changes` 기반 `front-test`·`front-build`·`back-test`·`back-build` job과 기존 `ci/test`·`ci/build` 호출을 추가한다
- [X] T004 `infra/evidence/gitlab-runner-gate.md`에 Docker 가능한 GitLab Runner 배정과 Front·Back MR gate 실측을 기록한다
- [X] T005 `infra/evidence/gitlab-merge-policy.md`에 GitLab required pipeline·protected `develop` merge 차단 설정 실측을 기록한다

**Checkpoint**: 필수 GitLab job 실패 MR은 merge되지 않으며, 성공 MR은 어떤 dev 컨테이너도 변경하지 않는다.

---

## Phase 1A: Game MR Unity test-only gate

**Purpose**: Game 변경 MR의 head SHA를 Jenkins Unity agent에서 `ci/test`로 검증하고, 결과를 GitLab의 필수 상태로 게시한다. `ci/test`는 Unity 스크립트 컴파일과 EditMode 정적 검사를 포함한다. 이 경로는 build·package·Registry·dev/demo 배포를 절대 호출하지 않는다.

- [X] T044 [P] `infra/tests/acceptance/us1-gitlab-mr-gate.sh`에 Game 변경이 Unity MR gate를 요구하고, docs-only MR에는 요구하지 않는 contract fixture를 추가한다
- [X] T045 [P] `infra/jenkins/tests/unity-mr-validation.sh`에 MR head SHA checkout, `ci/test` 실행, build/package/deploy 명령 부재와 실패 상태 게시 계약을 검사하는 fixture를 작성한다
- [X] T046 `infra/jenkins/pipelines/unity-mr-validation.groovy`에 Unity agent의 MR head SHA checkout → `ci/test` → GitLab commit status 게시 순서를 구현한다. 이 pipeline에는 image build, package, Compose, deploy credential 또는 promotion stage를 두지 않는다
- [X] T047 `infra/jenkins/jobs/gitlab-unity-mr-validation.groovy`에 Game MR만 수신하고 source SHA·MR 식별자를 전달하는 Jenkins job을 정의한다
- [X] T048 `.gitlab-ci.yml`에 `festa-unity/**`, Game CI adapter와 Unity project 설정 변경을 감지해 Jenkins Unity MR gate를 dispatch하고, Jenkins 결과가 GitLab pipeline의 필수 성공 상태가 되도록 연결한다
- [X] T049 [P] `infra/jenkins/tests/test-foundation.sh`에 MR Unity job의 Unity agent label, no-deploy 명령 집합, GitLab status context와 Jenkins credential masking을 정적 검증한다
- [ ] T050 `infra/evidence/gitlab-unity-mr-gate.md`에 정상 EditMode, 의도적 컴파일/EditMode 실패, agent 미가용 실패가 각각 GitLab merge를 차단하고 dev/demo 컨테이너 restart delta가 0인 실측을 기록한다 — 증거 파일과 수집 명령은 준비됐고, MR 3건 실행 결과만 남았다

**Checkpoint**: Game MR의 Unity 컴파일 또는 EditMode가 실패·timeout·skip이면 merge되지 않으며, 성공·실패 어느 경우에도 dev/demo 런타임은 변경되지 않는다.

---

## Phase 2: Jenkins develop 선택·배포 경계

**Purpose**: Jenkins는 merge 후 `develop` range만 판별하고 dev 배포를 담당한다.

- [X] T006 `infra/jenkins/tests/detect-changed-components.sh`에 develop push 단일·다중·shared CI·docs-only·미분류 경로 selection fixture를 작성한다
- [X] T007 `infra/jenkins/scripts/detect-changed-components.sh`에 develop Git diff 범위 검증과 `ai`, `back`, `front`, `game` JSON selection 출력을 구현한다
- [X] T008 `infra/jenkins/scripts/detect-changed-components.sh`에 알려진 공통 CI·Infra 경로의 전체 컴포넌트 CI·비배포, 미분류 in-scope 경로 fail-closed, docs-only no-op을 구현한다
- [X] T009 `Jenkinsfile`에 `develop` push만 받는 dispatcher를 추가하고 feature MR branch dispatch를 제거한다
- [X] T010 `infra/jenkins/pipelines/component.groovy`가 `CI_COMPONENT`, source SHA, artifact directory를 입력으로 받아 merge 후 CI만 수행하도록 분리한다
- [X] T011 `infra/jenkins/pipelines/component.groovy`에 selected component stage summary·artifact fingerprint 기록을 추가한다
- [X] T012 `infra/jenkins/jobs/gitlab-develop-multibranch.groovy`를 추가해 develop push 전용 Jenkins job을 정의한다
- [X] T012A `infra/jenkins/pipelines/develop.groovy`에서 `game` 빌드를 앱 컴포넌트 뒤로 옮기고 release manifest 를 배포 단위(`app` / `game`)별로 분리해, Unity 실행기 대기가 `ai`·`back`·`front` 배포를 막지 않게 한다 (2026-09-18 #468 실측: unity 대기 50분 뒤 전체 ABORT 로 완성된 back·front 이미지가 배포되지 못했다)
- [ ] T012B 서로 다른 MR 이 한 푸시 범위로 묶이지 않도록 배포 트리거를 MR 단위로 분리한다 — 머지 커밋별 빌드 또는 GitLab MR 머지 웹훅 기반 트리거. 현재는 `GIT_BEFORE_SHA..GIT_COMMIT` 범위 판정이라 3초 차로 머지된 백엔드·게임 MR 이 한 빌드가 된다

**Checkpoint**: Jenkins develop job은 MR test-only job과 분리되어 develop push의 selection 결과만 dev 배포 후보로 만든다.

---

### 운영 실측 (2026-09-09)

- [X] `festa-gitlab-develop/develop`이 GitLab `develop` push로 자동 실행되고, `SELECTED_COMPONENTS: ai, back, front, game; deploy disabled in Phase 2`를 출력함을 확인했다.
- [X] 새 Multibranch Job에서 selection artifact를 기록하고 AI·Back·Front·Game CI 순서로 진입하며, Phase 2에서 dev 컨테이너를 재생성하지 않음을 확인했다.
- [X] Unity CI의 Missing Prefab 실패는 `festa-unity/Assets/_Project/Scenes/main.unity` 기존 소스 결함으로 분리한다. selector·Multibranch·agent 연결 실패로 처리하지 않는다.

---

## Phase 3: User Story 1 - 파트 변경을 독립적으로 검증하고 demo에 배포한다 (Priority: P0) 🎯 MVP

**Goal**: Squash Merge된 `develop`의 변경 컴포넌트만 모두 검증한 뒤 하나의 batch로 `demo.ssafesta.world`에 안전하게 반영한다. 별도 `dev` 환경은 운영하지 않는다 (spec §Session 2026-09-17). WebGL은 Unity 담당자가 QA 완료 최종 zip을 GitLab Generic Package Registry에 업로드하면 별도 Jenkins job이 deploy-agent에서 EC2 nginx release로 승격한다.

**Independent Test**: back 단독 변경, shared CI 변경, back+front 동시 변경, 강제 verify 실패를 각각 실행해 selection·비배포·batch rollback을 확인한다. WebGL은 정상 package, bad SHA, ZIP traversal, bad manifest, 중복 trigger, 전환 후 HTTP 실패를 실행해 release 설치·원자적 current·rollback·retention을 확인한다.

- [X] T013 [P] [US1] `infra/jenkins/tests/deploy-dev-batch.sh`에 단일 component 성공, 다중 component 원자적 승격, 가역 실패 snapshot rollback fixture를 작성한다
- [X] T014 [P] [US1] `infra/jenkins/tests/freshness-develop-push.sh`에 superseded develop SHA가 lock 획득 후 배포되지 않는 fixture를 작성한다
- [X] T015 [US1] `infra/jenkins/scripts/deploy-dev-batch.sh`에 candidate image 사전 검증, batch lock, affected current/known-good snapshot, ordered service-scoped deploy를 구현한다
- [X] T016 [US1] `infra/jenkins/scripts/deploy-dev-batch.sh`에 모든 component verify 후 batch active 승격과 가역 실패 시 batch가 변경한 component만 snapshot 복원하는 처리를 구현한다
- [X] T017 [US1] `infra/jenkins/scripts/deploy-dev-batch.sh`에 DB·secret/config·비가역·unknown 실패의 자동 rollback 금지 및 `MANUAL_ACTION_REQUIRED` evidence 출력을 구현한다
- [X] T017A [US1] develop 머지 batch 의 배포 대상을 아무도 조회하지 않던 `festa-dev` 컨테이너에서 Nginx 가 실제로 프록시하는 `festa-demo`(18080/18081/18082)로 정합한다. `deploy-environment.sh`·`verify-environment.sh`가 `dev|demo`를 모두 받고, `deploy-dev-batch.sh`는 `FESTA_DEPLOY_ENVIRONMENT`(기본 `demo`)로 대상을 고른다
- [X] T017B [US1] `infra/environments/compose/demo/{base,ai,back,front}.yaml`과 `infra/environments/config/manifests/demo.json`을 추가한다. DB·Redis 값은 compose 에 박지 않고 `COMPONENT_ENV_FILE` 하나에서만 읽으며, `PUBLIC_API_BASE_URL`·`PUBLIC_UNITY_BUILD_BASE`는 비면 기동을 거부한다. game 오버레이는 두지 않는다 (infra-003 소유)
- [X] T017C [US1] `develop.groovy`가 `DEMO_*` credential 로 바인딩하고 `PUBLIC_API_BASE_URL`을 `ROOT_DOMAIN` 으로 보강하도록 연결한다. `DEMO_INTERNAL_INFRA_TO_SPRING_TOKENS_CREDENTIAL_ID`를 controller·CASC 에 노출한다
- [X] T017D [US1] `infra/environments/tests/contract/demo-components.sh`에 루프백 포트·fail-closed 공개 엔드포인트·env_file 단일 출처·demo game 오버레이 부재 계약을 검증하는 테스트를 추가한다
- [X] T018 [US1] `infra/jenkins/pipelines/develop.groovy`에 `develop` range detector → selected CI 전체 성공 gate → dev batch 호출 순서를 연결한다
- [X] T019 [US1] `infra/jenkins/pipelines/develop.groovy`에 deploy 직전 develop head 재확인과 superseded run 무변경 종료를 연결한다
- [X] T020 [US1] `infra/environments/tests/integration/dev-component-isolation.sh`에 단일 component 배포가 나머지 세 service를 recreate하지 않는 EC2 rehearsal을 추가한다
- [X] T021 [US1] `infra/environments/tests/failure/dev-deploy-failure.sh`에 다중 component deploy/verify 실패 시 snapshot rollback rehearsal을 추가한다
- [X] T022 [US1] `specs/infra-001-ci-cd-pipelines/quickstart.md`에 GitLab MR gate와 Jenkins develop 단일·다중·rollback 실측 절차를 갱신한다
- [X] T022A [P] [US1] `infra/jenkins/tests/deploy-webgl-release.sh`에 정상 package, bad SHA, ZIP traversal, bad manifest, 중복 trigger, 전환 후 HTTP 실패 rollback과 retention fixture를 작성한다
- [X] T022B [US1] Jenkins에 `read_package_registry` 전용 GitLab Deploy Token credential을 만들고 Unity 담당자 PC→Jenkins 외부 trigger 접근, deploy-agent의 `/srv/festa/webgl` bind와 공개 URL 접근 preflight를 `infra/evidence/webgl-package-deploy-preflight.md`에 실측 기록한다
- [X] T022C [US1] `infra/jenkins/scripts/deploy-webgl-release.sh`에 Registry download, SHA-256·안전한 ZIP·manifest 검증, immutable release 설치, 원자적 `current`, 공개 HTTP 검증과 실패 rollback을 구현하고 Nginx가 `.br`·`.unityweb`을 동일한 Brotli 계약으로 제공하게 한다
- [X] T022D [US1] `infra/jenkins/scripts/publish-webgl-release.sh`, `infra/jenkins/jobs/gitlab-webgl-package-deploy.groovy`, `infra/jenkins/pipelines/webgl-package-deploy.groovy`로 upload 성공 후 Jenkins parameterized job→deploy-agent 흐름을 연결한다. Windows Agent는 추가하지 않는다
- [X] T022E [US1] 동일 release/SHA 중복 trigger 멱등 처리, release ID의 다른 SHA 재사용 거부, `current`·`previous` 보호와 성공 배포 뒤 UTC timestamp legacy 최신 두 개만 보존하는 retention을 구현한다
- [X] T022F [US1] `specs/infra-001-ci-cd-pipelines/quickstart.md`에 credential, 업로드, Jenkins job, MIME·Brotli·Cache-Control, rollback 확인 절차를 추가한다
- [X] T022G [US1] Redis 세션 영속성을 위해 `infra/environments/redis/redis.conf`에 `appendonly yes`를, `infra/environments/compose/data/compose.yaml`에 `mem_limit: 256m`을 적용한다 (40명 피크 1.27MB 실측 기반)

**Checkpoint**: feature MR 하나는 변경 파트 CI만 수행하고, 해당 Squash merge는 그 파트만 `demo.ssafesta.world`에서 갱신한다. QA 완료 WebGL package 업로드는 별도 Jenkins deploy-agent job으로 EC2 정적 release를 원자적으로 갱신하며, Linux Dedicated Server 배포·WSS 검증은 infra-003의 독립 경로로 유지된다.

---

## Phase 4: User Story 4 - 비밀정보를 노출하지 않고 배포한다 (Priority: P0)

**Goal**: GitLab MR gate와 Jenkins dev batch 모두 기존 credential·scan 경계를 우회하지 않는다.

**Independent Test**: canary secret을 사용한 성공·실패 batch의 console, archive, manifest, workspace scan 결과가 모두 원문 미검출이다.

- [X] T023 [P] [US4] `infra/tests/security/test-secret-leak.sh`에 GitLab MR job log, dev batch state, Jenkins stage summary의 canary 누출 fixture를 추가한다
- [X] T024 [US4] `.gitlab-ci.yml`, `Jenkinsfile`, `infra/jenkins/pipelines/develop.groovy`의 새 경로를 각 CI secret masking·`infra/jenkins/scripts/with-credentials.sh`·`infra/jenkins/scripts/secret-scan.sh` 경계로 감싼다
- [X] T025 [US4] `infra/tests/acceptance/us4-secret-safety.sh`에 GitLab MR gate와 Jenkins develop batch의 성공·실패 증거 검증을 추가한다

**Checkpoint**: 새 자동 경로가 credential ID만 기록하고 값은 어떤 보존물에도 남기지 않는다.

---

## Phase 5: User Story 2 - demo에서 검증된 release를 production(main) 환경에 수동 승격한다 (Priority: P0)

**Goal**: develop push 자동배포로 갱신된 demo.ssafesta.world 에서 팀 검증을 통과한 known-good 릴리스만 수동 승인(Production Promotion)으로 main 및 ssafesta.world 에 배포한다.
**Architecture Invariant**: develop → main 승격 MR 은 절대 Squash 하지 않고 ancestry 를 보존한다 (`squash=false` 강제).
**Artifact Invariant**: main 승격 시 아티팩트를 재빌드하지 않고 demo 에서 검증된 동일 아티팩트를 프로덕션에 승격한다 (`Demo Artifact == Production Artifact`).
**Release State Invariant**: candidate → current → known-good 세 상태를 분리한다. readiness 통과는 current 까지만 보장하며, known-good 은 사람이 demo 를 실측한 뒤에만 갱신된다. 프로덕션 승격은 known-good 조합과 image ref 가 일치하는 릴리스에만 허용한다 (spec §Session 2026-09-18 규칙 5·10).

**Independent Test**: 미검증 current 및 비승인 release는 승격이 거절되고, human 검증된 known-good manifest 만 main 반영 및 프로덕션 배포 후 web→login→world→AI 검증을 모두 통과할 때만 성공이다.

 - [X] T026 [P] [US2] `infra/jenkins/tests/demo-promotion.sh`에 비승인·미검증 manifest 거절과 승인 dev manifest 수용 fixture를 작성한다
 - [X] T027 [US2] `infra/jenkins/pipelines/demo-promotion.groovy`를 추가해 demo 에서 검증된 known-good release manifest 만 입력으로 받는 수동 Production Promotion pipeline을 구현한다
 - [X] T028 [US2] `infra/jenkins/pipelines/demo-promotion.groovy`에서 기존 `build-release-manifest.sh`, `deploy-release.sh`, `verify-release.sh`, `decide-recovery.sh`, `rollback-release.sh`를 재사용해 프로덕션(`ssafesta.world`) 배포를 수행하도록 연결한다
 - [X] T029 [US2] `infra/jenkins/jobs/gitlab-demo-promotion.groovy`를 추가해 승인 권한과 manifest 입력 파라미터를 가진 수동 Jenkins job을 정의한다
 - [ ] T029A [US2] 프로덕션 진입점 조기 오픈을 위해 `infra/environments/nginx/sites/demo.conf.template`에 `${ROOT_DOMAIN}` 라우팅을 추가하고 Cloudflare TLS 프록시를 검증한다 (S15P21A604-928)
 - [X] T030 [US2] `infra/tests/acceptance/us2-demo-promotion.sh`에 정상, 비AI 가역 rollback, DB·secret/config 수동대기, AI-only 재시도 대기 rehearsal을 추가한다

**Checkpoint**: develop push 는 demo.ssafesta.world 로 자동 배포되고, 사람 검증을 통과한 known-good 릴리스만 No-Squash MR 을 통해 main 및 ssafesta.world 에 배포된다.

---

## Phase 6: User Story 3 - 릴리스·배포·복구 이력을 추적한다 (Priority: P1)

**Goal**: selection부터 dev batch·demo promotion·rollback까지 한 run ID로 재구성한다.

**Independent Test**: 성공 batch와 rollback batch 각각에서 source range, candidate image, before state, verification, 현재 release를 원본 log 없이 확인한다.

- [ ] T031 [P] [US3] `infra/tests/contract/test-dev-deployment-batch.sh`에 `DevDeploymentBatch` 상태 전이와 snapshot 참조 검증을 추가한다
- [X] T031A [US3] `infra/deploy/scripts/release-history.sh`를 추가해 current 가 된 릴리스의 환경 전체 조합을 `history/<releaseId>/`에 적재하고 조회(`list`·`show`)와 과거 릴리스 재배포(`restore`)를 제공한다 (규칙 1·2·7)
- [X] T031B [US3] `release-history.sh` 보관 정책을 최근 10개(`RELEASE_HISTORY_RETENTION`)로 두고 known-good 이 가리키는 이력은 개수와 무관하게 보호한다 (규칙 8)
- [X] T031C [US3] `infra/deploy/scripts/approve-known-good.sh`에 `environment` 대상을 추가해 demo 전체 조합을 `known-good/environment.json`으로 굳히고 같은 조합의 이력 검증 상태를 `verified`로 올린다 (규칙 4·9)
- [X] T031D [US3] `infra/jenkins/scripts/deploy-dev-batch.sh`의 상태 루트 기본값을 `/var/lib/festa-environments`로 정정하고, current 승격 직후 이력을 적재하며 롤백 성공 시 `current`를 되돌린 릴리스로 맞춘다 (규칙 6)
- [X] T031E [US3] `infra/jenkins/scripts/validate-demo-promotion.sh`가 `known-good/environment.json` 승인 조합과 image ref 가 일치하지 않는 릴리스를 거절하도록 승격 게이트를 강화한다 (규칙 5)
- [X] T031F [US3] `infra/jenkins/tests/release-history.sh`에 이력 적재·retention·known-good 보호·전체 조합 승인·검증 상태 전이·game restore 거절 검증을 추가한다
- [ ] T031G [US3] Jenkins 팀원 6인 개별 계정과 조회 전용·배포/승격 승인 권한 분리를 구성해 known-good 승인·수동 롤백·프로덕션 승격 수행자를 추적한다 (규칙 3, C-13)
- [ ] T032 [US3] `infra/jenkins/scripts/provenance.sh`에 selection record, batch ID, source range, candidate content ID, before state, rollback/verification evidence를 기록한다
- [ ] T033 [US3] `infra/deploy/scripts/show-release.sh`에 dev batch와 demo release의 current/known-good provenance 조회를 추가한다
- [ ] T034 [US3] `infra/tests/acceptance/us3-provenance.sh`에 성공·rollback·manual action 이력 재구성 검증을 추가한다

**Checkpoint**: 한 commit이 왜 어느 환경에 배포·복구됐는지 summary artifact만으로 추적된다.

---

## Phase 7: User Story 5 - 운영 로그의 특이사항과 서버 사용량을 관측한다 (Priority: P1)

**Goal**: 기존 관측 stack을 CI/CD 판정과 분리한 채 실제 운영에서 검증한다.

**Independent Test**: 승인 rule만 Mattermost에 전달되고 observability stack을 중지해도 build·deploy·application health가 유지된다.

- [ ] T035 [P] [US5] `infra/observability/BASELINE.md`에 EC2 실측 기반 retention, disk cap, scrape/evaluation interval, threshold 승인값을 기록한다
- [X] T035A [P] [US5] `infra/observability/signal-catalog.json`에 운영·제품·개발 신호의 원천, 구현 상태, 개인정보 등급과 소비자를 기록하고 CI에서 PII·고카디널리티 label을 거부한다
- [ ] T036 [US5] `infra/observability/grafana/provisioning/alerting/contact-points.yaml`의 Jenkins credential 참조와 Mattermost delivery를 실제 canary rule로 검증한다
- [ ] T037 [US5] `infra/tests/acceptance/us5-observability.sh`에 운영 stack 중지 상태에서 CI/dev deploy가 판정 변경 없이 완료되는 실환경 evidence를 추가한다

**Checkpoint**: 운영 관측 실패는 파이프라인의 성공·실패 판정에 영향을 주지 않는다.

---

## Phase 7A: Production Promotion final correction

**Purpose**: 승인된 Demo exact artifact를 canonical Production으로 전환하되 CURRENT, KNOWN-GOOD와 rollback 기준을 분리한다.

- [X] T051 `infra/deploy/scripts/bootstrap-production-data.sh`, `deploy-production-candidate.sh`, Redis ACL fixture와 runtime test에 `conversation:*` allow/deny, persisted ACL, additive evidence contract를 구현한다
- [X] T052 `prepare-production-cutover.sh`, maintenance Nginx template과 candidate deploy guard에 public fence 뒤 legacy 제거 규칙을 구현한다
- [X] T053 `activate-production-release.sh`, `verify-production-public.sh`, `approve-production-known-good.sh`에 verified candidate → CURRENT → external/human → KNOWN-GOOD 전환을 구현한다
- [X] T054 `rollback-production-release.sh`에 최초 migration의 maintenance 유지와 첫 canonical known-good 이후 exact previous rollback을 구현한다
- [X] T055 Demo root alias 제거, Production OAuth/WebGL/World contract test와 atomic WebGL pointer 전환을 구현한다
- [X] T056 `infra/jenkins/pipelines/production-promotion.groovy`에 main receipt 검증, readiness gate, maintenance, canonical 배치, public 검증, human approval, evidence archive와 failure handler를 연결한다
- [X] T057 `infra/jenkins/tests/production-runtime.sh`, `production-promotion.sh`에 Redis, Nginx, state, pipeline regression을 구현한다
- [X] T058 Production state README와 spec/plan/tasks를 최종 runtime contract에 맞춘다

**Checkpoint**: Stage 1 repository test는 Production runtime을 변경하지 않으며, 실제 activation은 main 승격과 모든 manual gate 뒤에만 수행한다.

---

## Phase 8: 실환경 gate 및 마무리

**Purpose**: 구현이 아닌 실제 Jenkins/GitLab/EC2에서만 확인 가능한 항목을 기록한다.

- [X] T038 [P] `infra/evidence/server-preflight.md`에 EC2, Docker/rootless, UFW, DNS/TLS, webhook, 관리 포트, disk baseline 실측을 기록한다
- [X] T039 [P] `infra/evidence/unity-agent-preflight.md`에 영속 Unity license, agent 재생성 유지, Editor/module, credential 없는 batch smoke evidence를 기록한다
- [ ] T040 `infra/evidence/gitlab-component-pipeline-rehearsal.md`에 GitLab MR gate와 Jenkins develop selected dev deployment 실측을 기록한다
- [ ] T041 `infra/evidence/demo-promotion-rehearsal.md`에 승인 release의 demo 통합 deploy/verification/rollback evidence를 기록한다
- [ ] T042 `infra/evidence/quickstart-results.md`에 `specs/infra-001-ci-cd-pipelines/quickstart.md` 전체 실행 결과를 연결한다
- [ ] T043 `docs/24_작업일지.md`, `docs/25_트러블슈팅.md`, `docs/26_팀_결정_필요사항.md`에 완료 task와 외부 의존·미해결 gate를 반영한다

---

## Dependencies & Execution Order

```text
T001–T005 GitLab Front·Back MR gate
  ├→ T044–T050 Jenkins Unity Game MR gate
  └→ T006–T012 Jenkins develop selection
    → T013–T022 dev batch (US1)
      ├→ T023–T025 secret verification (US4)
      ├→ T026–T030 manual demo promotion (US2)
      └→ T031–T034 provenance (US3)
T035–T037 observability validation (US5, independent after secrets)
all implementation → T038–T043 live evidence
```

## Parallel Opportunities

- T001 and T004; T006 and T010/T012; T013 and T014; T020 and T021; T023 and T025; T026 and T029; T031 and T033; T035 and T036 can proceed in parallel when their direct prerequisite is complete.
- The Unity pipeline test currently fails on missing prefabs in `festa-unity/Assets/_Project/Scenes/main.unity`; this is a game-team source fix, not an Infra-001 workaround. T039 records the agent evidence only after that test passes.

## Implementation Strategy

1. **MVP**: T001–T022와 T044–T050 — Front·Back GitLab gate, Game Unity MR gate, Jenkins selected dev deployment과 rollback을 검증한다.
2. Add T023–T025 before using any new route with real credentials.
3. Add T026–T030 to move demo only by approval.
4. Complete traceability/observability and then record actual EC2 evidence.

## Notes

- `[P]` means separate files with no incomplete direct dependency; it does not bypass the one Unity executor or a dev batch lock.
- Do not modify `festa-unity/Docker/`. Do not commit `.env`, tokens, license files, or runtime state.
- Every task uses exact repository-owned paths; external Unity source repair and credentials are recorded as dependencies, not simulated in Infra code.
