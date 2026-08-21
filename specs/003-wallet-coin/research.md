# Phase 0 Research: Coin 지갑 / 원장

**Spec**: `003-wallet-coin` | **Date**: 2026-08-19

spec.md의 Clarification(C-01~C-06)이 이미 확정돼 있어 남은 미지수는 **구현 방식**뿐이다. 아래는 그 결정과 근거다.

---

## R-01. 잔액을 저장할 것인가, 원장에서 매번 유도할 것인가

- **Decision**: `wallets.balance`에 저장하고, 원장 합계와 일치시킨다 (저장 + 검증).
- **Rationale**: 잔액 조회는 모든 기능(004 임대·007/008 AI·014 미니게임)이 호출하는 최다 경로다. 매번 `SUM`을 돌리면 원장이 커질수록 느려진다. 대신 FR-006/SC-001을 지키기 위해 **정합성 점검(FR-014)** 을 별도로 제공한다.
- **Alternatives considered**:
  - *원장에서 매번 유도*: 정합성은 정의상 보장되지만 조회 비용이 원장 크기에 비례하고, 잔액에 `CHECK(balance >= 0)`를 걸 수 없어 동시 차감 방어가 약해진다.
  - *스냅샷 + 델타*: MVP 규모에 과하다.

## R-02. 금액 부호 규약

- **Decision**: 지급은 `amount > 0`, 차감은 `amount < 0`. V1의 `CHECK(amount <> 0)`이 이미 0을 막는다.
- **Rationale**: `balance == SUM(amount)`가 성립해 정합성 점검이 SQL 한 줄이 된다. `entry_type`과 부호가 이중 표현되지만 검증으로 묶는다(아래 R-06).
- **Alternatives considered**: *항상 양수 + entry_type으로 방향 판단* — 합계 계산에 `CASE`가 필요하고, 새 `entry_type`이 추가될 때마다 정합성 SQL을 고쳐야 해서 조용히 틀리기 쉽다.

## R-03. 멱등성 구현 위치

- **Decision**: 두 겹으로 둔다. ① `coin_ledger_entries.idempotency_key` UNIQUE 제약(V1에 이미 있음)이 규칙을 표현하고, ② `applyEntry`는 **지갑 행 락을 멱등성 키 조회보다 먼저** 잡아 동시 중복 요청을 직렬화한다. 두 번째 요청은 락을 얻은 뒤 커밋된 원장을 읽어 같은 결과를 반환한다.
- **Rationale**: "먼저 조회하고 없으면 INSERT"만으로는 동시 요청 둘 다 통과한 뒤 INSERT에서 충돌한다. 그런데 JPA에서 제약 위반이 나면 그 트랜잭션은 rollback-only가 되어 **같은 트랜잭션 안에서 복구할 수 없다** — 호출자의 트랜잭션에 참여하는 이 서비스에서는 치명적이다. 락을 먼저 잡으면 충돌 자체가 일어나지 않는다. UNIQUE 제약은 최후 방어선으로 남고, 그것이 실제로 터지면 이 순서가 무너진 것이므로 조용히 넘기지 않고 실패시킨다 (SC-002).
- **전제**: PostgreSQL 기본 격리 수준 READ COMMITTED. 문장마다 새 스냅샷을 잡으므로 락 획득 후 커밋된 행이 보인다. 격리 수준을 올리면 재검토해야 한다.
- **Alternatives considered**:
  - *UNIQUE 위반을 잡아 복구*: 위 rollback-only 문제로 호출자 트랜잭션까지 함께 죽는다.
  - *Redis 분산 락*: Redis 장애 시 보호가 사라진다. 경제 무결성을 캐시에 의존시킬 수 없다.
  - *애플리케이션 선조회만*: TOCTOU 경합에 열려 있다.

## R-04. 일일 지급의 "하루" 표현

- **Decision**: 멱등성 키에 KST 날짜를 넣는다 — `DAILY_GRANT:{userId}:{yyyy-MM-dd}` (`Asia/Seoul` 기준). 저장 시각은 UTC(`TIMESTAMPTZ`) 그대로 둔다.
- **Rationale**: "회원별 KST 날짜당 한 번"이라는 규칙이 **DB 제약 자체로 표현**된다. 별도의 "마지막 지급일" 컬럼과 그 갱신 로직이 필요 없고, 그래서 어긋날 수도 없다 (FR-003a, SC-004).
- **Alternatives considered**: *`wallets.last_daily_grant_date` 컬럼* — 원장과 별개의 상태가 하나 더 생기고, 둘이 어긋나면 SC-004를 조용히 위반한다.

## R-05. 일일 지급을 어느 시점에 판정할 것인가

- **Decision**: **인증된 MEMBER 요청 전역**에서 판정한다. Spring MVC `HandlerInterceptor`가 `/api/v1/**`의 `preHandle`에서 SecurityContext의 JWT role이 `MEMBER`면 지급 여부를 확인한다.
- **Rationale**: spec의 "당일 처음 인증된 상태로 서비스에 접속"과 "새로고침·재로그인에도 한 번"을 둘 다 만족하는 유일한 지점이다. 로그인 시점에만 걸면, access token이 아직 유효한 상태의 새로고침(F5)은 로그인을 거치지 않으므로 지급이 누락된다.
- **비용 대책**: Redis 키 `wallet:daily:{userId}:{KST date}`를 지급 후 기록하고 다음 KST 자정까지 TTL을 준다. 캐시가 있으면 DB를 건드리지 않는다. **Redis는 권위가 아니다** — 캐시 미스·Redis 장애 시 DB 경로로 내려가고 정확성은 R-03의 UNIQUE 제약이 지킨다.
- **Alternatives considered**:
  - *세션 발급·재발급 시점*: 위 새로고침 케이스를 놓친다.
  - *지갑 조회 API 호출 시*: 지갑을 열지 않고 코인을 쓰는 경로에서 지급이 늦다.

## R-06. 일일 지급 실패를 어떻게 다룰 것인가 (T-24 규칙과의 관계)

- **Decision**: 인터셉터는 지급 실패를 **삼키지 않고 ERROR 로그로 드러내되, 사용자의 원래 요청은 계속 진행시킨다.** 예상된 중복(UNIQUE 위반)만 DEBUG로 조용히 처리한다.
- **Rationale**: T-24의 교훈은 "실패를 **보이지 않게** 기본값으로 되돌리지 마라"다. 여기서는 실패가 ERROR 로그로 드러나고, 지급이 멱등하므로 **다음 요청에서 자동 재시도**되며, 누락은 정합성 점검(FR-014)에도 잡힌다. 반대로 지급 실패로 무관한 API 요청 전체를 500으로 떨어뜨리면 부가 기능이 서비스 전체를 멈추게 한다(헌법 3조의 장애 격리 정신에 어긋난다).
- **Alternatives considered**: *예외를 그대로 전파* — 지급 경로의 일시적 장애가 로그인·조회를 포함한 모든 API를 막는다.

## R-07. 동시 차감 방어

- **Decision**: `SELECT ... FOR UPDATE`(JPA `PESSIMISTIC_WRITE`)로 지갑 행을 잠그고 잔액 확인 → 원장 INSERT → 잔액 UPDATE를 같은 트랜잭션에서 수행한다. V1의 `CHECK(balance >= 0)`을 마지막 방어선으로 둔다.
- **Rationale**: 같은 지갑에 대한 차감은 직렬화되므로 "둘 다 잔액이 충분하다고 읽는" 경합이 사라진다 (FR-008, SC-003). 락 범위가 지갑 1행이라 서로 다른 사용자끼리는 경합하지 않는다.
- **Alternatives considered**:
  - *낙관적 락(@Version)*: 충돌 시 재시도 루프가 필요하고, 재시도 중 멱등성 키 처리가 복잡해진다.
  - *조건부 UPDATE (`WHERE balance >= ?`)만*: 잔액은 지켜지지만 원장 INSERT와의 순서 보장을 코드로 다시 만들어야 한다.

## R-08. entry_type / reason_type 매핑

- **Decision**:

  | entry_type | 부호 | 용도 | reason_type 예 |
  |---|---|---|---|
  | `CHARGE` | + | 시스템 지급 (초기·일일), 관리자 증액 | `INITIAL_GRANT`, `DAILY_GRANT`, `ADMIN_ADJUSTMENT` |
  | `SPEND` | − | 사용 | `LEASE_PAYMENT`, `AI_SERVICE_FEE`, `ITEM_PURCHASE`, `ADMIN_ADJUSTMENT` |
  | `REWARD` | + | 보상 | `SURVEY_REWARD`, `MINIGAME_REWARD` |
  | `REFUND` | + | 환불 | `LEASE_REFUND` |

- **Rationale**: spec이 정한 4종을 그대로 쓴다. 코인은 **게임 내에서만 벌고 쓰며 현금 충전은 하지 않는다**(팀 결정 2026-08-19). 따라서 `CHARGE`는 시스템 지급 전용으로 굳는다. 사유는 `reason_type`이 표현하므로 유형을 늘리지 않는다.
- **확정 (구 U-02)**: 현금 충전이 영구히 없으므로 "충전 vs 시스템 지급"을 구분할 필요가 생기지 않는다. spec 012에서도 재검토하지 않는다.

## R-09. 정합성 점검(FR-014) 제공 형태

- **Decision**: **온디맨드 서비스 실행 + 결과 테이블 기록**. 새 테이블 `coin_reconciliation_runs`(V4). 자동 보정하지 않는다.
- **Rationale**: 운영자가 원하는 시점에 돌리고 결과가 남는다. 스케줄 배치가 필요해지면 이 서비스를 호출하는 스케줄러만 나중에 얹으면 된다.
- **HTTP 노출**: 보류. ADMIN 권한 모델(U-01)이 없어서 지금 endpoint를 열면 인증 없는 운영 API가 된다. 서비스·테이블·테스트까지 구현하고 REST만 남긴다.

## R-10. 관리자 조정(FR-013)

- **Decision**: `WalletService`에 관리자 조정 경로를 두고 원장에 `reason_type = ADMIN_ADJUSTMENT`, 행위자 식별자를 `reference_type/reference_id`(`ADMIN_USER` / actor userId)로 남긴다. REST 노출은 U-01 확정까지 보류.
- **Rationale**: FR-013의 본질은 "관리자 조정도 원장에 남는다"이며, 그것은 도메인 계층에서 완결된다. 001에서도 같은 이유로 관리자 endpoint를 제거한 선례가 있다(`docs/HDD/작업일지.md` 2026-08-19).

## R-11. 지갑 생성 시점과 게스트

- **Decision**: `RegistrationService.createMember(...)`의 **같은 트랜잭션 안**에서 지갑 생성 + 초기 200코인 원장 기록을 수행한다. 게스트는 지갑을 만들지 않는다.
- **Rationale**: "계정 생성이 완료되면"(AS-1)을 원자적으로 만족시킨다. 회원 생성과 지갑 생성이 갈라지면 "지갑 없는 회원"이라는 반쪽 상태가 남는다(Edge Case). 게스트 토큰의 subject는 `guest:{uuid}`라 회원 식별자가 아예 없으므로 role 검사만으로 차단된다 (FR-003b, 헌법 12조).

## R-12. 탈퇴 시 정리

- **Decision**: 추가 작업 없음. `AccountDeletionService`가 이미 `coin_ledger_entries` → `wallets` 순서로 삭제한다.
- **Rationale**: 001에서 구현된 hard delete 그래프에 지갑·원장이 포함돼 있고 삭제 순서도 FK에 맞다. 003에서 중복 구현하면 두 곳이 갈라진다.
