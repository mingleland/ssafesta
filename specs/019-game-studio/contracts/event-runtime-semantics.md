# Game Event Runtime Semantics v1

> 상태: Draft v1.0 — Studio Preview, Published Web Runtime, Spring semantic validator의 공통 기준

## 원칙

1. Runtime state 변경은 Action reducer만 수행한다.
2. Event와 Action은 JSON 배열 순서대로 결정적으로 처리한다.
3. Conditions는 모두 참일 때만 실행하는 AND다.
4. 사용자 코드·동적 표현식·Reflection 실행은 없다.
5. 실행 budget 초과는 해당 RuntimeSession을 `FAILED`로 격리한다.

## Dispatch 순서

```text
Trigger 발생
→ 현재 Scene의 events[]에서 type/target 일치 Event를 배열 순서로 snapshot
→ Event별 conditions[]를 배열 순서로 평가
→ 하나라도 false면 해당 Event skip
→ actions[]를 배열 순서로 적용
→ terminal Action이면 dispatch 종료
→ 남은 일치 Event 계속
```

Dispatch 도중 Object visibility나 inventory가 변해도 처음 만든 일치 Event snapshot은 바꾸지 않는다.
다만 아직 실행하지 않은 Event의 Condition은 실행 직전에 최신 Runtime state로 평가한다.

## Trigger

| Trigger | 발생 시점 | target |
|---|---|---|
| `ON_SCENE_START` | Scene state 초기화가 끝난 직후 1회 | 없음 |
| `ON_ENTER` | Player hit area가 target 밖→안으로 바뀔 때 | 같은 TOP_DOWN Scene Object |
| `ON_INTERACT` | 상호작용 입력 시 가장 우선순위 높은 target 하나 | 같은 TOP_DOWN Scene Object |

숨겨진 Object는 Trigger target이 되지 않는다. `ON_ENTER`는 영역 안에 머무는 동안 반복 실행하지 않고,
나갔다 다시 들어올 때 새로 발생한다.

## Condition

- `VARIABLE_EQUALS`: 선언된 scalar type을 변환하지 않고 strict equality로 비교한다.
- `HAS_ITEM`: Runtime inventory Set에 itemId가 있으면 true다.
- 빈 `conditions[]`는 true다.
- Condition 평가에는 side effect가 없다.

## Action

| Action | State effect |
|---|---|
| `SET_VARIABLE` | 선언 type이 같은 값으로 교체 |
| `GIVE_ITEM` | inventory Set에 추가, 이미 있으면 idempotent |
| `REMOVE_ITEM` | inventory Set에서 제거, 없어도 성공 |
| `SHOW_OBJECT` / `HIDE_OBJECT` | session-local visibility override 변경 |
| `SHOW_DIALOGUE` | `OVERLAY` DIALOGUE를 열고 현재 이동 Scene 입력 중지 |
| `CLOSE_DIALOGUE` | 열린 `OVERLAY` DIALOGUE를 닫고 호출 Scene 입력 복구 |
| `GO_TO_SCENE` | Overlay를 정리하고 대상 TOP_DOWN 또는 `FULL_SCREEN` DIALOGUE로 전환 |
| `COMPLETE_GAME` | session status를 COMPLETED로 변경 |

`SHOW_DIALOGUE`, `CLOSE_DIALOGUE`, `GO_TO_SCENE`, `COMPLETE_GAME`은 flow terminal Action이며 반드시
actions 배열의 마지막이다.
terminal Action 뒤 Action이 있으면 Publish validation 오류 `TERMINAL_ACTION_NOT_LAST`다.

## Dialogue Overlay

1. `SHOW_DIALOGUE` target은 `presentation=OVERLAY`인 DIALOGUE Scene이어야 한다.
2. `currentSceneId`, Player 위치, variables, inventory, object visibility는 변경하지 않는다.
3. `activeDialogueSceneId`와 `currentDialogueNodeId`를 설정하고 World input을 중지한다.
4. Choice의 `nextNodeId`는 같은 Overlay 안에서 Node만 이동한다.
5. `CLOSE_DIALOGUE`는 active Overlay를 정리하고 World input을 복구한다.
6. `GO_TO_SCENE` 또는 `COMPLETE_GAME`은 Overlay를 먼저 정리한 뒤 해당 terminal 의미를 수행한다.

Overlay가 없는 상태의 `CLOSE_DIALOGUE`, `FULL_SCREEN` DIALOGUE의 `CLOSE_DIALOGUE`, Overlay DIALOGUE를
시작 Scene 또는 `GO_TO_SCENE` 대상으로 쓰는 조합은 validation 오류다.

## Scene Transition

1. 기존 Scene의 transient input/physics listener를 정리한다.
2. 열려 있는 Dialogue Overlay가 있으면 함께 정리한다.
3. `currentSceneId`를 변경한다.
4. 공통 variables, inventory, object visibility override는 유지한다.
5. 대상 Scene state를 초기화한다.
6. 대상 Scene이 TOP_DOWN이면 `ON_SCENE_START`를 dispatch한다.
7. 대상 Scene이 `FULL_SCREEN` DIALOGUE면 `startNodeId`에서 대기한다.

DIALOGUE Choice의 `nextNodeId`가 있으면 같은 Scene에서 Node만 이동한다. Choice Actions에 terminal Action이
있으면 terminal Action이 우선하며 `nextNodeId`를 함께 둘 수 없다. 이 조합은 semantic validation 오류다.

## Execution Budget

v1 reference budget:

| Budget | Limit | Error |
|---|---:|---|
| 단일 Event actions | Schema상 20 | `EVENT_ACTION_LIMIT_EXCEEDED` |
| 한 dispatch cycle 적용 Action | 64 | `ACTION_BUDGET_EXCEEDED` |
| 연속 Scene transition depth | 8 | `TRANSITION_DEPTH_EXCEEDED` |

Scene transition으로 새 `ON_SCENE_START`가 발생해도 같은 dispatch cycle budget을 공유한다. 새 사용자 입력,
animation frame, timer를 이용해 budget을 우회하지 않는다. 숫자는 v1 consumer fixture에 포함하기 전까지
조정할 수 있지만 FE/BE가 서로 다른 값을 배포해서는 안 된다.

## Failure Isolation

- validation error: Publish를 거부하며 기존 Published Version은 유지한다.
- runtime budget/error: 해당 RuntimeSession만 FAILED로 전환하고 animation/audio/input을 정리한다.
- Preview error: Preview iframe만 실패한다.
- standalone/overlay error: Unity world, FESTA Host, 다른 게임 session을 종료하지 않는다.
- 오류가 나도 Coin/Reward/Ranking 상태를 변경하지 않는다.

## Required Error Codes

```text
GAME_SCHEMA_UNSUPPORTED
START_SCENE_NOT_FOUND
DUPLICATE_OBJECT_ID
DIALOGUE_TARGET_INVALID
DIALOGUE_PRESENTATION_INVALID
DIALOGUE_CLOSE_CONTEXT_INVALID
TERMINAL_ACTION_NOT_LAST
DIALOGUE_NEXT_WITH_TERMINAL_ACTION
ACTION_BUDGET_EXCEEDED
TRANSITION_DEPTH_EXCEEDED
```
