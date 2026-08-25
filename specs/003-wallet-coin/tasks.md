# Tasks: Coin 지갑 / 원장

**Input**: `specs/003-wallet-coin/` — [spec.md](spec.md) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [contracts/](contracts/)

**Tests**: 포함한다. spec의 Success Criteria(SC-001~004)가 전부 **동시성·멱등성 요구**라서 테스트 없이는 충족을 증명할 수 없다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이라 병렬 가능
- **[Story]**: US1(코인을 받고 쓴다) / US2(중복 요청 방어) / US3(거래 내역)

## Path Conventions

Web app 구조. 백엔드 경로는 `backend/src/main/java/com/example/ssafesta/`, 테스트는 `backend/src/test/java/com/example/ssafesta/`.

---

## Phase 1: Setup

- [x] T001 `backend/src/main/resources/db/migration/V4__coin_reconciliation_runs.sql` 작성 — `coin_reconciliation_runs` 테이블 (data-model.md §3). `wallets`·`coin_ledger_entries`는 V1에 이미 있으므로 **건드리지 않는다**
- [x] T002 [P] `wallet/WalletProperties.java` — `@ConfigurationProperties("app.wallet")`로 초기 지급 200, 일일 지급 50, `Asia/Seoul` zone을 외부화하고 `application-local.yml`에 기본값 추가

**Checkpoint**: 마이그레이션이 올라가고 정책 상수가 설정으로 분리됐다

---

## Phase 2: Foundational (Blocking)

**⚠️ 이 단계가 끝나기 전에는 어떤 User Story도 시작할 수 없다**

- [x] T003 [P] `wallet/LedgerEntryType.java` — `CHARGE`/`SPEND`/`REWARD`/`REFUND` enum과 각 유형의 허용 부호 (research.md R-08)
- [x] T004 [P] `wallet/CoinReason.java` — `INITIAL_GRANT`·`DAILY_GRANT`·`ADMIN_ADJUSTMENT` 사유 상수. 소비 spec이 값을 추가할 수 있게 확장 가능한 형태로
- [x] T005 [P] `wallet/Wallet.java` — `wallets` 매핑. 잔액 변경은 `credit(int)`/`debit(int)` 메서드로만 가능하게 하고 **음수 잔액을 만들면 예외**를 던진다 (I-2)
- [x] T006 [P] `wallet/CoinLedgerEntry.java` — `coin_ledger_entries` 매핑. **setter·변경 메서드를 두지 않는다** (I-6, append-only)
- [x] T007 `wallet/WalletRepository.java` — `findByUserId`, 그리고 `@Lock(PESSIMISTIC_WRITE)`가 붙은 잠금 조회 메서드 (R-07)
- [x] T008 [P] `wallet/CoinLedgerEntryRepository.java` — `findByIdempotencyKey`, 지갑별 페이지 조회(정렬 `created_at DESC, id DESC`), 지갑별 `SUM(amount)` 집계 쿼리
- [x] T009 [P] `wallet/InsufficientCoinException.java`(필요 금액·현재 잔액 보유) + `wallet/WalletNotFoundException.java`
- [x] T010 `wallet/WalletService.java` 골격 — `openWallet`/`balanceOf`/`credit`/`spend`/`adjustByAdmin`/`history` 시그니처를 [contracts/wallet-service-api.md](contracts/wallet-service-api.md) 그대로. 내부 공통 경로 `applyEntry(...)`: **지갑 락 → 멱등성 키 조회 → 원장 INSERT → 잔액 UPDATE**를 한 트랜잭션에서 수행한다. 락을 먼저 잡아 동시 중복 요청을 직렬화하므로, 두 번째 요청은 커밋된 원장을 읽어 `alreadyApplied = true`를 반환한다. UNIQUE 제약은 최후 방어선으로 남긴다 (R-03)
- [x] T011 테스트 공용 **불변식 I-1 검증 헬퍼** 추가 — 이후 모든 통합 테스트가 종료 시 `balance == SUM(amount)`를 확인하도록 공용화

**Checkpoint**: 코인을 바꾸는 유일한 경로가 존재하고 트랜잭션·락·멱등 처리가 한 곳에 모였다

---

## Phase 3: User Story 1 — 코인을 받고 쓴다 (P0) 🎯 MVP

**Goal**: 가입 시 200코인, KST 당일 첫 인증 접속 시 50코인, 잔액 조회, 차감과 잔액 부족 거부

**Independent Test**: 가입 → 잔액 확인 → 차감 → 잔액과 원장이 일치

- [x] T012 [US1] `WalletService.openWallet` 구현 — 지갑 생성 + `INITIAL_GRANT:{userId}` 키로 200코인 원장 기록 (FR-002)
- [x] T013 [US1] `auth/RegistrationService.createMember`에서 `openWallet` 호출 — **같은 트랜잭션 안**에서 (R-11). 게스트 경로에서는 호출하지 않는다 (FR-003b)
- [x] T014 [US1] `wallet/DailyCoinGrantService.java` — KST 날짜 계산 후 `DAILY_GRANT:{userId}:{yyyy-MM-dd}` 키로 50코인 지급. Redis 키 `wallet:daily:{userId}:{date}`를 **캐시로만** 사용하고 TTL은 다음 KST 자정까지. **Redis 실패는 WARN 로그 후 DB 경로로 진행**한다 (R-04, R-05)
- [x] T015 [US1] `wallet/DailyCoinGrantInterceptor.java` + `wallet/WalletWebConfiguration.java` — `/api/v1/**`의 `preHandle`에서 SecurityContext JWT role이 `MEMBER`일 때만 지급 판정. 예상된 중복은 DEBUG, **그 외 실패는 ERROR 로그 + 원 요청은 계속 진행** (R-06)
- [x] T016 [US1] `WalletService.spend` 구현 — 지갑 락 → 잔액 확인 → 음수 금액 원장 INSERT → 잔액 UPDATE. 부족하면 `InsufficientCoinException`, **잔액 불변** (FR-008·FR-009)
- [x] T017 [US1] `wallet/WalletController.java` — `GET /api/v1/wallets/me`. GUEST는 403, 지갑 없는 회원은 404 + ERROR 로그 ([contracts/wallet-api.md](contracts/wallet-api.md))
- [x] T018 [P] [US1] `test/.../wallet/DailyGrantKstDateTest.java` — KST 자정 경계 단위 테스트. UTC 15:00(=KST 익일 00:00) 전후에서 날짜가 바뀌는지 확인
- [x] T019 [US1] `test/.../wallet/WalletServiceIntegrationTest.java` — 초기 지급 1회(FR-002) / 일일 지급 1회(FR-003) / 같은 KST 날짜 반복 요청 시 불변(SC-004) / 날짜 변경 시 재지급 / 차감 성공(AS1-5) / 잔액 부족 거부(AS1-6) / **매 시나리오 종료 시 I-1 확인**
- [x] T020 [US1] 게스트 검증 테스트 — GUEST 토큰으로 `GET /wallets/me` 호출 시 403이고 `wallets` 행이 생기지 않음 (FR-003b, I-5)

**Checkpoint**: US1 단독으로 배포 가능 — 가입·지급·조회·차감이 원장과 일치한 채 동작한다

---

## Phase 4: User Story 2 — 같은 요청이 두 번 와도 한 번만 (P0)

**Goal**: 재시도·더블클릭·동시 요청에서 중복 반영 0건, 음수 잔액 0건

**Independent Test**: 동일 차감 요청 2회 → 잔액 1회만 변함 / 동시 차감 2건 → 1건만 성공

- [x] T021 [US2] `applyEntry`의 UNIQUE 위반 경로 완성 — 기존 원장 항목을 조회해 **첫 요청과 동일한 `balanceAfter`** 를 반환한다. 새 원장 행을 만들지 않는다 (FR-007)
- [x] T022 [US2] `test/.../wallet/WalletConcurrencyIntegrationTest.java` — ① 같은 멱등성 키로 차감 2회 순차 호출 시 잔액 1회만 변함 (SC-002, US2-1) ② `CountDownLatch`로 동시 차감 2건, 잔액은 1건만 감당 → 1 성공 · 1 `InsufficientCoinException`, 잔액 ≥ 0 (SC-003, US2-2) ③ 동시 일일 지급 요청 N건 → `DAILY_GRANT` 원장 정확히 1행 (SC-004)
- [x] T023 [US2] 위 테스트를 **여러 회 반복 실행**해 경합이 우연히 통과하지 않았음을 확인한다

**Checkpoint**: 중복·동시 요청 방어가 증명됐다. 004(임대)가 이 서비스를 안전하게 호출할 수 있다

---

## Phase 5: User Story 3 — 거래 내역을 본다 (P1)

**Goal**: 지급·차감이 시각·사유와 함께 페이지 단위로 보인다

- [x] T024 [US3] `WalletService.history` 구현 — `created_at DESC, id DESC` 고정 정렬 (FR-012)
- [x] T025 [US3] `WalletController`에 `GET /api/v1/wallets/me/transactions` 추가 — `page`/`size`(1~100, 초과 시 400), 응답은 [contracts/wallet-api.md](contracts/wallet-api.md)의 필드·부호 규약 그대로
- [x] T026 [P] [US3] 내역 조회 통합 테스트 — 페이지 분할, 최신순 정렬, 빈 내역, `size` 범위 밖 400

**Checkpoint**: 사용자가 잔액 변화를 내역만으로 설명할 수 있다 (SC-005)

---

## Phase 6: 정합성 점검 · 관리자 조정 (FR-013 · FR-014)

- [x] T027 [P] `wallet/CoinReconciliationRun.java` + `wallet/CoinReconciliationRunRepository.java` — data-model.md §3 매핑
- [x] T028 `wallet/CoinReconciliationService.java` — 전체 지갑의 `balance`와 원장 `SUM(amount)`를 대조해 결과를 기록한다. **불일치를 자동 보정하지 않는다** (C-05, FR-014)
- [x] T029 `WalletService.adjustByAdmin` 구현 — `reason_type = ADMIN_ADJUSTMENT`, `reference_type = ADMIN_USER`, `reference_id = actorUserId`. 감액이 잔액을 음수로 만들면 거부 (FR-013)
- [x] T030 `test/.../wallet/CoinReconciliationIntegrationTest.java` — 정상 상태에서 불일치 0건 / `wallets.balance`를 JDBC로 직접 어긋나게 만든 뒤 실행 → 불일치 탐지·상세 기록되고 **잔액이 보정되지 않음** 확인
- [x] T031 관리자 조정 테스트 — 증액·감액이 원장에 남고 행위자가 기록되는지, 잔액 초과 감액이 거부되는지
- [x] T032 **REST 노출은 하지 않는다** — U-01(ADMIN 권한 모델 미정)을 `docs/HDD/코인_지갑_구현_정리.md`에 미결로 기록하고, 003 범위에서 관리자 endpoint를 만들지 않았음을 명시한다 (헌법 30조)

**Checkpoint**: SC-001을 감시할 수단이 생겼고 관리자 조정이 원장에 남는다

---

## Phase 7: Polish · 문서

- [x] T033 [P] `backend/bruno/03-wallet/` — 내 지갑 조회 · 내 거래 내역 조회 request 추가. 각 request의 Docs 탭에 목적·인증 규약·응답·게스트 403을 기존 `01-auth`/`02-users` 수준으로 작성
- [x] T034 [P] `backend/bruno/README.md`에 `03-wallet` 폴더와 실행 순서 반영
- [x] T035 [P] Swagger — 두 조회 endpoint에 `@SecurityRequirement(name = "bearerAuth")`와 응답 코드 문서화 (기존 `MyAccountController` 방식과 동일하게)
- [x] T036 [quickstart.md](quickstart.md) §1의 검증 표를 실제 테스트 이름과 대조해 빠진 항목이 없는지 확인
- [x] T037 `docs/HDD/코인_지갑_구현_정리.md` 작성 — 구현 결과, entry_type 매핑 규약, 미결 U-01·U-02, 소비 spec(004·012·014)이 지켜야 할 호출 규칙
- [x] T038 `docs/HDD/작업일지.md` 2026-08-19 섹션 갱신 (헌법 29조). 문제가 생겼다면 해결 여부와 무관하게 `docs/HDD/트러블슈팅.md`에 T-번호로 등록
- [x] T039 `specs/README.md`의 003 행에서 plan·tasks를 ✅로 갱신

---

## Dependencies

```text
Phase 1 (T001~T002)
   └─> Phase 2 (T003~T011)          ← 모든 Story의 선행
          ├─> Phase 3 US1 (T012~T020)
          │      └─> Phase 4 US2 (T021~T023)   ← US1의 spend 경로 위에서 검증
          ├─> Phase 5 US3 (T024~T026)          ← US1과 병렬 가능
          └─> Phase 6 (T027~T032)              ← US1과 병렬 가능
                 └─> Phase 7 (T033~T039)
```

- **T013은 T012 이후**: 지갑 생성 로직이 있어야 회원 생성에 연결한다
- **T015는 T014 이후**: 지급 서비스가 있어야 인터셉터가 호출한다
- **T021은 T010·T016 이후**: 공통 경로와 차감이 있어야 중복 경로를 완성한다
- **T028은 T001·T027 이후**: 테이블과 엔티티가 있어야 결과를 기록한다

## Parallel Execution

- Phase 2에서 T003·T004·T005·T006·T008·T009는 전부 다른 파일이라 병렬 가능
- Phase 3의 T018과 Phase 5의 T026은 다른 테스트 파일이라 병렬 가능
- Phase 7의 T033·T034·T035는 병렬 가능

## Implementation Strategy

**MVP는 Phase 1~4까지다.** US3(내역)과 정합성 점검이 없어도 004(임대)가 코인을 차감할 수 있고, 경제 무결성 요구(SC-001~004)는 Phase 4에서 이미 증명된다. Phase 5·6은 그 위에 독립적으로 얹는다.
