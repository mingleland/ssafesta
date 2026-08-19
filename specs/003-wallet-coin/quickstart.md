# Quickstart: Coin 지갑 / 원장 검증

**Spec**: `003-wallet-coin` | **Date**: 2026-08-19

이 문서는 003이 **동작함을 증명하는 실행 절차**다. 구현 코드는 담지 않는다 (설계는 [data-model.md](data-model.md)·[contracts/](contracts/), 작업 분할은 `tasks.md`).

---

## 사전 조건

| 항목 | 확인 |
|---|---|
| Docker Desktop 실행 중 | Testcontainers가 PostgreSQL·Redis를 띄운다. 꺼져 있으면 통합 테스트가 전부 실패한다 (T-97) |
| 로컬 인프라 | `docker compose up -d` (`backend/compose.yaml`) — 수동 검증용 |
| PostgreSQL 이미지 | `pgvector/pgvector:pg17`이어야 한다. 일반 `postgres` 이미지는 V1의 `CREATE EXTENSION vector`에서 실패한다 (T-98) |
| 실행 위치 | 저장소 루트 또는 `backend/` |

---

## 1. 자동 검증 — 이것이 주 검증 수단이다

```bash
cd backend && ./mvnw test
```

Windows PowerShell:

```bash
cd backend; .\mvnw.cmd test
```

**통과해야 하는 것** (각 항목이 어느 Success Criteria를 증명하는지 표기)

| 테스트 | 증명 |
|---|---|
| 신규 회원 생성 후 잔액 200, 원장 `INITIAL_GRANT` 1행 | FR-002, AS1-1 |
| 같은 회원에게 초기 지급을 다시 시도해도 잔액 불변 | FR-002 |
| 첫 인증 요청에서 일일 50코인 지급, 원장 `DAILY_GRANT` 1행 | FR-003, AS1-3 |
| 같은 KST 날짜에 요청을 여러 번 반복해도 `DAILY_GRANT` 1행 | FR-003a, **SC-004**, AS1-4 |
| KST 날짜가 바뀌면 다시 1회 지급 | FR-003, Edge Case(KST 자정) |
| 게스트 토큰 요청에서는 지갑이 생기지 않고 지급도 없다 | FR-003b, I-5 |
| 차감 후 잔액 감소 + 원장 `SPEND` 음수 1행 | FR-005, AS1-5 |
| 같은 멱등성 키로 차감 2회 → 잔액 1회만 변함 | FR-007, **SC-002**, US2-1 |
| 잔액보다 큰 차감 → `InsufficientCoinException`, 잔액 불변 | FR-009, AS1-6 |
| 동시 차감 2건, 잔액은 1건만 감당 → 1건 성공·1건 거부, 잔액 ≥ 0 | FR-008, **SC-003**, US2-2 |
| 동시 일일 지급 요청 N건 → 원장 1행 | FR-003a, **SC-004** |
| 임의의 트랜잭션 후 `balance == SUM(amount)` | FR-006, **SC-001**, I-1 |
| 잔액을 인위적으로 어긋나게 만든 뒤 점검 실행 → 불일치 탐지·기록, **잔액은 보정되지 않음** | FR-014, C-05 |
| 내역 조회가 페이지 단위로 반환되고 최신순 정렬 | FR-012, US3 |

> 동시성 테스트는 실제 스레드로 돌린다. 통과했는데 의심스러우면 반복 횟수를 늘려 재실행한다 — 경합 버그는 1회 통과로 증명되지 않는다.

## 2. 마이그레이션 확인

```bash
cd backend && ./mvnw test -Dtest=SsafestaApplicationTests
```

Flyway가 V1 → V4까지 올라가고 `jpa.hibernate.ddl-auto=validate`가 엔티티-스키마 일치를 검증한다. **엔티티와 컬럼이 어긋나면 여기서 실패한다** — 통과가 스키마 정합의 증거다.

## 3. 수동 검증 (Bruno) — 조회 API

```bash
cd backend && docker compose up -d
cd backend && ./mvnw spring-boot:run
```

Bruno 컬렉션 `backend/bruno`:

1. `01-auth` — Google 또는 Kakao로 로그인해 Access Token을 환경변수에 채운다 (신규 계정이면 닉네임 입력까지)
2. `03-wallet/내 지갑 조회` → **신규 계정이면 `balance: 250`** (초기 200 + 당일 첫 접속 50)
3. `03-wallet/내 거래 내역 조회` → `INITIAL_GRANT`와 `DAILY_GRANT` 2행이 최신순으로 보인다
4. 같은 두 요청을 여러 번 반복 → **`balance`와 내역이 변하지 않는다** (SC-004 육안 확인)
5. `01-auth/게스트 로그인` 토큰으로 2번 요청 → **`403`** (FR-003b)

## 4. 확인해야 할 함정

| 증상 | 먼저 의심할 것 |
|---|---|
| 통합 테스트가 컨테이너 단계에서 전부 실패 | Docker Desktop 미기동 (T-97) |
| Flyway V1에서 `extension "vector" is not available` | 실행 중인 PostgreSQL 컨테이너 이미지가 `pgvector/pgvector:pg17`이 아님 (T-98) |
| 잔액이 안 바뀌는데 원장은 늘어남 (또는 반대) | 잔액 UPDATE와 원장 INSERT가 다른 트랜잭션에 있음 — I-1 위반 |
| 일일 지급이 하루에 두 번 | 멱등성 키의 날짜가 KST가 아니라 UTC로 계산됨 |
| 일일 지급이 아예 안 됨 | 인터셉터가 해당 경로에 등록되지 않았거나 SecurityContext가 아직 비어 있는 시점에서 실행됨 |
| 코드를 고쳤는데 결과가 그대로 | 빌드 산출물·컨테이너를 함께 의심한다 (T-23/T-25). 포트로 컨테이너를 확인한다 |
