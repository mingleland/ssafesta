# Phase 1 Data Model: 부스 슬롯 / 임대

**Spec**: `004-booth-slot-lease` | **Date**: 2026-08-19

`booth_slots` · `booths` · `booth_leases`는 **V1 초기 스키마에 이미 존재한다.** 004는 엔티티·제약 해석을 붙이고 슬롯 시딩 마이그레이션 하나만 추가한다.

---

## 1. BoothSlot (`booth_slots`) — 기존 테이블

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | BIGINT | PK | |
| `slot_code` | VARCHAR(30) | NOT NULL, **UNIQUE** | 사람이 읽는 식별자 (`F11-R01`) |
| `floor_no` | SMALLINT | NOT NULL | 층 |
| `slot_type` | VARCHAR(30) | NOT NULL | `USER_RENTAL` / 관리자 유형 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `AVAILABLE` | 슬롯 자체의 운영 상태 |
| `created_at` | TIMESTAMPTZ | NOT NULL | |

**규칙**

- **점유 여부는 이 테이블에 두지 않는다.** 활성 임대의 존재가 곧 점유다 (research R-02). `status`는 "이 슬롯을 임대 상품으로 여는가"라는 운영 스위치이고, 임대 생명주기와 무관하다.
- `USER_RENTAL`이 아닌 슬롯은 임대 대상이 아니다 (`SlotNotRentableException`).
- V5 마이그레이션이 7개를 시딩한다 (11층, U-03).

## 2. Booth (`booths`) — 기존 테이블

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | BIGINT | PK | |
| `owner_user_id` | BIGINT | NOT NULL, FK → `users(id)` | **주인. 콘텐츠가 여기 귀속된다** (C-01) |
| `current_slot_id` | BIGINT | **UNIQUE**, FK → `booth_slots(id)` | 임대 중에만 채워지는 연결. 만료 시 `NULL` |
| `name` | VARCHAR(100) | NOT NULL | |
| `description` | TEXT | | 005에서 편집 |
| `facade_code` | VARCHAR(50) | NOT NULL, DEFAULT `DEFAULT` | **미사용** (U-05 — docs/09와 불일치) |
| `homepage_url` | VARCHAR(2048) | | spec 016 |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT `INACTIVE` | `ACTIVE` = 임대 중 |
| `created_at`·`updated_at` | TIMESTAMPTZ | NOT NULL | |

**규칙**

- **사용자당 Booth 하나를 재사용한다.** 재임대해도 새로 만들지 않고 같은 Booth에 슬롯을 다시 연결한다 → 그 사용자의 콘텐츠가 이어진다.
- **다른 사용자의 Booth는 절대 연결하지 않는다.** 재임대자는 자기 Booth를 받으므로 이전 소유자 콘텐츠에 도달할 경로가 없다 (SC-004).
- `current_slot_id`가 **UNIQUE**라는 점이 중요하다 — 만료된 Booth의 연결을 끊지 않으면 그 슬롯에 다른 Booth를 붙일 수 없다 (FR-017).
- 임대 만료 시 **삭제하지 않는다.** 슬롯 연결과 `status`만 바꾼다 (FR-010).

## 3. BoothLease (`booth_leases`) — 기존 테이블

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | BIGINT | PK | |
| `booth_id` | BIGINT | NOT NULL, FK → `booths(id)` | |
| `slot_id` | BIGINT | NOT NULL, FK → `booth_slots(id)` | |
| `lessee_user_id` | BIGINT | NOT NULL, FK → `users(id)` | 임차인 = Booth 소유자 |
| `status` | VARCHAR(20) | NOT NULL | `ACTIVE` / `EXPIRED` |
| `starts_at` | TIMESTAMPTZ | NOT NULL | 결제 시각 |
| `ends_at` | TIMESTAMPTZ | NOT NULL, **CHECK(ends_at > starts_at)** | `starts_at + 24h` (D02) |
| `charged_coin` | INTEGER | NOT NULL, CHECK(>= 0) | 실제 차감액 |
| `created_at` | TIMESTAMPTZ | NOT NULL | |

**핵심 제약 — V1에 이미 있다**

```sql
CREATE UNIQUE INDEX ux_booth_leases_active_slot ON booth_leases(slot_id) WHERE status = 'ACTIVE';
```

- 슬롯당 `ACTIVE` 임대가 **최대 1행**이다. 동시 임대 방어의 최종 수단이다 (FR-004, SC-001).
- **이 인덱스는 `ends_at`을 보지 않는다.** 만료된 임대를 `ACTIVE`로 두면 재임대가 영구히 막힌다 → FR-017이 필요한 이유다.

**규칙**

- 임대 기록은 **수정하지 않는다.** 유일한 변경은 `ACTIVE → EXPIRED` 상태 전이다.
- 원장 연결: 차감 원장의 멱등성 키가 `LEASE_PAYMENT:BOOTH_LEASE:{leaseId}`다 (research R-04).

### 만료 판정 술어 — 한 곳에서만 표현한다

```sql
status = 'ACTIVE' AND ends_at > now()
```

이 술어를 서비스 코드에 흩뿌리지 않고 `BoothLeaseRepository`의 쿼리로만 표현한다. 한 곳에서라도 빠뜨리면 만료된 임대가 활성으로 취급돼 조용히 틀린다.

## 4. 불변식 (Invariants)

| # | 불변식 | 지키는 수단 |
|---|---|---|
| I-1 | 한 슬롯에 유효한 활성 임대는 최대 1개 | `ux_booth_leases_active_slot` (FR-004, SC-001) |
| I-2 | 코인이 차감됐다면 대응하는 임대가 반드시 있다 | 단일 트랜잭션 (FR-003, SC-002) |
| I-3 | 사용자당 유효한 활성 임대는 최대 1개 | `ux_booth_leases_active_lessee` + 임대 진입부의 지갑 행 락 (FR-005, D01) |
| I-4 | `ends_at = starts_at + 24h` | 서버가 계산. 요청값을 쓰지 않는다 (FR-006, 헌법 16조) |
| I-5 | Booth의 콘텐츠는 소유자가 바뀌지 않는다 | Booth가 `owner_user_id`에 귀속 + `ux_booths_owner` (C-01, SC-004) |
| I-6 | 만료된 임대의 슬롯 연결은 남지 않는다 | 재임대 트랜잭션에서 해제 (FR-017) |
| I-7 | 게스트는 임대 기록을 만들 수 없다 | 컨트롤러 role 검사 (FR-016, 헌법 12조) |

## 5. 인덱스

| 인덱스 | 상태 | 용도 |
|---|---|---|
| `ux_booth_leases_active_slot` | V1에 존재 | 슬롯 독점 (I-1) |
| `booths.current_slot_id` UNIQUE | V1에 존재 | 슬롯당 Booth 1개 |
| `booth_slots.slot_code` UNIQUE | V1에 존재 | 슬롯 식별 |
| `ux_booth_leases_active_lessee` | **V6에서 추가** | 회원당 활성 임대 1건 (I-3) |
| `ux_booths_owner` | **V7에서 추가** | 회원당 Booth 1개 (I-5) |

**조회 성능용 인덱스는 두지 않는다** — 슬롯이 7개라 전체 조회가 곧 최적이다. 위 목록은 전부 **무결성 제약**이다.

> V6·V7은 처음 설계에 없었다. 부하 테스트에서 **한 회원이 7개 슬롯에 동시 요청해 임대 7건과 부스 7개를 만드는 것**을 발견하고 추가했다 (T-110). I-3과 I-5가 코드의 조회 한 줄에만 기대고 있었고, 그건 check-then-act라 경합에서 통과한다.
>
> **두 인덱스 모두 `ends_at`을 보지 않는다.** `ux_booth_leases_active_slot`이 그랬듯, 만료된 `ACTIVE` 행이 남으면 그 회원은 영구히 재임대 불가가 된다. 그래서 재임대 트랜잭션이 **슬롯 기준과 회원 기준 양쪽**의 낡은 임대를 전이한다 (FR-017).

## 6. 상태 전이

### Lease

```text
(없음) ──임대 생성──> ACTIVE ──재임대 시점에 만료 확인──> EXPIRED
```

`EXPIRED`에서 되돌아오지 않는다. 재임대는 **새 행**을 만든다 (D05: 연장 없음).

> `ends_at`이 지난 `ACTIVE` 행은 **논리적으로 만료**이며 읽기 판정이 그렇게 취급한다. 물리적 전이는 그 슬롯을 누군가 다시 임대할 때 일어난다.

### Booth

```text
INACTIVE ──임대 성립──> ACTIVE ──슬롯 연결 해제──> INACTIVE
```

콘텐츠는 어느 상태에서도 보존된다 (FR-010).
