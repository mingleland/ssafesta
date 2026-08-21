# FESTA Game Studio 작업일지

> **범위**: `specs/019-game-studio`, `festa-frontend/src/game-studio/`, GameProject 계약, Web 2D Runtime,
> Spring Draft/Publish·Portal Binding, FESTA Host 연동.
>
> Unity 월드·Booth Runtime 일반 작업은 이 문서에 기록하지 않는다.
> Game Studio 작업과 문제는 각각 `27_Game_Studio_작업일지.md`,
> `28_Game_Studio_트러블슈팅.md`에만 기록한다.

## 2026-08-21

### 최신 front 재동기화·남은 Runtime 이슈 분리 ✅

- 🤖 `front`에 Booth Studio MVP가 추가되면서 PR #47의 공통 router가 충돌 상태가 된 것을 확인했다. 기존 tip을 `backup/pr47-pre-front-sync-20260821`로 보존하고 Game Studio 4개 커밋을 최신 `front`(`f81aa07`) 위로 다시 배치했다.
- 🤖 `/app/studio/:boothId`와 `/app/games/:gameId/edit|play`를 함께 보존해 충돌을 해소했다. 재검증은 Vitest **7 files / 29 tests**, production build, oxlint 모두 통과했고 PR #47은 다시 `CLEAN/MERGEABLE` 상태다.
- 🤖 구현 PR·커밋·테스트 링크 없이 닫힌 Backend #48을 최신 `back`(`cb84d46`) 소스와 대조했다. Game Studio package, migration, API, 통합 테스트가 없음을 근거로 이슈를 재개하고 Published 응답 DTO·오류 코드 확정을 요청했다.
- 🤖 Unity 없는 Published Web Runtime의 남은 T045·T047~T050을 [#55](https://github.com/kanghyunsoon/ssafesta/issues/55)로 분리해 `@ghkim1632`, `@colosair`에게 배정했다. #48 응답 계약과 #35 renderer/Asset resolver가 확정되기 전에는 필드와 동작을 임의 구현하지 않는다.
- 🤖 #34가 Portal 계약 결정만 닫고 실제 구현을 추적하지 않는 공백을 확인했다. Backend T051·T053·T054와 Frontend T052·T055~T058을 [#56](https://github.com/kanghyunsoon/ssafesta/issues/56)으로 묶어 `@strdeok`, `@ghkim1632`, `@colosair`에게 배정하고 BE whitelist 선배포 순서를 명시했다.
- 🤖 이번 변경은 Frontend 전용 브랜치와 Game Studio 문서 브랜치에서만 수행했다. 공유 `game` 작업트리와 `festa-unity/**`, `backend/**`는 수정하지 않았다.
- 트러블슈팅: GS-T025

### develop 동기화·파트별 SDD 분리·#33/#34 계약 반영 ✅

- 🤖 기존 Foundation tip을 백업한 뒤 새 `codex/game-studio-docs-sync` 브랜치에서 18개 Game Studio 문서 커밋을 최신 `github/develop`(`e515a70`) 위로 재배치했다. 공유 `game` 작업트리와 Unity/Backend 구현은 변경하지 않았다.
- 🤖 develop이 ERD를 `020-erd-schema`로 이동해 019를 Game Studio에 비운 최신 결정을 확인하고, 중간 브랜치의 `020-game-studio`를 최종 `019-game-studio`로 바로잡았다.
- 🤖 PR #44의 다중 파트 SDD 규칙에 따라 루트에는 `spec.md`와 `contracts/`만 두고 실행 산출물 5종을 `FE/` 69개 작업, `BE/` 21개 작업으로 분리했다. Unity는 2D Runtime 소유자가 아니고 AI는 P2 후보라 빈 실행 디렉터리를 만들지 않았다.
- 🤖 #33 합의를 반영해 신규 route/overlay 진입만 REST로 차단하고 loaded 무보상 세션은 완료 허용, 일반 soft/회원 탈퇴 hard delete, Published 이력 유지, Ranking P1 절연을 고정했다.
- 🤖 #34 합의를 반영해 `configId` signed Int32 `1..2147483647`, 0 금지, 내부 BIGINT+공개 INTEGER 분리, `GAME_PORTAL requiresConfig=true`, BE whitelist 선배포를 공통 계약과 API/DB/FE/Unity 문서에 동기화했다.
- 🤖 총 작업 ID는 90개로 유지하고 FE 35/69 완료, BE 0/21 완료로 분리했다. BE #48과 FE #49 담당 구현은 중복 착수하지 않았고 #35 renderer/Preview/Asset 선택만 결정 gate로 남겼다.
- 🤖 원격 `codex/game-studio-docs-sync`를 게시하고 develop 대상 [PR #53](https://github.com/kanghyunsoon/ssafesta/pull/53)을 열었다. 합의가 끝난 #33은 문서 링크와 함께 닫았고 #34도 Backend 확인으로 종료됐다. FE 구현 확인은 #49에서 추적한다.
- 🤖 #34·#35·#48·#49에 최종 spec 번호 019와 `FE/`·`BE/` 실행 문서 경로를 댓글로 공유해 이전 020 경로를 기준으로 구현하는 일을 막았다.
- 트러블슈팅: GS-T023, GS-T024

### Frontend Web Runtime Core·Authoring 기반 구현 ✅

- 🤖 최신 `front`(`da2ea76`)를 기준으로 `codex/game-studio-web-runtime-core` 브랜치를 분리하고 GameProject v1 TypeScript 타입·구조/참조/의미 검증기와 fixture 기반 계약 테스트를 구현했다.
- 🤖 불변 `RuntimeSessionState`, Condition 평가, Action reducer, Event 배열 순서, Action 64회·Scene transition depth 8 예산, 실패 Session 격리, OVERLAY/FULL_SCREEN DIALOGUE 실행기를 구현했다.
- 🤖 `/app/games/:gameId/edit|play` lazy route 경계, 유효 snapshot만 보관하는 undo/redo authoring store, Pickup/Locked Door 편의 필드와 표준 Component/Event 간 왕복 preset recipe를 추가했다.
- 🤖 Unity 없이 `열쇠 → 문 → Overlay → Full-screen Dialogue → 완료` 전체 상태 전이를 통합 검증했다. Vitest **7 files / 29 tests**, TypeScript+Vite production build, oxlint를 모두 통과했고 Edit/Play가 별도 lazy chunk로 출력됨을 확인했다.
- 🤖 구현 변경 23경로는 전부 `festa-frontend/**`이며 `festa-unity/**`와 `backend/**`는 0건이다. 원격 브랜치를 최신 `front` 위로 충돌 없이 재배치하고 `--force-with-lease`로 갱신했다.
- 🤖 Frontend 검토용 [PR #47](https://github.com/kanghyunsoon/ssafesta/pull/47)을 `front` 대상으로 열고, #35에 완료한 비의존 범위와 남은 renderer/Preview/Asset gate를 분리해 공유했다.
- 🤖 #33의 신규 진입 판정 질문에는 전용 게임 소켓 없이 route/overlay 진입 때 REST로 상태를 재검증하고 이미 로드된 무보상 로컬 Session은 완료까지 허용한다고 답변했다. 이후 Backend가 같은 정책과 삭제·이력·Ranking 권장안 전체에 동의했다.
- 🤖 남은 수직 구현을 [#48 Backend Draft/Publish](https://github.com/kanghyunsoon/ssafesta/issues/48)(`@strdeok`)와 [#49 Frontend Workspace](https://github.com/kanghyunsoon/ssafesta/issues/49)(`@busypark`, `@colosair`, `@ghkim1632`)로 분리했다. 결정 gate #33~#35와 구현 추적 #48~#49가 중복되지 않도록 범위를 나눴다.
- 🤖 당시 통합 `tasks.md`의 T010~T013, T017~T024, T026, T077을 완료 처리해 총 90개 중 누적 **35개 완료**로 갱신했다. 현재는 FE/BE tasks로 분리했고 renderer/Preview iframe/Asset resolver #35만 결정 gate로 남았다.
- 트러블슈팅: GS-T020~GS-T022

### 파트 이슈 답변 반영·spec 번호 선제 이동·후속 결정 분리 ✅

- 🤖 결정 반영이 완료된 GitHub #20 Frontend, #21 Backend를 후속 이슈 링크와 함께 닫았다. #22 AI까지 원계약 질의 3건은 모두 종료했으며, 미결정·구현 항목은 #33~#35에서 독립 추적한다.
- 🤖 최신 `origin/develop` 위로 재정렬한 `feature/game-studio-foundation`을 `--force-with-lease`로 원격 동기화했다. 사전 백업 브랜치를 보존했으며 Unity/Game 작업 경로는 포함하지 않았다.
- 🤖 원격 tip과 로컬 HEAD 일치, `origin/develop` 대비 behind 0/ahead 14, 가상 병합 성공, 변경 43경로 중 `festa-unity/**`와 Frontend 구현 경로 0건을 재확인했다. 계약 fixture 7/7과 Runtime trace 6/6도 다시 통과했다.
- 🤖 GitHub #20 Frontend, #21 Backend, #22 AI의 신규 답변을 전체 확인했다. FE는 기존 `festa-frontend` 내부 lazy module과 same-origin Preview, BE는 Draft/Published 2테이블·revision 409·atomic Publish, AI는 P0/P1 비의존과 P2 candidate/patch·spec 007 Job 정책을 확정했다.
- 🤖 당시 Backend 브랜치의 `019-erd-schema`와 번호 충돌을 피하기 위해 Game Studio를 020으로 선제 이동했다. 이후 develop에서 ERD가 `020-erd-schema`로 정리되어 현재 Game Studio 정식 번호는 다시 019다.
- 🤖 완료된 AI 이슈 #22를 닫았다. #20·#21의 남은 항목은 [#33 제품 정책](https://github.com/kanghyunsoon/ssafesta/issues/33), [#34 Portal ID](https://github.com/kanghyunsoon/ssafesta/issues/34), [#35 Studio 내부](https://github.com/kanghyunsoon/ssafesta/issues/35)로 분리했다.
- 🤖 API/DB 계약을 `game_drafts` + `game_published_versions`, `expectedRevision`, 불변 append, nullable 공개본 포인터, Portal `no-store`, builtin Asset MVP로 구체화했다. FE 계획·tasks의 별도 `festa-game-studio/` 가정을 폐기하고 실제 `festa-frontend/src/game-studio/` 경로로 고쳤다.
- 🤖 최신 `origin/develop`(`a8b0398`) 위로 전용 브랜치를 다시 맞췄으며 Game/Unity 작업트리는 변경하지 않았다.
- 🤖 `tasks.md`는 정합성 분석에서 찾은 Guest 권한·자동 보정 금지·lazy chunk 격리·20분 사용성 검증을 보강해 총 90개, 완료 21개로 갱신했다. 계약 fixture 7/7, Runtime trace 6/6, JSON 10/10, task ID 90/90 unique, checklist 16/16을 통과했다.
- 트러블슈팅: GS-T016~GS-T019

### 전체 브랜치 재대조 및 develop 기준선 재정렬 ✅

- 🤖 전체 로컬·원격 브랜치를 갱신해 비교한 결과, 기능 브랜치가 처음 `game` 커밋에서 분기되어 `origin/develop` 기준 변경 목록에 무관한 Unity 경로 **2,057개**가 따라오는 것을 확인했다.
- 🤖 원본 이력을 `backup/game-studio-gamebase-20260821`에 보존한 뒤 Game Studio 9개 커밋만 최신 `origin/develop`(`41b119b`) 위로 재배치했다. 최종 변경은 문서·spec 43개 경로이며 `festa-unity/**` 변경은 **0개**다.
- 🤖 작업트리를 바꾸지 않는 가상 병합으로 `origin/develop` 병합이 충돌 없이 가능한 것을 확인했다. `front/back/ai/game`에 직접 병합하면 각 브랜치의 오래된 공통 문서·삭제 이력 때문에 충돌하므로, 이 브랜치의 병합 대상은 **develop 한 곳**으로 제한한다.
- 🤖 공유 `game` 작업트리와 사용자의 미커밋 Unity Scene은 수정·stage·commit하지 않았다. Game Studio 작업은 별도 worktree에서만 수행했다.
- 🤖 로컬 검증 완료 후 `feature/game-studio-foundation`을 동명의 원격 브랜치에 최초 push하고 upstream 추적을 연결했다.
- 트러블슈팅: GS-T012~GS-T015

### 편집기 시안 반영 및 계약 정합화 ✅

- 🤖 spec 019에 실제 편집기 작업공간(Scene 목록, Object palette, Tile/Object canvas, Properties, Event Editor, Preview/Save/Publish), Asset reference, TOP_DOWN 격자 좌표, preset recipe를 요구사항으로 반영했다.
- 🤖 DIALOGUE를 `OVERLAY`와 `FULL_SCREEN`으로 구분하고 `SHOW_DIALOGUE`, `CLOSE_DIALOGUE`, `GO_TO_SCENE`의 호출·복귀 의미를 고정했다. Web 2D Runtime이 직접 실행하며 Unity는 Portal 진입 트리거만 담당한다는 경계는 유지했다.
- 🤖 JSON Schema의 `nextNodeId` 위치를 Event가 아닌 Dialogue Choice로 바로잡고, asset scheme·Scene 경계·Object 위치 검증과 음수 fixture 2종을 추가했다. Preview와 Published Runtime이 같은 GameProject 해석 결과를 내야 한다는 작업도 명시했다.
- 🤖 상위 서비스·아키텍처·Backend API·DB·Frontend·다음 할 일·팀 결정 문서를 동기화했다. Frontend asset catalog/resolver와 Backend asset metadata·publish validation 책임은 별도 작업으로 나눴다.
- 🤖 갱신된 계약과 미결정 항목을 GitHub [#20 Frontend](https://github.com/kanghyunsoon/ssafesta/issues/20#issuecomment-5364266235), [#21 Backend](https://github.com/kanghyunsoon/ssafesta/issues/21#issuecomment-5364266871), [#22 AI](https://github.com/kanghyunsoon/ssafesta/issues/22#issuecomment-5364267551)에 댓글로 남기고 담당자를 다시 태그했다.
- 🤖 계약 fixture **7/7**, 결정론적 runtime trace **6/6**, 계약 JSON **10/10**을 통과했다. `tasks.md`는 총 80개 중 결정 비의존 작업 **18개 완료**다.
- 🤖 Spec-Kit 비파괴 정합성 분석에서 발견한 branch 표기, Dialogue 종료, Preview/Published 동등성, FE/BE asset 책임 분리 문제를 모두 수정했다.
- 🤖 관련 커밋: `e2b86ad docs(game-studio): align editor and dialogue contracts`, `b4d842e docs(game-studio): sync cross-part architecture`.
- 트러블슈팅: GS-T011

### Game Studio 편집기 시안 구현 가능성 검토 ✅

- 🤖 사용자 제공 시안의 Scene 목록, Object palette, Tile/Object canvas, Properties, Event Editor, Preview/Save/Publish 구성을 spec 019와 대조했다.
- 🤖 시안의 핵심인 `타일 레이어 + Asset 참조 + Object/Component + Trigger/Condition/Action + GameProject JSON + Web Runtime` 흐름은 현재 v1 계약과 일치하며, 첫 MVP 화면 구조로 사용할 수 있음을 확인했다.
- 🤖 실제 구현에서는 브라우저 로컬 `Assets 폴더`를 영구 기준으로 삼지 않고, 기본 asset catalog 또는 서버가 발급한 asset reference를 GameProject가 참조하도록 구분해야 한다. 편집기와 Runtime은 같은 원본 JSON을 소비하되 Runtime은 편집기 상태를 직접 읽지 않는다.
- 🤖 첫 수직 범위는 `TOP_DOWN + DIALOGUE`, 열쇠 획득→문 열기→대화→Scene 이동으로 유지한다. PLATFORMER와 범용 퍼즐 노드 편집기는 MVP 검증 뒤 확장한다.
- 🤖 시안 검토만 수행했으며 spec, schema, 구현 코드는 변경하지 않았다.
- 트러블슈팅: GS-T010

## 2026-08-20

### Game Studio 전용 기록 문서 분리 ✅

- 🤖 사용자 요청에 따라 Game Studio 작업 기록을 일반 Unity/프로젝트 일지에서 분리했다.
- 🤖 기존 `24_작업일지.md`의 Game Studio 3개 작업 섹션을 이 문서로 이동하고, 기존 T-162~T-168은 `28_Game_Studio_트러블슈팅.md`의 `GS-T001~GS-T007`로 재분류했다.
- 🤖 당시 agent 진입 문서, `docs/KHS/README.md`, spec 019의 실행 문서, `docs/22_다음_할일.md`에 전용 기록 경로를 반영했다. 최신 develop 동기화에서는 agent 문서를 수정하지 않고 KHS 문서만 유지했다.
- 🤖 앞으로 Game Studio 작업 완료 시 이 파일만 갱신하고, 일반 `24_작업일지.md`에는 중복 기록하지 않는다.
- 트러블슈팅: GS-T008, GS-T009

### Game Studio 계약 기반·reference Runtime 구현 ✅

- 🤖 파트 답변과 무관한 즉시 작업 9개를 모두 완료했다. `unsupported schema`, `missing start Scene`, `duplicate Object ID`, `invalid DIALOGUE target` 음수 fixture와 기대 error code manifest를 추가했다.
- 🤖 무의존 Node reference validator를 단일 샘플 검사에서 manifest runner로 확장했다. Positive 1건과 Negative 4건이 각각 정확한 code로 실패하는지 확인해 **5/5 통과**, 전체 계약 JSON 문법도 통과했다.
- 🤖 Studio→iframe Preview 계약을 추가했다. exact origin/source 검사, 2 MiB snapshot 상한, requestId lifecycle, Token 금지, 오류 격리와 close cleanup을 정의했다. 실제 origin·배포 단위·CSP 값은 #20 결정을 유지한다.
- 🤖 Event Runtime 의미를 고정했다. Event 배열 순서, Condition AND, Action 순차 적용, terminal Action 마지막 강제, Scene transition 상태 유지, Action 64/transition depth 8 reference budget과 실패 격리를 정의했다.
- 🤖 renderer 없는 reference Runtime과 입력/state trace를 추가했다. `START → ENTER(roomKey) → INTERACT(exitDoor) → CHOOSE(finish)`에서 Scene·Node·변수·inventory·visibility·완료 상태가 **4/4 통과**했다.
- 🤖 지원 major matrix와 Published 원본을 제자리 수정하지 않는 migration policy를 작성했다.
- 🤖 `docs/22_다음_할일.md`에 Game Studio P2의 완료 계약·검증 결과와 #20/#21 이후 실행 순서를 추가했다. `tasks.md`는 71개 중 결정 비의존 작업 **14개 완료**다.
- 🤖 quickstart 예상 결과를 실제 runner 출력과 맞췄다. Unity/Frontend/Backend 구현 파일은 변경하지 않았다.
- 트러블슈팅: GS-T007

### Game Studio 구현 계획·작업분할 + 기능 브랜치 분리 ✅

- 🤖 사용자 요청에 따라 **`feature/game-studio-foundation`** 브랜치를 만들고, 검증된 spec·상위 문서·공통 계약을 커밋했다. 이후 브랜치 오염 점검에서 `game` 기준선 문제를 발견해 develop 위로 재배치했으며 현재 대응 커밋은 `52f6973 docs(game-studio): define web runtime contracts`다.
- 🤖 Spec-Kit plan/tasks 흐름으로 `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `tasks.md`를 작성했다. 파트 답변 없이 가능한 작업과 #20 Frontend·#21 Backend 결정 gate를 파일 경로 단위로 분리했다. #22 AI 답변은 MVP 선행 조건이 아니다.
- 🤖 실제 파트 브랜치를 다시 확인했다. Frontend는 `festa-frontend/`의 React 19.2 + Vite 8.2 + TypeScript 6 + npm, Backend는 `backend/`의 Java 21 + Spring Boot 4.1 + Maven + JPA/Flyway/PostgreSQL/Testcontainers다. 계획은 이 기준을 사용하되 앱 위치·2D renderer와 DB 모델은 담당자 결정을 선점하지 않는다.
- 🤖 구현 순서는 **공통 계약 → 로컬 TOP_DOWN/DIALOGUE 제작·Preview → Draft/Publish → Unity 없는 독립 웹 플레이 → 선택적 Unity Portal → PLATFORMER**로 고정했다.
- 트러블슈팅: GS-T005, GS-T006

### Game Studio P2 스펙·파트 계약·GitHub 이슈 등록 ✅

- 🤖 기존 001~018 기능 spec과 Unity 동결 기준선은 수정하지 않고, 웹 2D UGC 전용 Draft spec을 `019-game-studio`로 신설했다. 첫 범위는 `TOP_DOWN + DIALOGUE`, 후속은 `PLATFORMER`이며 `PUZZLE`은 별도 Runtime이 아니라 Event/Component 조합으로 먼저 검증한다.
- 🤖 실행 경계를 확정 가능한 수준까지 정리했다. **독립 URL은 React 2D Runtime이 바로 실행**하고, 부스 안에서는 `Unity 상호작용 → React Host → Spring Portal Binding → React Runtime` 순서로 진입한다. Unity는 GameProject를 조회·해석·실행하지 않으며 별도 WebGL 게임 Build도 만들지 않는다.
- 🤖 파트 답변이 필요한 계약을 GitHub 이슈로 등록했다 — [#20 Frontend](https://github.com/kanghyunsoon/ssafesta/issues/20)(`@ghkim1632`, `@colosair`), [#21 Backend](https://github.com/kanghyunsoon/ssafesta/issues/21)(`@strdeok`), [#22 AI](https://github.com/kanghyunsoon/ssafesta/issues/22)(담당 계정 미지정).
- 🤖 답변 전 진행 가능한 공통 계약을 작성했다. GameProject JSON Schema, Draft/Publish·Runtime·Portal API 경계, Unity→React Bridge, 파트 책임표와 최소 수직 fixture(`열쇠 → 문 → 대화 → 완료`)를 추가했다.
- 🤖 서비스 기능, 전체 아키텍처, Backend API, DB, Frontend, Unity Client, 팀 결정 필요사항, SDD 분할안, specs 인덱스를 갱신했다. 기존 Booth Studio/Layout/Runtime 계약과 014 Unity 미니게임 책임은 유지했다.
- 트러블슈팅: GS-T001~GS-T004
