# Phase 1 Data Model: Coin 지갑 / 원장

**Spec**: `003-wallet-coin` | **Date**: 2026-08-19

`wallets`와 `coin_ledger_entries`는 **V1 초기 스키마에 이미 존재한다.** 003은 이 두 테이블에 엔티티·제약 해석을 붙이고, 정합성 점검 결과 테이블 하나만 추가한다.

---

## 1. Wallet (`wallets`) — 기존 테이블

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `user_id` | BIGINT | NOT NULL, **UNIQUE**, FK → `users(id)` | 소유자. 회원 1명당 지갑 1개 |
| `balance` | INTEGER | NOT NULL, DEFAULT 0, **CHECK(balance >= 0)** | 현재 잔액 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now | 갱신 시각 (UTC 저장) |

**규칙**

- 회원(MEMBER)에게만 존재한다. 게스트는 행을 만들지 않는다 (FR-003b).
- 생성 시점은 회원 생성 트랜잭션 내부 (R-11).
- 잔액 변경은 항상 원장 1행과 **같은 트랜잭션**에서만 (FR-004, FR-010).
- 차감 시 `PESSIMISTIC_WRITE`로 이 행을 잠근다 (R-07).

## 2. CoinLedgerEntry (`coin_ledger_entries`) — 기존 테이블, append-only

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `wallet_id` | BIGINT | NOT NULL, FK → `wallets(id)` | 대상 지갑 |
| `entry_type` | VARCHAR(20) | NOT NULL | `CHARGE` / `SPEND` / `REWARD` / `REFUND` |
| `amount` | INTEGER | NOT NULL, **CHECK(amount <> 0)** | **부호 있는 금액.** 지급 +, 차감 − (R-02) |
| `balance_after` | INTEGER | NOT NULL, CHECK(>= 0) | 이 항목 반영 직후 잔액 |
| `reason_type` | VARCHAR(40) | NOT NULL | 사유 (FR-005) |
| `reference_type` | VARCHAR(40) | NULL 허용 | 참조 대상 종류 (`BOOTH_LEASE`, `MINIGAME_SESSION`, `ADMIN_USER` …) |
| `reference_id` | VARCHAR(100) | NULL 허용 | 참조 대상 식별자 |
| `idempotency_key` | VARCHAR(100) | NOT NULL, **UNIQUE** | 중복 방지 키 (FR-007) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now | 발생 시각 (UTC 저장, 표시 Asia/Seoul) |

**규칙**

- **수정·삭제하지 않는다.** 잘못된 항목은 반대 부호의 새 항목으로 정정한다 (관리자 조정 / REFUND).
- `entry_type`과 `amount` 부호는 R-08 표대로 일치해야 한다. 서비스 계층에서 검증한다.
- `balance_after`는 같은 트랜잭션에서 계산한 `wallets.balance`와 같아야 한다.

### 멱등성 키 형식

| 용도 | 형식 | 예 |
|---|---|---|
| 초기 지급 | `INITIAL_GRANT:{userId}` | `INITIAL_GRANT:42` |
| 일일 지급 | `DAILY_GRANT:{userId}:{KST yyyy-MM-dd}` | `DAILY_GRANT:42:2026-08-19` |
| 소비처(004·010·012·014) | `{REASON}:{referenceType}:{referenceId}` 또는 호출자가 제공한 키 | `LEASE_PAYMENT:BOOTH_LEASE:317` |

키는 **VARCHAR(100)** 안에 들어와야 한다. 서비스 계층에서 길이를 검증하고, 초과 시 예외로 드러낸다(조용히 자르지 않는다 — T-24).

## 3. CoinReconciliationRun (`coin_reconciliation_runs`) — **신규 (V4)**

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | BIGINT | PK, identity | |
| `started_at` | TIMESTAMPTZ | NOT NULL | 점검 시작 |
| `finished_at` | TIMESTAMPTZ | NULL 허용 | 점검 종료 |
| `status` | VARCHAR(20) | NOT NULL | `RUNNING` / `COMPLETED` / `FAILED` |
| `checked_wallet_count` | INTEGER | NOT NULL, DEFAULT 0 | 대조한 지갑 수 |
| `mismatched_wallet_count` | INTEGER | NOT NULL, DEFAULT 0 | 불일치 지갑 수 |
| `mismatches` | JSONB | NULL 허용 | 불일치 상세 `[{walletId, userId, balance, ledgerSum, difference}]` |
| `failure_reason` | TEXT | NULL 허용 | `FAILED`일 때 사유 |

**규칙**

- **자동 보정하지 않는다.** 이 테이블은 탐지 결과의 기록일 뿐이다 (FR-014).
- `mismatched_wallet_count > 0`이면 SC-001 위반이며 운영 대응 대상이다.

## 4. 불변식 (Invariants)

| # | 불변식 | 지키는 수단 |
|---|---|---|
| I-1 | `wallets.balance == SUM(coin_ledger_entries.amount)` (지갑별) | 같은 트랜잭션에서만 변경 + 정합성 점검으로 검출 (FR-006, SC-001) |
| I-2 | `wallets.balance >= 0` | DB `CHECK` + 차감 시 비관적 락 (FR-008, SC-003) |
| I-3 | 같은 `idempotency_key`로 잔액이 두 번 변하지 않는다 | DB `UNIQUE` (FR-007, SC-002) |
| I-4 | 회원별 KST 날짜당 `DAILY_GRANT` 원장은 최대 1행 | I-3 + 키에 KST 날짜 포함 (FR-003a, SC-004) |
| I-5 | 게스트에게는 `wallets` 행이 없다 | 회원 생성 경로에서만 지갑 생성 (FR-003b) |
| I-6 | 원장 항목은 UPDATE·DELETE되지 않는다 (탈퇴 시 전량 삭제 제외) | 엔티티에 변경 메서드를 두지 않는다 (FR-004) |

## 5. 인덱스

| 인덱스 | 상태 | 용도 |
|---|---|---|
| `ix_coin_ledger_entries_wallet_created_at (wallet_id, created_at DESC)` | V1에 존재 | 거래 내역 페이지 조회 (FR-012, US3) |
| `coin_ledger_entries.idempotency_key` UNIQUE | V1에 존재 | 멱등성 |
| `wallets.user_id` UNIQUE | V1에 존재 | 사용자→지갑 조회 |

추가 인덱스는 두지 않는다. 정합성 점검은 전체 스캔 1회이며 온디맨드다.

## 6. 상태 전이

지갑·원장에는 상태 머신이 없다 (원장은 append-only, 지갑은 잔액 하나). 유일한 전이는 정합성 점검 실행이다:

```text
RUNNING ──정상 종료──> COMPLETED   (불일치 수는 결과에 기록)
   └────예외 발생─────> FAILED     (failure_reason 기록)
```
