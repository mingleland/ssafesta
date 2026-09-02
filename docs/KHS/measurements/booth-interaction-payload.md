# 부스 상호작용 payload 계약 검증 — AI_AGENT / LAPTOP

- 일시: 2026-08-31
- 이슈: S15P21A604-305 (검증), S15P21A604-339 (검증 중 발견한 사거리 결함)
- 조건: 에디터 플레이 모드, 부스 7, 앵커 lossy 10, Mock 레이아웃

## 왜 WebGL 브라우저가 아니라 에디터인가

`-305` 의 원문은 "WebGL 빌드에서 NPC 클릭 → 브라우저 콘솔" 이다. 두 가지가 달라졌다.

1. **클릭이 아니라 F 키다** (S15P21A604-323). 원문이 낡았다.
2. 브라우저 창이 백그라운드로 스로틀되면 프레임이 1,050 ms ~ 28,700 ms 까지 늘어
   키 입력이 프레임에 걸리지 않는다. 그 상태에서는 "안 되는 것" 과 "느린 것" 을
   구분할 수 없다.

그래서 **검증 대상을 나눴다.**

| 검증 대상 | 어디서 | 근거 |
|---|---|---|
| JSON 문자열 자체 | Editor 단위 테스트 | `BoothInteractBridgeTests` 가 정확한 문자열을 단언한다 |
| 레이아웃 → 오브젝트 필드 배선 | 에디터 플레이 모드 | 플랫폼 무관한 C# 경로다 |
| jslib → `window.FestaUnity` 전달 | WebGL 빌드 | LAPTOP 실측으로 확인됨 (아래) |

WebGL 빌드에서 실제로 잡힌 로그 — 브리지가 브라우저 경계까지 도달함을 보인다.

```
[FestaUnityBridge] window.FestaUnity.onBoothInteract is not ready
{"type":"BOOTH_LAPTOP_INTERACT","boothId":7,"objectId":"laptop-1"}
```

## 배선 확인

```
부스7 AiAgent — objectId='ai-1' configId=78 AiNpcInteractable=있음
부스7 Laptop  — objectId='laptop-1' configId=16 AiNpcInteractable=없음
```

## 전 경로 실행 (레이캐스트 → 사거리 판정 → Interact → 브리지)

디스패처(`BoothInteractionInput.Update`)와 같은 방식으로 눈높이 1.6 m 에서
대상 중심을 향해 레이를 쏘고, 같은 사거리 식으로 판정한 뒤 `Interact()` 를 불렀다.

```
AiAgent 'ai-1'    — 레이 적중 True(Counter02),  거리  5.8/30 unit → 사거리 안
Laptop  'laptop-1'— 레이 적중 True(TableSquare), 거리 10.9/30 unit → 사거리 안

[BoothInteractBridge] onBoothInteract → {"type":"AI_AGENT_INTERACT","boothId":7,"objectId":"ai-1","configId":78}
[BoothInteractBridge] onBoothInteract → {"type":"BOOTH_LAPTOP_INTERACT","boothId":7,"objectId":"laptop-1"}
```

## 판정

- `AI_AGENT_INTERACT` 3필드 — `boothId` · `objectId` · `configId` **전부 존재**. 계약 일치.
- `BOOTH_LAPTOP_INTERACT` — `configId` 를 **넣지 않는다**. 회귀 없음.
  (선택 필드는 키 자체를 넣지 않는 규칙이 지켜진다.)

## 부수 발견 — S15P21A604-339

검증 도중 **사거리가 10배 작아 F 가 원리적으로 발동하지 않는 것**을 발견했다.
`Configure(interactive ? 3f : 2.2f, ...)` 의 3f 는 미터로 읽히지만 월드는 1 m = 10 unit 이다.

| 대상 | 판정 기준점 | 밖에서 최단 접근 | 사거리(전) |
|---|---|---|---|
| AI_AGENT `ai-1` | (2070, 0, 710) | 4.3 unit | 3 unit → 불가 |
| LAPTOP `laptop-1` | (2130, 8.5, 740) | 6.8 unit | 3 unit → 불가 |

`30f / 22f` (3 m / 2.2 m)로 고쳤다. 상세는 T-232.
