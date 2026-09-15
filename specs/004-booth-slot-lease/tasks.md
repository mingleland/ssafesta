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

- [x] T001 `backend/src/main/resources/db/migration/V5__booth_slot_seed.sql` — USER_RENTAL 슬롯 7개를 11층에 시딩 (`F11-R01`~`F11-R07`). 재실행 안전하게 `slot_code` 충돌 시 무시. *(→ V12가 `F11-R08`~`F11-R12`를 추가해 12개로 확장, `slotId` 1~12를 Unity 앵커에 대응 고정 — #62, T057)* **테이블은 V1에 이미 있으므로 만들지 않는다**
- [x] T002 [P] `booth/LeaseProperties.java` — `@ConfigurationProperties("app.lease")`로 가격 100코인, 기간 24시간을 외부화하고 `application-local.yml`에 기본값 추가

**Checkpoint**: 슬롯이 존재하고 가격·기간이 설정으로 분리됐다

---

## Phase 2: Foundational (Blocking)

**⚠️ 이 단계가 끝나기 전에는 어떤 User Story도 시작할 수 없다**

- [x] T003 [P] `booth/SlotType.java`(`USER_RENTAL`/`ADMIN`/`EVENT` — `EVENT`는 V28에서 추가, S15P21A604-615) · `booth/LeaseStatus.java`(`ACTIVE`/`EXPIRED`) · `booth/BoothStatus.java`(`ACTIVE`/`INACTIVE`)
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
- [x] T035 [US3] **보완 (2026-09-09, S15P21A604-152)** — 만료 상태 전이 주기 배치 `BoothLeaseExpirySweeper`. spec C-02·plan 이 004 범위에서 배치를 넣지 않은 것은 `research.md`가 *"배치는 정리 작업일 뿐이다 … 필요해지면 이 위에 얹을 수 있다"*로 열어 둔 결정이고, 그 위에 얹었다. **권위는 그대로 읽기 시 판정**(T024)이다. T025 가 만든 전이를 공용 메서드로 추출해 lazy 두 경로와 배치가 같은 메서드를 지난다 — spec 007 FR-041 이 같은 트랜잭션을 요구하는 S15P21A604-496 이 붙을 자리를 한 곳으로 만든다. 전역 조회는 `FOR UPDATE SKIP LOCKED` + batch 상한, 부스 detach 는 그 임대의 슬롯을 가리킬 때만. `Booth`에 `@DynamicUpdate` — 잠금 없는 편집 경로(`requireActiveEditor`)의 전체 컬럼 UPDATE 가 배치가 끊은 슬롯 연결을 되살리기 때문이다. 테스트 `BoothLeaseExpirySweeperIntegrationTest` 23건
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
                 └─> Phase 5 US3 (T024~T027, T035)   ← US1 이후, US2와 병렬 가능
                        └─> Phase 6 (T028~T034)
```

- **T011은 T008 이후**: 만료 술어가 있어야 점유·한도를 판정한다
- **T012는 T011 이후**: 임대 id가 있어야 멱등성 키를 만든다
- **T025는 T011 이후**: 임대 생성 경로 안에 전이가 들어간다
- **T035는 T025 이후**: 배치는 T025 가 만든 전이를 공용 메서드로 뽑아 같이 쓴다
- **T020은 T011 이후**: 제약 위반 변환은 임대 경로의 일부다

## Parallel Execution

- Phase 2에서 T003·T004·T005·T006·T007·T009·T010은 다른 파일이라 병렬 가능
- Phase 4의 T022와 Phase 5의 T027은 다른 테스트 파일이라 병렬 가능
- Phase 6의 T028·T029·T030은 병렬 가능

## Implementation Strategy

**MVP는 Phase 1~4까지다.** 만료 처리(US3)가 없어도 임대 자체는 동작하고, 경제 무결성 요구(SC-001·SC-002)는 Phase 4에서 증명된다. 다만 **US3의 T025(상태 전이)가 없으면 재임대가 불가능**하므로 실사용 전에는 Phase 5까지 필요하다.

---

## Phase 7 — US4 부스 반납 (조기 취소) · 2026-09-14 추가

spec 004에 D12·FR-020·FR-021·User Story 4가 들어오면서 붙은 작업이다. **위 T001~T035는 그대로 유효하고 지우지 않는다** — 반납은 그 위에 얹힌다.

핵심은 **새 해제 경로를 만들지 않는 것**이다. T025/T035가 만든 공용 만료 메서드를 상태 인자로 일반화해 반납이 같은 자리를 지나게 한다. 두 번째 해제 경로는 나중에 한쪽만 고치는 자리가 된다.

| ID | 상태 | 작업 | 파일 |
|---|---|---|---|
| T036 | [X] | `LeaseStatus`에 `CANCELLED` 추가 + javadoc 정정("no cancellation" 문구 제거). **마이그레이션 없음** — `status`는 `VARCHAR(20)`이고 CHECK 제약이 없으며, 부분 유니크 인덱스 둘 다 `status='ACTIVE'`만 본다 | `booth/LeaseStatus.java` |
| T037 | [X] | `BoothLease.cancel()` 추가 (`expire()` 옆, 상태만 다름) | `booth/BoothLease.java` |
| T038 | [X] | `findActiveByLesseeUserIdForUpdate(userId)` 신설 — `PESSIMISTIC_WRITE`, **시간 술어 없음**. 시간 판정은 기존 `findValidByLesseeUserId`가 계속 맡는다(유효성 규칙은 Repository 한 곳) | `booth/BoothLeaseRepository.java` |
| T039 | [X] | private `expire(BoothLease)` → `release(BoothLease, LeaseStatus)` 일반화. 기존 만료 3경로는 `EXPIRED`로 호출. **만료 로그 문구는 글자 그대로 유지** — `BoothLeaseExpirySweeperIntegrationTest`가 문자열 원본을 단언한다 | `booth/BoothLeaseService.java` |
| T040 | [X] | `cancel(userId, slotId)` 신설 — 지갑 락 → 임대 행 락 → 락 뒤 유효성 재조회 → 슬롯 일치 확인 → `release(.., CANCELLED)`. **지갑은 건드리지 않는다**(FR-021) | `booth/BoothLeaseService.java` |
| T041 | [X] | `BoothDocumentDeactivationService.deactivate(boothId, Cause)` — 사람이 읽는 문구·로그만 원인별로 가른다. **기계용 `last_error_code`는 `BOOTH_LEASE_EXPIRED` 유지**(AI 파트와 공유하는 어휘, "유효한 임대 없음"으로 재정의) | `ai/BoothDocumentDeactivationService.java` |
| T042 | [X] | `ACTIVE_LEASE_NOT_FOUND` + `ActiveLeaseNotFoundException`. `ACTIVE_LEASE_LIMIT` 메시지에 반납 경로 반영 | `common/ErrorCode.java`, `booth/ActiveLeaseNotFoundException.java` |
| T043 | [X] | `DELETE /api/v1/booth-slots/{slotId}/leases/mine` → `204`. Swagger 포함 | `booth/BoothSlotController.java` |
| T044 | [X] | 반납 한 바퀴 + 거부 3경로 통합테스트. **"다른 회원이 그 자리를 빌린다"가 detach를 증명하는 줄**이다 — 같은 회원의 재임대만 보면 `detachSlot` 누락도 통과한다 | `BoothApiIntegrationTest` |
| T045 | [X] | 락 프로토콜 경합 테스트 2종(반납 선점 / 배치 선점). `findStaleActive`의 `moment`에 미래 시각을 넘겨 `Clock` 주입 없이 고정한다. **`@Lock` 제거 변이로 10건 실패 확인** | `BoothLeaseConcurrencyIntegrationTest` |
| T046 | [X] | 반납 시 AI Job `CANCELLED`·문서 `DISABLED`·staging/chunk 정리·FastAPI cancel 호출 수 검증 | `BoothLeaseExpiryAiDocumentIntegrationTest` |
| T047 | [X] | 문서: spec 004(spec·data-model·contract) · spec 007 FR-015·FR-041 · spec 008 FR-024·FR-026 · docs/02 BOOTH-09 · docs/08 · bruno | — |

### 범위 밖 (Phase 7에서 하지 않는다)

- **코인 환불** — D06이 유지된다 (FR-021)
- **관리자 강제 회수** — admin 권한 모델 미결, `S15P21A604-736`
- **열린 AI Conversation 즉시 차단** — spec 008 FR-024가 대화 생성 시 저장한 `leaseEndsAt`을 보므로 반납이 즉시 끊지 못한다. 실질 노출이 없고(신규 대화 차단 + 문서 `DISABLED`로 "자료 미준비" 안내) 30분 유휴 TTL이 닫으므로 **한계를 문서화하는 쪽으로 확정**했다 (spec 004 Edge Cases)
- **월드 실시간 전파** — 만료와 같은 구멍이다 (FR-019)
