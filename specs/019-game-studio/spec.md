# Feature Specification: FESTA Game Studio

**Feature Branch**: `feature/game-studio-foundation`

**Created**: 2026-08-20

**Updated**: 2026-08-21 — 편집기 시안, Asset 참조, DIALOGUE Overlay 복귀 규칙 반영

**Status**: Draft — FE·BE·AI 계약 이슈 검토 대기 (#20, #21, #22)

**Priority**: P2 — 기존 P0/P1 안정화 이후 착수

**Primary Owners**: Frontend + Backend / Unity는 선택적 부스 진입 연동 / AI는 MVP 비의존

**Related Specs**: 005(Booth Layout), 006(Booth Runtime), 014(관리자 미니게임), 016(Web Overlay)

**Work Records**: `docs/KHS/27_Game_Studio_작업일지.md` / `docs/KHS/28_Game_Studio_트러블슈팅.md`

**Input**: 사용자가 웹에서 하나의 공통 2D 제작기로 Scene·오브젝트·이벤트를 조합해 게임을 만들고, 같은 웹 Runtime에서 미리보기·공개·플레이한다. FESTA 부스 NPC와의 상호작용은 선택적 진입점이며 Unity가 2D 게임을 실행하거나 해석하지 않는다.

> 이 기능은 `014-minigame`을 대체하지 않는다. 014는 Unity 관리자 부스의 타이머 정지 게임 1종이고, 019는 사용자가 제작한 웹 2D 콘텐츠를 다루는 독립 UGC 기능이다.

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

### User Story 5 - 플랫폼 액션 Scene 확장 (Priority: P2)

제작자는 동일 프로젝트에 횡스크롤 플랫폼 Scene을 추가하고 대화·탐색 Scene과 전환한다.

**Why this priority**: 공통 Scene·Variable·Event 구조가 두 번째 움직임 방식에서도 재사용되는지 검증하지만 첫 MVP의 선행 조건은 아니다.

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
- 게임 Runtime 로딩 실패가 FESTA 월드나 다른 Overlay에 영향을 주려는 경우
- 게스트가 제작·Publish를 시도하는 경우

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 게임 프로젝트를 Draft와 Published Version으로 구분해야 한다.
- **FR-002**: 제작자는 여러 Scene을 생성·이름 변경·정렬·삭제하고 시작 Scene을 하나 지정할 수 있어야 한다.
- **FR-003**: 첫 MVP는 `TOP_DOWN`과 `DIALOGUE` Scene을 지원해야 한다.
- **FR-004**: 후속 범위는 동일 프로젝트 안에서 `PLATFORMER` Scene을 지원할 수 있어야 한다.
- **FR-005**: 제작자는 허용된 오브젝트 프리셋을 배치하고 각 Scene 유형에서 지원되는 속성만 편집할 수 있어야 한다.
- **FR-006**: 이벤트는 허용된 Trigger·Condition·Action의 구조화된 조합으로만 구성되어야 하며 사용자 임의 스크립트를 실행해서는 안 된다.
- **FR-007**: 첫 MVP Trigger는 `ON_SCENE_START`, `ON_INTERACT`, `ON_ENTER`를 지원해야 한다.
- **FR-008**: 첫 MVP Condition은 변수 비교와 아이템 보유 여부를 지원해야 한다.
- **FR-009**: 첫 MVP Action은 대화 표시·종료, 변수 변경, 아이템 지급·제거, 오브젝트 표시·숨김, Scene 이동, 게임 완료를 지원해야 한다.
- **FR-010**: 시스템은 저장 전 구조 검증과 Publish 전 참조·진행 가능성 검증을 수행해야 한다.
- **FR-011**: 제작자는 Published Version을 변경하지 않고 현재 편집 상태를 미리볼 수 있어야 한다.
- **FR-012**: Published Version은 생성 후 변경되지 않아야 하며 새 Publish는 새 버전을 만들어야 한다.
- **FR-013**: 방문자는 Unity를 실행하지 않고도 Published 게임을 웹에서 시작·진행·종료할 수 있어야 한다.
- **FR-014**: Runtime은 지원하지 않는 계약 버전과 손상된 프로젝트를 실행하지 않고 명시적 오류를 표시해야 한다.
- **FR-015**: Game Studio의 제작·저장·Publish·플레이 핵심 흐름은 AI 서비스 가용성에 의존해서는 안 된다.
- **FR-016**: AI 생성 기능을 추가하더라도 사용자 검토 후 일반 Asset·Dialogue·Event 데이터로 저장되어야 하며 Runtime은 AI 서버를 호출하지 않아야 한다.
- **FR-017**: Unity 연동 시 Unity는 부스 상호작용과 실행 요청만 담당하고 GameProject를 조회·해석·실행해서는 안 된다.
- **FR-018**: 부스와 게임의 연결은 Booth Layout 안에 GameProject를 포함하지 않고 별도 연결 식별자로 참조해야 한다.
- **FR-019**: 게임 화면이 열려 있는 동안 월드 연결은 유지되어야 하며 로컬 이동 입력은 차단되어야 한다.
- **FR-020**: 게임 종료·로드 실패는 해당 게임 화면에만 영향을 주고 Unity 월드와 다른 FESTA 기능을 종료해서는 안 된다.
- **FR-021**: 서버는 클라이언트가 주장한 게임 완료·점수·사용자 식별자를 검증 없이 보상이나 랭킹에 사용해서는 안 된다.
- **FR-022**: 첫 MVP는 Coin, Reward, Ranking을 포함하지 않아야 한다.
- **FR-023**: 게스트는 Published 게임을 플레이할 수 있지만 게임 생성·Draft 저장·Publish는 할 수 없어야 한다.
- **FR-024**: GameProject 계약은 명시적인 버전을 포함하고, 소비자는 지원하는 버전 범위를 확인해야 한다.
- **FR-025**: 편집 충돌이 발생하면 마지막 저장으로 조용히 덮어쓰지 않고 현재 서버 버전과 충돌 사실을 알려야 한다.
- **FR-026**: 제작 화면은 Scene 목록, 재사용 가능한 Object/Asset 목록, 배치 공간, 선택 대상 속성, Event 구성을 오가며 현재 선택과 수정 결과를 잃지 않게 해야 한다.
- **FR-027**: 배경과 Object의 시각 자료는 배치 데이터와 분리된 안정적인 Asset 참조로 저장해야 하며, 완성 화면의 캡처 이미지를 게임 원본으로 저장해서는 안 된다.
- **FR-028**: Tile Layer는 Scene 크기와 일치하는 셀 배치 데이터로 저장하고, Object 위치는 TOP_DOWN 격자 좌표 기준으로 해석해야 한다.
- **FR-029**: 문 잠금·필요 아이템 같은 편의 설정은 공통 Component·Condition·Action으로 표현되어야 하며 동일 의미를 가진 별도 Runtime 규칙을 만들지 않아야 한다.
- **FR-030**: TOP_DOWN에서 호출한 대화는 현재 맵 상태를 보존한 Overlay로 표시하고, 대화 중 월드 입력을 차단해야 한다.
- **FR-031**: Overlay 대화는 명시적 종료 시 호출한 맵으로 복귀할 수 있어야 하며, 선택에 따라 다른 Scene으로 이동하거나 게임을 완료할 수도 있어야 한다.
- **FR-032**: 시작 Scene 또는 일반 Scene 이동 대상으로 쓰는 전체 화면 대화와, 맵 위에서 호출하는 Overlay 대화를 구분하고 잘못된 호출·복귀 조합을 Publish 전에 거부해야 한다.
- **FR-033**: GameProject에는 실행에 필요한 Asset 식별자와 검증 가능한 참조만 포함하고 이미지·오디오 원본 binary, 만료되는 임시 주소, 브라우저 로컬 파일 경로를 포함하지 않아야 한다.

### Part Boundaries

| Part | Owns | Must Not Own |
|---|---|---|
| Frontend | Studio, Preview, Web 2D Runtime, Game Overlay, 계약 검증 UX | 영구 Published 판정, Coin 지급, Unity 상호작용 판정 |
| Backend | Game/Version/Portal Binding, 권한, 검증, Draft/Publish, 공개 조회 | 2D 프레임 실행, Unity Prefab, AI 동기 중계 |
| Unity | 부스 NPC/Portal 표현, 거리·입력 판정, 웹 실행 요청 | GameProject 해석, 2D Runtime, 결과·보상 판정 |
| AI | 후속 선택형 제작 보조 | MVP 핵심 경로, Runtime 실행 의존성 |

### Key Entities *(include if feature involves data)*

- **Game**: 소유자, 제목, 공개 상태와 현재 Published Version을 가진 사용자 제작 게임의 루트.
- **Game Version**: 특정 시점의 GameProject. Draft 또는 불변 Published 상태를 가진다.
- **Game Project**: Scene, 변수, 아이템, 에셋 참조와 시작 Scene을 묶는 버전 계약.
- **Scene**: `TOP_DOWN`, `DIALOGUE`, 후속 `PLATFORMER` 중 하나의 실행 단위. DIALOGUE는 전체 화면 또는 호출한 맵 위 Overlay로 제시된다.
- **Game Asset Reference**: 타일셋·스프라이트·오디오 원본을 직접 포함하지 않고 안정적인 식별자와 종류로 가리키는 값.
- **Game Object**: Scene에 배치된 안정적인 식별자와 허용된 동작 구성을 가진 요소.
- **Game Event**: Trigger, Conditions, Actions의 제한된 실행 규칙.
- **Game Portal Binding**: Booth Object와 Published 가능한 Game을 연결하는 서버 소유 설정.
- **Game Play Session**: 선택적으로 기록되는 한 번의 게임 실행. MVP에서는 보상 근거가 아니다.

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

## Assumptions

- 기존 Google/Kakao 인증과 Access/Refresh 정책을 재사용한다.
- Game Studio는 기존 FESTA Web과 같은 저장소 안의 독립 웹 앱으로 시작한다.
- 첫 MVP는 데스크톱 브라우저 편집을 우선하며 모바일은 플레이만 허용할 수 있다.
- 첫 MVP는 버전이 고정된 기본 Asset catalog로 검증하며 사용자 업로드와 보존 정책은 Backend 계약 확정 후 추가한다.
- Object preset의 편의 입력은 저장 전에 공통 Component/Event 데이터로 변환되며 별도 실행 엔진을 만들지 않는다.
- 미리보기는 공개 버전을 변경하지 않는 로컬/격리 실행을 기본으로 한다.
- `014-minigame`의 Coin 보상과 서버 권위 게임 규칙은 019에 재사용하지 않는다.
- `PUZZLE`, 전투, 적 AI, Quest, Projectile, Spawner, 멀티플레이 UGC는 첫 MVP에 포함하지 않는다.

## Dependencies

- FE 계약 결정: GitHub Issue #20
- BE 계약 결정: GitHub Issue #21
- AI 범위 결정: GitHub Issue #22
- Booth Layout/Runtime 연결: specs 005, 006
- 기존 Overlay/Bridge 패턴: spec 016

## Out of Scope

- 사용자 코드·수식·플러그인 실행
- 턴제 전투, 적 AI, 디펜스, 네트워크 멀티플레이
- 사용자 제작 게임의 Coin 보상과 경쟁 랭킹
- Unity 안에서 2D 게임을 렌더링하거나 Unity Dedicated Server가 게임 상태를 권위 처리하는 구조
- AI가 플레이 중 응답해야만 진행되는 게임
