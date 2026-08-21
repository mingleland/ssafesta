# FESTA Game Studio 작업일지

> **범위**: `specs/019-game-studio`, 향후 `festa-game-studio/`, GameProject 계약, Web 2D Runtime,
> Spring Draft/Publish·Portal Binding, FESTA Host 연동.
>
> Unity 월드·Booth Runtime 일반 작업은 기존 `24_작업일지.md`에 기록한다.
> Game Studio 작업 중 문제는 `28_Game_Studio_트러블슈팅.md`의 `GS-T###`으로 참조한다.

## 2026-08-21

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
- 🤖 `AGENTS.md`, `CLAUDE.md`, `docs/KHS/README.md`, spec 019의 spec/plan/tasks, `docs/22_다음_할일.md`에 전용 기록 경로를 반영했다.
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

- 🤖 사용자 요청에 따라 `game`에서 **`feature/game-studio-foundation`** 브랜치를 분기하고, 검증된 spec·상위 문서·공통 계약을 `fabef30 docs(game-studio): define web runtime contracts`로 먼저 커밋했다.
- 🤖 Spec-Kit plan/tasks 흐름으로 `plan.md`, `research.md`, `data-model.md`, `quickstart.md`, `tasks.md`를 작성했다. 파트 답변 없이 가능한 작업과 #20 Frontend·#21 Backend 결정 gate를 파일 경로 단위로 분리했다. #22 AI 답변은 MVP 선행 조건이 아니다.
- 🤖 실제 파트 브랜치를 다시 확인했다. Frontend는 `festa-frontend/`의 React 19.2 + Vite 8.2 + TypeScript 6 + npm, Backend는 `backend/`의 Java 21 + Spring Boot 4.1 + Maven + JPA/Flyway/PostgreSQL/Testcontainers다. 계획은 이 기준을 사용하되 앱 위치·2D renderer와 DB 모델은 담당자 결정을 선점하지 않는다.
- 🤖 구현 순서는 **공통 계약 → 로컬 TOP_DOWN/DIALOGUE 제작·Preview → Draft/Publish → Unity 없는 독립 웹 플레이 → 선택적 Unity Portal → PLATFORMER**로 고정했다.
- 트러블슈팅: GS-T005, GS-T006

### Game Studio P2 스펙·파트 계약·GitHub 이슈 등록 ✅

- 🤖 기존 001~018 기능 spec과 Unity 동결 기준선은 수정하지 않고, 웹 2D UGC 전용 **`019-game-studio` Draft spec**을 신설했다. 첫 범위는 `TOP_DOWN + DIALOGUE`, 후속은 `PLATFORMER`이며 `PUZZLE`은 별도 Runtime이 아니라 Event/Component 조합으로 먼저 검증한다.
- 🤖 실행 경계를 확정 가능한 수준까지 정리했다. **독립 URL은 React 2D Runtime이 바로 실행**하고, 부스 안에서는 `Unity 상호작용 → React Host → Spring Portal Binding → React Runtime` 순서로 진입한다. Unity는 GameProject를 조회·해석·실행하지 않으며 별도 WebGL 게임 Build도 만들지 않는다.
- 🤖 파트 답변이 필요한 계약을 GitHub 이슈로 등록했다 — [#20 Frontend](https://github.com/kanghyunsoon/ssafesta/issues/20)(`@ghkim1632`, `@colosair`), [#21 Backend](https://github.com/kanghyunsoon/ssafesta/issues/21)(`@strdeok`), [#22 AI](https://github.com/kanghyunsoon/ssafesta/issues/22)(담당 계정 미지정).
- 🤖 답변 전 진행 가능한 공통 계약을 작성했다. GameProject JSON Schema, Draft/Publish·Runtime·Portal API 경계, Unity→React Bridge, 파트 책임표와 최소 수직 fixture(`열쇠 → 문 → 대화 → 완료`)를 추가했다.
- 🤖 서비스 기능, 전체 아키텍처, Backend API, DB, Frontend, Unity Client, 팀 결정 필요사항, SDD 분할안, specs 인덱스를 갱신했다. 기존 Booth Studio/Layout/Runtime 계약과 014 Unity 미니게임 책임은 유지했다.
- 트러블슈팅: GS-T001~GS-T004
