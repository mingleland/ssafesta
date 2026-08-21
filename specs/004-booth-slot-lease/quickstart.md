# Quickstart: 부스 슬롯 / 임대 검증

**Spec**: `004-booth-slot-lease` | **Date**: 2026-08-19

004가 동작함을 증명하는 실행 절차다. 설계는 [data-model.md](data-model.md)·[contracts/](contracts/), 작업 분할은 `tasks.md`.

---

## 사전 조건

| 항목 | 확인 |
|---|---|
| Docker Desktop 실행 중 | Testcontainers가 PostgreSQL·Redis를 띄운다. 꺼져 있으면 통합 테스트가 전부 실패한다 (T-97) |
| PostgreSQL 이미지 | `pgvector/pgvector:pg17`이어야 한다 (T-98) |
| 003이 병합돼 있을 것 | `WalletService`가 없으면 컴파일되지 않는다 |
| 실행 위치 | 저장소 루트 또는 `backend/` |

---

## 1. 자동 검증 — 주 검증 수단

```bash
cd backend && ./mvnw test
```

Windows PowerShell:

```bash
cd backend; .\mvnw.cmd test
```

**통과해야 하는 것**

| 테스트 | 증명 |
|---|---|
| 빈 슬롯 임대 → 코인 100 차감 + 임대 생성 + Booth가 슬롯에 연결 | FR-002·FR-003, AS1-2 |
| 임대 후 원장에 `LEASE_PAYMENT` 음수 1행, 잔액-원장 일치 | 003 I-1 |
| 잔액 부족 → 거부되고 **임대도 코인도 변화 없음** | FR-003, AS1-3 |
| 이미 활성 임대가 있는 사용자의 추가 임대 → 거부 | FR-005, D01, AS1-4 |
| `USER_RENTAL`이 아닌 슬롯 임대 시도 → 거부 | `BOOTH_SLOT_NOT_RENTABLE` |
| 게스트 토큰 임대 시도 → 403, 임대 0건 | FR-016, I-7 |
| **동시 임대 2건 → 정확히 1건 성공** | FR-004, **SC-001**, US2-1 |
| **실패한 쪽의 코인이 차감되지 않음** | FR-003, **SC-002**, US2-2 |
| `ends_at`이 지난 임대 → 슬롯이 `AVAILABLE`로 보이고 `entryAvailable=false` | FR-008, **SC-003** |
| 만료된 부스 `GET /booths/{id}` → 409 `BOOTH_LEASE_EXPIRED` | FR-019 |
| 만료 후 재임대 → 성공하고 이전 임대가 `EXPIRED`로 전이 | FR-017, D05 |
| **다른 사용자가 재임대 → 이전 소유자의 Booth를 받지 않는다** | C-01, **SC-004**, I-5 |
| 만료돼도 이전 소유자의 Booth와 콘텐츠가 남아 있다 | FR-010 |
| 만료된 임대는 소유자의 활성 임대 한도를 점유하지 않는다 | Edge Case, I-3 |
| 같은 사용자가 자기 슬롯에 재요청 → 200 + 기존 임대, 추가 차감 없음 | FR-018 |
| `ends_at = starts_at + 24h` (요청값 무시) | FR-006, I-4 |

> 동시성 테스트는 실제 스레드로 돌리고 반복 실행한다. 경합 버그는 1회 통과로 증명되지 않는다.

## 2. 마이그레이션 확인

```bash
cd backend && ./mvnw test -Dtest=SsafestaApplicationTests
```

Flyway가 V1 → V5까지 올라가고 `ddl-auto=validate`가 엔티티-스키마 일치를 검증한다. V5 시딩으로 **USER_RENTAL 슬롯 7개(11층)** 가 들어간다.

## 3. 수동 검증 (Bruno)

```bash
cd backend && docker compose up -d
cd backend && ./mvnw spring-boot:run
```

컬렉션 `backend/bruno`:

1. `01-auth` — 로그인해 Access Token 확보
2. `03-wallet/내 지갑 조회` → 신규 계정이면 **250**
3. `04-booth-lease/슬롯 목록 조회` → 7개, 전부 `AVAILABLE`
4. `04-booth-lease/부스 임대` → `201`, `chargedCoin: 100`, `balanceAfter: 150`
5. `03-wallet/내 거래 내역 조회` → `LEASE_PAYMENT` `-100` 1행
6. `04-booth-lease/슬롯 목록 조회` → 그 슬롯이 `OCCUPIED`, `remainingSeconds`가 줄어든다
7. **같은 슬롯을 다시 임대** → `200` + 기존 임대 (추가 차감 없음, FR-018)
8. `04-booth-lease/내 부스 조회` → 임대 정보와 남은 시간
9. **다른 계정으로 같은 슬롯 임대** → `409 BOOTH_SLOT_ALREADY_LEASED`, 그 계정 잔액 불변
10. `01-auth/게스트 로그인` 토큰으로 임대 → `403`

### 만료를 손으로 확인하려면

`starts_at`도 함께 옮겨야 한다 — `booth_leases`에 `CHECK(ends_at > starts_at)`가 걸려 있어 종료 시각만 과거로 당기면 제약에 걸린다.

```bash
docker compose exec postgres psql -U ssafesta -d ssafesta -c "UPDATE booth_leases SET starts_at = now() - interval '25 hours', ends_at = now() - interval '1 hour' WHERE status = 'ACTIVE';"
```

- `슬롯 목록 조회` → 해당 슬롯이 다시 `AVAILABLE`, `entryAvailable: false`
- `GET /booths/{id}` → **409 `BOOTH_LEASE_EXPIRED`**
- 다른 계정으로 그 슬롯 임대 → **성공**하고 이전 임대가 `EXPIRED`로 바뀐다
- 이전 소유자의 `GET /booths/mine` → `status: INACTIVE`, `lease: null`, **부스는 남아 있다**

## 4. 확인해야 할 함정

| 증상 | 먼저 의심할 것 |
|---|---|
| 통합 테스트가 컨테이너 단계에서 전부 실패 | Docker Desktop 미기동 (T-97) |
| 재임대가 `BOOTH_SLOT_ALREADY_LEASED`로 계속 거부됨 | 만료 임대를 `EXPIRED`로 전이하지 않았다. `ux_booth_leases_active_slot`은 `ends_at`을 보지 않는다 (FR-017) |
| 재임대 시 `current_slot_id` 중복 오류 | 이전 Booth의 슬롯 연결을 끊지 않았다 (UNIQUE 컬럼) |
| 만료됐는데 슬롯이 `OCCUPIED`로 보임 | 조회 쿼리에서 `ends_at > now()` 술어가 빠졌다 |
| 만료됐는데 새 슬롯을 임대할 수 없음 | 활성 임대 한도 검사에 만료 술어가 빠졌다 (I-3) |
| 코인은 줄었는데 임대가 없음 | **트랜잭션 경계가 깨졌다.** I-2 위반 — 가장 심각한 결함이다 |
| 재임대자에게 이전 콘텐츠가 보임 | Booth를 슬롯에 귀속시켰다. Booth는 소유자에 귀속해야 한다 (I-5) |
| 코드를 고쳤는데 결과가 그대로 | 빌드 산출물·컨테이너를 함께 의심한다 (T-23/T-25/T-104) |
