# Contract: NGO 외형 동기화

**Spec**: 013 | **당사자**: Unity 클라이언트 ↔ Unity Dedicated Server
**헌법 근거**: 5조(아바타는 데이터로 생성), 16조(클라이언트 불신), 23조(외형 표현)

---

## 전송 데이터

`NetworkAvatarConfig` — `INetworkSerializable`을 구현하는 **고정 크기 struct**.

```text
Gender          : byte
ItemIds         : 카테고리 수만큼의 정수 필드 (고정)
ColorIds        : 카테고리 수만큼의 바이트 필드 (고정)
```

카테고리 수는 R-01 확정 후 고정한다. **가변 길이 컬렉션을 쓰지 않는다.**

**금지**: 메시 / 텍스처 / 머티리얼 / 프리팹 경로 / JSON 문자열 전송

---

## 흐름

```text
Owner 클라이언트
   │ 사용자가 항목 변경
   ▼
RequestChangeServerRpc(NetworkAvatarConfig)
   │
   ▼
서버: Catalog.Validate()
   ├─ 실패 → 거부 + 사유 로그. 값을 바꾸지 않는다
   └─ 성공 ↓
NetworkVariable<NetworkAvatarConfig> 기록  (서버 쓰기 / 전원 읽기)
   │
   ▼
전 클라이언트 OnValueChanged
   │
   ▼
AvatarAssembler.Apply()  — 로컬 조립
```

## 규칙

| # | 규칙 |
|---|---|
| 1 | 전송은 **Spawn 시점과 변경 시점에만**. 이동 동기화 주기와 분리한다 |
| 2 | 서버는 기록 전 **카탈로그 범위 검증**을 통과시킨다 (헌법 16조) |
| 3 | 거부 시 **사유를 남기고 사용자에게 표시**한다. 조용히 기본값으로 대체하지 않는다 (T-24) |
| 4 | 알 수 없는 itemId를 받은 클라이언트는 **해당 카테고리 기본값으로 폴백**하고 나머지는 정상 적용한다 |
| 5 | 외형 오브젝트에 `NetworkObject`를 붙이지 않는다. 캐릭터당 `NetworkObject`는 하나다 |

## 변경 절차

이 계약을 바꾸려면 **Unity + BE 합의**가 필요하다 (헌법 24조).
특히 카테고리 추가는 struct 크기를 바꾸므로 **클라이언트와 서버 빌드를 반드시 쌍으로** 재생성해야 한다 (T-24/T-25).

## 회귀 주의

네트워크 변수 타입이 바뀌면 **Web 빌드와 Linux Server 빌드를 둘 다** 다시 만들어야 한다.
한쪽만 갱신하면 서로 읽지 못하고 **무반응처럼 보인다** — 과거 이 문제로 반나절을 썼다 (T-24, T-25).
컨테이너 교체 시 `docker ps`로 실제 이름을 확인한다 (`festa-world-01`).
