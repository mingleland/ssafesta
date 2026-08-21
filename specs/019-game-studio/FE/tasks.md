# FE Tasks: FESTA Game Studio

**Shared spec**: [../spec.md](../spec.md) | **BE tasks**: [../BE/tasks.md](../BE/tasks.md)

FE가 소유하는 69개 작업이다. ID는 기존 통합 목록과의 추적성을 위해 유지한다.

## Contract foundation

- [x] T001 Add unsupported-schema negative fixture
- [x] T002 Add missing-start-scene negative fixture
- [x] T003 Add duplicate-object-id negative fixture
- [x] T004 Add invalid-dialogue-target negative fixture
- [x] T005 Define fixture manifest and expected semantic error codes
- [x] T006 Implement no-dependency fixture runner
- [x] T007 Define Preview message envelope, origin checks, and lifecycle
- [x] T008 Define deterministic Event ordering and execution budgets
- [x] T009 Document positive/negative validation commands
- [x] T072 Define Scene/Object/Properties/Event workspace and Asset reference rules
- [x] T073 Define OVERLAY/FULL_SCREEN Dialogue and CLOSE_DIALOGUE semantics
- [x] T074 Fix Choice nextNodeId placement and add negative fixtures
- [x] T075 Extend the reference trace through Overlay close and Full-screen transition
- [x] T081 Reconcile merged numbering by using `019-game-studio` and split FE/BE execution artifacts
- [x] T082 Apply #20~#22 decisions to shared contracts and part boundaries
- [x] T083 Split remaining decisions into GitHub Issues #33, #34, and #35

## Frontend setup and pure core

- [x] T010 Add lazy `/app/games/:gameId/edit|play` routes and module boundary
- [x] T011 Add Game Studio Vitest scripts while preserving build/lint
- [x] T012 Implement GameProject v1 TypeScript types and guard
- [x] T013 Add fixture-driven contract tests
- [x] T017 Add RuntimeSessionState reducer tests
- [x] T018 Add Condition/Action interpreter tests
- [x] T019 Add Dialogue traversal tests
- [x] T020 Implement immutable RuntimeSessionState initialization
- [x] T021 Implement Condition evaluation
- [x] T022 Implement Action reduction
- [x] T023 Implement Event ordering, action budget, and transition-depth guard
- [x] T024 Implement OVERLAY/FULL_SCREEN Dialogue runner
- [ ] T025 After #35 implement the selected TOP_DOWN renderer adapter
- [x] T026 Implement authoring store with undo/redo

## Authoring workspace

- [ ] T027 Implement Scene list and start-Scene editor
- [ ] T028 Implement grid/tile/object canvas
- [ ] T029 Implement typed Component/Event inspector
- [ ] T030 Implement DIALOGUE node/choice editor
- [ ] T031 Add minimal authoring-to-local-preview integration test
- [ ] T076 Implement Scene/Object palette, canvas, Properties and Event workspace shell
- [x] T077 Implement reversible preset Component/Event recipes
- [ ] T090 Run first-time-user key→door→dialogue authoring test within 20 minutes

## Preview and Publish UI

- [ ] T034 Add Preview protocol origin/source/request lifecycle tests
- [ ] T040 Implement revision-aware Draft/publish API client
- [ ] T041 After #35 implement isolated same-origin PreviewHost
- [ ] T042 Implement revision conflict and validation-error UI
- [ ] T043 Run Draft→Preview→Publish acceptance flow and record it in FE quickstart
- [ ] T078 After #35 implement builtin Asset reference resolver and reject transient sources
- [ ] T080 After #35 add Preview-versus-Published parity E2E

## Standalone Published play

- [ ] T045 Add unsupported-schema and damaged-project error-boundary tests
- [ ] T047 Complete `/app/games/:gameId/play` page
- [ ] T048 Implement Published loader and schema-major guard
- [ ] T049 Implement Runtime-local error boundary and close lifecycle
- [ ] T050 Add Unity-free Published completion E2E

## Booth Portal Host integration

- [ ] T052 Add `BOOTH_GAME_INTERACT` parsing and failure-isolation tests
- [ ] T055 Extend the existing Unity event union
- [ ] T056 Implement Portal resolver and Game overlay adapter
- [ ] T057 Implement overlay open/close/fail input lifecycle
- [ ] T058 Add Booth Portal browser E2E after BE resolver is available

## PLATFORMER extension

- [ ] T059 Add PLATFORMER fixture and migration test
- [ ] T060 After #35 add platform physics adapter tests
- [ ] T061 After #35 implement PLATFORMER renderer/physics adapter
- [ ] T062 After #35 implement platform object palette/editor
- [ ] T063 Add cross-scene state preservation E2E

## Cross-cutting verification

- [ ] T064 Verify create/save/publish/play with FastAPI unavailable
- [ ] T065 Verify max project limits, 60fps target, and 100ms editor response target
- [x] T066 Document schema migration policy and supported-major matrix
- [ ] T067 Run all FE quickstart commands and record final results
- [x] T068 Update implementation status and KHS worklog
- [x] T069 Add deterministic input/state trace
- [x] T070 Implement renderer-free reference Runtime
- [x] T071 Verify and document Runtime trace command
- [ ] T089 Verify ordinary FESTA routes do not load the Game Studio lazy chunk

## Summary

- Total: 69
- Completed: 35
- Remaining: 34
- Active blockers: #35 for renderer/Preview/Asset choices; #48 BE API and #49 FE workspace are separately owned
