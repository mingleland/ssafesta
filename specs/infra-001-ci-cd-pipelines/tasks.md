# Tasks: 변경 컴포넌트 CI와 dev 배포

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`
**Branch policy**: GitLab CI/Runner는 `feature/* → develop` MR의 Front·Back merge gate만 수행한다. Jenkins는 Squash Merge된 `develop`의 변경 컴포넌트 CI·dev 배포와 수동 demo promotion을 수행한다.

## Phase 1: GitLab MR merge gate

**Purpose**: GitLab native `rules:changes`로 Front·Back의 merge 전 build·test를 최소 경로로 강제한다.

- [X] T001 `infra/tests/acceptance/us1-gitlab-mr-gate.sh`에 Front-only, Back-only, Front·Back shared-path MR gate fixture를 작성한다
- [X] T002 `.gitlab-ci.yml`에 MR pipeline workflow와 `jira-*` job 정의 보존·비실행 규칙을 구현한다
- [X] T003 `.gitlab-ci.yml`에 `rules:changes` 기반 `front-test`·`front-build`·`back-test`·`back-build` job과 기존 `ci/test`·`ci/build` 호출을 추가한다
- [X] T004 `infra/evidence/gitlab-runner-gate.md`에 Docker 가능한 GitLab Runner 배정과 Front·Back MR gate 실측을 기록한다
- [X] T005 `infra/evidence/gitlab-merge-policy.md`에 GitLab required pipeline·protected `develop` merge 차단 설정 실측을 기록한다

**Checkpoint**: 필수 GitLab job 실패 MR은 merge되지 않으며, 성공 MR은 어떤 dev 컨테이너도 변경하지 않는다.

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

**Checkpoint**: Jenkins는 MR을 재실행하지 않고 develop push의 selection 결과만 dev 배포 후보로 만든다.

---

### 운영 실측 (2026-09-09)

- [X] `festa-gitlab-develop/develop`이 GitLab `develop` push로 자동 실행되고, `SELECTED_COMPONENTS: ai, back, front, game; deploy disabled in Phase 2`를 출력함을 확인했다.
- [X] 새 Multibranch Job에서 selection artifact를 기록하고 AI·Back·Front·Game CI 순서로 진입하며, Phase 2에서 dev 컨테이너를 재생성하지 않음을 확인했다.
- [X] Unity CI의 Missing Prefab 실패는 `festa-unity/Assets/_Project/Scenes/main.unity` 기존 소스 결함으로 분리한다. selector·Multibranch·agent 연결 실패로 처리하지 않는다.

---

## Phase 3: User Story 1 - 파트 변경을 독립적으로 검증하고 dev에 배포한다 (Priority: P0) 🎯 MVP

**Goal**: Squash Merge된 `develop`의 변경 컴포넌트만 모두 검증한 뒤 하나의 dev batch로 안전하게 반영한다. WebGL은 Unity 담당자가 QA 완료 최종 zip을 GitLab Generic Package Registry에 업로드하면 별도 Jenkins job이 deploy-agent에서 EC2 nginx release로 승격한다.

**Independent Test**: back 단독 변경, shared CI 변경, back+front 동시 변경, 강제 verify 실패를 각각 실행해 selection·비배포·batch rollback을 확인한다. WebGL은 정상 package, bad SHA, ZIP traversal, bad manifest, 중복 trigger, 전환 후 HTTP 실패를 실행해 release 설치·원자적 current·rollback·retention을 확인한다.

- [X] T013 [P] [US1] `infra/jenkins/tests/deploy-dev-batch.sh`에 단일 component 성공, 다중 component 원자적 승격, 가역 실패 snapshot rollback fixture를 작성한다
- [X] T014 [P] [US1] `infra/jenkins/tests/freshness-develop-push.sh`에 superseded develop SHA가 lock 획득 후 배포되지 않는 fixture를 작성한다
- [X] T015 [US1] `infra/jenkins/scripts/deploy-dev-batch.sh`에 candidate image 사전 검증, batch lock, affected current/known-good snapshot, ordered service-scoped deploy를 구현한다
- [X] T016 [US1] `infra/jenkins/scripts/deploy-dev-batch.sh`에 모든 component verify 후 batch active 승격과 가역 실패 시 batch가 변경한 component만 snapshot 복원하는 처리를 구현한다
- [X] T017 [US1] `infra/jenkins/scripts/deploy-dev-batch.sh`에 DB·secret/config·비가역·unknown 실패의 자동 rollback 금지 및 `MANUAL_ACTION_REQUIRED` evidence 출력을 구현한다
- [X] T018 [US1] `infra/jenkins/pipelines/develop.groovy`에 `develop` range detector → selected CI 전체 성공 gate → dev batch 호출 순서를 연결한다
- [X] T019 [US1] `infra/jenkins/pipelines/develop.groovy`에 deploy 직전 develop head 재확인과 superseded run 무변경 종료를 연결한다
- [ ] T020 [US1] `infra/environments/tests/integration/dev-component-isolation.sh`에 단일 component 배포가 나머지 세 service를 recreate하지 않는 EC2 rehearsal을 추가한다
- [X] T021 [US1] `infra/environments/tests/failure/dev-deploy-failure.sh`에 다중 component deploy/verify 실패 시 snapshot rollback rehearsal을 추가한다
- [ ] T022 [US1] `specs/infra-001-ci-cd-pipelines/quickstart.md`에 GitLab MR gate와 Jenkins develop 단일·다중·rollback 실측 절차를 갱신한다
- [X] T022A [P] [US1] `infra/jenkins/tests/deploy-webgl-release.sh`에 정상 package, bad SHA, ZIP traversal, bad manifest, 중복 trigger, 전환 후 HTTP 실패 rollback과 retention fixture를 작성한다
- [ ] T022B [US1] Jenkins에 `read_package_registry` 전용 GitLab Deploy Token credential을 만들고 Unity 담당자 PC→Jenkins 외부 trigger 접근, deploy-agent의 `/srv/festa/webgl` bind와 공개 URL 접근 preflight를 `infra/evidence/webgl-package-deploy-preflight.md`에 실측 기록한다
- [X] T022C [US1] `infra/jenkins/scripts/deploy-webgl-release.sh`에 Registry download, SHA-256·안전한 ZIP·manifest 검증, immutable release 설치, 원자적 `current`, 공개 HTTP 검증과 실패 rollback을 구현하고 Nginx가 `.br`·`.unityweb`을 동일한 Brotli 계약으로 제공하게 한다
- [X] T022D [US1] `infra/jenkins/scripts/publish-webgl-release.sh`, `infra/jenkins/jobs/gitlab-webgl-package-deploy.groovy`, `infra/jenkins/pipelines/webgl-package-deploy.groovy`로 upload 성공 후 Jenkins parameterized job→deploy-agent 흐름을 연결한다. Windows Agent는 추가하지 않는다
- [X] T022E [US1] 동일 release/SHA 중복 trigger 멱등 처리, release ID의 다른 SHA 재사용 거부, `current`·`previous` 보호와 성공 배포 뒤 UTC timestamp legacy 최신 두 개만 보존하는 retention을 구현한다
- [X] T022F [US1] `specs/infra-001-ci-cd-pipelines/quickstart.md`에 credential, 업로드, Jenkins job, MIME·Brotli·Cache-Control, rollback 확인 절차를 추가한다

**Checkpoint**: feature MR 하나는 변경 파트 CI만 수행하고, 해당 Squash merge는 그 파트만 dev에서 갱신한다. QA 완료 WebGL package 업로드는 별도 Jenkins deploy-agent job으로 EC2 정적 release를 원자적으로 갱신하며, Linux Dedicated Server 배포·WSS 검증은 infra-003의 독립 경로로 유지된다.

---

## Phase 4: User Story 4 - 비밀정보를 노출하지 않고 배포한다 (Priority: P0)

**Goal**: GitLab MR gate와 Jenkins dev batch 모두 기존 credential·scan 경계를 우회하지 않는다.

**Independent Test**: canary secret을 사용한 성공·실패 batch의 console, archive, manifest, workspace scan 결과가 모두 원문 미검출이다.

- [X] T023 [P] [US4] `infra/tests/security/test-secret-leak.sh`에 GitLab MR job log, dev batch state, Jenkins stage summary의 canary 누출 fixture를 추가한다
- [X] T024 [US4] `.gitlab-ci.yml`, `Jenkinsfile`, `infra/jenkins/pipelines/develop.groovy`의 새 경로를 각 CI secret masking·`infra/jenkins/scripts/with-credentials.sh`·`infra/jenkins/scripts/secret-scan.sh` 경계로 감싼다
- [X] T025 [US4] `infra/tests/acceptance/us4-secret-safety.sh`에 GitLab MR gate와 Jenkins develop batch의 성공·실패 증거 검증을 추가한다

**Checkpoint**: 새 자동 경로가 credential ID만 기록하고 값은 어떤 보존물에도 남기지 않는다.

---

## Phase 5: User Story 2 - 승인된 release를 demo 통합 환경에 배포한다 (Priority: P0)

**Goal**: develop 자동배포와 demo 통합배포를 분리하고, dev 검증된 release만 수동 승인으로 demo에 올린다.

**Independent Test**: 비승인 release는 거절되고, 승인 manifest는 demo deploy 후 web→login→world→AI 검증을 모두 통과할 때만 성공이다.

- [X] T026 [P] [US2] `infra/jenkins/tests/demo-promotion.sh`에 비승인·미검증 manifest 거절과 승인 dev manifest 수용 fixture를 작성한다
- [X] T027 [US2] `infra/jenkins/pipelines/demo-promotion.groovy`를 추가해 승인된 active dev batch 또는 dev-verified release manifest만 입력으로 받는 수동 pipeline을 구현한다
- [X] T028 [US2] `infra/jenkins/pipelines/demo-promotion.groovy`에서 기존 `build-release-manifest.sh`, `deploy-release.sh`, `verify-release.sh`, `decide-recovery.sh`, `rollback-release.sh`를 재사용하도록 연결한다
- [X] T029 [US2] `infra/jenkins/jobs/gitlab-demo-promotion.groovy`를 추가해 승인 권한과 manifest 입력 파라미터를 가진 수동 Jenkins job을 정의한다
- [X] T030 [US2] `infra/tests/acceptance/us2-demo-promotion.sh`에 정상, 비AI 가역 rollback, DB·secret/config 수동대기, AI-only 재시도 대기 rehearsal을 추가한다

**Checkpoint**: develop push는 demo를 절대 변경하지 않으며 승인된 dev release만 demo에 배포된다.

---

## Phase 6: User Story 3 - 릴리스·배포·복구 이력을 추적한다 (Priority: P1)

**Goal**: selection부터 dev batch·demo promotion·rollback까지 한 run ID로 재구성한다.

**Independent Test**: 성공 batch와 rollback batch 각각에서 source range, candidate image, before state, verification, 현재 release를 원본 log 없이 확인한다.

- [ ] T031 [P] [US3] `infra/tests/contract/test-dev-deployment-batch.sh`에 `DevDeploymentBatch` 상태 전이와 snapshot 참조 검증을 추가한다
- [ ] T032 [US3] `infra/jenkins/scripts/provenance.sh`에 selection record, batch ID, source range, candidate content ID, before state, rollback/verification evidence를 기록한다
- [ ] T033 [US3] `infra/deploy/scripts/show-release.sh`에 dev batch와 demo release의 current/known-good provenance 조회를 추가한다
- [ ] T034 [US3] `infra/tests/acceptance/us3-provenance.sh`에 성공·rollback·manual action 이력 재구성 검증을 추가한다

**Checkpoint**: 한 commit이 왜 어느 환경에 배포·복구됐는지 summary artifact만으로 추적된다.

---

## Phase 7: User Story 5 - 운영 로그의 특이사항과 서버 사용량을 관측한다 (Priority: P1)

**Goal**: 기존 관측 stack을 CI/CD 판정과 분리한 채 실제 운영에서 검증한다.

**Independent Test**: 승인 rule만 Mattermost에 전달되고 observability stack을 중지해도 build·deploy·application health가 유지된다.

- [ ] T035 [P] [US5] `infra/observability/BASELINE.md`에 EC2 실측 기반 retention, disk cap, scrape/evaluation interval, threshold 승인값을 기록한다
- [ ] T036 [US5] `infra/observability/grafana/provisioning/alerting/contact-points.yaml`의 Jenkins credential 참조와 Mattermost delivery를 실제 canary rule로 검증한다
- [ ] T037 [US5] `infra/tests/acceptance/us5-observability.sh`에 운영 stack 중지 상태에서 CI/dev deploy가 판정 변경 없이 완료되는 실환경 evidence를 추가한다

**Checkpoint**: 운영 관측 실패는 파이프라인의 성공·실패 판정에 영향을 주지 않는다.

---

## Phase 8: 실환경 gate 및 마무리

**Purpose**: 구현이 아닌 실제 Jenkins/GitLab/EC2에서만 확인 가능한 항목을 기록한다.

- [ ] T038 [P] `infra/evidence/server-preflight.md`에 EC2, Docker/rootless, UFW, DNS/TLS, webhook, 관리 포트, disk baseline 실측을 기록한다
- [ ] T039 [P] `infra/evidence/unity-agent-preflight.md`에 영속 Unity license, agent 재생성 유지, Editor/module, credential 없는 batch smoke evidence를 기록한다
- [ ] T040 `infra/evidence/gitlab-component-pipeline-rehearsal.md`에 GitLab MR gate와 Jenkins develop selected dev deployment 실측을 기록한다
- [ ] T041 `infra/evidence/demo-promotion-rehearsal.md`에 승인 release의 demo 통합 deploy/verification/rollback evidence를 기록한다
- [ ] T042 `infra/evidence/quickstart-results.md`에 `specs/infra-001-ci-cd-pipelines/quickstart.md` 전체 실행 결과를 연결한다
- [ ] T043 `docs/24_작업일지.md`, `docs/25_트러블슈팅.md`, `docs/26_팀_결정_필요사항.md`에 완료 task와 외부 의존·미해결 gate를 반영한다

---

## Dependencies & Execution Order

```text
T001–T005 GitLab MR gate
  → T006–T012 Jenkins develop selection
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

1. **MVP**: T001–T022 — prove GitLab MR gate and Jenkins selected dev deployment, including rollback.
2. Add T023–T025 before using any new route with real credentials.
3. Add T026–T030 to move demo only by approval.
4. Complete traceability/observability and then record actual EC2 evidence.

## Notes

- `[P]` means separate files with no incomplete direct dependency; it does not bypass the one Unity executor or a dev batch lock.
- Do not modify `festa-unity/Docker/`. Do not commit `.env`, tokens, license files, or runtime state.
- Every task uses exact repository-owned paths; external Unity source repair and credentials are recorded as dependencies, not simulated in Infra code.
