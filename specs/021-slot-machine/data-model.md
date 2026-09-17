# Data Model: 슬롯 연속 낙첨 보장

## Existing `coin_ledger_entries`

| Field | Use in this feature |
|---|---|
| `wallet_id` | 회원 지갑과 슬롯 원장을 연결한다. |
| `reason_type` | 베팅은 `SLOT_BET`, 지급은 `SLOT_PAYOUT`이다. |
| `reference_type` | 슬롯 한 판은 `SLOT_SPIN`이다. |
| `reference_id` | 같은 스핀의 베팅·지급을 결합하는 서버 생성 sessionId다. |
| `created_at`, `id` | 최신 스핀부터 안정적으로 정렬한다. |

## Derived state

`consecutiveLosses`는 저장 컬럼이 아니다. 최신 슬롯 베팅부터 역순으로 조회하여 해당 referenceId에
지급 원장이 없는 행을 세고, 첫 지급 행에서 멈춘다. 10이면 다음 스핀의 tier는 1이다.

## Concurrency invariant

지갑 행 잠금을 얻은 뒤 조회와 정산을 수행한다. 보장 당첨이 원장에 기록되기 전에는 다음 같은 회원
스핀이 잠금을 얻지 못하므로, 새 요청은 그 지급 행을 보고 연속 낙첨을 0으로 계산한다.
