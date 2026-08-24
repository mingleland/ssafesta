# Booth Game Portal Bridge 계약

> 상태: Draft v0.3 — FE Host 계약 #20과 signed Int32/DB mapping #34 반영

## 목적

Unity 부스의 NPC·게임기·포털 상호작용을 기존 FESTA React Host에 전달한다. 이 계약은 게임 실행 엔진 계약이 아니라 **웹 게임 화면을 열기 위한 진입 요청**이다.

## Unity → React

기존 전역 callback을 재사용한다.

```text
window.FestaUnity.onBoothInteract(json)
```

Payload:

```json
{
  "type": "BOOTH_GAME_INTERACT",
  "boothId": 7,
  "objectId": "game-npc-01",
  "configId": 42
}
```

| Field | Required | Meaning |
|---|:---:|---|
| `type` | ✅ | `BOOTH_GAME_INTERACT` 고정 |
| `boothId` | ✅ | 현재 Published Booth 식별자 |
| `objectId` | ✅ | Layout 안의 canonical objectId |
| `configId` | ✅ | Spring 소유 공개 Binding ID. signed Int32 `1..2147483647`; 0은 미연결이라 금지 |

## 처리 흐름

```text
Unity Booth Object Interact
→ onBoothInteract(payload)
→ React가 configId로 no-store Portal resolution REST 조회
→ Game Overlay 열기
→ `/app/games/:gameId/play` lazy route
→ Web Runtime이 Published GameProject 조회
→ 종료
→ Overlay 닫기 + Unity 입력 복구
```

## 책임 경계

- Unity는 `configId → gameId`를 해석하지 않는다.
- Unity는 GameProject를 조회하지 않는다.
- React는 이벤트의 `boothId/objectId/configId`를 권한·공개 상태의 최종 근거로 신뢰하지 않는다.
- Spring이 삭제·비공개·임대 만료·연결 해제를 판정한다.
- 새 overlay open마다 상태를 재검증한다. Game Studio 전용 socket이나 서버 push 종료 경로는 없다.
- 이미 GameProject를 로드한 무보상 로컬 세션은 이후 비공개 전환에도 완료까지 진행할 수 있다.
- Game Overlay 오류는 해당 Overlay로 격리하고 Unity 월드를 종료하지 않는다.
- 게임 결과와 Coin/Reward는 이 Bridge payload로 전달하지 않는다.

## Host → Unity Lifecycle

React Host는 Unity loader가 보유한 instance에 단일 메서드로 상태를 전달한다.

```js
unityInstance.SendMessage(receiverObjectName, "OnOverlayStateChanged", JSON.stringify({
  state: "OPENED" | "CLOSED" | "FAILED",
  overlay: "GAME"
}))
```

`receiverObjectName`은 Scene 구현 시 한 상수로 확정하고 코드 여러 곳에 문자열을 흩뿌리지 않는다.
Unity 수신 컴포넌트는 알 수 없는 state/overlay를 무시하고 월드 연결을 종료하지 않는다.

의미 이벤트는 다음 세 가지다.

| Event | Meaning |
|---|---|
| `GAME_OVERLAY_OPENED` | 로컬 이동·상호작용 입력 차단 |
| `GAME_OVERLAY_CLOSED` | 로컬 입력·포커스 복구 |
| `GAME_OVERLAY_FAILED` | 입력 복구 후 안내, 월드 연결 유지 |

React Error Boundary는 Overlay 하위 오류를 `FAILED`로 변환한 뒤 Unity 입력을 복구한다. Access/Refresh,
Connection Token, GameProject, 점수·완료 결과는 이 lifecycle payload에 포함하지 않는다.

## Layout/DB mapping

| Layer | Type | Rule |
|---|---|---|
| Layout JSON / Bridge | signed Int32 | `1..2147483647`, 0 금지 |
| Unity | `int` | 변경 없음, 0은 미연결 |
| FE | integer `number` | 범위 검사 후 REST 조회 |
| BE wire | `Integer` | 범위 밖 거부 |
| DB public | `INTEGER UNIQUE NOT NULL CHECK (>0)` | sequence START 1 |
| DB internal | `BIGINT` | FK/조인용, 외부 비노출 |

`GAME_PORTAL`은 Layout canonical type whitelist에 `requiresConfig=true`로 추가한다. 서버 whitelist가 먼저
배포된 뒤 FE가 이 type을 전송한다. Unity는 payload를 해석하지 않고 Host에 전달하므로 변경이 없다.
