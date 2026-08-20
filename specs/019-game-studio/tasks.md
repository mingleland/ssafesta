# Tasks: FESTA Game Studio

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Organization**: 계약 기반 즉시 작업을 먼저 완료한 뒤, #20 Frontend와 #21 Backend 결정이 필요한
작업만 해당 gate 뒤에 수행한다. #22 AI는 MVP 구현의 선행 조건이 아니다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일을 수정해 병렬 실행 가능
- **[USn]**: `spec.md`의 User Story
- 각 항목의 `After #20/#21`은 담당 파트 답변 전 착수 금지 gate다.

## Phase 1: Contract Foundation — 즉시 가능

**Purpose**: 앱·DB 결정을 선점하지 않고 모든 consumer가 공유할 실행 규칙을 고정한다.

- [ ] T001 [P] Add unsupported-schema negative fixture in `specs/019-game-studio/contracts/fixtures/invalid/unsupported-schema.json`
- [ ] T002 [P] Add missing-start-scene negative fixture in `specs/019-game-studio/contracts/fixtures/invalid/missing-start-scene.json`
- [ ] T003 [P] Add duplicate-object-id negative fixture in `specs/019-game-studio/contracts/fixtures/invalid/duplicate-object-id.json`
- [ ] T004 [P] Add invalid-dialogue-target negative fixture in `specs/019-game-studio/contracts/fixtures/invalid/invalid-dialogue-target.json`
- [ ] T005 Define fixture names and expected semantic error codes in `specs/019-game-studio/contracts/fixtures/manifest.json`
- [ ] T006 Extend the no-dependency contract runner to execute the manifest in `specs/019-game-studio/contracts/fixtures/validate-fixtures.mjs`
- [ ] T007 [P] Define Studio-to-Preview postMessage envelope, origin checks, and lifecycle in `specs/019-game-studio/contracts/game-preview-protocol.md`
- [ ] T008 [P] Define deterministic Trigger→Condition→Action ordering and execution budgets in `specs/019-game-studio/contracts/event-runtime-semantics.md`
- [ ] T009 Update runnable positive/negative validation steps in `specs/019-game-studio/quickstart.md`

**Checkpoint**: Positive fixture and every negative fixture produce deterministic results without FE/BE/Unity.

---

## Phase 2: Project Setup & Shared Foundations — decision gated

**Purpose**: 승인된 파트 구조 안에 공통 계약과 테스트 기반을 설치한다.

- [ ] T010 After #20, create the approved Vite/React app boundary and scripts in `festa-game-studio/package.json` and `festa-game-studio/vite.config.ts`
- [ ] T011 [P] After #20, configure strict TypeScript and lint rules in `festa-game-studio/tsconfig.json` and `festa-game-studio/oxlint.json`
- [ ] T012 Generate or hand-map GameProject v1 TypeScript types and guards in `festa-game-studio/src/contracts/gameProject.ts`
- [ ] T013 Add fixture-driven contract tests in `festa-game-studio/tests/contract/gameProject.contract.test.ts`
- [ ] T014 After #21, create the Spring game package boundary in `backend/src/main/java/com/example/ssafesta/game/`
- [ ] T015 [P] After #21, add append-only Flyway migration for approved Game aggregates in `backend/src/main/resources/db/migration/V*_game_studio.sql`
- [ ] T016 [P] After #21, define Game Studio error codes and exception mapping in `backend/src/main/java/com/example/ssafesta/game/api/GameErrorCode.java`

**Checkpoint**: FE contract test and BE application test can consume the same positive fixture.

---

## Phase 3: User Story 1 — 탐색·대화 게임 제작 (Priority: P1) 🎯 MVP Authoring

**Goal**: 코드 없이 TOP_DOWN 방, Item, Door, Event, DIALOGUE를 구성하고 로컬 상태에서 실행한다.

**Independent Test**: 빈 프로젝트에서 `열쇠 획득 → 문 상호작용 → 대화 → 완료`를 만들고 로컬 Preview한다.

### Tests

- [ ] T017 [P] [US1] Add runtime-state reducer unit tests in `festa-game-studio/tests/unit/runtimeState.test.ts`
- [ ] T018 [P] [US1] Add condition/action interpreter unit tests in `festa-game-studio/tests/unit/eventInterpreter.test.ts`
- [ ] T019 [P] [US1] Add dialogue graph traversal unit tests in `festa-game-studio/tests/unit/dialogueRunner.test.ts`

### Implementation

- [ ] T020 [US1] Implement immutable RuntimeSessionState initialization in `festa-game-studio/src/core/runtimeState.ts`
- [ ] T021 [P] [US1] Implement condition evaluation in `festa-game-studio/src/core/evaluateCondition.ts`
- [ ] T022 [P] [US1] Implement action reduction in `festa-game-studio/src/core/applyAction.ts`
- [ ] T023 [US1] Implement event ordering, action budget, and transition-depth guard in `festa-game-studio/src/core/runEvent.ts`
- [ ] T024 [US1] Implement DIALOGUE node/choice runner in `festa-game-studio/src/runtime/dialogue/dialogueRunner.ts`
- [ ] T025 After #20 [US1] Implement TOP_DOWN renderer adapter in `festa-game-studio/src/runtime/top-down/TopDownRuntime.ts`
- [ ] T026 After #20 [US1] Implement project authoring store with undo/redo in `festa-game-studio/src/studio/store/gameProjectStore.ts`
- [ ] T027 [P] After #20 [US1] Implement Scene list/start Scene editor in `festa-game-studio/src/studio/scenes/SceneListPanel.tsx`
- [ ] T028 [P] After #20 [US1] Implement grid/tile/object canvas in `festa-game-studio/src/studio/map/TopDownMapEditor.tsx`
- [ ] T029 [P] After #20 [US1] Implement typed Component/Event inspector in `festa-game-studio/src/studio/inspector/EventInspector.tsx`
- [ ] T030 [P] After #20 [US1] Implement DIALOGUE node/choice editor in `festa-game-studio/src/studio/dialogue/DialogueEditor.tsx`
- [ ] T031 [US1] Add the minimal authoring-to-local-preview integration test in `festa-game-studio/tests/integration/minimalAuthoringPreview.test.tsx`

**Checkpoint**: 서버와 Unity 없이 US1 수직 시나리오를 제작하고 Preview할 수 있다.

---

## Phase 4: User Story 2 — 저장 전 Preview와 Publish (Priority: P1)

**Goal**: Draft 충돌을 검출하고 유효한 snapshot만 불변 Published Version으로 발행한다.

**Independent Test**: invalid Draft Publish는 거부되고, 수정 후 Publish한 뒤 Draft 변경이 공개본을 바꾸지 않는다.

### Tests

- [ ] T032 [P] After #21 [US2] Add revision-conflict and authorization integration tests in `backend/src/test/java/com/example/ssafesta/game/GameDraftApiIntegrationTest.java`
- [ ] T033 [P] After #21 [US2] Add invalid-reference and immutable-publish integration tests in `backend/src/test/java/com/example/ssafesta/game/GamePublishIntegrationTest.java`
- [ ] T034 [P] After #20 [US2] Add Preview origin/lifecycle tests in `festa-game-studio/tests/integration/previewProtocol.test.ts`

### Implementation

- [ ] T035 After #21 [US2] Implement approved Game/GameVersion entities and repositories in `backend/src/main/java/com/example/ssafesta/game/domain/`
- [ ] T036 After #21 [US2] Implement revision-aware Draft service in `backend/src/main/java/com/example/ssafesta/game/application/GameDraftService.java`
- [ ] T037 After #21 [US2] Implement Schema and semantic validator adapter in `backend/src/main/java/com/example/ssafesta/game/application/GameProjectValidator.java`
- [ ] T038 After #21 [US2] Implement immutable Publish transaction in `backend/src/main/java/com/example/ssafesta/game/application/GamePublishService.java`
- [ ] T039 After #21 [US2] Implement authoring endpoints in `backend/src/main/java/com/example/ssafesta/game/api/GameAuthoringController.java`
- [ ] T040 After #20 [US2] Implement Draft/validate/publish API client in `festa-game-studio/src/app/api/gameAuthoringApi.ts`
- [ ] T041 After #20 [US2] Implement isolated iframe Preview host in `festa-game-studio/src/preview/PreviewHost.tsx`
- [ ] T042 After #20 [US2] Implement save-conflict and validation-error UI in `festa-game-studio/src/studio/publish/PublishPanel.tsx`
- [ ] T043 [US2] Run and document Draft→Preview→Publish acceptance flow in `specs/019-game-studio/quickstart.md`

**Checkpoint**: Draft와 Published 격리가 서버 통합 테스트로 증명된다.

---

## Phase 5: User Story 3 — 독립 웹 Published 게임 플레이 (Priority: P1)

**Goal**: Unity 없이 Published 게임을 독립 URL에서 시작·완료·종료한다.

**Independent Test**: `/play/:gameId`에서 fixture 기반 Published 게임을 끝까지 완료하고 이전 화면으로 복귀한다.

### Tests

- [ ] T044 [P] After #21 [US3] Add public/private/not-published runtime query tests in `backend/src/test/java/com/example/ssafesta/game/GameRuntimeApiIntegrationTest.java`
- [ ] T045 [P] After #20 [US3] Add unsupported-schema and damaged-project error-boundary tests in `festa-game-studio/tests/integration/runtimeLoadFailure.test.tsx`

### Implementation

- [ ] T046 After #21 [US3] Implement Published query service and endpoint in `backend/src/main/java/com/example/ssafesta/game/api/GameRuntimeController.java`
- [ ] T047 After #20 [US3] Implement standalone play route in `festa-game-studio/src/app/routes/PlayGamePage.tsx`
- [ ] T048 After #20 [US3] Implement Published loader and schema-major guard in `festa-game-studio/src/runtime/loadPublishedGame.ts`
- [ ] T049 After #20 [US3] Implement runtime-local error boundary and close lifecycle in `festa-game-studio/src/runtime/GameRuntimeBoundary.tsx`
- [ ] T050 [US3] Add Unity-free Published completion E2E in `festa-game-studio/tests/e2e/standalonePlay.spec.ts`

**Checkpoint**: SC-005와 SC-007을 Unity 없이 검증한다.

---

## Phase 6: User Story 4 — 부스 NPC/Portal 진입 (Priority: P2)

**Goal**: 기존 Unity Booth 상호작용을 선택적 Web Runtime 진입점으로 연결한다.

**Independent Test**: Portal에서 게임을 열고 닫은 뒤 Unity 연결·위치·입력이 복구된다.

### Tests

- [ ] T051 [P] After #21 [US4] Add Binding/Booth/Game status matrix tests in `backend/src/test/java/com/example/ssafesta/game/GamePortalApiIntegrationTest.java`
- [ ] T052 [P] After #20 [US4] Add BOOTH_GAME_INTERACT parsing/isolation tests in `festa-frontend/src/unity/bridge/events.test.ts`

### Implementation

- [ ] T053 After #21 [US4] Implement approved GamePortalBinding model and resolver in `backend/src/main/java/com/example/ssafesta/game/application/GamePortalService.java`
- [ ] T054 After #21 [US4] Implement Portal resolution endpoint in `backend/src/main/java/com/example/ssafesta/game/api/GamePortalController.java`
- [ ] T055 After #20 [US4] Extend the existing event union in `festa-frontend/src/unity/bridge/events.ts`
- [ ] T056 After #20 [US4] Implement Portal resolver and Game overlay adapter in `festa-frontend/src/game-studio/gameStudioHost.ts`
- [ ] T057 After #20 [US4] Implement overlay-open/close/fail input lifecycle in `festa-frontend/src/game-studio/gameStudioLifecycle.ts`
- [ ] T058 After #20 and #21 [US4] Add Booth Portal open/close browser E2E in `festa-frontend/src/game-studio/gameStudioHost.e2e.ts`

**Checkpoint**: Unity는 trigger만 전송하며 GameProject·결과를 처리하지 않는다.

---

## Phase 7: User Story 5 — PLATFORMER 확장 (Priority: P2)

**Goal**: 공통 변수·Event·Scene 이동을 유지하며 Side View 물리 Scene을 추가한다.

**Independent Test**: TOP_DOWN→PLATFORMER→DIALOGUE 전환 동안 공통 상태가 유지된다.

- [ ] T059 [P] [US5] Add PLATFORMER schema fixture and migration test in `specs/019-game-studio/contracts/fixtures/platformer-transition.json`
- [ ] T060 [P] After #20 [US5] Add platform physics adapter tests in `festa-game-studio/tests/unit/platformerRuntime.test.ts`
- [ ] T061 After #20 [US5] Implement PLATFORMER renderer/physics adapter in `festa-game-studio/src/runtime/platformer/PlatformerRuntime.ts`
- [ ] T062 After #20 [US5] Implement platform object palette/editor in `festa-game-studio/src/studio/map/PlatformerMapEditor.tsx`
- [ ] T063 [US5] Add cross-scene state preservation E2E in `festa-game-studio/tests/e2e/crossScenePlatformer.spec.ts`

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T064 [P] Verify create/save/publish/play with FastAPI unavailable in `festa-game-studio/tests/e2e/aiOutageIsolation.spec.ts`
- [ ] T065 [P] Add maximum-size fixture performance checks in `festa-game-studio/tests/performance/maxProject.test.ts`
- [ ] T066 Add schema migration policy and supported-major matrix in `specs/019-game-studio/contracts/README.md`
- [ ] T067 Run all commands and record expected results in `specs/019-game-studio/quickstart.md`
- [ ] T068 Update implementation status and remaining gates in `docs/22_다음_할일.md` and `docs/KHS/24_작업일지.md`

## Dependencies & Execution Order

```text
Phase 1 contract foundation (now)
   ├─ #20 decision → Phase 2 FE → US1 local authoring → US3 standalone runtime
   ├─ #21 decision → Phase 2 BE → US2 Draft/Publish → US3 public query
   └─ #20 + #21 → US4 Portal integration

US1 + US2 + US3 stable → US5 PLATFORMER
AI #22 does not block MVP
```

## Parallel Opportunities

- T001~T004, T007~T008은 서로 다른 계약 파일이라 병렬 가능하다.
- #20 이후 core tests(T017~T019)와 editor UI(T027~T030)를 병렬 진행할 수 있다.
- #21 이후 Draft tests(T032)와 Publish tests(T033)를 병렬 진행할 수 있다.
- US3의 Backend query(T044/T046)와 Frontend load boundary(T045/T047~T049)는 계약을 기준으로 병렬 가능하다.
- US4는 Backend Portal(T051/T053/T054)과 Frontend bridge(T052/T055~T057)를 병렬 진행한 뒤 T058에서 합친다.

## Implementation Strategy

1. T001~T009를 먼저 완료해 결정과 무관한 계약 기반을 고정한다.
2. #20 답변이 오면 Frontend Setup과 US1 로컬 수직 흐름을 우선 구현한다.
3. #21 답변이 오면 US2 Draft/Publish를 구현하고 US3 독립 URL까지 연결한다.
4. 독립 웹 Runtime을 먼저 완료한 뒤 US4 Unity Portal을 붙인다.
5. MVP 판정 후에만 US5 PLATFORMER를 시작한다.

## Task Summary

- 총 68개
- 즉시 가능: T001~T009
- User Story 1: T017~T031 (15개)
- User Story 2: T032~T043 (12개)
- User Story 3: T044~T050 (7개)
- User Story 4: T051~T058 (8개)
- User Story 5: T059~T063 (5개)
- 권장 최초 MVP: US1 로컬 제작/Preview → US2 Publish → US3 독립 웹 플레이
