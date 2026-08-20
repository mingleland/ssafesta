# Data Model: FESTA Game Studio

## Aggregate Boundary

```text
Game
├─ Draft GameVersion (mutable by revision)
├─ Published GameVersion 0..N (immutable)
└─ PortalBinding 0..N

GameVersion.projectJson
├─ Variables / Items / Assets
└─ Scenes
   ├─ TOP_DOWN → TileLayers / Objects / Events
   └─ DIALOGUE → Nodes / Choices
```

Spring은 Aggregate의 영구 상태를 소유한다. Web Runtime은 Published snapshot으로부터 한 번의
`RuntimeSessionState`를 메모리에 생성하지만 이를 서버의 영구 상태로 취급하지 않는다.

## Entities

### Game

| Field | Type | Rule |
|---|---|---|
| id | long | 서버 발급, immutable |
| ownerUserId | long | Guest 불가 |
| title | string(1..100) | 표시용, GameProject title과 publish 시 동기 검증 |
| visibility | PRIVATE/PUBLIC | 공개 조회 정책은 #21 |
| latestPublishedVersionId | nullable long | 같은 Game의 PUBLISHED만 참조 |
| createdAt/updatedAt | instant | 서버 기록 |

### GameVersion

| Field | Type | Rule |
|---|---|---|
| id | long | 서버 발급 |
| gameId | long | Game FK |
| state | DRAFT/PUBLISHED | PUBLISHED→DRAFT 역전 금지 |
| schemaVersion | semver string | Project envelope와 일치 |
| revision | non-negative integer | Draft 낙관적 잠금용 |
| versionNo | positive integer nullable | Published 순번 |
| projectJson | JSONB | Schema + semantic validation 대상 |
| publishedAt | instant nullable | PUBLISHED에서 필수 |

### GamePortalBinding

| Field | Type | Rule |
|---|---|---|
| id/configId | long | Unity가 전달하는 opaque identifier |
| boothId | long | Booth FK |
| objectId | stable string | Booth Layout canonical objectId |
| gameId | long | Game FK |
| publishedVersionId | nullable long | null이면 최신 공개본 정책 후보, #21 결정 |
| enabled | boolean | 실행 가능성의 한 조건 |

Unique 후보: `(boothId, objectId)`. Booth Layout은 `configId`만 가지고 GameProject를 포함하지 않는다.

## Contract Value Objects

### GameProject

- `schemaVersion`, `gameId`, `revision`, `title`, `startSceneId`
- `variables[]`, `items[]`, `assets[]`, `scenes[]`
- 상세 구조는 `contracts/game-project-v1.schema.json`이 유일한 구조 계약이다.

### Scene

| Type | Own data | Runtime |
|---|---|---|
| TOP_DOWN | width/height, tileLayers, objects, events | grid movement + interaction |
| DIALOGUE | startNodeId, nodes, choices | graph traversal |
| PLATFORMER | 후속 계약 | side-view physics adapter |

### GameObject

- `id`, `preset`, `position`, `visible`, `components[]`
- preset은 editor 기본값이며 runtime capability를 대신하지 않는다.
- v1 Component: SPRITE, COLLIDER, INTERACTABLE, PICKUP.

### GameEvent

- Trigger: ON_SCENE_START, ON_INTERACT(target), ON_ENTER(target)
- Conditions: VARIABLE_EQUALS, HAS_ITEM
- Actions: SHOW_DIALOGUE, SET_VARIABLE, GIVE/REMOVE_ITEM, SHOW/HIDE_OBJECT, GO_TO_SCENE, COMPLETE_GAME

## RuntimeSessionState

```text
projectVersionId
currentSceneId
currentDialogueNodeId?
variables: Map<variableId, scalar>
inventory: Set<itemId>
objectVisibility: Map<objectId, boolean>
status: LOADING | PLAYING | COMPLETED | FAILED | CLOSED
executedActionsThisTick
transitionDepth
```

Runtime state는 브라우저 메모리의 비권위 상태다. MVP에서는 완료·점수·inventory를 Coin/Reward 근거로 저장하지 않는다.

## State Transitions

### Authoring

```text
NO_GAME → DRAFT
DRAFT(revision N) → DRAFT(revision N+1)
DRAFT(valid) → PUBLISHED(version M) + DRAFT 유지
PUBLISHED(version M) → immutable
```

동일 revision 재저장은 conflict다. Publish 실패는 Draft를 변경하지 않는다.

### Runtime

```text
LOADING → PLAYING
LOADING → FAILED
PLAYING → PLAYING (Scene/Node transition)
PLAYING → COMPLETED
PLAYING → FAILED
PLAYING|COMPLETED|FAILED → CLOSED
```

Runtime failure는 Game overlay 안에 격리되며 Unity 월드/다른 FESTA 화면 상태를 변경하지 않는다.

## Validation Invariants

1. 모든 참조 ID가 존재하고 namespace 안에서 중복되지 않는다.
2. startScene과 DIALOGUE startNode가 존재한다.
3. TOP_DOWN Scene은 PLAYER_SPAWN을 정확히 하나 가진다.
4. Tile layer data 길이는 width×height다.
5. 같은 Object에 동일 Component type을 중복하지 않는다.
6. SHOW_DIALOGUE는 DIALOGUE Scene만 참조한다.
7. Variable 선언 type과 initialValue 실제 type이 일치한다.
8. 한 tick Action 수와 transition depth가 budget을 넘으면 FAILED로 격리한다.
9. 지원하지 않는 schema major는 migration 추측 없이 거부한다.
