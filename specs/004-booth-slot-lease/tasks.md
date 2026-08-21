# Tasks: 부스 슬롯 / 임대

**Input**: `specs/004-booth-slot-lease/` — [spec.md](spec.md) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [contracts/](contracts/)

**Tests**: 포함한다. SC-001(슬롯 중복 임대 0건)·SC-002(코인만 차감 0건)가 **동시성 요구**라서 테스트 없이는 충족을 증명할 수 없다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이라 병렬 가능
- **[Story]**: US1(빈 부스를 임대한다) / US2(한 슬롯은 한 사람만) / US3(만료되면 정리된다)

## Path Conventions

백엔드 경로는 `backend/src/main/java/com/example/ssafesta/`, 테스트는 `backend/src/test/java/com/example/ssafesta/`.

---

## Phase 1: Setup

- [x] T001 `backend/src/main/resources/db/migration/V5__booth_slot_seed.sql` — USER_RENTAL 슬롯 7개를 11층에 시딩 (`F11-R01`~`F11-R07`). 재실행 안전하게 `slot_code` 충돌 시 무시. **테이블은 V1에 이미 있으므로 만들지 않는다**
- [x] T002 [P] `booth/LeaseProperties.java` — `@ConfigurationProperties("app.lease")`로 가격 100코인, 기간 24시간을 외부화하고 `application-local.yml`에 기본값 추가

**Checkpoint**: 슬롯이 존재하고 가격·기간이 설정으로 분리됐다

---

## Phase 2: Foundational (Blocking)

**⚠️ 이 단계가 끝나기 전에는 어떤 User Story도 시작할 수 없다**

- [x] T003 [P] `booth/SlotType.java`(`USER_RENTAL`/`ADMIN`) · `booth/LeaseStatus.java`(`ACTIVE`/`EXPIRED`) · `booth/BoothStatus.java`(`ACTIVE`/`INACTIVE`)
- [x] T004 [P] `booth/BoothSlot.java` — `booth_slots` 매핑. **점유 상태를 갖지 않는다** (활성 임대의 존재가 곧 점유, research R-02)
- [x] T005 [P] `booth/Booth.java` — `booths` 매핑. `owner_user_id`가 주인이고 `current_slot_id`는 임대 중에만 채워진다. `attachSlot`/`detachSlot` 메서드로만 변경 (data-model §2)
- [x] T006 [P] `booth/BoothLease.java` — `booth_leases` 매핑. 유일한 변경은 `expire()` 상태 전이. `ends_at`은 생성자가 `starts_at + 기간`으로 계산한다 (I-4)
- [x] T007 [P] `booth/BoothSlotRepository.java` — 전체 조회(층·코드 정렬), `findById`
- [x] T008 `booth/BoothLeaseRepository.java` — **만료 판정 술어를 여기에만 둔다**: 슬롯별 유효 활성 임대 조회, 사용자별 유효 활성 임대 조회, 전체 유효 활성 임대 목록, 슬롯의 만료된 `ACTIVE` 임대 조회. 서비스가 `status`를 직접 비교하지 않게 한다 (research R-03)
- [x] T009 [P] `booth/BoothRepository.java` — `findByOwnerUserId`, `findByCurrentSlotId`
- [x] T010 [P] 예외 4종 — `SlotNotRentableException` · `SlotAlreadyLeasedException` · `ActiveLeaseLimitException` · `BoothExpiredException`. 각각 [contracts/lease-api.md](contracts/lease-api.md)의 오류 코드를 담는다

**Checkpoint**: 엔티티와 만료 술어가 한 곳에 모였다

---

## Phase 3: User Story 1 — 빈 부스를 임대한다 (P0) 🎯 MVP

**Goal**: 코인으로 빈 슬롯을 임대하고, 잔액 부족·한도 초과·게스트를 거부한다

**Independent Test**: 빈 슬롯 확인 → 임대 → 코인 차감 → 내 부스로 표시

- [x] T011 [US1] `booth/BoothLeaseService.java` — `lease(userId, slotId, durationDays)`를 **하나의 `@Transactional`** 로 구현. 순서는 [contracts/lease-api.md](contracts/lease-api.md)의 처리 순서 그대로: 슬롯 검증 → 슬롯 점유 검증 → 사용자 한도 검증 → Booth 확보·연결 → 임대 INSERT → `WalletService.spend`. **003은 `REQUIRED` 전파라 그대로 참여한다** (research R-01)
- [x] T012 [US1] 차감 호출 — 멱등성 키 `LEASE_PAYMENT:BOOTH_LEASE:{leaseId}`, `reasonType=LEASE_PAYMENT`, `referenceType=BOOTH_LEASE`. **금액은 서버 정책에서만 유도**한다 (헌법 16조, research R-04)
- [x] T013 [US1] Booth 확보 로직 — 사용자의 Booth가 있으면 재사용하고 없으면 생성한다. **다른 사용자의 Booth는 절대 연결하지 않는다** (C-01, I-5)
- [x] T014 [US1] `booth/BoothQueryService.java` — 슬롯 목록(`status`·`remainingSeconds`·`entryAvailable`·`mine`), 내 부스, 부스 상세
- [x] T015 [US1] `booth/BoothSlotController.java` — `GET /api/v1/booth-slots`(비인증 허용) · `POST /api/v1/booth-slots/{slotId}/leases`. `durationDays`는 1만 허용, 게스트는 403 (FR-016)
- [x] T016 [US1] `booth/BoothController.java` — `GET /api/v1/booths/mine`(부스 없으면 204) · `GET /api/v1/booths/{boothId}`
- [x] T017 [US1] `SecurityConfiguration`에 `GET /api/v1/booth-slots` 공개 허용 추가 — 게스트 관람 경로. **임대 endpoint는 인증 유지**
- [x] T018 [US1] `test/.../booth/BoothLeaseServiceIntegrationTest.java` — 임대 성공(코인 차감+임대+슬롯 연결) / 잔액 부족 거부 시 **임대·코인 둘 다 불변** / 활성 임대 한도 거부(D01) / USER_RENTAL 아닌 슬롯 거부 / `ends_at = starts_at + 24h`(I-4) / **매 시나리오 종료 시 003의 잔액-원장 일치 확인**
- [x] T019 [US1] `test/.../booth/BoothApiIntegrationTest.java` — 슬롯 목록 응답 형태, 게스트 임대 403 + 임대 0건, `durationDays` 검증 400, `GET /booths/mine` 204

**Checkpoint**: US1 단독 배포 가능 — 임대가 원장과 일치한 채 동작한다

---

## Phase 4: User Story 2 — 한 슬롯은 한 사람만 (P0)

**Goal**: 동시 임대에서 슬롯 중복 0건, 코인만 차감 0건

**Independent Test**: 두 사용자가 같은 슬롯에 동시 요청 → 한 명만 성공, 실패한 쪽 코인 불변

- [x] T020 [US2] `ux_booth_leases_active_slot` 제약 위반을 `SlotAlreadyLeasedException`으로 변환 — 사전 검사는 친절한 오류용이고 **최종 방어는 제약**이다. 위반 시 트랜잭션 전체가 롤백되는 것이 정확한 동작이다 (research R-02)
- [x] T021 [US2] 재요청 안전 (FR-018) — 요청자가 이미 그 슬롯의 유효한 임차인이면 새로 차감하지 않고 기존 임대를 `200`으로 반환한다
- [x] T022 [US2] `test/.../booth/BoothLeaseConcurrencyIntegrationTest.java` — ① `CountDownLatch`로 두 사용자가 같은 빈 슬롯에 동시 임대 → **정확히 1건 성공**(SC-001) ② **실패한 사용자의 잔액이 불변**이고 원장에 항목이 없다(SC-002, US2-2) ③ 같은 사용자가 같은 슬롯에 동시 2회 → 임대 1건, 차감 1회(FR-018)
- [x] T023 [US2] 위 테스트를 **반복 실행**해 경합이 우연히 통과하지 않았음을 확인한다

**Checkpoint**: 슬롯 독점과 코인 보전이 증명됐다

---

## Phase 5: User Story 3 — 만료되면 정리된다 (P0)

**Goal**: 만료 부스 입장 차단, 콘텐츠 보존, 재임대 시 빈 상태로 시작

- [x] T024 [US3] 만료 판정을 조회 경로 전체에 적용 — 슬롯 목록 `status`/`entryAvailable`, 활성 임대 한도 검사, 내 부스 조회. **모두 T008의 술어를 경유**한다 (I-3, SC-003)
- [x] T025 [US3] 재임대 시 상태 전이 (FR-017) — 그 슬롯의 만료된 `ACTIVE` 임대를 `EXPIRED`로 바꾸고 이전 Booth의 `current_slot_id`를 해제한 뒤 새 임대를 만든다. **같은 트랜잭션.** 이게 없으면 `ux_booth_leases_active_slot`과 `current_slot_id` UNIQUE 때문에 재임대가 영구히 막힌다
- [x] T026 [US3] `GET /booths/{boothId}`의 만료 응답 (FR-019) — `409 BOOTH_LEASE_EXPIRED` + "임대가 만료된 부스입니다." **조용히 빈 화면을 주지 않는다**
- [x] T027 [US3] `test/.../booth/BoothExpiryIntegrationTest.java` — 만료 임대의 슬롯이 `AVAILABLE`·`entryAvailable=false`(SC-003) / `GET /booths/{id}` 409 / **다른 사용자 재임대 성공 + 이전 임대 `EXPIRED` 전이**(FR-017) / **재임대자가 이전 소유자 Booth를 받지 않음**(SC-004, I-5) / 이전 소유자의 Booth·콘텐츠 보존(FR-010) / 만료 임대가 한도를 점유하지 않음(I-3)

**Checkpoint**: 만료·재임대가 콘텐츠 격리를 유지한 채 동작한다

---

## Phase 6: Polish · 문서

- [x] T028 [P] `backend/bruno/04-booth-lease/` — 슬롯 목록 · 부스 임대 · 내 부스 · 부스 상세 request 추가. Docs 탭에 목적·오류 코드·게스트 403·만료 409를 기존 폴더 수준으로 작성
- [x] T029 [P] `backend/bruno/README.md`에 `04-booth-lease` 폴더와 실행 순서 반영
- [x] T030 [P] Swagger — 인증 필요한 endpoint에 `@SecurityRequirement(name = "bearerAuth")`와 응답 코드 문서화
- [x] T031 [quickstart.md](quickstart.md) §1의 검증 표를 실제 테스트 이름과 대조해 빠진 항목이 없는지 확인
- [x] T032 `docs/HDD/부스_임대_구현_정리.md` 작성 — 구현 결과, 만료 술어 규약, 소비 spec(005·006·015)이 알아야 할 것, 미결 U-03·U-04·U-05
- [x] T033 `docs/HDD/작업일지.md` 갱신 (헌법 29조). 문제가 생기면 해결 여부와 무관하게 `docs/HDD/트러블슈팅.md`에 T-번호로 등록
- [x] T034 `specs/README.md`의 004 행에서 spec 확정·plan·tasks를 ✅로 갱신

---

## Dependencies

```text
Phase 1 (T001~T002)
   └─> Phase 2 (T003~T010)          ← 모든 Story의 선행
          └─> Phase 3 US1 (T011~T019)
                 ├─> Phase 4 US2 (T020~T023)   ← US1의 임대 경로 위에서 검증
                 └─> Phase 5 US3 (T024~T027)   ← US1 이후, US2와 병렬 가능
                        └─> Phase 6 (T028~T034)
```

- **T011은 T008 이후**: 만료 술어가 있어야 점유·한도를 판정한다
- **T012는 T011 이후**: 임대 id가 있어야 멱등성 키를 만든다
- **T025는 T011 이후**: 임대 생성 경로 안에 전이가 들어간다
- **T020은 T011 이후**: 제약 위반 변환은 임대 경로의 일부다

## Parallel Execution

- Phase 2에서 T003·T004·T005·T006·T007·T009·T010은 다른 파일이라 병렬 가능
- Phase 4의 T022와 Phase 5의 T027은 다른 테스트 파일이라 병렬 가능
- Phase 6의 T028·T029·T030은 병렬 가능

## Implementation Strategy

**MVP는 Phase 1~4까지다.** 만료 처리(US3)가 없어도 임대 자체는 동작하고, 경제 무결성 요구(SC-001·SC-002)는 Phase 4에서 증명된다. 다만 **US3의 T025(상태 전이)가 없으면 재임대가 불가능**하므로 실사용 전에는 Phase 5까지 필요하다.
