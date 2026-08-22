# FE Data Model: Authoring Document and Runtime State

구조 계약의 정본은 [GameProject v1 JSON Schema](../contracts/game-project-v1.schema.json)다. TypeScript type은
이 계약을 좁혀 읽되 독자 필드를 추가하지 않는다.

## GameProject authoring model

```text
GameProject
├── variables[] / items[] / assets[]
└── scenes[]
    ├── TOP_DOWN → tileLayers / objects / events
    ├── PLATFORMER → gravity / tileLayers / objects / events
    └── DIALOGUE → presentation / background / nodes / portrait / choices
```

- Scene/Object/Node/Event ID는 프로젝트 안에서 안정적이어야 한다.
- preset은 저장 가능한 Component/Event recipe로 변환하며 별도 Runtime 능력을 만들지 않는다.
- editor selection, undo history, viewport, 완성 화면 캡처는 GameProject에 저장하지 않는다.
- Asset source는 `builtin://` 또는 서버가 허용한 안정 reference만 사용한다.

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

Runtime state는 브라우저 메모리의 비권위 상태다. 완료·점수·inventory는 Coin/Reward의 근거가 아니다.
비공개 전환 전에 이미 GameProject를 로드한 무보상 세션은 서버 push 없이 완료까지 진행할 수 있다.
Reference Runtime은 여기에 Player 위치·방향·수직 속도·체력·점수·체크포인트, Object 위치/체력,
투사체·동적 적을 메모리 상태로만 추가한다. 이 값은 Draft/Published GameProject에 역으로 저장하지 않는다.

## State transitions

```text
LOADING → PLAYING | FAILED
PLAYING/WORLD → PLAYING/DIALOGUE
PLAYING/DIALOGUE → PLAYING/WORLD
PLAYING → COMPLETED | FAILED
PLAYING|COMPLETED|FAILED → CLOSED
```

OVERLAY 대화는 호출 Scene과 object visibility를 보존한다. FULL_SCREEN 대화는 `currentSceneId` 자체다.
한 tick action 수나 transition depth가 budget을 넘으면 해당 Runtime만 `FAILED`로 격리한다.

## FE validation invariants

1. 모든 참조 ID가 존재하고 namespace 안에서 중복되지 않는다.
2. start Scene과 Dialogue start Node가 존재한다.
3. TOP_DOWN/PLATFORMER Scene은 PLAYER_SPAWN을 정확히 하나 가진다.
4. Tile data 길이는 width×height이고 Object 위치는 범위 안의 정수 cell이다. PLATFORMER는 gravity `1..30`을 가진다.
5. 동일 Object에 같은 Component type을 중복하지 않는다.
6. SHOW_DIALOGUE는 OVERLAY만 참조하고 CLOSE_DIALOGUE는 Overlay choice에서만 허용한다.
7. terminal action과 `nextNodeId`를 동시에 두지 않는다.
8. 지원하지 않는 schema major는 추측 migration 없이 거부한다.
9. JSON은 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset 300을 넘지 않는다.
