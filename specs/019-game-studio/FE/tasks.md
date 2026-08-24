# FE Tasks: FESTA Game Studio

**Shared spec**: [../spec.md](../spec.md) | **BE tasks**: [../BE/tasks.md](../BE/tasks.md)

FE가 소유하는 85개 작업이다. ID는 기존 통합 목록과의 추적성을 위해 유지한다.

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
- [x] T025 Implement the reference TOP_DOWN renderer adapter behind the shared Runtime core
- [x] T026 Implement authoring store with undo/redo

## Authoring workspace

- [x] T027 Implement Scene list and start-Scene editor
- [x] T028 Implement grid/tile/object canvas
- [x] T029 Implement typed Component/Event inspector
- [x] T030 Implement DIALOGUE node/choice editor
- [x] T031 Add minimal authoring-to-local-preview integration test
- [x] T076 Implement Scene/Object palette, canvas, Properties and Event workspace shell
- [x] T077 Implement reversible preset Component/Event recipes
- [x] T091 Replace symbolic template cards with six real 16:9 gameplay previews and difficulty/time metadata
- [x] T092 Add searchable character/object/custom visual Asset picker without portrait/background leakage
- [x] T093 Split Object Inspector into beginner defaults and explicit advanced ID/position/Component controls
- [x] T094 Make onboarding project-scoped and genre-independent; add compact layout and Shift+F focus mode
- [x] T095 Align local Asset MIME/size/audio guard and repository ID/READY seam with #69; disable local injection in server mode
- [x] T096 Add marquee/additive multi-selection, bounded group movement, select/pan/grid canvas tools
- [x] T097 Add behavior-preserving Object duplication, reference-aware batch deletion, and Scene resize commands
- [x] T098 Replace title-only STORY/ESCAPE variants and same-shape action samples with structurally distinct playable blueprints
- [x] T099 Add template-profile, authoring-command, browser multi-select/duplicate/move, and 800/1024px regression verification
- [ ] T090 Run first-time-user key→door→dialogue authoring test within 20 minutes ([#73](https://github.com/kanghyunsoon/ssafesta/issues/73))

## Preview and Publish UI

- [ ] T034 Add Preview protocol origin/source/request lifecycle tests
- [x] T040 Implement revision-aware Draft/publish API client
- [x] T041 Implement same-origin local Preview route using the shared validator/runtime and a session port
- [x] T042 Implement revision conflict and validation-error UI
- [x] T043 Run Draft→Preview→Publish acceptance flow and record it in FE quickstart
- [x] T078 Implement builtin Asset reference resolver and keep transient local sources out of GameProject JSON
- [ ] T080 After #48/#55 Published loader integration add Preview-versus-Published parity E2E

## Standalone Published play

- [x] T045 Add unsupported-schema and damaged-project error-boundary tests
- [x] T047 Complete `/app/games/:gameId/play` page
- [x] T048 Implement Published loader and schema-major guard
- [x] T049 Implement Runtime-local error boundary and close lifecycle
- [ ] T050 Add Unity-free Published completion E2E

## Booth Portal Host integration

- [x] T052 Add `BOOTH_GAME_INTERACT` parsing and failure-isolation tests
- [x] T055 Extend the existing Unity event union
- [x] T056 Implement Portal resolver and Game overlay adapter
- [x] T057 Implement overlay open/close/fail input lifecycle
- [ ] T058 Add Booth Portal browser E2E after BE resolver is available

## PLATFORMER extension

- [x] T059 Add PLATFORMER contract and six-template tests
- [x] T060 Add platform gravity, jump, projectile, damage recovery, and bounded-spawner tests
- [x] T061 Implement reference PLATFORMER renderer/physics adapter
- [x] T062 Implement platform object palette/editor and playable jump-map template
- [x] T063 Add cross-scene state preservation E2E

## Cross-cutting verification

- [x] T064 Verify create/save/publish/play with FastAPI unavailable
- [ ] T065 Verify max project limits, 60fps target, and 100ms editor response target ([#73](https://github.com/kanghyunsoon/ssafesta/issues/73))
- [x] T066 Document schema migration policy and supported-major matrix
- [x] T067 Run all FE quickstart commands and record final results
- [x] T068 Update implementation status and KHS worklog
- [x] T069 Add deterministic input/state trace
- [x] T070 Implement renderer-free reference Runtime
- [x] T071 Verify and document Runtime trace command
- [x] T089 Verify production build emits separate Edit and Play lazy chunks
- [ ] T100 Define and implement versioned timer/score/defeat-count victory contract after FE/BE/AI agreement ([#78](https://github.com/kanghyunsoon/ssafesta/issues/78))
- [x] T101 Implement the backward-readable GameProject v1.1 Frontend candidate and explicit v1.0 authoring upgrade
- [x] T102 Implement deterministic score/defeat/survival objectives, progress HUD, and respawn/end-game behavior
- [x] T103 Implement Mock immutable publication ports and block projects with no completion path
- [x] T104 Run browser template→save→publish→public `/play` verification with visible objective parity
- [ ] T105 Implement the server-authoritative Published GameSession/Coin adapter after #81 agreement
- [x] T106 Record GitLab pre-migration ref/PR/Issue/CI handoff without creating a GitLab remote or changing product contracts

## Summary

- Total: 85
- Completed: 77
- Remaining: 8
- Active blockers: #48 서버 endpoint, #56 서버 Portal resolver, #69 stable user Asset upload/resolver, #78 v1.1 API/BE/AI 최종 합의, #81 서버 권위 Published Session/Coin. 자동화로 대체할 수 없는 사람 대상 20분·활성 PC 탭 성능 증거는 #73에서 추적한다. Mock에서는 제작·저장·불변 게시·일반 `/play`와 v1.1 목표 HUD까지 검증 완료했다. GitLab 이관은 [KHS 29](../../../docs/KHS/29_Game_Studio_GitLab_이관_준비.md)의 준비 문서만 작성했으며 실제 Import·remote 변경·push는 수행하지 않았다.
