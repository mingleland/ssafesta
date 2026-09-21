게임 파트(@gudtnslwkd) 현황 회신입니다. **현재 상태를 실측해서 올리고, 새 제안 1건을 붙입니다**(§5 — 부스를 거치지 않고 광장 오락기에서 바로 진입하는 경로).

@ejraks1548 님이 최후순위로 두신 것 반영하고 있습니다 — §6 에 게임 파트가 무엇을 보류하고 무엇이 남았는지 적었습니다.

## 1. Unity 현재 상태 — ⑴⑵ 가 반영돼 있지 않습니다

2026-08-24 코멘트에서 ⑴ 타입 인식 · ⑵ Registry 프리팹 엔트리를 whitelist 배포 전에 먼저 넣겠다고 했고 @strdeok 님이 이견 없다고 답해 주셨는데, **실제로는 들어가지 않은 상태입니다.** 실측 결과입니다.

| 항목 | 상태 |
|---|---|
| `Booth/Catalog/BoothObjectType.cs` | canonical **10종 그대로**, `GAME_PORTAL` 없음 |
| `BoothObjectRegistry.asset` | 엔트리 `type: 1`~`10` 만, `GAME_PORTAL` 엔트리 없음 |
| `festa-unity/**` 전체에서 `GAME_PORTAL`·`GamePortal` 문자열 | **0건** |
| ⑶ 진입 컴포넌트 | 없음 (예정대로 #55 대기) |

## 2. 유지되고 있는 약속 — `configId` 0 sentinel (실측 확인)

`BoothRuntimeObject.cs:28` 이 그대로입니다.

```csharp
public bool HasConfig => ConfigId > 0;
```

BE 의 `CHECK (config_id > 0)` 와 Int32 상한이 **여전히 이 판정의 전제**입니다. 완화·변경 계획이 생기면 구현 전에 알려 주세요 — 지난번 약속 그대로입니다.

## 3. forward-compat 확인 — 미등록 타입이 와도 부스가 깨지지 않습니다 (실측)

`BoothObjectFactory.cs:22`

```csharp
if (type == BoothObjectType.Unknown)
{
    // 알 수 없는 타입은 클라이언트를 깨뜨리지 않고 스킵한다 (forward compat).
    Debug.LogWarning($"[BoothObjectFactory] Unknown type '{dto.type}' (objectId={objectId}) — skipped");
    return null;
}
```

**그 오브젝트만 건너뛰고 부스는 계속 렌더링됩니다.** 즉 BE 가 whitelist 를 선배포해서 `GAME_PORTAL` 이 Layout 에 실려 와도 Unity 가 ⑴⑵ 없이 안전합니다 — BE-first 순서에 Unity 쪽 위험은 없습니다.

## 4. 체인 현황 — `origin/develop` 기준 실측

| 파트 | develop 반입 | 근거 |
|---|---|---|
| BE T051·T053·T054 | **없음** | `GamePortalBinding`·`game_portal` 마이그레이션·`/game-portals` 엔드포인트 모두 0건 |
| FE resolver·overlay | **있음** | `game-studio/runtime/ports/gamePortalRepository.ts`, `features/overlay/OverlayHost.tsx:46` 의 `request.type === 'GAME'`, `game-studio/host/GameOverlay.tsx` |
| Unity ⑴⑵⑶ | 없음 | §1 |

FE 어댑터가 feature flag 없이 붙어 있습니다(#104 에서 물어보신 그 건). **다만 지금은 도달 불가라 무해합니다** — GAME 오버레이는 Unity 의 `BOOTH_GAME_INTERACT` 로만 열리고, Unity 가 `GAME_PORTAL` 오브젝트를 만들 수 없기 때문입니다.

그래서 **순서를 지켜야 하는 지점이 Unity 쪽입니다.** BE resolver 없이 Unity ⑴⑵⑶를 다 넣으면 그 순간 처음으로 사용자에게 404 에러 UI 가 보입니다. ⑴⑵만 넣는 것은 안전하고(⑶ 없으면 상호작용이 없음), ⑶는 BE resolver 배포 뒤여야 합니다.

## 5. 제안 — 광장 오락기에서 부스를 거치지 않고 바로 진입

게임 파트에서 하고 싶은 것이 하나 생겼습니다. **축제 광장에 세워 둔 오락기(`Festival_Arcade`)에 완성된 사용자 생성 게임을 직접 연결해서, 부스에 들어가지 않고 오락기에서 바로 게임으로 진입하는 경로**입니다.

지금 #56 의 설계는 "부스 안의 Layout 오브젝트 → `BOOTH_GAME_INTERACT` → resolver" 인데, 광장 오락기는 부스 오브젝트가 아니라 **씬에 직접 놓인 프롭**입니다. 그래서 계약이 몇 군데 달라집니다.

### 5-1. 이 경로는 ⑴⑵⑶와 무관하게 진행할 수 있습니다

광장 오락기는 부스 Layout 경로를 타지 않으므로 `BoothObjectType`·`BoothObjectRegistry` 를 건드릴 필요가 없습니다. 씬 오브젝트에 상호작용 컴포넌트를 붙이면 되고(`BoothPortal` 패턴 그대로), **BE whitelist 배포와도 무관**합니다. #56 본체보다 오히려 단순합니다.

### 5-2. 정해야 하는 것 — 결정 요청 3건

**ⓐ 식별자와 resolver 경로.** 광장 오락기에는 `boothId`·`objectId` 가 없습니다. 그런데 현재 FE resolver 가 `configId + boothId` 를 요청 문맥과 대조합니다(2026-08-24 @kanghyunsoon 님 정리). `boothId` 가 없으면 이 대조가 성립하지 않습니다.

- **(안 1)** 광장용 별도 endpoint 를 신설한다 — 예: `GET /api/v1/arcade-machines/{machineId}`. 권한 모델이 다르므로(부스 lease 가 없다) 게임 파트는 이쪽이 깔끔하다고 봅니다.
- **(안 2)** 기존 평면 경로를 쓰고 `boothId` 대조를 생략하는 분기를 둔다. FE 변경이 필요합니다.
- **(안 3)** 광장을 예약 `boothId` 로 둔다. **`0` 은 sentinel 이라 쓸 수 없습니다**(§2) — 다른 예약값을 정해야 하고, "광장은 부스다" 라는 거짓 모델이 남습니다. 권하지 않습니다.

**ⓑ 진입 허용 판정.** #56 경계는 *"서버가 Booth lease, Binding, 공개 상태를 매번 판정한다"* 인데 광장 오락기에는 lease 가 없습니다. **공개(Published) 상태만으로 진입을 허용해도 되는지** 확정이 필요합니다. 게임 파트는 판정을 하지 않고 서버 답만 따릅니다 — 이전과 동일합니다.

**ⓒ 어떤 게임이 걸리는가.** 부스의 `GAME_PORTAL` 은 부스 주인이 Layout 에서 `configId` 를 지정합니다. 광장 오락기는 **주인이 없어서** 누가 무엇을 걸지 정해지지 않습니다. 큐레이션(운영자 지정) 인지 자동(인기순·최신순) 인지 기획 판단이 필요합니다. 오락기 대수보다 게임이 많아질 때 처리도 함께 정해야 합니다.

@ejraks1548 @dream_hyeon — ⓑⓒ 는 기획 판단이 필요한 항목입니다. ⓐ 는 @strdeok @colosair 님 의견 부탁드립니다.

이건 게임 파트 제안일 뿐이고 확정 사항이 아닙니다. 최후순위 결정과 어떻게 맞물릴지도 같이 봐 주세요 — **#56 본체보다 이 경로가 먼저일 수도 있다**고 보는 이유는 §5-1 때문입니다(BE whitelist·Binding 없이도 성립).

## 6. 게임 파트가 지금 보류하는 것 / 재개에 필요한 신호

최후순위 결정에 따라 ⑶(진입 컴포넌트)은 보류합니다.

⑴⑵는 작업량이 작고(enum 1줄 + 문자열 매핑 1줄 + Registry 엔트리 1개) §3 대로 위험이 없어서 신호만 오면 바로 넣겠습니다. 다만 **⑵는 `GAME_PORTAL` 로 쓸 프리팹이 정해져야 합니다** — 현재 부스 오브젝트 프리팹 중 포털로 쓸 만한 후보가 없습니다. §5 를 채택하면 광장 오락기와 같은 자산을 쓰는 것이 자연스럽겠습니다.

재개에 필요한 신호 2개입니다.

1. `GAME_PORTAL` 프리팹 확정 (기획/디자인)
2. BE whitelist 배포 알림 (@strdeok — 예정대로 이 이슈에 남겨 주세요)
