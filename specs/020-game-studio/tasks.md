# Tasks: FESTA Game Studio

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Organization**: #20 Frontend Host, #21 Backend, #22 AI 답변은 반영됐다. 앱 내부 구현과 Spring
수직 작업은 착수 가능하고, 제품 정책 #33·Portal ID #34·renderer/Preview 내부 #35만 gate로 남긴다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일을 수정해 병렬 실행 가능
- **[USn]**: `spec.md`의 User Story
- 각 항목의 `After #33/#34/#35`는 해당 결정 전 착수 금지 gate다.

## Phase 1: Contract Foundation — 즉시 가능

**Purpose**: 앱·DB 결정을 선점하지 않고 모든 consumer가 공유할 실행 규칙을 고정한다.

- [x] T001 [P] Add unsupported-schema negative fixture in `specs/020-game-studio/contracts/fixtures/invalid/unsupported-schema.json`
- [x] T002 [P] Add missing-start-scene negative fixture in `specs/020-game-studio/contracts/fixtures/invalid/missing-start-scene.json`
- [x] T003 [P] Add duplicate-object-id negative fixture in `specs/020-game-studio/contracts/fixtures/invalid/duplicate-object-id.json`
- [x] T004 [P] Add invalid-dialogue-target negative fixture in `specs/020-game-studio/contracts/fixtures/invalid/invalid-dialogue-target.json`
- [x] T005 Define fixture names and expected semantic error codes in `specs/020-game-studio/contracts/fixtures/manifest.json`
- [x] T006 Extend the no-dependency contract runner to execute the manifest in `specs/020-game-studio/contracts/fixtures/validate-fixtures.mjs`
- [x] T007 [P] Define Studio-to-Preview postMessage envelope, origin checks, and lifecycle in `specs/020-game-studio/contracts/game-preview-protocol.md`
- [x] T008 [P] Define deterministic Trigger→Condition→Action ordering and execution budgets in `specs/020-game-studio/contracts/event-runtime-semantics.md`
- [x] T009 Update runnable positive/negative validation steps in `specs/020-game-studio/quickstart.md`

**Checkpoint**: Positive fixture and every negative fixture produce deterministic results without FE/BE/Unity.

### Phase 1A: 시안 기반 계약 보완 — 즉시 완료

- [x] T072 [P] Define Scene/Object/Properties/Event workspace and Asset reference rules in `specs/020-game-studio/contracts/studio-authoring-model.md`
- [x] T073 Add OVERLAY/FULL_SCREEN Dialogue and CLOSE_DIALOGUE semantics in `specs/020-game-studio/contracts/event-runtime-semantics.md`
- [x] T074 Fix Dialogue Choice nextNodeId schema placement and add presentation/close negative fixtures in `specs/020-game-studio/contracts/`
- [x] T075 Extend the minimal project and renderer-free trace through Overlay close/resume and Full-screen transition in `specs/020-game-studio/contracts/fixtures/`

**Checkpoint**: 같은 방 상태를 보존한 대화 Overlay 종료와 실제 Scene 이동이 서로 다른 상태 전이로 검증된다.

### Phase 1B: 파트 답변 반영 — 완료

- [x] T081 Rename the feature directory from conflicting `019-game-studio` to `specs/020-game-studio/`
- [x] T082 Apply #20~#22 decisions to FE same-origin routes, BE Draft/Published persistence, and AI P2 boundaries in `specs/020-game-studio/`
- [x] T083 Split unresolved product, Portal ID, and Studio-internal choices into GitHub Issues #33, #34, and #35

**Checkpoint**: 완료된 파트 계약과 아직 결정하지 않은 제품·구현 선택이 문서와 이슈에서 분리된다.

---

## Phase 2: Project Setup & Shared Foundations — decision gated

**Purpose**: 승인된 파트 구조 안에 공통 계약과 테스트 기반을 설치한다.

- [ ] T010 Add `/app/games/:gameId/edit|play` lazy routes and the `src/game-studio/` ownership boundary in `festa-frontend/src/app/router/index.tsx`
- [ ] T011 [P] Add the approved Game Studio test scripts/dependencies while preserving existing build/lint in `festa-frontend/package.json` and `festa-frontend/vite.config.ts`
- [ ] T012 Generate or hand-map GameProject v1 TypeScript types and guards in `festa-frontend/src/game-studio/contracts/gameProject.ts`
- [ ] T013 Add fixture-driven contract tests in `festa-frontend/src/game-studio/__tests__/contract/gameProject.contract.test.ts`
- [ ] T014 create the Spring game package boundary in `backend/src/main/java/com/example/ssafesta/game/`
- [ ] T015 [P] Add append-only `games`, `game_drafts`, `game_published_versions`, and pointer-FK migration in `backend/src/main/resources/db/migration/V*_game_studio.sql`
- [ ] T016 [P] define Game Studio error codes and exception mapping in `backend/src/main/java/com/example/ssafesta/game/api/GameErrorCode.java`

**Checkpoint**: FE contract test and BE application test can consume the same positive fixture.

---

## Phase 3: User Story 1 — 탐색·대화 게임 제작 (Priority: P1) 🎯 MVP Authoring

**Goal**: 코드 없이 TOP_DOWN 방, Item, Door, Event, DIALOGUE를 구성하고 로컬 상태에서 실행한다.

**Independent Test**: 빈 프로젝트에서 `열쇠 획득 → 문 상호작용 → 대화 → 완료`를 만들고 로컬 Preview한다.

### Tests

- [ ] T017 [P] [US1] Add runtime-state reducer unit tests in `festa-frontend/src/game-studio/__tests__/unit/runtimeState.test.ts`
- [ ] T018 [P] [US1] Add condition/action interpreter unit tests in `festa-frontend/src/game-studio/__tests__/unit/eventInterpreter.test.ts`
- [ ] T019 [P] [US1] Add dialogue graph traversal unit tests in `festa-frontend/src/game-studio/__tests__/unit/dialogueRunner.test.ts`

### Implementation

- [ ] T020 [US1] Implement immutable RuntimeSessionState initialization in `festa-frontend/src/game-studio/core/runtimeState.ts`
- [ ] T021 [P] [US1] Implement condition evaluation in `festa-frontend/src/game-studio/core/evaluateCondition.ts`
- [ ] T022 [P] [US1] Implement action reduction in `festa-frontend/src/game-studio/core/applyAction.ts`
- [ ] T023 [US1] Implement event ordering, action budget, and transition-depth guard in `festa-frontend/src/game-studio/core/runEvent.ts`
- [ ] T024 [US1] Implement OVERLAY/FULL_SCREEN DIALOGUE node/choice runner in `festa-frontend/src/game-studio/runtime/dialogue/dialogueRunner.ts`
- [ ] T025 After #35 [US1] Implement the selected TOP_DOWN renderer adapter in `festa-frontend/src/game-studio/runtime/top-down/TopDownRuntime.ts`
- [ ] T026 [US1] Implement project authoring store with undo/redo in `festa-frontend/src/game-studio/studio/store/gameProjectStore.ts`
- [ ] T027 [P] [US1] Implement Scene list/start Scene editor in `festa-frontend/src/game-studio/studio/scenes/SceneListPanel.tsx`
- [ ] T028 [P] [US1] Implement grid/tile/object canvas in `festa-frontend/src/game-studio/studio/map/TopDownMapEditor.tsx`
- [ ] T029 [P] [US1] Implement typed Component/Event inspector in `festa-frontend/src/game-studio/studio/inspector/EventInspector.tsx`
- [ ] T030 [P] [US1] Implement DIALOGUE node/choice editor in `festa-frontend/src/game-studio/studio/dialogue/DialogueEditor.tsx`
- [ ] T031 [US1] Add the minimal authoring-to-local-preview integration test in `festa-frontend/src/game-studio/__tests__/integration/minimalAuthoringPreview.test.tsx`
- [ ] T076 [US1] Implement the Scene/Object palette, canvas, Properties and Event workspace shell in `festa-frontend/src/game-studio/studio/StudioWorkspace.tsx`
- [ ] T077 [US1] Implement preset convenience fields as reversible Component/Event recipes in `festa-frontend/src/game-studio/studio/presets/presetRecipes.ts`

**Checkpoint**: 서버와 Unity 없이 US1 수직 시나리오를 제작하고 Preview할 수 있다.

---

## Phase 4: User Story 2 — 저장 전 Preview와 Publish (Priority: P1)

**Goal**: Draft 충돌을 검출하고 유효한 snapshot만 불변 Published Version으로 발행한다.

**Independent Test**: invalid Draft Publish는 거부되고, 수정 후 Publish한 뒤 Draft 변경이 공개본을 바꾸지 않는다.

### Tests

- [ ] T032 [P] [US2] Add revision-conflict and authorization integration tests in `backend/src/test/java/com/example/ssafesta/game/GameDraftApiIntegrationTest.java`
- [ ] T033 [P] [US2] Add invalid-reference and immutable-publish integration tests in `backend/src/test/java/com/example/ssafesta/game/GamePublishIntegrationTest.java`
- [ ] T034 [P] [US2] Add same-origin/source/request lifecycle tests in `festa-frontend/src/game-studio/__tests__/integration/previewProtocol.test.ts`

### Implementation

- [ ] T035 [US2] Implement Game/GameDraft/GamePublishedVersion entities and repositories in `backend/src/main/java/com/example/ssafesta/game/domain/`
- [ ] T036 [US2] Implement revision-aware Draft service in `backend/src/main/java/com/example/ssafesta/game/application/GameDraftService.java`
- [ ] T037 [US2] Implement Schema and semantic validator adapter in `backend/src/main/java/com/example/ssafesta/game/application/GameProjectValidator.java`
- [ ] T038 [US2] Implement atomic Draft validate→Published append→published pointer update in `backend/src/main/java/com/example/ssafesta/game/application/GamePublishService.java`
- [ ] T039 [US2] Implement authoring endpoints in `backend/src/main/java/com/example/ssafesta/game/api/GameAuthoringController.java`
- [ ] T040 [US2] Implement revision-aware Draft/publish API client in `festa-frontend/src/game-studio/app/api/gameAuthoringApi.ts`
- [ ] T041 [US2] Implement isolated iframe Preview host in `festa-frontend/src/game-studio/preview/PreviewHost.tsx`
- [ ] T042 [US2] Implement save-conflict and validation-error UI in `festa-frontend/src/game-studio/studio/publish/PublishPanel.tsx`
- [ ] T043 [US2] Run and document Draft→Preview→Publish acceptance flow in `specs/020-game-studio/quickstart.md`
- [ ] T078 After #35 [US2] Implement builtin Asset reference resolution and reject transient sources in `festa-frontend/src/game-studio/runtime/assets/resolveAsset.ts`
- [ ] T079 [US2] Implement persisted Asset source allow-list validation in `backend/src/main/java/com/example/ssafesta/game/application/GameAssetPolicy.java`
- [ ] T080 After #35 [US2] Add Preview-versus-Published state parity E2E in `festa-frontend/e2e/game-studio/previewPublishedParity.spec.ts`

**Checkpoint**: Draft와 Published 격리가 서버 통합 테스트로 증명된다.

---

## Phase 5: User Story 3 — 독립 웹 Published 게임 플레이 (Priority: P1)

**Goal**: Unity 없이 Published 게임을 독립 URL에서 시작·완료·종료한다.

**Independent Test**: `/app/games/:gameId/play`에서 fixture 기반 Published 게임을 끝까지 완료하고 이전 화면으로 복귀한다.

### Tests

- [ ] T044 [P] [US3] Add public/private/not-published runtime query tests in `backend/src/test/java/com/example/ssafesta/game/GameRuntimeApiIntegrationTest.java`
- [ ] T045 [P] [US3] Add unsupported-schema and damaged-project error-boundary tests in `festa-frontend/src/game-studio/__tests__/integration/runtimeLoadFailure.test.tsx`

### Implementation

- [ ] T046 [US3] Implement Published query service and endpoint in `backend/src/main/java/com/example/ssafesta/game/api/GameRuntimeController.java`
- [ ] T047 [US3] Implement `/app/games/:gameId/play` lazy page in `festa-frontend/src/game-studio/app/routes/PlayGamePage.tsx`
- [ ] T048 [US3] Implement Published loader and schema-major guard in `festa-frontend/src/game-studio/runtime/loadPublishedGame.ts`
- [ ] T049 [US3] Implement runtime-local error boundary and close lifecycle in `festa-frontend/src/game-studio/runtime/GameRuntimeBoundary.tsx`
- [ ] T050 [US3] Add Unity-free Published completion E2E in `festa-frontend/e2e/game-studio/standalonePlay.spec.ts`

**Checkpoint**: SC-005와 SC-007을 Unity 없이 검증한다.

---

## Phase 6: User Story 4 — 부스 NPC/Portal 진입 (Priority: P2)

**Goal**: 기존 Unity Booth 상호작용을 선택적 Web Runtime 진입점으로 연결한다.

**Independent Test**: Portal에서 게임을 열고 닫은 뒤 Unity 연결·위치·입력이 복구된다.

### Tests

- [ ] T051 [P] After #34 [US4] Add Binding/Booth/Game status and signed Int32 configId matrix tests in `backend/src/test/java/com/example/ssafesta/game/GamePortalApiIntegrationTest.java`
- [ ] T052 [P] [US4] Add BOOTH_GAME_INTERACT parsing/isolation tests in `festa-frontend/src/unity/bridge/events.test.ts`

### Implementation

- [ ] T053 After #34 [US4] Implement GamePortalBinding and LayoutConfigResolver `GAME_PORTAL` validation in `backend/src/main/java/com/example/ssafesta/game/application/GamePortalService.java`
- [ ] T054 After #34 [US4] Implement no-store Portal resolution endpoint in `backend/src/main/java/com/example/ssafesta/game/api/GamePortalController.java`
- [ ] T055 [US4] Extend the existing event union in `festa-frontend/src/unity/bridge/events.ts`
- [ ] T056 [US4] Implement Portal resolver and Game overlay adapter in `festa-frontend/src/game-studio/host/gameStudioHost.ts`
- [ ] T057 [US4] Implement overlay-open/close/fail input lifecycle in `festa-frontend/src/game-studio/host/gameStudioLifecycle.ts`
- [ ] T058 After #34 [US4] Add Booth Portal open/close browser E2E in `festa-frontend/e2e/game-studio/gameStudioHost.spec.ts`

**Checkpoint**: Unity는 trigger만 전송하며 GameProject·결과를 처리하지 않는다.

---

## Phase 7: User Story 5 — PLATFORMER 확장 (Priority: P2)

**Goal**: 공통 변수·Event·Scene 이동을 유지하며 Side View 물리 Scene을 추가한다.

**Independent Test**: TOP_DOWN→PLATFORMER→DIALOGUE 전환 동안 공통 상태가 유지된다.

- [ ] T059 [P] [US5] Add PLATFORMER schema fixture and migration test in `specs/020-game-studio/contracts/fixtures/platformer-transition.json`
- [ ] T060 [P] After #35 [US5] Add platform physics adapter tests in `festa-frontend/src/game-studio/__tests__/unit/platformerRuntime.test.ts`
- [ ] T061 After #35 [US5] Implement PLATFORMER renderer/physics adapter in `festa-frontend/src/game-studio/runtime/platformer/PlatformerRuntime.ts`
- [ ] T062 After #35 [US5] Implement platform object palette/editor in `festa-frontend/src/game-studio/studio/map/PlatformerMapEditor.tsx`
- [ ] T063 [US5] Add cross-scene state preservation E2E in `festa-frontend/e2e/game-studio/crossScenePlatformer.spec.ts`

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T064 [P] Verify create/save/publish/play with FastAPI unavailable in `festa-frontend/e2e/game-studio/aiOutageIsolation.spec.ts`
- [ ] T065 [P] Verify 100×100 tiles, 500 Objects, and 300 Events load without failure, target 60fps play, and keep ordinary editor actions within 100ms in `festa-frontend/src/game-studio/__tests__/performance/maxProject.test.ts`
- [x] T066 Add schema migration policy and supported-major matrix in `specs/020-game-studio/contracts/README.md`
- [ ] T067 Run all commands and record expected results in `specs/020-game-studio/quickstart.md`
- [x] T068 Update implementation status and remaining gates in `docs/22_다음_할일.md` and `docs/KHS/27_Game_Studio_작업일지.md`
- [x] T069 [P] Add deterministic input/state trace in `specs/020-game-studio/contracts/fixtures/runtime-traces/minimal-top-down-dialogue.trace.json`
- [x] T070 Implement renderer-free reference state/event runtime in `specs/020-game-studio/contracts/fixtures/reference-runtime.mjs`
- [x] T071 Document and verify the runtime trace command in `specs/020-game-studio/quickstart.md`
- [ ] T084 [P] After #34 Document and test the final wire/DB `configId` mapping and `GAME_PORTAL` whitelist
- [ ] T085 [P] After #33 Encode unpublish/delete/Published-history policy in Backend and Runtime integration tests
- [ ] T086 [P] After #33 Add display-only score/leaderboard tasks only if #33 selects the P1 option
- [ ] T087 [P] [US2][US3] Verify Guest authoring is denied while Published play remains available in `backend/src/test/java/com/example/ssafesta/game/GameGuestAccessIntegrationTest.java`
- [ ] T088 [P] [US2] Verify invalid coordinates, unknown fields, and broken references are rejected without clamp/drop/substitution in `backend/src/test/java/com/example/ssafesta/game/GameProjectNoCorrectionIntegrationTest.java`
- [ ] T089 [P] Verify normal FESTA routes do not load the Game Studio lazy chunk in `festa-frontend/e2e/game-studio/lazyChunkIsolation.spec.ts`
- [ ] T090 [US1] Run a first-time-user script and record whether the minimal key→door→dialogue game is authored and previewed within 20 minutes in `specs/020-game-studio/quickstart.md`

## Dependencies & Execution Order

```text
Phase 1 contract foundation (now)
   ├─ #20 accepted → Phase 2 FE → US1 local authoring → US3 standalone runtime
   ├─ #21 accepted → Phase 2 BE → US2 Draft/Publish → US3 public query
   └─ #34 decision → US4 Portal integration

US1 + US2 + US3 stable → US5 PLATFORMER
AI #22 completed and does not block MVP
```

## Parallel Opportunities

- T001~T004, T007~T008은 서로 다른 계약 파일이라 병렬 가능하다.
- Frontend core tests(T017~T019)와 editor UI(T027~T030)를 병렬 진행할 수 있다.
- Backend Draft tests(T032)와 Publish tests(T033)를 병렬 진행할 수 있다.
- US3의 Backend query(T044/T046)와 Frontend load boundary(T045/T047~T049)는 계약을 기준으로 병렬 가능하다.
- US4는 Backend Portal(T051/T053/T054)과 Frontend bridge(T052/T055~T057)를 병렬 진행한 뒤 T058에서 합친다.

## Implementation Strategy

1. T001~T009를 먼저 완료해 결정과 무관한 계약 기반을 고정한다.
2. `festa-frontend/src/game-studio/` Setup과 US1 로컬 수직 흐름을 우선 구현한다.
3. `game_drafts`/`game_published_versions` 기반 US2 Draft/Publish와 US3 독립 URL을 병렬 연결한다.
4. 독립 웹 Runtime을 먼저 완료한 뒤 US4 Unity Portal을 붙인다.
5. MVP 판정 후에만 US5 PLATFORMER를 시작한다.

## Task Summary

- 총 90개
- 완료된 계약·파트 답변 반영 작업: T001~T009, T066, T068~T075, T081~T083 (21개)
- User Story 1: T017~T031 + T076~T077 + T090
- User Story 2: T032~T043 + T078~T080 + T087~T088
- User Story 3: T044~T050 (7개)
- User Story 4: T051~T058 (8개)
- User Story 5: T059~T063 (5개)
- Cross-cutting decision/quality: T084~T089
- 권장 최초 MVP: US1 로컬 제작/Preview → US2 Publish → US3 독립 웹 플레이
