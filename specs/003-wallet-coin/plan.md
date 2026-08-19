# Implementation Plan: Coin 지갑 / 원장

**Branch**: `feature/wallet-coin` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-wallet-coin/spec.md`

## Summary

회원 지갑과 코인 원장을 구현한다. 잔액은 `wallets.balance`에 두고 원장(`coin_ledger_entries`)의 부호 있는 금액 합계와 **항상 일치**시킨다. 지급·차감은 전부 하나의 DB 트랜잭션 안에서 `(원장 1행 INSERT + 잔액 UPDATE)`로만 일어나며, 중복은 원장의 `idempotency_key` **UNIQUE 제약**으로 DB가 막는다. 동시 차감은 지갑 행 **비관적 락**과 `CHECK(balance >= 0)`이 이중으로 막는다.

초기 200코인은 회원 생성 트랜잭션 안에서, 일일 50코인은 **인증된 MEMBER 요청 전역**(HandlerInterceptor)에서 KST 날짜 기준으로 지급한다. 차감(SPEND)은 **내부 서비스 API로만** 노출하고 REST 차감 endpoint를 만들지 않는다(헌법 2·16조). 관리자 조정·정합성 점검은 **도메인·서비스 계층까지 구현**하고 HTTP 노출은 ADMIN 권한 모델 확정 후로 미룬다.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security (Resource Server), Redis, Flyway

**Storage**: PostgreSQL 17 (`wallets` · `coin_ledger_entries`는 V1에 이미 존재), Redis 7.2 (일일 지급 캐시 — 권위 아님)

**Testing**: JUnit 5, Testcontainers(PostgreSQL·Redis), Spring Security Test

**Target Platform**: Docker Spring API

**Project Type**: Web API (`backend/`)

**Performance Goals**: 일일 지급 판정이 인증 요청마다 실행되므로 캐시 적중 시 Redis 조회 1회로 끝난다

**Constraints**: 게임 서버·클라이언트가 코인을 직접 변경 불가, 실시간 이벤트로 잔액 변경 금지, 잔액-원장 불일치 자동 보정 금지

**Scale/Scope**: 회원 지갑 1:1, 원장은 append-only

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | 영구 상태의 Source of Truth는 Spring | 잔액·원장 모두 Spring/PostgreSQL에만 존재 |
| 2 | 게임 서버가 Coin을 바꾸지 않는다 | 차감은 내부 서비스 API만. Unity가 호출할 수 있는 코인 변경 endpoint 없음 |
| 12 | 게스트 비영속 | GUEST 토큰에는 지갑을 만들지 않고 지급도 하지 않는다 |
| 16 | 클라이언트 주장 불신 | 금액·대상은 서버 정책 상수와 인증 주체(JWT subject)에서만 유도한다. 요청 body의 금액을 신뢰하지 않는다 |
| 20 | REST/DB 트랜잭션으로만, Ledger 기록 + idempotency | 모든 변경이 `@Transactional` 안 `(ledger INSERT + balance UPDATE)`. idempotency는 DB UNIQUE로 강제 |
| 29 | 기록 의무 | `docs/HDD/작업일지.md` · `docs/HDD/트러블슈팅.md` 갱신 |
| 30 | 미정 항목 임의 확정 금지 | ADMIN 권한 모델은 확정하지 않고 `docs/HDD/코인_지갑_구현_정리.md`에 미결로 등록 |

**결과: PASS** (Phase 1 설계 후 재점검 — 아래 "Post-Design Re-check")

## Project Structure

### Documentation (this feature)

```text
specs/003-wallet-coin/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정과 근거
├── data-model.md        # Phase 1 — 엔티티·제약·불변식
├── quickstart.md        # Phase 1 — 검증 절차
├── contracts/
│   ├── wallet-api.md            # 외부 REST 계약 (조회 전용)
│   └── wallet-service-api.md    # 내부 서비스 계약 (004·010·012·014 소비)
└── tasks.md             # Phase 2 — /speckit-tasks 산출물
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/wallet/
├── Wallet.java                       # 지갑 엔티티 (잔액)
├── WalletRepository.java             # 비관적 락 조회 포함
├── CoinLedgerEntry.java              # 원장 엔티티 (append-only)
├── CoinLedgerEntryRepository.java
├── LedgerEntryType.java              # CHARGE / SPEND / REWARD / REFUND
├── CoinReason.java                   # reason_type 상수 (INITIAL_GRANT, DAILY_GRANT, ADMIN_ADJUSTMENT ...)
├── WalletProperties.java             # 초기 200 / 일일 50 / KST zone
├── WalletService.java                # 내부 API: open / balance / credit / spend / history
├── DailyCoinGrantService.java        # KST 날짜 기준 일일 지급
├── DailyCoinGrantInterceptor.java    # 인증된 MEMBER 요청 전역 훅
├── WalletWebConfiguration.java       # 인터셉터 등록
├── WalletController.java             # GET /wallets/me, GET /wallets/me/transactions
├── InsufficientCoinException.java
├── WalletNotFoundException.java
├── CoinReconciliationService.java    # 잔액 vs 원장 합계 점검 (자동 보정 없음)
├── CoinReconciliationRun.java        # 점검 결과 기록 엔티티
└── CoinReconciliationRunRepository.java

backend/src/main/resources/db/migration/
└── V4__coin_reconciliation_runs.sql  # 점검 결과 테이블 + 원장 조회 인덱스

backend/src/test/java/com/example/ssafesta/wallet/
├── DailyGrantKstDateTest.java              # 단위 — KST 날짜 경계
├── WalletServiceIntegrationTest.java       # 지급·차감·멱등·잔액부족
├── WalletConcurrencyIntegrationTest.java   # 동시 차감 / 동시 일일 지급
└── CoinReconciliationIntegrationTest.java  # 불일치 탐지 + 결과 기록

backend/bruno/03-wallet/                    # 조회 API 수동 검증
```

**Structure Decision**: 기존 백엔드의 도메인별 평면 패키지(`auth/`, `user/`)를 그대로 따라 `wallet/` 패키지 하나를 추가한다. 새 계층 구조를 도입하지 않는다.

## 핵심 설계 결정 (상세 근거는 [research.md](research.md))

1. **부호 있는 금액** — 지급은 `amount > 0`, 차감은 `amount < 0`. 그래서 정합성 점검이 `balance == SUM(amount)` 한 줄이 된다 (FR-006, SC-001).
2. **멱등성은 락 + DB 제약 두 겹** — 지갑 행 락을 **멱등성 키 조회보다 먼저** 잡아 동시 중복 요청을 직렬화하고, 두 번째 요청은 커밋된 원장을 읽어 **같은 결과**를 반환한다. `idempotency_key` UNIQUE는 최후 방어선이다 (FR-007, SC-002, research R-03).
3. **일일 지급 키는 날짜를 포함** — `DAILY_GRANT:{userId}:{KST yyyy-MM-dd}`. 하루 한 번이 제약으로 표현되므로 재로그인·새로고침·동시 요청이 전부 자동으로 막힌다 (FR-003a, SC-004).
4. **동시 차감은 비관적 락** — 위와 같은 `SELECT ... FOR UPDATE`가 잔액 확인·갱신도 직렬화한다. `CHECK(balance >= 0)`은 마지막 방어선 (FR-008, SC-003).
5. **차감은 내부 서비스 API만** — REST 차감 endpoint를 만들지 않는다 (FR-011, 헌법 2조).
6. **자동 보정 금지** — 정합성 점검은 탐지·기록만 한다 (FR-014).

## Post-Design Re-check

Phase 1 설계 후 재점검 — **PASS**. 새로 도입한 것은 결과 기록 테이블 1개와 지급 판정 인터셉터 1개뿐이며 헌법 위반 없음. 아래 Complexity Tracking에 정당화가 필요한 항목 없음.

## Complexity Tracking

*Constitution Check 위반 없음 — 비어 있다.*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## 미결 항목 (헌법 30조 — 임의 확정하지 않음)

| # | 항목 | 상태 |
|---|---|---|
| U-01 | ADMIN 권한 모델 (role claim 또는 별도 테이블) | 미정 — 003에서 확정하지 않는다. 관리자 조정·정합성 점검 **REST 노출 보류**, 서비스 계층은 구현·테스트한다 |
| ~~U-02~~ | `entry_type` 매핑 중 시스템 지급을 `CHARGE`로 둔 결정 | ✅ **확정 (2026-08-19)** — 현금 충전은 영구히 없고 코인은 게임 내에서만 순환한다. `CHARGE`는 시스템 지급 전용이며 재검토하지 않는다 |

기록 위치: `docs/HDD/코인_지갑_구현_정리.md`
