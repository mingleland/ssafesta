# Contract: WalletService 내부 API

**Spec**: `003-wallet-coin` | **Consumer**: spec 004(임대) · 007/008(AI 이용) · 010(설문 보상) · 012(장식 구매) · 014(미니게임 보상) | **Date**: 2026-08-19

코인을 바꾸는 유일한 경로다. **HTTP로 노출되지 않는다** — 같은 Spring 애플리케이션 안에서 서비스 빈으로 호출한다 (FR-010·FR-011, 헌법 2·16조).

---

## 사용 규칙 (소비 spec이 반드시 지킨다)

1. **호출자의 트랜잭션 안에서 호출한다.** 예: 임대 생성과 차감이 하나의 트랜잭션이어야 한다. `WalletService`의 메서드는 `Propagation.REQUIRED`이므로 호출자의 트랜잭션에 참여한다.
2. **멱등성 키는 호출자가 만든다.** 같은 업무 행위는 재시도해도 **같은 키**여야 한다. 키에 시각·난수를 넣지 않는다. 권장 형식: `{REASON}:{referenceType}:{referenceId}` (최대 100자).
3. **금액은 항상 양수로 전달한다.** 부호는 `WalletService`가 붙인다.
4. **실패를 삼키지 않는다.** `InsufficientCoinException`을 잡아 조용히 기본 동작으로 넘어가지 않는다 (FR-009, T-24).

---

## 메서드

### `Wallet openWallet(Long userId)`

회원 지갑을 만들고 초기 200코인을 지급한다 (FR-002).

- **호출자**: `RegistrationService` (회원 생성 트랜잭션 내부)
- **멱등성**: `INITIAL_GRANT:{userId}`. 이미 지갑이 있으면 기존 지갑을 반환하고 추가 지급하지 않는다
- **게스트**: 호출하지 않는다 (FR-003b)

### `int balanceOf(Long userId)`

현재 잔액을 반환한다 (FR-001).

- 지갑이 없으면 `WalletNotFoundException`

### `LedgerResult credit(CoinCreditCommand command)`

지급한다 — `CHARGE` / `REWARD` / `REFUND`.

```java
record CoinCreditCommand(
    Long userId,
    LedgerEntryType entryType,   // CHARGE | REWARD | REFUND (SPEND 불가)
    int amount,                  // 양수
    String reasonType,
    String referenceType,        // nullable
    String referenceId,          // nullable
    String idempotencyKey
) { }
```

- **결과**: `LedgerResult(long entryId, int balanceAfter, boolean alreadyApplied)`
- **재시도**: 같은 키로 다시 호출하면 잔액을 바꾸지 않고 `alreadyApplied = true`와 **기존 항목의 잔액**을 반환한다 (FR-007)
- **검증 실패**: `amount <= 0`, `entryType == SPEND`, 키 길이 초과 → `IllegalArgumentException`

### `LedgerResult spend(CoinSpendCommand command)`

차감한다 — `SPEND`.

```java
record CoinSpendCommand(
    Long userId,
    int amount,                  // 양수로 전달, 원장에는 음수로 기록
    String reasonType,
    String referenceType,
    String referenceId,
    String idempotencyKey
) { }
```

- **동작 순서**: 지갑 행 `PESSIMISTIC_WRITE` 잠금 → 잔액 확인 → 원장 INSERT → 잔액 UPDATE (모두 같은 트랜잭션)
- **잔액 부족**: `InsufficientCoinException(required, balance)` — **잔액을 바꾸지 않는다** (FR-009)
- **재시도**: 같은 키면 `alreadyApplied = true`, 추가 차감 없음 (FR-007)
- **동시 요청**: 잔액이 한 건에만 충분하면 하나만 성공하고 다른 하나는 `InsufficientCoinException` (FR-008, SC-003)

### `LedgerResult adjustByAdmin(CoinAdminAdjustCommand command)`

관리자 수동 조정 (FR-013).

```java
record CoinAdminAdjustCommand(
    Long userId,
    int signedAmount,            // 증액 +, 감액 − (0 불가)
    String note,
    Long actorUserId,
    String idempotencyKey
) { }
```

- 원장에 `reason_type = ADMIN_ADJUSTMENT`, `reference_type = ADMIN_USER`, `reference_id = actorUserId`로 기록한다
- `entry_type`은 부호에 따라 `CHARGE`(+) / `SPEND`(−)
- 감액이 잔액을 음수로 만들면 `InsufficientCoinException` — 관리자라도 I-2를 깨지 않는다
- **HTTP 노출 없음** (U-01 확정까지 보류)

### `Page<CoinLedgerEntryView> history(Long userId, Pageable pageable)`

거래 내역을 페이지 단위로 반환한다 (FR-012). 정렬은 `created_at DESC, id DESC` 고정.

---

## 예외

| 예외 | 의미 | REST 매핑(소비 spec이 결정) |
|---|---|---|
| `InsufficientCoinException` | 잔액 부족. 필요 금액과 현재 잔액을 담는다 | 권장 `409 Conflict` + 사유 문구 (FR-009) |
| `WalletNotFoundException` | 회원인데 지갑이 없다 (비정상) | `404` + ERROR 로그 |
| `IllegalArgumentException` | 잘못된 command (금액·유형·키) | `500` — 호출자 버그다. 사용자 입력으로 도달할 수 없어야 한다 |

---

## 정합성 점검 — `CoinReconciliationService`

### `CoinReconciliationRun run()`

전체 지갑의 `balance`와 원장 합계를 대조하고 결과를 `coin_reconciliation_runs`에 기록한다 (FR-014).

- **자동 보정하지 않는다.** 불일치를 발견해도 잔액을 고치지 않고 기록만 한다
- 불일치가 1건이라도 있으면 결과의 `mismatchedWalletCount > 0`이며 SC-001 위반이다
- **HTTP 노출 없음** (U-01 확정까지 보류). 현재 호출자는 통합 테스트다
