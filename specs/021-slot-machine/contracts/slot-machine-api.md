# 광장 슬롯머신 API 계약 (spec 021)

**확정**: 2026-09-16 (GitLab #205 — BE 제시, 게임 파트 이견 없음 회신)
**구현**: `backend/.../minigame/SlotMachineController.java` · `SlotMachineService.java`
**Unity 측**: `Integration/Spring/HttpSlotMachineClient.cs` · `Integration/Contracts/ISlotMachineClient.cs`
**FE 작업 없음** — 화면은 Unity 가 월드에서 직접 그린다

---

## 1. 스핀

```
POST /api/v1/minigames/slot-machines/{machineId}/spins
Authorization: Bearer <AT>      회원 전용 — 게스트는 403 MEMBER_ONLY
Content-Type: application/json

{ "bet": 10 }
```

### 성공

```
200 { "sessionId": "3f1a6d2c-8b5e-4c11-9a77-2d0e5f8c4b31",
      "bet": 10, "payout": 20, "tier": 1, "balanceAfter": 110 }
```

| 필드 | 뜻 |
|---|---|
| `sessionId` | 이 판의 식별자. 원장 두 줄의 `referenceId` 와 같은 값이라 내역과 이어진다 |
| `bet` | 실제로 차감된 금액. **서버 설정값이고 요청값이 아니다** |
| `payout` | 지급 코인. `0` = 낙첨 |
| `tier` | `0` 낙첨 · `1` ×2 · `2` ×3 · `3` ×10. Unity 가 릴 프리셋을 이 값으로 고른다 |
| `balanceAfter` | 판이 끝난 뒤 잔액 |

`payout = bet × tier 의 배수` 가 항상 성립한다.

### 실패

| 상태 | `code` | 언제 | 차감 |
|---|---|---|---|
| `400` | `VALIDATION_FAILED` | `bet` 누락 또는 서버 설정값과 불일치. `errors[0].field = "bet"` | 없음 |
| `401` | `UNAUTHORIZED` | 토큰 없음·만료 | 없음 |
| `403` | `MEMBER_ONLY` | 게스트 | 없음 |
| `404` | `SLOT_MACHINE_NOT_FOUND` | 화이트리스트에 없는 `machineId` | 없음 |
| `409` | `INSUFFICIENT_COIN` | 잔액 < 베팅액. 본문에 `balance` (현재 잔액) | 없음 |

```
409 { "code": "INSUFFICIENT_COIN", "message": "코인이 부족합니다. 필요: 10, 잔액: 5",
      "requestId": "...", "errors": [], "warnings": [], "balance": 5 }
```

> `balance` 는 **코인 관련 오류에만 실린다.** 다른 오류에는 필드 자체가 없다(`null` 이 아니라 부재).

### 나오지 않는 것

- **`429 DAILY_LIMIT` 은 이 계약에 없다.** 슬롯에는 일일 한도가 없다 (spec 021 FR-006).
- `404` 는 **경로 미배포가 아니라 기계 미등록**을 뜻한다. Unity 는 본문 `code` 로 갈라야 한다 —
  `SLOT_MACHINE_NOT_FOUND` 면 체험판 폴백이 아니라 오류 표면화다.

---

## 2. 확률표

| tier | 배수 | 가중치 | 기댓값 기여 |
|---|---|---|---|
| 0 | 낙첨 | 78.1% | 0.000 |
| 1 | ×2 | 20% | 0.400 |
| 2 | ×3 | 1% | 0.030 |
| 3 | ×10 | 0.9% | 0.090 |

**합 1.00 · 기본 RTP 0.52.** 10코인 한 판 기대 손실 4.8코인 — 이 기계는 코인 소각처다.

서버 설정(`app.minigame.slot-machine.tiers`)이 정본이고 **낙첨에는 행이 없다** — 남는 가중치가
그대로 낙첨 확률이다. 표를 잘못 고쳐 합이 1을 넘거나 배수가 내림차순이 되면 **기동이 실패한다.**

> ⚠️ **4번째 tier 를 추가하지 마라.** Unity 가 `tier` 를 0..3 으로 자르고 릴 당첨 프리셋도 3개라,
> 코인은 나가는데 화면은 다른 등급을 보여 준다. 설정 검증이 기동 시 거부하지만, 애초에 늘리려면
> Unity 작업이 함께 필요하다.

### 연속 낙첨 보장

회원의 최신 슬롯 베팅을 역순으로 읽어, 같은 `referenceId`의 `SLOT_PAYOUT`이 없는 행만 센다. 연속
낙첨이 10개면 **다음 스핀**은 확률표 대신 `tier=1`(×2)으로 정산한다. 당첨 원장이 생기면 다음 스핀의
연속 낙첨 수는 0부터 다시 계산된다. 이 조회는 지갑 행 잠금 안에서 수행하며 별도 pity 테이블·Redis 키는
만들지 않는다.

---

## 3. 기계 목록

`plaza-slot-01` · `plaza-slot-02` (씬 `Arcade_01_Slot_Machine_01`·`Arcade_02_Slot_Machine_02`).
서버 설정 `app.minigame.slot-machine.machine-ids` 에 있고, 기계가 늘면 그 목록만 고친다.

---

## 4. 원장

한 판 = 한 트랜잭션. 차감과 지급이 함께 커밋된다.

| | `reasonType` | `entryType` | `referenceType` | `referenceId` |
|---|---|---|---|---|
| 베팅 | `SLOT_BET` | `SPEND` | `SLOT_SPIN` | `sessionId` |
| 당첨 | `SLOT_PAYOUT` | `REWARD` | `SLOT_SPIN` | `sessionId` |

**낙첨이면 지급 줄이 없다** — 0원짜리 원장은 남기지 않는다.

`MINIGAME_REWARD`(타이밍 스톱)와 다른 사유라, 슬롯 지급은 타이밍 스톱의 일일 50코인 한도에
집계되지 않는다.

---

## 5. 멱등성

멱등키는 `SLOT_BET:SLOT_SPIN:<sessionId>` · `SLOT_PAYOUT:SLOT_SPIN:<sessionId>` 다.
`sessionId` 를 **서버가 만들기 때문에** 이 키는 한 판의 두 줄이 두 번 적용되는 것을 막고,
**클라이언트 재전송은 막지 못한다** — 재전송은 새 판이다. spec 021 §알려진 한계(C-08) 참조.
