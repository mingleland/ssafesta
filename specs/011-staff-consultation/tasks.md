---

description: "Task list for 011-staff-consultation"
---

# Tasks: 직원 / 사람 상담

**Input**: Design documents from `/specs/011-staff-consultation/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: 포함한다. spec의 Success Criteria 6건이 전부 "사례가 0건" 형태의 **동시성·권한 불변식**이라 실행으로만 확인된다. 저장소 관례도 같다(develop 스위트 1195건, Testcontainers 통합 테스트).

**Organization**: 사용자 스토리 단위. 세 스토리 모두 P1이지만 **의존 순서가 우선순위를 이긴다** — US1(상담 인계)은 직원 행이 있어야 성립하므로 US3(초대·권한)이 먼저다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능 (다른 파일, 미완료 작업에 의존하지 않음)
- **[Story]**: US1 / US2 / US3
- 모든 작업에 정확한 파일 경로를 적는다

## Path Conventions

Web app — 백엔드는 `backend/src/main/java/com/example/ssafesta/`, 테스트는 `backend/src/test/java/com/example/ssafesta/`. 마이그레이션은 `backend/src/main/resources/db/migration/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 의존성과 패키지 골격

- [ ] T001 `backend/pom.xml` 에 `spring-boot-starter-websocket` 의존성 추가 (research R-05)
- [ ] T002 [P] `backend/src/main/java/com/example/ssafesta/staff/` 패키지 생성
- [ ] T003 [P] `backend/src/main/java/com/example/ssafesta/consultation/` 및 `consultation/ws/` 패키지 생성

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 딛는 스키마·권한 기반

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 어떤 스토리도 시작하지 않는다. 특히 T006 전에 초대 API를 열면 `CONSULTANT` 가 남의 부스 문서·설문까지 만지게 된다.

- [x] T004 `backend/src/main/resources/db/migration/V31__booth_staff_role_vocabulary.sql` — `booth_staffs.role` CHECK. **T006 이 딛는 부분만 넣는다** ([data-model.md](./data-model.md))
- [ ] T004b `booth_staffs.consultation_status` 컬럼과 `staff_invitations` 상태·역할 CHECK — Phase 3·4 가 행을 만들 때 함께 (V32)
- [ ] T004c `ux_consultations_active_staff` 부분 유니크 인덱스와 `ix_consultations_booth_status`, `consultations` 상태 CHECK — Phase 5 에서 (V33)
- [x] T005 [P] `backend/src/main/java/com/example/ssafesta/booth/StaffRole.java` — 역할 enum(`ADMIN`·`CONTENT_EDITOR`·`CONSULTANT`)과 `mayEditBoothContent()`. **`staff/` 가 아니라 `booth/` 다** — `booth_staffs.role` 컬럼의 의미이고 유일한 소비자인 `BoothAccessGuard` 가 거기 있다. `staff/` 가 이것을 import 한다 (FR-002, C-09)
- [x] T006 `backend/src/main/java/com/example/ssafesta/booth/BoothAccessGuard.java` 의 `requireEditor` 에 역할 게이트를 태운다 — 현재는 행 존재만 본다. `CONSULTANT` 는 거부하고 Owner·`ADMIN`·`CONTENT_EDITOR` 만 통과시킨다
- [x] T007 `backend/src/test/java/com/example/ssafesta/booth/BoothAccessGuardRoleTest.java` — 역할별 통과·거부 매트릭스. **`requireEditor` 를 쓰는 경로 전부(Layout 2곳·LayoutQuery·AiAgent 2곳·AiDocument·Survey·SurveyResult·Project)가 같은 판정을 받는지** 함께 고정한다
- [ ] T008 [P] 기존 `ErrorCode` enum에 이 spec의 오류 코드 추가 — `STAFF_INVITATION_PENDING`·`STAFF_ALREADY_MEMBER`·`STAFF_INVITATION_FORBIDDEN`·`STAFF_INVITATION_NOT_PENDING`·`STAFF_OWNER_IMMUTABLE`·`CONSULTATION_ALREADY_ACTIVE`·`CONSULTATION_NOT_REQUESTED`·`CONSULTATION_REQUEST_PENDING`. `GUEST_FORBIDDEN`·`BOOTH_LEASE_EXPIRED`·`BOOTH_FORBIDDEN`은 기존 코드를 재사용한다(신설 금지, docs/08 §1.3)
- [ ] T009 [P] 기존 springdoc 스키마 충돌 검증 테스트 갱신 — 새 DTO가 단순 이름 충돌을 일으키지 않는지 (T-148 재발 방지)

**Checkpoint**: V31이 적용되고 역할 게이트가 서면 US3를 시작할 수 있다.

---

## Phase 3: US3 — 직원을 초대하고 권한을 준다 (P1)

**Goal**: Owner가 직원을 초대하고 역할을 부여한다. 상담 없이도 독립적으로 완결된다.

**Independent Test**: [quickstart.md](./quickstart.md) 시나리오 1 — 초대 → 조회 → 수락 → 목록. 수락 후에도 `CONSULTANT` 의 Layout 편집이 `403` 이다.

### 테스트

- [ ] T010 [P] [US3] `backend/src/test/java/com/example/ssafesta/staff/StaffInvitationApiIntegrationTest.java` — 초대·조회·수락·취소 왕복과 오류 5종
- [ ] T011 [P] [US3] `backend/src/test/java/com/example/ssafesta/staff/StaffPermissionMatrixTest.java` — 권한 매트릭스 (SC-005). Lease 경로가 남의 부스에 닿지 않는 것(FR-003)도 함께 고정한다

### 구현

- [ ] T012 [P] [US3] `backend/src/main/java/com/example/ssafesta/staff/StaffInvitation.java` 엔티티 — `staff_invitations` 매핑, 상태 전이 메서드
- [ ] T013 [P] [US3] `backend/src/main/java/com/example/ssafesta/staff/StaffInvitationRepository.java` — 대기 중 초대 조회(본인·부스별), 만료 대상 조회
- [ ] T014 [US3] `backend/src/main/java/com/example/ssafesta/staff/StaffInvitationService.java` — 초대 생성(48시간 `expires_at`), 수락 시 `booth_staffs` 행 생성, Owner 취소
- [ ] T015 [US3] `backend/src/main/java/com/example/ssafesta/staff/StaffInvitationController.java` — `POST /booths/{boothId}/staff-invitations`, `GET /staff-invitations/mine`, `POST /staff-invitations/{id}/accept`, `DELETE /booths/{boothId}/staff-invitations/{id}`
- [ ] T016 [US3] `backend/src/main/java/com/example/ssafesta/staff/StaffController.java` — `GET /booths/{boothId}/staff`(Owner를 읽기 전용 `OWNER` 행으로 합성, FR-018), `PATCH`·`DELETE /booths/{boothId}/staff/{userId}`
- [ ] T017 [US3] `backend/src/main/java/com/example/ssafesta/staff/StaffInvitationExpirySweeper.java` — `@Scheduled` 로 `PENDING` + `expires_at < now()` 를 `EXPIRED` 로 (C-07, research R-07)
- [ ] T018 [P] [US3] `backend/src/test/java/com/example/ssafesta/staff/StaffInvitationExpirySweeperTest.java` — 만료 fixture로 전이 1회 확인

**Checkpoint**: US3 단독 배포 가능. 직원 행이 생기고 역할이 지켜진다.

---

## Phase 4: US2 — 담당자가 없어도 막히지 않는다 (P1)

**Goal**: 전원 오프라인이어도 AI 상담이 계속 되고, 사람 상담 버튼의 가부가 상태로 드러난다.

**Independent Test**: [quickstart.md](./quickstart.md) 시나리오 5 — 전원 `OFFLINE` 에서 AI 대화·문서 검색이 100% 동작한다 (SC-002).

### 테스트

- [ ] T019 [P] [US2] `backend/src/test/java/com/example/ssafesta/staff/StaffPresenceApiTest.java` — `AVAILABLE`·`AWAY`·`OFFLINE` 전환, **`BUSY` 직접 지정은 400** (research R-04)
- [ ] T020 [P] [US2] `backend/src/test/java/com/example/ssafesta/consultation/AiAvailabilityUnaffectedTest.java` — 전원 오프라인에서 AI 경로 정상 (SC-002, 헌법 3조)

### 구현

- [ ] T021 [US2] `backend/src/main/java/com/example/ssafesta/booth/BoothStaff.java` 에 `consultationStatus` 필드 매핑 추가 (기본 `OFFLINE`)
- [ ] T022 [US2] `backend/src/main/java/com/example/ssafesta/staff/StaffPresenceService.java` — 명시적 전환만 허용, `BUSY` 는 서버 전용
- [ ] T023 [US2] `backend/src/main/java/com/example/ssafesta/staff/StaffController.java` 에 `PUT /booths/{boothId}/staff/me/presence` 추가
- [ ] T024 [US2] `GET /booths/{boothId}/staff` 응답에 `consultationStatus` 노출 — `OWNER` 행은 `null` ([contracts/staff-consultation-api.md](./contracts/staff-consultation-api.md))

**Checkpoint**: 직원 가용 상태가 서버에 있고 FE가 버튼 활성/비활성을 판단할 근거가 생긴다.

---

## Phase 5: US1 — AI에서 사람으로 넘어간다 (P1)

**Goal**: 방문자 요청 → 직원 한 명이 수락 → 종료. **요약 없이 먼저 연다**(research R-02).

**Independent Test**: [quickstart.md](./quickstart.md) 시나리오 2·3·4·6·7·8.

### 5-1. 요청·수락·종료 (요약 없이)

- [ ] T025 [P] [US1] `backend/src/test/java/com/example/ssafesta/consultation/ConsultationConcurrencyIntegrationTest.java` — **두 직원 동시 수락에 정확히 한 명만 성공**(SC-001)과 **직원당 활성 1건**(SC-006). 둘 다 동시 요청으로 던진다
- [ ] T026 [P] [US1] `backend/src/test/java/com/example/ssafesta/consultation/ConsultationApiIntegrationTest.java` — 요청·취소·대기열·수락·종료 왕복, 게스트 `403`(FR-014), 만료 부스 `409`
- [ ] T027 [P] [US1] `backend/src/main/java/com/example/ssafesta/consultation/Consultation.java` 엔티티 — `consultations` 매핑, `REQUESTED→ACCEPTED→ENDED`·`EXPIRED` 전이
- [ ] T028 [P] [US1] `backend/src/main/java/com/example/ssafesta/consultation/ConsultationRepository.java` — 대기열 조회, **조건부 수락 갱신**(`WHERE status='REQUESTED'`, research R-03), 만료 대상 조회
- [ ] T029 [US1] `backend/src/main/java/com/example/ssafesta/consultation/ConsultationService.java` — 요청 생성(10분 만료·게스트 차단·임대 확인), 수락(조건부 갱신 + 활성 1건 제약 위반을 `409 CONSULTATION_ALREADY_ACTIVE` 로 번역 + `BUSY` 전환), 종료(양쪽 누구나 + 직전 presence 복귀)
- [ ] T030 [US1] `backend/src/main/java/com/example/ssafesta/consultation/ConsultationController.java` — `POST /consultation/requests`, `DELETE /consultation/requests/{requestId}`, `GET /booths/{boothId}/consultation/requests`, `POST /consultation/requests/{requestId}/accept`, `POST /consultation/sessions/{sessionId}/end`. **id는 문자열로 직렬화하고 `accept` 응답에 `requestId`·`sessionId` 를 함께 싣는다**(research R-06)
- [ ] T031 [US1] `backend/src/main/java/com/example/ssafesta/consultation/ConsultationExpirySweeper.java` — `@Scheduled` 로 10분 경과 `REQUESTED` 를 `EXPIRED` 로 + `expired` 이벤트 발행 (C-01)
- [ ] T032 [P] [US1] `backend/src/test/java/com/example/ssafesta/consultation/ConsultationExpirySweeperTest.java` — 만료 fixture, 만료된 요청의 `accept` 가 `409`

### 5-2. WS Token 과 STOMP 알림

- [ ] T033 [P] [US1] `backend/src/test/java/com/example/ssafesta/consultation/ws/WsTokenApiTest.java` — 5분 만료, **URL query 토큰 거부**, Access Token 재사용 거부, 연결 후 만료로 끊지 않음 (FR-019·FR-020, 헌법 13조)
- [ ] T034 [P] [US1] `backend/src/main/java/com/example/ssafesta/consultation/ws/WsTokenService.java` — Redis 5분 TTL 발급·검증 (기존 Refresh Token 저장소 재사용)
- [ ] T035 [P] [US1] `backend/src/main/java/com/example/ssafesta/consultation/ws/WsTokenController.java` — `POST /consultation/ws-token`
- [ ] T036 [US1] `backend/src/main/java/com/example/ssafesta/consultation/ws/WebSocketConfig.java` — `/ws/consultation` native WebSocket + STOMP 등록, **SockJS 미등록**, in-memory simple broker (C-05, research R-05)
- [ ] T037 [US1] `backend/src/main/java/com/example/ssafesta/consultation/ws/StompAuthChannelInterceptor.java` — `CONNECT` 의 `Authorization: Bearer` 검증, 실패 시 연결 거부
- [ ] T038 [US1] `backend/src/main/java/com/example/ssafesta/consultation/ws/ConsultationEventPublisher.java` — 방문자 `/user/queue/consultation`(`accepted`·`expired`·`ended`), 직원 `/topic/booths/{boothId}/consultation`(`requested`·`cancelled`·`expired`·`taken`). 봉투 `{type, requestId, occurredAt, …}`
- [ ] T039 [P] [US1] `backend/src/test/java/com/example/ssafesta/consultation/ws/ConsultationEventPublisherTest.java` — 수락 시 **진 직원들에게 `taken`** 이 가고 방문자에게 `accepted` 가 간다

### 5-3. 요약 조달 (`S15P21A604-139` 도착 후)

- [ ] T040 [P] [US1] `backend/src/main/java/com/example/ssafesta/consultation/HandoffSummaryClient.java` — 인터페이스와 **항상 `null` 을 반환하는 1차 구현**. 요청 생성 경로는 이 반환값을 그대로 저장한다
- [ ] T041 [P] [US1] `backend/src/test/java/com/example/ssafesta/consultation/HandoffSummaryNullPathTest.java` — 요약이 `null` 이어도 요청·수락·종료가 전부 동작한다 (quickstart 시나리오 8, 헌법 3조)
- [ ] T042 [US1] **`-139` 도착 후**: `HandoffSummaryClient` 를 FastAPI 호출 구현으로 교체. `conversationId` 로 요약을 받아 **요청 생성 시점 스냅샷**으로 굳히고, 실패·timeout은 `null` 로 흘린다

**Checkpoint**: US1 완료. spec의 P1 범위가 전부 선다.

---

## Phase 6: Polish & Cross-Cutting

- [ ] T043 `docs/08_Backend_API_명세서.md` §12를 확정 계약에 맞춘다 — 현재 §12는 다른 경로를 적고 있다 (research R-01). §10에 `GET /staff-invitations/mine`(FR-016)과 Owner 취소(FR-017) 추가, §11 Presence에 `BUSY` 서버 전용 규칙 명시
- [ ] T044 [P] Realtime 명세(`docs/08` §12가 참조하는 docs/16 §11)에 STOMP 구독·이벤트 봉투 반영
- [ ] T045 [P] `specs/011-staff-consultation/spec.md` 리뷰 표의 BE 검토칸 서명
- [ ] T046 헌법 24조 통보 — FE·AI에 `docs/08` §12 정정 사실을 알린다(경로 변경이 아니라 **문서를 실제 계약에 맞추는 것**임을 명시). GitLab #133·#176에 회신
- [ ] T047 `cd backend && ./mvnw -B test` 전 스위트 통과 확인 (기준선 1195건 + 이 spec의 신규 테스트)

---

## Dependencies

```text
Phase 1 (Setup)
   └─> Phase 2 (Foundational)  ← T006 역할 게이트가 여기 있는 것이 핵심
          ├─> Phase 3 (US3 초대·권한)          단독 배포 가능
          │      └─> Phase 5 (US1 상담)        직원 행이 있어야 수락자가 존재한다
          └─> Phase 4 (US2 Presence)           US3와 병렬 가능
                 └─> Phase 5 (US1)             BUSY 전환이 presence 위에 선다
Phase 5 ─> Phase 6 (Polish)
```

**스토리 간 의존**

- US3 → US1: 수락할 직원이 있어야 상담이 성립한다.
- US2 → US1: `BUSY` 자동 전환이 presence 어휘 위에 선다. US2 없이 US1을 열면 수락해도 상태가 남지 않는다.
- US2는 US3와 **병렬 가능** — presence 컬럼은 초대 기능과 무관하다.

**Phase 5 내부**: 5-1(REST)이 5-2(STOMP)보다 앞선다. 이벤트는 알림이고 **정본은 REST** 라서, 5-1만으로도 quickstart 시나리오 2·3·4·6이 선다. 5-3은 `-139` 에 묶여 있어 마지막이다.

## Parallel Execution Examples

**Phase 2**: T005·T008·T009 동시. T004는 먼저, T006·T007은 T005 뒤.

**Phase 3**: T010·T011(테스트) 동시 → T012·T013 동시 → T014 → T015·T016 → T017·T018.

**Phase 5-1**: T025·T026·T027·T028 동시 → T029 → T030·T031 → T032.

**Phase 5-2**: T033·T034·T035 동시 → T036 → T037·T038 → T039.

**Phase 6**: T043·T044·T045 동시, T046은 T043 뒤, T047은 마지막.

## Implementation Strategy

**MVP**: **Phase 1·2 + Phase 3(US3)**. 직원 초대와 역할 게이트만으로 하나의 완결된 증분이다 — 부스 운영을 여럿이 나눌 수 있게 되고, spec 005·007·010의 기존 권한 판정이 역할을 보게 된다.

**증분 순서**

1. **Phase 2까지** — 역할 게이트. 이것만 먼저 들어가도 `requireEditor` 의 구멍이 닫힌다.
2. **+ Phase 3** — US3 완성. 단독 배포.
3. **+ Phase 4** — presence. FE가 버튼 가부를 판단할 수 있다.
4. **+ Phase 5-1** — 상담 REST. 폴링만으로도 요청·수락·종료가 돈다.
5. **+ Phase 5-2** — STOMP 알림. 새로고침 없이 대기열이 움직인다.
6. **+ Phase 5-3** — 요약. `-139` 도착 시점에 붙인다.

**마감이 위태로울 때 자르는 순서**: 5-3(요약) → 5-2(STOMP, REST 폴링으로 대체) → Phase 4. 이 순서는 GitLab #176에 적은 축소안("Presence·요약을 뒤로 미루고 요청→수락→텍스트 상담만 먼저 여는 축소안")과 같다.
