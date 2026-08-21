# Implementation Plan: 부스 슬롯 / 임대

**Branch**: `feature/booth-slot-lease` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-booth-slot-lease/spec.md`

## Summary

USER_RENTAL 슬롯 7개의 임대를 구현한다. 핵심은 **코인 차감과 임대 생성이 하나의 트랜잭션**이라는 것(FR-003)과 **한 슬롯에 활성 임대가 하나뿐**이라는 것(FR-004)이다. 둘 다 DB 제약이 지킨다 — V1에 이미 있는 `ux_booth_leases_active_slot` 부분 UNIQUE 인덱스와 `booths.current_slot_id` UNIQUE다. 003의 `WalletService.spend`를 같은 트랜잭션에서 호출하므로, 임대가 실패하면 코인도 함께 롤백된다.

만료는 **읽기 시 판정**(`status='ACTIVE' AND ends_at > now()`)이 권위이고, **재임대 트랜잭션에서 상태를 전이**한다(FR-017). 스케줄러를 두지 않는다. 만료를 월드에 실시간 전파하지 않고, 접근 시 거부·안내로 처리한다(FR-019) — 전파 계약은 `docs/HDD/부스_변경_신호_계약.md`로 분리했다.

콘텐츠는 **소유자에 귀속**한다(C-01). Booth는 사용자에 묶이고 슬롯과 분리되므로, 재임대자는 자기 Booth를 새로 받아 빈 상태로 시작한다.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Data JPA, Spring Security (Resource Server), Flyway. **003 `WalletService`** (내부 서비스 호출)

**Storage**: PostgreSQL 17 — `booth_slots` · `booths` · `booth_leases` 전부 V1에 존재

**Testing**: JUnit 5, Testcontainers(PostgreSQL·Redis), MockMvc

**Target Platform**: Docker Spring API

**Project Type**: Web API (`backend/`)

**Performance Goals**: 슬롯 7개. 동시 임대 요청이 실제로 발생하는 규모

**Constraints**: 코인만 차감되는 상태 0건, 한 슬롯 두 명 임대 0건, 게스트 임대 불가

**Scale/Scope**: 슬롯 7개 / 사용자당 활성 임대 1개 / 임대 1일 100코인

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 조 | 요구 | 이 계획에서 |
|---|---|---|
| 1 | 영구 상태의 Source of Truth는 Spring | Lease·Booth·Slot 전부 Spring/PostgreSQL |
| 2 | 게임 서버가 Lease를 바꾸지 않는다 | Unity가 호출할 수 있는 임대 변경 endpoint 없음. 조회만 |
| 12 | 게스트 비영속 | GUEST 토큰은 임대 거부 (FR-016) |
| 16 | 클라이언트 주장 불신 | 가격·기간·소유자는 서버 정책과 JWT subject에서만 유도. 요청의 금액을 신뢰하지 않는다 |
| 20 | 경제 변경은 REST/DB 트랜잭션 + Ledger + idempotency | 차감은 003 `WalletService.spend` 경유. 원장 키는 `LEASE_PAYMENT:BOOTH_LEASE:{leaseId}` |
| 24 | 파트 간 계약 변경은 합의로만 | 실시간 전파 계약을 004에서 확정하지 않고 분리 (FR-019) |
| 29 | 기록 의무 | `docs/HDD/작업일지.md` · `트러블슈팅.md` |
| 30 | 미정 항목 임의 확정 금지 | C-04(층 배치)를 확정하지 않고 데이터로 교체 가능하게 둔다 |

**결과: PASS**

## Project Structure

### Documentation (this feature)

```text
specs/004-booth-slot-lease/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정과 근거
├── data-model.md        # Phase 1 — 엔티티·제약·불변식
├── quickstart.md        # Phase 1 — 검증 절차
├── contracts/
│   └── lease-api.md     # REST 계약 (FE·Unity 소비)
└── tasks.md             # Phase 2
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/booth/
├── BoothSlot.java                    # 슬롯 엔티티
├── BoothSlotRepository.java
├── SlotType.java                     # USER_RENTAL / ADMIN
├── Booth.java                        # 부스 — 소유자에 귀속 (C-01)
├── BoothRepository.java
├── BoothStatus.java
├── BoothLease.java                   # 임대
├── BoothLeaseRepository.java         # 만료 판정 술어를 여기 모은다
├── LeaseStatus.java                  # ACTIVE / EXPIRED
├── LeaseProperties.java              # 가격 100 / 기간 24h
├── BoothLeaseService.java            # 임대 생성 — 차감과 한 트랜잭션
├── BoothQueryService.java            # 슬롯 목록 · 내 부스 · 부스 상세
├── BoothSlotController.java          # GET /booth-slots, POST /booth-slots/{id}/leases
├── BoothController.java              # GET /booths/mine, GET /booths/{id}
├── SlotNotRentableException.java
├── SlotAlreadyLeasedException.java
├── ActiveLeaseLimitException.java
└── BoothExpiredException.java        # 만료 부스 접근 (FR-019)

backend/src/main/resources/db/migration/
└── V5__booth_slot_seed.sql           # USER_RENTAL 슬롯 7개 (11층) 시딩

backend/src/test/java/com/example/ssafesta/booth/
├── BoothLeaseServiceIntegrationTest.java      # 임대·잔액부족·한도·재임대
├── BoothLeaseConcurrencyIntegrationTest.java  # 동시 임대 / 코인 보전
├── BoothExpiryIntegrationTest.java            # 만료 판정·접근 거부·콘텐츠 보존
└── BoothApiIntegrationTest.java               # REST 계약·게스트 차단

backend/bruno/04-booth-lease/                  # 수동 검증
```

**Structure Decision**: 기존 도메인별 평면 패키지(`auth/`, `user/`, `wallet/`)를 따라 `booth/` 하나를 추가한다. 005(Layout)·009(Project)가 나중에 같은 패키지에 얹힌다.

## 핵심 설계 결정 (상세 근거는 [research.md](research.md))

1. **원자성은 트랜잭션 하나로** — 임대 생성과 `WalletService.spend`가 같은 트랜잭션이다. 003이 `REQUIRED` 전파라 그대로 참여한다. 어느 쪽이 실패하든 둘 다 롤백되므로 "코인만 사라짐"이 구조적으로 불가능하다 (FR-003, SC-002).
2. **슬롯 독점은 DB 제약이 지킨다** — `ux_booth_leases_active_slot`(부분 UNIQUE)이 슬롯당 `ACTIVE` 1행만 허용한다. 애플리케이션 검사가 아니라 제약이라 경합에서 뚫리지 않는다 (FR-004, SC-001).
3. **만료는 읽기 시 판정** — `ends_at > now()`가 권위다. 스케줄러가 없어도 만료 부스 입장이 막힌다 (FR-008, SC-003).
4. **재임대는 쓰기 시 전이** — 위 UNIQUE 제약과 `booths.current_slot_id` UNIQUE 때문에, 만료된 임대를 `EXPIRED`로 바꾸고 슬롯 연결을 끊지 않으면 **재임대 자체가 불가능**하다 (FR-017, D05).
5. **Booth는 소유자에 귀속** — 슬롯과 분리한다. 재임대자에게 이전 콘텐츠가 넘어갈 경로가 없다 (C-01, SC-004).
6. **실시간 전파는 범위 밖** — 접근 거부·안내로 대체한다 (FR-019).

## Post-Design Re-check

Phase 1 설계 후 재점검 — **PASS**. 새 테이블 없이 V1 스키마를 그대로 쓰고, 새로 추가하는 것은 슬롯 시딩 마이그레이션 1개뿐이다.

## Complexity Tracking

*Constitution Check 위반 없음.*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## 미결 항목 (헌법 30조)

| # | 항목 | 상태 |
|---|---|---|
| U-03 | 슬롯의 층 배치·좌표 (C-04) | 미정. 7개를 11층에 시딩하고 마이그레이션 데이터로 교체 가능하게 둔다. spec 018 확정 시 데이터만 바꾼다 |
| U-04 | 부스 변경 실시간 전파 경로 (A: Netcode / B: SSE) | 미정. 004 범위 밖. `docs/HDD/부스_변경_신호_계약.md` |
| U-05 | `facade` 스키마 — V1 단일 컬럼 vs docs/09 4컬럼 | 미정. 아직 아무도 안 쓴다. **005 착수 전** 정리 필요 |
| U-01 | ADMIN 권한 모델 (003에서 이월) | 미정. C-05(신고·비공개)가 여기 걸려 범위에서 제외 |
