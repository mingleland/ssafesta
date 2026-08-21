# Data Model: FESTA Game Studio

## Aggregate Boundary

```text
Game
├─ GameDraft 0..1 (mutable by revision, PK=gameId)
├─ GamePublishedVersion 0..N (immutable, UNIQUE gameId+versionNo)
└─ PortalBinding 0..N

GameDraft/GamePublishedVersion.projectJson
├─ Variables / Items / Assets
└─ Scenes
   ├─ TOP_DOWN → TileLayers / Objects / Events
   └─ DIALOGUE → presentation + Nodes / Choices

Asset Catalog / Asset Storage
└─ GameProject.assets[].source가 안정적인 reference로만 가리킴
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
| visibility | PRIVATE/PUBLIC | 신규 실행 가능 여부. 세션 중 비공개 정책은 #33 |
| publishedVersion | nullable positive integer | 현재 공개 versionNo. null은 공개본 없음 |
| createdAt/updatedAt | instant | 서버 기록 |

복합 FK `(id, publishedVersion) → game_published_versions(game_id, version_no)`를 사용한다.
공개본 삭제 시 `ON DELETE SET NULL (published_version)`처럼 **nullable 포인터 컬럼만** 지정한다.
FK 전체를 SET NULL하여 `games.id`까지 비우려는 삭제 오류를 금지한다.

### GameDraft

| Field | Type | Rule |
|---|---|---|
| gameId | long | PK이자 Game FK. Game마다 Draft 최대 1개를 DB로 강제 |
| schemaVersion | semver string | Project envelope와 일치 |
| revision | non-negative integer | Draft 낙관적 잠금용 |
| projectJson | JSONB | Schema + semantic validation 대상 |
| updatedByUserId | long | Guest 불가, 서버 인증 사용자 |
| updatedAt | instant | 서버 기록 |

### GamePublishedVersion

| Field | Type | Rule |
|---|---|---|
| id | long | 서버 내부 식별자 |
| gameId | long | Game FK |
| versionNo | positive integer | `UNIQUE(gameId, versionNo)` |
| schemaVersion | semver string | Project envelope와 일치 |
| projectJson | JSONB | 생성 후 update 금지 |
| publishedByUserId | long | 서버 인증 사용자 |
| publishedAt | instant | 서버 기록 |

### GamePortalBinding

| Field | Type | Rule |
|---|---|---|
| id/configId | signed Int32 wire contract | DB PK와 동일하게 둘지 별도 public ID로 둘지는 #34 |
| boothId | long | Booth FK |
| objectId | stable string | Booth Layout canonical objectId |
| gameId | long | Game FK |
| enabled | boolean | 실행 가능성의 한 조건 |

Unique: `(boothId, objectId)`. Booth Layout은 `configId`만 가지고 GameProject를 포함하지 않는다.
Portal resolver는 `Game.publishedVersion`이 가리키는 현재 공개본을 반환하며 실행 가능 여부 응답은 캐시하지 않는다.

## Contract Value Objects

### GameProject

- `schemaVersion`, `gameId`, `revision`, `title`, `startSceneId`
- `variables[]`, `items[]`, `assets[]`, `scenes[]`
- 상세 구조는 `contracts/game-project-v1.schema.json`이 유일한 구조 계약이다.
- Asset binary, editor selection/history, 완성 화면 캡처는 포함하지 않는다.

### GameAssetReference

- `id`, `kind(IMAGE/TILESET/AUDIO)`, `source`, 선택적 `integrity`.
- MVP `source`는 버전이 고정된 `builtin://` 또는 서버가 관리하는 `asset://` reference다.
- Runtime 전달 주소와 원본 binary는 계약 밖의 Asset resolver가 관리한다.

### Scene

| Type | Own data | Runtime |
|---|---|---|
| TOP_DOWN | width/height, tileLayers, objects, events | grid movement + interaction |
| DIALOGUE/OVERLAY | startNodeId, nodes, choices | 호출 Scene 보존 + world input 중지 + graph traversal |
| DIALOGUE/FULL_SCREEN | startNodeId, nodes, choices | 독립 graph Scene traversal |
| PLATFORMER | 후속 계약 | side-view physics adapter |

### GameObject

- `id`, `preset`, 0-based 정수 셀 `position`, `visible`, `components[]`
- preset은 editor 기본값이며 runtime capability를 대신하지 않는다.
- v1 Component: SPRITE, COLLIDER, INTERACTABLE, PICKUP.
- 문 잠금·필요 Item 같은 편의 입력은 별도 필드로 이중 저장하지 않고 Event recipe로 변환한다.

### GameEvent

- Trigger: ON_SCENE_START, ON_INTERACT(target), ON_ENTER(target)
- Conditions: VARIABLE_EQUALS, HAS_ITEM
- Actions: SHOW/CLOSE_DIALOGUE, SET_VARIABLE, GIVE/REMOVE_ITEM, SHOW/HIDE_OBJECT, GO_TO_SCENE, COMPLETE_GAME

## RuntimeSessionState

```text
projectVersionId
currentSceneId
activeDialogueSceneId?
currentDialogueNodeId?
inputMode: WORLD | DIALOGUE
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
DRAFT(valid) → PUBLISHED(version M) insert + Game.publishedVersion=M + DRAFT 유지 (single transaction)
PUBLISHED(version M) → immutable
```

요청 `expectedRevision`이 현재 revision과 다르면 `409 GAME_REVISION_CONFLICT`다. Publish 실패는
Published 행·포인터를 남기지 않고 Draft도 변경하지 않는다. Draft 저장은 구조·스키마·상한을,
Publish는 구조·참조·소유권·진행 가능성을 다시 검증하며 자동 보정하지 않는다.

### Runtime

```text
LOADING → PLAYING
LOADING → FAILED
PLAYING → PLAYING (Scene/Node transition)
PLAYING/WORLD → PLAYING/DIALOGUE (OVERLAY open)
PLAYING/DIALOGUE → PLAYING/WORLD (OVERLAY close)
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
6. SHOW_DIALOGUE는 OVERLAY DIALOGUE만 참조하며, OVERLAY는 시작 Scene이나 GO_TO_SCENE 대상이 아니다.
7. CLOSE_DIALOGUE는 OVERLAY DIALOGUE Choice에서만 허용한다.
8. Choice의 nextNodeId는 같은 DIALOGUE Scene Node를 가리키며 flow terminal Action과 함께 둘 수 없다.
9. TOP_DOWN Object 위치는 Scene 범위 안의 정수 셀 좌표다.
10. Asset source는 저장 가능한 안정 reference이며 binary·임시 URL을 포함하지 않는다.
11. Variable 선언 type과 initialValue 실제 type이 일치한다.
12. 한 tick Action 수와 transition depth가 budget을 넘으면 FAILED로 격리한다.
13. 지원하지 않는 schema major는 migration 추측 없이 거부한다.
