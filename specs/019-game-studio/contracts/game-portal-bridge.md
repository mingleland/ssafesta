# Booth Game Portal Bridge 계약

> 상태: Draft / FE Issue #20 검토 대기

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
| `configId` | ✅ | Spring 소유 Game Portal Binding 식별자 |

## 처리 흐름

```text
Unity Booth Object Interact
→ onBoothInteract(payload)
→ React가 configId 실행 가능 여부 조회
→ Game Overlay 열기
→ Web Runtime이 Published GameProject 조회
→ 종료
→ Overlay 닫기 + Unity 입력 복구
```

## 책임 경계

- Unity는 `configId → gameId`를 해석하지 않는다.
- Unity는 GameProject를 조회하지 않는다.
- React는 이벤트의 `boothId/objectId/configId`를 권한·공개 상태의 최종 근거로 신뢰하지 않는다.
- Spring이 삭제·비공개·임대 만료·연결 해제를 판정한다.
- Game Overlay 오류는 해당 Overlay로 격리하고 Unity 월드를 종료하지 않는다.
- 게임 결과와 Coin/Reward는 이 Bridge payload로 전달하지 않는다.

## Host → Unity Lifecycle

구체 메서드명은 #20 답변 후 확정한다. 의미 이벤트는 다음 세 가지다.

| Event | Meaning |
|---|---|
| `GAME_OVERLAY_OPENED` | 로컬 이동·상호작용 입력 차단 |
| `GAME_OVERLAY_CLOSED` | 로컬 입력·포커스 복구 |
| `GAME_OVERLAY_FAILED` | 입력 복구 후 안내, 월드 연결 유지 |
