# Feature Specification: FESTA Game Studio

**Feature Branch**: `feature/game-studio-web-runtime`

**Created**: 2026-08-20

**Updated**: 2026-08-24 — GitLab 이관 후 브랜치 Convention 정합, Portal 응답 소유권, Published Session·Coin 후보 경계 반영

**Status**: 구현 진행 — 로컬 Authoring·Preview·Reference Runtime 완료 / Backend Draft·Publish·Portal 통합 대기

**Priority**: P2 — 기존 P0/P1 안정화 이후 착수

**Primary Owners**: Game Studio Frontend + Backend / FESTA Host Frontend는 진입·인증·Bridge / Unity는 선택적 부스 진입 / AI는 MVP 비의존

**Related Specs**: 005(Booth Layout), 006(Booth Runtime), 014(관리자 미니게임), 016(Web Overlay)

**Work Records**: `docs/KHS/27_Game_Studio_작업일지.md` / `docs/KHS/28_Game_Studio_트러블슈팅.md`

**Input**: 사용자가 웹에서 하나의 공통 2D 제작기로 Scene·오브젝트·이벤트를 조합해 게임을 만들고, 같은 웹 Runtime에서 미리보기·공개·플레이한다. FESTA 부스 NPC와의 상호작용은 선택적 진입점이며 Unity가 2D 게임을 실행하거나 해석하지 않는다.

> 이 기능은 `014-minigame`을 대체하지 않는다. 014는 Unity 관리자 부스의 타이머 정지 게임 1종이고, 019는 사용자가 제작한 웹 2D 콘텐츠를 다루는 독립 UGC 기능이다.

## Clarifications

### Session 2026-08-24

- Q: Portal 응답이 Unity 상호작용의 `objectId`를 되돌려줘야 하는가? → A: 아니다. Backend Binding은 `configId → booth/game`을 소유하고 `objectId`는 소유하지 않는다. `objectId`는 React가 Overlay를 연 Booth Object와 입력 복구 위치를 추적하는 로컬 문맥으로만 유지하고, 서버 응답에서는 `configId + boothId`만 대조한다(#56, PR #82).
- Q: Published 게임의 선택적 Coin 차감을 GameProject에 넣는가? → A: 아니다. 가격·잔액·차감·idempotency·세션은 Backend metadata/session이 소유한다. #81 합의 전 endpoint·정책 후보는 `contracts/game-session-api.candidate.md`에 격리하며 운영 API 정본으로 구현하지 않는다.

### Session 2026-08-23

- Q: 메이플스토리 월드 메이커를 어떤 수준으로 참고하는가? → A: Scene 중심 작업, 배치 가능한 Preset, 즉시 테스트, 단축키, 난이도별 학습 구조를 UX 기준으로 삼는다. FESTA v1은 임의 Script/API를 노출하지 않고 `배치 → 모습 선택 → 빠른 행동 → 플레이`가 기본이며 Component/ID/좌표는 고급 설정에 둔다.
- Q: #69 운영 업로드 전 로컬 Asset이 서버 Draft를 막지 않게 하려면? → A: v1 업로드는 5MiB 이하 PNG/JPEG/GIF/WebP만 허용하고 SVG·AUDIO는 거부한다. 원격 Asset repository는 서버가 발급한 ID와 READY 상태의 stable `asset://`만 반환하며, 원격 repository가 없는 서버 Authoring 모드에서는 로컬 Asset을 GameProject에 추가하지 않는다.
- Q: 장르마다 별도 저장 모델을 만들 것인가? → A: 아니다. Backend 문서 형식은 `GameProject v1` 하나이고 실행 방식은 `TOP_DOWN`·`PLATFORMER` 두 개만 둔다. 스토리·방탈출·수집·점프맵·슈팅·생존은 검색/추천용 장르 태그와 제작 시작 템플릿일 뿐 별도 Runtime이나 테이블이 아니다.
- Q: 현재 로컬 수직 구현은 무엇을 선택했는가? → A: 기존 `festa-frontend` 안의 lazy module, TypeScript 계약/상태 코어, DOM/CSS reference renderer, same-origin `/app/games/:gameId/play?source=local` Preview를 선택했다. Production renderer나 API adapter는 port 뒤에서 교체할 수 있고 GameProject 의미를 바꾸지 않는다.
- Q: 기본 자산과 사용자 이미지는 어떻게 노출하는가? → A: versioned `builtin://` 타일셋·스프라이트·배경·인물 표정을 먼저 제공하고, 사용자는 선택한 Sprite/배경만 명시적으로 교체한다. 로컬 blob은 Preview 전용이며 Publish에는 Backend가 발급한 안정 Asset reference만 허용한다.
- Q: DB와 브라우저를 보호하는 상한은 무엇인가? → A: GameProject JSON 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset reference 300을 v1 상한으로 검증한다. FE 로컬 저장과 BE Draft 저장·Publish 모두 같은 상한과 내부 참조 무결성을 적용하며 이미지·오디오 binary는 JSON/DB에 넣지 않는다.

### Session 2026-08-21

- Q: Game Studio와 Runtime을 어디에 배치하고 어떤 URL·인증 경계를 사용할 것인가? → A: 기존 FESTA Web 안의 분리된 영역에서 인증을 공유하고 편집·플레이 화면을 지연 로드하며 Preview도 같은 출처에서 실행한다(#20).
- Q: Draft와 Published 데이터를 어떻게 저장·충돌 검출·Publish할 것인가? → A: 게임별 현재 Draft와 불변 Published Version을 분리하고 revision 충돌을 명시하며 Publish는 전부 성공하거나 전부 취소되게 한다(#21).
- Q: AI는 MVP와 후속 제작 보조에 어떤 방식으로 연결되는가? → A: P0/P1은 FastAPI 비의존이며 P2는 GameProject v1 호환 candidate/patch를 사용자 승인 후 일반 데이터로 저장하고 spec 007 Job 정책을 재사용한다(#22).
- Q: 공개 중단·삭제·이력·랭킹 정책은 무엇인가? → A: 새 진입만 REST 조회에서 차단하고 이미 로드된 무보상 세션은 완료까지 허용한다. 일반 삭제는 soft, 회원 탈퇴는 관련 데이터를 hard delete하며 Published 이력은 Game 존속 중 유지한다. 랭킹은 Coin/Reward와 절연된 P1 표시 전용 후보로 미룬다(#33).
- Q: Portal `configId`는 각 계층에서 어떻게 표현하는가? → A: wire/Unity는 signed Int32, FE는 정수 `number`, DB 공개 ID는 `INTEGER UNIQUE NOT NULL CHECK (>0)`이고 내부 PK/FK는 BIGINT다. `GAME_PORTAL`은 canonical whitelist에 `requiresConfig=true`로 추가한다(#34).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 탐색·대화 게임 제작 (Priority: P1)

부스 소유자는 준비된 Scene, 맵, 오브젝트, 변수, 대화와 이벤트를 조합해 코드를 작성하지 않고 간단한 탐색·대화 게임을 만든다.

**Why this priority**: 실행 가능한 가장 작은 공통 제작 경험이며, 방탈출·조사·대화형 어드벤처를 하나의 데이터 구조로 검증할 수 있다.

**Independent Test**: 빈 프로젝트에서 시작 Scene, 플레이어, NPC, 열쇠, 문과 대화를 배치하고
"열쇠 획득 → 문 상호작용 → 맵 위 대화 → 기존 맵 복귀 → 다음 Scene 이동" 흐름을 완성한다.

**Acceptance Scenarios**:

1. **Given** 빈 게임 프로젝트, **When** 제작자가 시작 Scene과 플레이어 시작점을 구성하면, **Then** 프로젝트는 실행 가능한 시작 위치를 가진다.
2. **Given** 오브젝트가 배치된 Scene, **When** 제작자가 Trigger·Condition·Action을 설정하면, **Then** 시스템은 허용된 조합만 저장하고 잘못된 참조를 알려준다.
3. **Given** NPC와 대화 데이터, **When** 제작자가 선택지와 변수 조건을 연결하면, **Then** 선택 결과에 따라 서로 다른 대화 또는 Scene으로 진행할 수 있다.
4. **Given** 제작자가 Scene 또는 Object를 선택한 상태, **When** 속성이나 이벤트를 수정하면, **Then** 선택 대상과 수정 결과를 한 화면에서 확인할 수 있다.
5. **Given** TOP_DOWN Scene에서 대화가 시작된 상태, **When** 대화를 닫는 선택지를 실행하면, **Then** 기존 맵 상태를 유지한 채 같은 위치로 복귀하고 월드 입력이 다시 활성화된다.

---

### User Story 2 - 저장 전 미리보기와 공개 (Priority: P1)

제작자는 현재 편집 중인 게임을 즉시 미리보고, 오류를 수정한 뒤 방문자가 플레이할 Published Version으로 공개한다.

**Why this priority**: 제작 도구는 결과를 빠르게 확인할 수 있어야 하며 Draft가 방문자에게 노출되지 않아야 한다.

**Independent Test**: 저장하지 않은 변경을 미리보기에서 확인하고, 잘못된 Scene 참조가 있는 상태의 Publish는 거부되며 수정 후 새 Published Version이 생성되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 저장 전 편집 상태, **When** 제작자가 미리보기를 실행하면, **Then** 공개 버전을 변경하지 않고 현재 상태로 게임이 시작된다.
2. **Given** 유효하지 않은 오브젝트·Scene 참조, **When** Publish를 시도하면, **Then** 공개되지 않고 수정 위치와 사유가 표시된다.
3. **Given** 유효한 Draft, **When** 제작자가 Publish하면, **Then** 새로운 불변 Published Version이 생성되고 이전 공개 버전은 보존된다.
4. **Given** 같은 Draft snapshot, **When** Preview와 Published 플레이에서 각각 실행하면, **Then** 동일한 Scene·Asset·Event 의미와 상태 전이가 재현된다.

---

### User Story 3 - 웹에서 Published 게임 플레이 (Priority: P1)

방문자는 게임 목록이나 공유된 진입점에서 Published 게임을 열고 2D로 플레이한 뒤 안전하게 종료한다.

**Why this priority**: Game Studio의 가치는 제작물이 Unity와 무관하게 웹에서 끝까지 실행될 때 성립한다.

**Independent Test**: Unity를 실행하지 않은 브라우저에서 Published 게임을 열어 시작 Scene부터 완료까지 진행하고 종료한다.

**Acceptance Scenarios**:

1. **Given** Published Version이 있는 게임, **When** 방문자가 플레이를 시작하면, **Then** 최신 공개 버전이 로드되고 지정된 시작 Scene에서 시작한다.
2. **Given** 지원하지 않는 데이터 버전 또는 손상된 데이터, **When** Runtime이 로드하면, **Then** 실행을 중단하고 사용자에게 복구 가능한 오류를 표시한다.
3. **Given** 게임 진행 중, **When** 방문자가 종료하면, **Then** 게임 화면이 닫히고 이전 FESTA 화면으로 돌아간다.

---

### User Story 4 - 부스 NPC를 통한 게임 진입 (Priority: P2)

방문자는 FESTA 3D 부스에서 특정 NPC 또는 게임 포털과 상호작용하여 그 부스에 연결된 Published 게임을 연다.

**Why this priority**: Game Studio 본체에는 필요 없지만 FESTA의 부스 경험과 사용자 제작 게임을 연결한다.

**Independent Test**: 게임이 연결된 부스 오브젝트와 상호작용하여 웹 게임을 열고, 종료 후 같은 월드 위치와 연결 상태로 복귀한다.

**Acceptance Scenarios**:

1. **Given** Published 게임이 연결된 부스 NPC, **When** 방문자가 상호작용하면, **Then** Unity는 게임 실행 요청만 웹 레이어에 전달한다.
2. **Given** 실행 요청을 받은 웹 레이어, **When** 서버가 게임을 실행 가능하다고 확인하면, **Then** 2D 게임 화면을 열고 월드 이동 입력을 차단한다.
3. **Given** 2D 게임이 종료된 상태, **When** 웹 레이어가 게임 화면을 닫으면, **Then** Unity 입력과 화면이 복구되고 월드 연결은 유지된다.
4. **Given** 삭제·비공개·연결 해제된 게임, **When** NPC와 상호작용하면, **Then** 월드를 종료하지 않고 이용 불가 안내를 표시한다.

---

### User Story 5 - 플랫폼 액션 Scene 확장 (Priority: P1)

제작자는 동일 프로젝트에 횡스크롤 플랫폼 Scene을 추가하고 대화·탐색 Scene과 전환한다.

**Why this priority**: 하나의 공통 계약으로 탐색 외 액션 장르까지 만들 수 있음을 증명하고, 장르별 Backend 모델 증가를 막는다.

**Independent Test**: 탐색 Scene에서 플랫폼 Scene으로 이동해 장애물을 통과하고 Goal에 도달한 뒤 대화 Scene으로 전환한다.

**Acceptance Scenarios**:

1. **Given** 플랫폼 Scene, **When** 제작자가 시작점·바닥·위험물·Goal을 배치하면, **Then** 기본 액션 흐름을 미리볼 수 있다.
2. **Given** 서로 다른 유형의 Scene, **When** Scene 이동 Action이 실행되면, **Then** 공통 변수 상태를 유지한 채 대상 Scene으로 전환된다.

### Edge Cases

- 시작 Scene, Player Spawn 또는 이동 대상 Scene이 없거나 중복된 경우
- Event가 삭제된 오브젝트·변수·아이템·대화를 참조하는 경우
- 같은 Event가 자기 자신을 다시 실행해 무한 반복하려는 경우
- 한 처리 주기에 너무 많은 Action이 연쇄 실행되는 경우
- 지원하지 않는 Component·Action·schemaVersion이 포함된 경우
- 대화 Overlay를 시작한 맵이 삭제되거나 대화 종료 전에 다른 Scene으로 이동하는 경우
- 전체 화면 대화에서 복귀 Action을 사용하거나 Overlay 대화를 시작 Scene으로 지정한 경우
- 참조 Asset이 삭제·비공개·변조되었거나 Runtime에서 해석할 수 없는 형식인 경우
- 편집 중 Object preset의 편의 속성과 생성된 Event 규칙이 서로 다른 값을 가리키는 경우
- 제작자가 편집 중 다른 기기에서 같은 Draft를 저장한 경우
- Published 게임 또는 Portal Binding이 플레이 직전에 비공개·삭제된 경우
- 게임이 비공개 전환될 때 이미 GameProject를 로드한 무보상 로컬 플레이 세션이 남아 있는 경우(세션은 완료 허용, 새 진입만 차단)
- Portal Binding 공개 ID가 0·음수·signed Int32 상한을 넘는 경우(DB 제약과 wire validation으로 거부)
- 게임 Runtime 로딩 실패가 FESTA 월드나 다른 Overlay에 영향을 주려는 경우
- 게스트가 제작·Publish를 시도하는 경우

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 게임 프로젝트를 Draft와 Published Version으로 구분해야 한다.
- **FR-002**: 제작자는 여러 Scene을 생성·이름 변경·정렬·삭제하고 시작 Scene을 하나 지정할 수 있어야 한다.
- **FR-003**: Game Studio v1은 `TOP_DOWN`, `PLATFORMER`, `DIALOGUE` Scene을 지원해야 한다.
- **FR-004**: 이동·물리 Runtime 유형은 `TOP_DOWN`과 `PLATFORMER` 두 개로 제한하고, `DIALOGUE`는 두 Runtime에서 호출할 수 있는 표현/분기 Scene으로 사용해야 한다.
- **FR-005**: 제작자는 허용된 오브젝트 프리셋을 배치하고 각 Scene 유형에서 지원되는 속성만 편집할 수 있어야 한다.
- **FR-006**: 이벤트는 허용된 Trigger·Condition·Action의 구조화된 조합으로만 구성되어야 하며 사용자 임의 스크립트를 실행해서는 안 된다.
- **FR-007**: 첫 MVP Trigger는 `ON_SCENE_START`, `ON_INTERACT`, `ON_ENTER`를 지원해야 한다.
- **FR-008**: 첫 MVP Condition은 변수 비교와 아이템 보유 여부를 지원해야 한다.
- **FR-009**: 첫 MVP Action은 대화 표시·종료, 변수 변경, 아이템 지급·제거, 오브젝트 표시·숨김, Scene 이동, 게임 완료를 지원해야 한다.
- **FR-010**: 시스템은 Draft 저장 시 구조·스키마·상한을 검증하고 Publish 시 구조·참조·소유권·진행 가능성을 서버에서 다시 검증해야 한다.
- **FR-011**: 제작자는 Published Version을 변경하지 않고 현재 편집 상태를 미리볼 수 있어야 한다.
- **FR-012**: Publish는 검증·새 Published Version 생성·현재 공개본 전환이 전부 성공하거나 전부 취소되어야 하며 성공 후에도 Draft를 유지해야 한다.
- **FR-013**: 방문자는 Unity를 실행하지 않고도 Published 게임을 웹에서 시작·진행·종료할 수 있어야 한다.
- **FR-014**: Runtime은 지원하지 않는 계약 버전과 손상된 프로젝트를 실행하지 않고 명시적 오류를 표시해야 한다.
- **FR-015**: Game Studio의 제작·저장·Publish·플레이 핵심 흐름은 AI 서비스 가용성에 의존해서는 안 된다.
- **FR-016**: P2 AI 생성 기능은 GameProject v1 호환 candidate 또는 patch만 반환하고 Editor 검증과 사용자 승인 후 일반 Dialogue·Event 데이터로 저장해야 하며 Runtime은 AI 서버를 호출하지 않아야 한다.
- **FR-017**: Unity 연동 시 Unity는 부스 상호작용과 실행 요청만 담당하고 GameProject를 조회·해석·실행해서는 안 된다.
- **FR-018**: 부스와 게임의 연결은 Booth Layout 안에 GameProject를 포함하지 않고 별도 연결 식별자로 참조해야 한다.
- **FR-019**: 게임 화면이 열려 있는 동안 월드 연결은 유지되어야 하며 로컬 이동 입력은 차단되어야 한다.
- **FR-020**: 게임 종료·로드 실패는 해당 게임 화면에만 영향을 주고 Unity 월드와 다른 FESTA 기능을 종료해서는 안 된다.
- **FR-021**: 서버는 클라이언트가 주장한 게임 완료·점수·사용자 식별자를 검증 없이 보상이나 랭킹에 사용해서는 안 된다.
- **FR-022**: 첫 MVP는 Coin, Reward, Ranking을 포함하지 않아야 하며 표시 전용 Ranking은 P1 별도 범위로 미뤄야 한다.
- **FR-023**: 게스트는 Published 게임을 플레이할 수 있지만 게임 생성·Draft 저장·Publish는 할 수 없어야 한다.
- **FR-024**: GameProject 계약은 명시적인 버전을 포함하고, 소비자는 지원하는 버전 범위를 확인해야 한다.
- **FR-025**: Draft 저장은 현재 revision의 일치를 확인하고 불일치 시 충돌 사실과 최신 revision을 알려 조용한 덮어쓰기를 금지해야 한다.
- **FR-026**: 제작 화면은 Scene 목록, 재사용 가능한 Object/Asset 목록, 배치 공간, 선택 대상 속성, Event 구성을 오가며 현재 선택과 수정 결과를 잃지 않게 해야 한다.
- **FR-027**: 배경과 Object의 시각 자료는 배치 데이터와 분리된 안정적인 Asset 참조로 저장해야 하며, 완성 화면의 캡처 이미지를 게임 원본으로 저장해서는 안 된다.
- **FR-028**: Tile Layer는 Scene 크기와 일치하는 셀 배치 데이터로 저장하고, Object 위치는 TOP_DOWN 격자 좌표 기준으로 해석해야 한다.
- **FR-029**: 문 잠금·필요 아이템 같은 편의 설정은 공통 Component·Condition·Action으로 표현되어야 하며 동일 의미를 가진 별도 Runtime 규칙을 만들지 않아야 한다.
- **FR-030**: TOP_DOWN에서 호출한 대화는 현재 맵 상태를 보존한 Overlay로 표시하고, 대화 중 월드 입력을 차단해야 한다.
- **FR-031**: Overlay 대화는 명시적 종료 시 호출한 맵으로 복귀할 수 있어야 하며, 선택에 따라 다른 Scene으로 이동하거나 게임을 완료할 수도 있어야 한다.
- **FR-032**: 시작 Scene 또는 일반 Scene 이동 대상으로 쓰는 전체 화면 대화와, 맵 위에서 호출하는 Overlay 대화를 구분하고 잘못된 호출·복귀 조합을 Publish 전에 거부해야 한다.
- **FR-033**: GameProject에는 실행에 필요한 Asset 식별자와 검증 가능한 참조만 포함하고 이미지·오디오 원본 binary, 만료되는 임시 주소, 브라우저 로컬 파일 경로를 포함하지 않아야 한다.
- **FR-034**: 서버는 잘못된 좌표·참조·알 수 없는 필드를 clamp·삭제·치환하여 자동 보정하지 않고 명시적인 오류로 거부해야 한다.
- **FR-035**: Published 내용이 캐시되어 있더라도 새 Portal 진입 가능 여부는 비공개·임대 만료·연결 해제를 즉시 반영해야 한다.
- **FR-036**: Editor와 Runtime은 기존 FESTA Web의 인증을 공유하는 같은 출처의 분리된 화면으로 제공되어야 하며 일반 FESTA 화면의 초기 로드를 불필요하게 지연시키지 않아야 한다.
- **FR-037**: P2 AI Job은 spec 007의 heartbeat·lease·sweeper·retry·오류 정제·상태 소유권 정책을 재사용하고 생성 실패 시 기존 수동 편집 데이터를 변경해서는 안 된다.
- **FR-038**: 독립 URL은 play route 진입 시, Booth Portal은 overlay open 시 REST 조회로 공개·임대·Binding 상태를 검증해야 하며 Game Studio 전용 socket을 만들지 않아야 한다.
- **FR-039**: 공개 중단 뒤 새 진입은 즉시 거부하되 이미 GameProject를 로드한 무보상 로컬 세션은 서버 push 없이 완료까지 허용해야 한다.
- **FR-040**: 일반 게임 삭제는 soft delete여야 하고 회원 탈퇴는 Game·Draft·Published Version·Asset·Score를 hard delete해야 한다.
- **FR-041**: Published Version 이력은 Game이 존속하는 동안 유지하고 hard delete 시 함께 제거해야 한다.
- **FR-042**: Portal `configId`는 `1..2147483647`만 유효하고 0을 발급해서는 안 되며, DB 내부 BIGINT PK와 별도 INTEGER 공개 ID로 관리해야 한다.
- **FR-043**: `GAME_PORTAL`은 Layout canonical type whitelist에 `requiresConfig=true`로 등록해야 한다. 남의/없는 Binding은 error, 소유한 비활성·비공개 Game은 Booth Publish warning, 방문자 실행은 엄격 차단해야 한다.
- **FR-044**: 기본 제작 시작점은 스토리 탐색, 방탈출, 수집 퀘스트, 플랫폼 액션, 횡스크롤 슈팅, 생존 웨이브 6종을 제공하되 모두 동일 GameProject 계약으로 저장해야 한다.
- **FR-045**: 액션 제작을 위해 피해·체력·점수값·체크포인트·자동 이동·발사·생성 Component를 구조화된 값으로 제공하고 임의 사용자 스크립트를 요구해서는 안 된다.
- **FR-046**: 제작자는 오브젝트 이미지 크기와 겹침 순서를 조정할 수 있어야 하며 타일/배경이 캐릭터와 상호작용 오브젝트를 덮지 않도록 일관된 layer 규칙을 사용해야 한다.
- **FR-047**: DIALOGUE는 별도 장르가 아니라 플레이 중 Overlay 또는 전체 화면 연출로 삽입할 수 있어야 하며 배경·인물·표정·대사·선택지를 편집할 수 있어야 한다.
- **FR-048**: 편집 UI는 한국어 기본 재료, 빠른 행동 recipe, 프로젝트별 첫 방문 guide와 `시작 맵 → 오브젝트 → 모습 → 동작 → 저장·플레이` 5단계 범용 튜토리얼을 제공해 사용자가 Asset 경로나 JSON을 직접 다루지 않고 배치와 기획에 집중하게 해야 한다.
- **FR-049**: 기본 Asset은 바로 선택 가능해야 하고 사용자 파일 선택은 전역 작업 흐름이 아니라 선택한 요소의 `내 이미지로 교체` 또는 접힌 고급 영역에서만 노출해야 한다.
- **FR-050**: v1 상한은 JSON 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset 300이며 초과 데이터는 저장 전에 명시적으로 거부해야 한다.
- **FR-051**: PC 편집기는 배치된 Object를 ID·종류로 검색하고 편집 화면에서만 숨기거나 이동을 잠그며 `zIndex`를 조정하는 Layer UI를 제공해야 한다. 숨김·잠금은 Runtime 데이터와 분리한다.
- **FR-052**: Draft revision 충돌 시 현재 로컬 변경을 화면에 유지하고 JSON 백업 뒤 서버 최신본을 불러오는 복구 경로를 제공해야 하며 조용히 덮어써서는 안 된다.
- **FR-053**: Publish 전 임시·불안정 Asset이 있으면 Asset ID뿐 아니라 사용 중인 Scene·Object·Item 위치를 표시해야 한다.
- **FR-054**: PC 키보드는 저장·undo/redo·Layer 열기·선택 Object 한 칸 이동을 제공하고 modal 입력 중에는 편집 단축키를 실행하지 않아야 한다.
- **FR-055**: 6종 시작 템플릿은 이모지나 설명만이 아니라 장르·핵심 플레이·난이도·예상 수정 시간을 판단할 수 있는 실제 16:9 플레이 화면 미리보기를 제공해야 한다.
- **FR-056**: Object 이미지 선택은 모든 Asset ID를 한 목록에 노출하지 않고 캐릭터·사물/장식·내 이미지 범주와 한국어 검색, 실제 frame 미리보기를 제공해야 하며 인물 초상·배경·Tileset을 Object 후보에 섞어서는 안 된다.
- **FR-057**: Object Inspector는 기본 상태에서 모습·크기·가시성·현재 동작만 설명하고 ID·격자 좌표·zIndex·Component 추가/삭제·Object 삭제는 명시적 고급 설정에서만 노출해야 한다.
- **FR-058**: PC 편집기는 760px 이상 창에서 문서 전체 가로 스크롤 없이 세 영역을 유지하고, 큰 맵에서는 양쪽 패널을 숨기는 집중 모드와 내부 Canvas 이동을 제공해야 한다. 1280px 이상을 권장 작업 폭으로 안내한다.
- **FR-059**: v1 사용자 Asset은 파일당 5MiB 이하 PNG/JPEG/GIF/WebP만 허용하고 SVG·AUDIO를 거부해야 한다. 원격 업로드는 서버가 ID를 발급하고 READY가 된 stable `asset://`만 프로젝트에 추가해야 하며, 업로드 계약이 비활성인 서버 저장 모드에서 `asset://local`을 Draft로 보내서는 안 된다.
- **FR-060**: PC Canvas는 단일 클릭뿐 아니라 Shift/Ctrl 추가 선택과 빈 영역 드래그 선택을 제공하고, 선택 묶음의 상대 배치를 유지한 채 이동 경계 안에서 함께 이동해야 한다.
- **FR-061**: Object 복제는 새 전역 ID를 발급하고 해당 Object가 Trigger인 Event와 선택 묶음 내부 Object Action 참조를 함께 복제해야 한다. 삭제는 Player Spawn과 선택 밖 Event가 참조하는 Object를 보존하고 삭제 가능한 선택만 명시적으로 처리해야 한다.
- **FR-062**: 제작자는 World Scene의 허용 범위 안에서 가로·세로 크기를 바꿀 수 있어야 한다. 확대 시 기존 Tile을 같은 좌상단 좌표에 유지하고, 축소 시 남는 Tile을 재배열하며 Object는 새 경계 안으로 이동해야 한다.
- **FR-063**: 6종 시작 템플릿은 미리보기와 제목만 달리해서는 안 되며 Scene 유형·Object 배치·Event/Dialogue·목표 흐름 중 하나 이상의 구조적 차이를 가진 즉시 플레이 가능한 GameProject를 제공해야 한다.
- **FR-064**: 적 처치 수·경과 시간·점수 임계값 기반 승리 규칙을 추가할 때는 기존 v1 필드를 임의 재해석하지 않고 명시적 schemaVersion과 FE·BE·AI 허용 타입을 함께 확정해야 한다. v1.0.0의 Trigger·Condition·Action 집합은 그 결정 전까지 유지한다.
- **FR-065**: Backend가 없어도 공식 Mock 모드에서 Draft 저장, 불변 Published Version 생성, 일반 `/app/games/{gameId}/play` 조회를 같은 port로 검증할 수 있어야 한다. 이 브라우저 저장소는 운영 공유 저장소로 간주해서는 안 된다.
- **FR-066**: GameProject v1.1 FE candidate는 `rules.completion.mode(ALL|ANY)`, `SCORE_AT_LEAST`, `DEFEAT_ENEMIES`, `SURVIVE_SECONDS`, `playerDefeat(RESPAWN|END_GAME)`만 추가한다. Runtime tick은 120ms 결정적 시간으로 누적하고 v1.0 프로젝트는 편집 시 기본 규칙을 가진 v1.1로 명시적으로 승격한다.
- **FR-067**: Publish preflight는 안정 Asset뿐 아니라 `rules` 목표 또는 도달 가능한 `COMPLETE_GAME` Action의 존재를 확인해야 한다. 완료 경로가 하나도 없는 프로젝트는 다른 사용자에게 게시하지 않아야 한다.
- **FR-068**: Portal resolution 응답은 Backend Binding이 소유하지 않는 `objectId`를 요구하거나 반사하지 않아야 한다. `objectId`는 Unity → React 요청 로컬 문맥이며 FE는 서버 소유 `configId + boothId`만 응답과 대조해야 한다.

### Part Boundaries

| Part | Owns | Must Not Own |
|---|---|---|
| Game Studio Frontend | Editor, Preview, Web 2D Runtime, builtin Asset resolver, 계약 검증 UX | FESTA 인증 재구현, 영구 Published 판정, Coin 지급 |
| FESTA Host Frontend | lazy route, 인증/API client, Game Overlay, Unity Bridge와 오류 격리 | GameProject 실행 규칙, renderer 내부, 서버 권한 판정 |
| Backend | Game/Version/Portal Binding, 권한, 검증, Draft/Publish, 공개 조회 | 2D 프레임 실행, Unity Prefab, AI 동기 중계 |
| Unity | 부스 NPC/Portal 표현, 거리·입력 판정, 웹 실행 요청 | GameProject 해석, 2D Runtime, 결과·보상 판정 |
| AI | 후속 선택형 제작 보조 | MVP 핵심 경로, Runtime 실행 의존성 |

### Key Entities *(include if feature involves data)*

- **Game**: 소유자, 제목, 공개 상태와 현재 Published Version을 가진 사용자 제작 게임의 루트.
- **Game Draft**: Game마다 최대 하나 존재하며 revision으로 충돌을 검출하는 가변 작업본.
- **Game Published Version**: `(gameId, versionNo)`로 식별되고 생성 후 변경되지 않는 공개 snapshot.
- **Game Project**: Scene, 변수, 아이템, 에셋 참조와 시작 Scene을 묶는 버전 계약.
- **Scene**: `TOP_DOWN`, `PLATFORMER`, `DIALOGUE` 중 하나의 실행 단위. DIALOGUE는 전체 화면 또는 호출한 맵 위 Overlay로 제시된다.
- **Game Asset Reference**: 타일셋·스프라이트·오디오 원본을 직접 포함하지 않고 안정적인 식별자와 종류로 가리키는 값.
- **Game Object**: Scene에 배치된 안정적인 식별자와 허용된 동작 구성을 가진 요소.
- **Game Event**: Trigger, Conditions, Actions의 제한된 실행 규칙.
- **Game Portal Binding**: Booth Object와 Published 가능한 Game을 연결하는 서버 소유 설정.
- **Game Score**: P1에서만 추가하는 회원별 표시용 최고 점수. Coin·Reward·Inventory와 FK로 연결하지 않고 서버 검증 보상 근거로 사용하지 않는다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 처음 사용하는 제작자가 20분 이내에 "열쇠 획득 → 문 열기 → 대화 → 완료" 게임을 만들고 미리볼 수 있다.
- **SC-002**: 정상 샘플 프로젝트의 제작·미리보기·Publish·웹 플레이 핵심 시나리오 성공률이 100%다.
- **SC-003**: 삭제된 ID, 중복 ID, 없는 Scene 이동 등 정의된 잘못된 참조가 Publish 전에 100% 탐지된다.
- **SC-004**: Draft 변경이 Published 방문자 게임에 노출되는 사례가 0건이다.
- **SC-005**: Unity 없이 Published 게임 전체 흐름을 완료할 수 있다.
- **SC-006**: 부스 진입 통합 테스트에서 게임 실행·종료 뒤 월드 연결과 플레이어 위치가 유지되는 비율이 100%다.
- **SC-007**: 손상된 게임 한 건 때문에 Unity 월드 또는 다른 Published 게임이 중단되는 사례가 0건이다.
- **SC-008**: AI 서비스가 중단된 상태에서도 제작·저장·Publish·Published 플레이 핵심 시나리오가 모두 성공한다.
- **SC-009**: Overlay 대화 종료 후 호출 전 Scene, 변수, 인벤토리, Object 표시 상태가 보존되는 계약 테스트가 100% 통과한다.
- **SC-010**: 정상 프로젝트의 Preview와 Published 플레이에서 같은 입력 순서에 대한 최종 상태가 100% 일치한다.
- **SC-011**: 500개 Object를 가진 유효 Scene에서 단일 Object 이동 commit이 기준 개발 장비의 자동 테스트에서 100ms 미만이다.
- **SC-012**: 활성 PC 브라우저 탭의 대표 TOP_DOWN·PLATFORMER Published/Preview 시나리오에서 렌더링이 55fps 아래로 3초 이상 머무르지 않는다. 백그라운드 throttling 측정은 제외한다.
- **SC-013**: 6종 시작 템플릿의 Scene/Object/Event/Dialogue 구조 프로필이 서로 구분되고 모든 템플릿이 계약 검증과 시작 Scene 실행 검증을 100% 통과한다.
- **SC-014**: 공식 Mock 모드의 브라우저에서 `템플릿 선택 → 저장 → 게시 v1 → 일반 /play 조회 → 동일 목표 HUD 표시` 흐름이 Backend·Unity 없이 100% 성공한다.

## Assumptions

- 기존 Google/Kakao 인증과 Access/Refresh 정책을 재사용한다.
- Game Studio는 기존 FESTA Web과 인증을 공유하는 같은 출처의 분리 영역으로 시작하며 별도 인증 앱을 만들지 않는다.
- 편집과 Published 플레이는 서로 구분되는 인증된 게임 화면으로 제공한다.
- 첫 MVP는 데스크톱 브라우저 편집을 우선하며 모바일은 플레이만 허용할 수 있다.
- v1은 버전이 고정된 기본 Asset catalog와 로컬 교체 Preview를 제공한다. 사용자 업로드의 영구 보존·공개 정책은 Backend Asset 계약 뒤 연결한다.
- Object preset의 편의 입력은 저장 전에 공통 Component/Event 데이터로 변환되며 별도 실행 엔진을 만들지 않는다.
- 미리보기는 공개 버전을 변경하지 않는 로컬/격리 실행을 기본으로 한다.
- `014-minigame`의 Coin 보상과 서버 권위 게임 규칙은 019에 재사용하지 않는다.
- 퍼즐과 Quest는 별도 Runtime을 만들지 않고 Event/Component 조합과 템플릿으로 제공한다. 단순 체력·투사체·자동 이동·Spawner는 v1 reference 범위이며 경로 탐색 AI·복잡한 전투식·멀티플레이 UGC는 포함하지 않는다.

## Dependencies

- FE Host 계약 답변: GitHub Issue #20 — 반영 완료. Local renderer·Preview·Asset resolver #35도 PR #63 병합으로 완료
- BE 저장·Publish 계약 답변: GitHub Issue #21 — 반영 완료
- AI 범위 결정: GitHub Issue #22 — 완료·종료
- 공개 중단·삭제·표시 전용 Ranking 정책: GitHub Issue #33 — 합의 반영 완료
- `configId` Int32·`GAME_PORTAL` whitelist: GitHub Issue #34 — BE·Unity 합의 반영, FE 구현 확인만 추적
- Reference renderer·same-origin local Preview·builtin Asset resolver: GitHub Issue #35 — PR #63 병합 및 이슈 종료. Production Published parity는 #48·#55, Booth 진입은 #56에서 추적
- 사용자 교체 Asset 업로드·stable `asset://` 승격·Publish 연결: GitHub Issue #69 — Frontend·Backend 후속
- 타이머·점수·적 처치 기반 승리 조건과 GameProject v1.1 FE·BE·AI 계약: GitHub Issue #78 — FE candidate와 Mock Runtime은 구현 완료, API 모드 허용·BE validator·AI 출력 허용 목록은 합의 전 미확정
- Published 플레이 세션·선택적 Coin 차감·idempotency·재시도 정책: GitHub Issue #81 — 가격과 차감은 GameProject가 아닌 Backend Game/session metadata가 소유하며 MVP 무보상 Runtime과 분리. 합의 전 후보는 `contracts/game-session-api.candidate.md`
- Booth Layout/Runtime 연결: specs 005, 006
- 기존 Overlay/Bridge 패턴: spec 016

## Repository Handoff

- GitLab 이관 전 Game Studio ref, stacked PR, OPEN Issue, CI/Secret, 검증 기준은
  [KHS Game Studio GitLab 이관 준비](../../docs/KHS/29_Game_Studio_GitLab_이관_준비.md)를 사용한다.
- 이관 준비는 제품 계약이나 파트 소유권을 바꾸지 않는다. 팀 cutover 결정 전까지 GitHub remote와
  PR/Issue가 정본이며, 이 문서 갱신만으로 GitLab remote·Import·mirror를 실행하지 않는다.
- Game Studio 코드 PR 병합 순서는 #72 → #79 → #80 → #82다. 문서 PR #53은 2026-08-24 `develop`에 병합됐다(`64d544e`).

## Out of Scope

- 사용자 코드·수식·플러그인 실행
- 턴제 전투, 경로 탐색/행동 트리 적 AI, 서버 권위 디펜스, 네트워크 멀티플레이
- 사용자 제작 게임의 Coin 보상과 MVP 랭킹. 표시 전용 랭킹은 P1 별도 범위다.
- Unity 안에서 2D 게임을 렌더링하거나 Unity Dedicated Server가 게임 상태를 권위 처리하는 구조
- AI가 플레이 중 응답해야만 진행되는 게임
