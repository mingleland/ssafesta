# Research: FESTA Game Studio

## 1. 2D Runtime과 Unity의 관계

**Decision**: Web 2D Runtime은 Unity WebGL과 독립 실행하고 Unity는 Portal trigger만 제공한다.

**Rationale**: 독립 URL 플레이, 빠른 Preview, 작은 배포, 오류 격리라는 요구를 만족한다. 기존
`window.FestaUnity.onBoothInteract(json)` 경로도 실제 구현되어 있어 새 엔진 간 통신이 필요 없다.

**Alternatives considered**:

- Unity WebGL 내부 2D Scene: 이중 Web Runtime, 느린 Preview, Unity 재빌드 결합 때문에 제외.
- Unity iframe 안에 Web Runtime: Host/입력/포커스가 불필요하게 복잡해져 제외.

## 2. Runtime Core와 Renderer 분리

**Decision**: 변수·인벤토리·Scene·Event 실행은 순수 TypeScript state machine으로 두고,
TOP_DOWN/후속 PLATFORMER 렌더링·물리는 adapter로 분리한다.

**Rationale**: Preview와 Published Runtime의 동작 차이를 줄이고 renderer 선택 전에도 계약 테스트가 가능하다.

**Alternatives considered**:

- UI/renderer 코드 안에 Event 처리: editor preview와 play가 쉽게 달라져 제외.
- 장르별 완전 별도 엔진: 변수·대화·Scene 이동이 중복되어 제외.

## 3. Scene 분류

**Decision**: TOP_DOWN과 PLATFORMER만 이동 Runtime 유형으로 보고 DIALOGUE는 Node graph Scene으로 둔다.
DIALOGUE는 호출 Scene을 보존하는 `OVERLAY`와 시작/전환 대상인 `FULL_SCREEN` presentation을 명시한다.
PUZZLE은 v1 별도 Scene이 아니라 Object Component + Event 조합이다.

**Rationale**: 시안처럼 맵 위 대화창을 제공하면서도 대화 중심 게임을 만들 수 있다. 명시적 presentation과
`CLOSE_DIALOGUE`가 없으면 Runtime이 복귀할 Scene을 추측해야 한다. 퍼즐은 실행 방식이 아니라 규칙
조합인 경우가 많아 별도 Scene을 먼저 만들면 유형만 늘어난다.

**Alternatives considered**:

- 모든 장르를 Scene type으로 추가: schema와 editor 분기 폭증으로 제외.
- 대화를 TOP_DOWN 내부 데이터로만 처리: 대화 중심 게임과 재사용 가능한 graph 구성이 불편해 제외.
- 모든 DIALOGUE를 Scene 전환으로 처리: 맵 위 대화 뒤 위치·입력 복귀 의미가 없어 제외.

## 4. Asset과 배치 데이터 분리

**Decision**: GameProject에는 Asset reference와 Tile/Object 배치만 저장하고 binary나 완성 화면 이미지를
저장하지 않는다. MVP는 Game Studio가 소유하는 versioned builtin catalog를 사용하고 업로드는 별도 Asset spec으로 확장한다.

**Rationale**: 같은 프로젝트를 Studio Preview와 Published Runtime이 동일하게 재구성할 수 있고,
이미지 중복·만료 URL·브라우저 로컬 경로가 Published snapshot에 섞이지 않는다.

**Alternatives considered**:

- Scene을 한 장의 이미지로 저장: 충돌·상호작용·개별 Object 편집이 불가능해 제외.
- base64 binary를 JSON에 포함: Project 크기와 검증 비용이 폭증해 제외.
- 임시 signed URL 저장: Published Version보다 URL이 먼저 만료될 수 있어 제외.

## 5. 제한형 Component/Event

**Decision**: preset은 authoring shortcut, 실행 능력은 allow-list typed Component와
Trigger·Condition·Action이 결정한다. 임의 JavaScript와 사용자 표현식은 금지한다.

**Rationale**: 서버 validation, 안전한 publish, 호환성 있는 migration이 가능하다.

**Alternatives considered**:

- 사용자 스크립트: 보안 sandbox와 무한 실행 문제가 MVP 범위를 초과해 제외.
- preset마다 하드코딩된 행동: 조합형 UGC 목표를 충족하지 못해 제외.

## 6. 검증 계층

**Decision**: JSON Schema 구조 검증과 semantic reference 검증을 분리하고 Studio 저장 전,
Spring Publish 전, Runtime load 시 각 경계에 맞춰 반복한다.

**Rationale**: Schema만으로 시작 Scene 존재, 참조 무결성, Player Spawn 수, Event budget을 충분히 표현할 수 없다.

**Alternatives considered**:

- Frontend 검증만: 악성·구버전 client를 신뢰하게 되어 제외.
- Backend 검증만: editor feedback이 늦고 손상된 Runtime load 방어가 없어 제외.

## 7. Draft와 Published

**Decision**: `game_drafts`는 Game당 하나를 PK로 강제하고 revision 충돌을 검출한다.
`game_published_versions`는 `(gameId, versionNo)`별 불변 행을 append하며 Publish는 새 행과
`games.publishedVersion` 포인터 갱신을 단일 트랜잭션으로 처리한다.

**Rationale**: 편집 중 저장이 현재 방문자 게임을 바꾸지 않고, rollback과 cache가 단순해진다.

**Alternatives considered**:

- 한 JSON 행을 Draft/Published가 공유: 편집 노출과 rollback 문제가 있어 제외.
- 모든 autosave를 영구 version으로 보존: 저장량·정리 정책이 불필요하게 커져 채택하지 않는다.

## 8. FESTA Frontend 내부 모듈

**Decision**: 별도 origin/Vite 앱 대신 `festa-frontend/src/game-studio/` 내부 소유 모듈로 두고
`/app/games/:gameId/edit`, `/app/games/:gameId/play` lazy route를 사용한다. Preview도 same-origin이다.

**Rationale**: Access Token은 JavaScript 메모리에 있고 Preview payload로 Token 전달을 금지하므로,
같은 앱에서 기존 인증과 API client를 재사용하는 것이 가장 작은 안전 경계다. lazy chunk로 일반 FESTA
사용자의 초기 번들 결합을 줄이고 디렉터리 소유권으로 수직 구현 책임을 분리한다.

**Alternatives considered**:

- 별도 Vite origin: 인증 전달·배포·CSP 계약이 추가되어 제외.
- 새 workspace/monorepo: 현재 단일 앱 규모에서 설정 비용이 커 제외.

## 9. 확정 보류 항목

다음은 조사 부족이 아니라 담당 파트 또는 제품 결정권 때문에 보류한다.

- #33: 공개 중단 중 세션, 수동 삭제·Published 이력, 표시 전용 Ranking 도입 시점
- #34: Portal `configId` DB key와 signed Int32 wire mapping, `GAME_PORTAL` whitelist
- #35: TOP_DOWN renderer, Preview sandbox/CSP/UI, builtin Asset resolver 세부

계약 fixture와 순수 상태 전이는 이 결정과 독립적이다.
