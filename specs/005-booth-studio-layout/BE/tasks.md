# Tasks: Booth Studio / Layout 계약

**Input**: `specs/005-booth-studio-layout/` — [spec.md](../spec.md) · [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [contracts/](../contracts/) · [quickstart.md](quickstart.md)

**Tests**: 포함한다. SC-003(작업본 노출 0건)·SC-004(왕복 일치)·FR-014(덮어쓰기 0건)는 **전부 "없어야 하는 것"**이라 테스트 없이는 충족을 증명할 수 없다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이라 병렬 가능
- **[Story]**: US1(꾸며서 공개한다) / US2(콘텐츠를 연결한다) / US3(실수로 공개되지 않게 한다) / **US4(외부 Facade — FR-018, spec 리뷰 ③칸에서 추가된 요구사항)**

## Path Conventions

`backend/src/main/java/com/example/ssafesta/`, 테스트는 `backend/src/test/java/com/example/ssafesta/`.

---

## Phase 1: Setup — 오류 봉투 기반 (research R-09)

**왜 먼저인가**: 005의 모든 endpoint가 이 봉투로 응답한다. 나중에 하면 005 코드를 두 번 고친다. 003·004 이관도 같은 이유로 여기서 끝낸다 — 봉투가 두 종류인 기간을 만들지 않는다.

- [X] T001 [P] `common/ErrorCode.java` — 코드·기본 HTTP status·기본 메시지를 갖는 enum. **docs/08 오류 코드 표와 1:1**로 맞춘다. 005 신규분(`LAYOUT_VALIDATION_FAILED` · `LAYOUT_REVISION_CONFLICT` · `LAYOUT_NOT_PUBLISHED` · `BOOTH_EDITOR_FORBIDDEN`)과 기존분(`BOOTH_LEASE_EXPIRED` · `BOOTH_SLOT_ALREADY_LEASED` · `BOOTH_SLOT_NOT_RENTABLE` · `ACTIVE_LEASE_LIMIT` · `BOOTH_NOT_FOUND` 등)을 함께 등록
- [X] T002 [P] `common/ApiErrorResponse.java` — `{code, message, requestId}` record. `errors`·`warnings`는 **null이면 직렬화에서 빠지도록** `@JsonInclude(NON_NULL)` (검증 응답에만 등장, [contracts/layout-api.md](../contracts/layout-api.md) §0)
- [X] T003 `common/ApiException.java` — `ErrorCode`를 싣는 기반 예외. 메시지 override와 상세 목록 첨부를 허용한다 (T001 의존)
- [X] T004 [P] `common/RequestIdFilter.java` — 요청당 `req_{8자}` 생성 → **MDC + 응답 헤더 `X-Request-Id`**. 봉투의 `requestId`와 서버 로그가 같은 값이어야 문의 추적이 성립한다. 로그 패턴에 `%X{requestId}` 추가
- [X] T005 `common/GlobalExceptionHandler.java` — `@RestControllerAdvice`. `ApiException` → 봉투, `ResponseStatusException` → 봉투(코드 미상은 `INTERNAL_ERROR`), Bean Validation 실패 → `400 VALIDATION_FAILED`. **스택트레이스·예외 클래스명을 본문에 넣지 않는다** (T002·T003·T004 의존)
- [X] T006 `auth/SecurityConfiguration.java` — `AuthenticationEntryPoint`·`AccessDeniedHandler`를 봉투로 응답하도록 등록. **⚠️ Security 필터 체인에서 나는 401/403은 `@RestControllerAdvice`를 타지 않는다** — 이걸 빠뜨리면 인증 실패만 형태가 다른 채 남는다
- [X] T007 기존 throw 지점 이관 — `booth/BoothController.java` · `booth/BoothSlotController.java`(**중복 `conflict()` 헬퍼 삭제**) · `booth/BoothPrincipal.java` · `wallet/WalletController.java` · `user/MyAccountController.java` · `auth/GuestAuthController.java` · `auth/OAuthAuthorizationController.java` · `auth/OAuthCompletionController.java`. `ResponseStatusException` 23곳을 `ApiException`으로 옮기고, **코드 없이 한국어 문장만 나가던 곳에 코드를 부여**한다
- [X] T008 [P] `test/.../common/ErrorEnvelopeIntegrationTest.java` — 만료 부스 409 본문이 `{code:"BOOTH_LEASE_EXPIRED", message, requestId}`인지 / 본문 `requestId`와 응답 헤더 `X-Request-Id`가 **같은 값**인지 / 토큰 없는 요청의 401도 같은 형태인지(T006 회귀) / 404 본문에 스택트레이스가 없는지
- [X] T009 `./mvnw test` 전체 회귀 — 기존 103건이 그대로 통과하는지 확인 — **결과: 112건 통과(103 + 신규 9), 실패 0**. **오류 본문 단언이 0건이므로 실패가 나면 그건 이관 실수다**

**Checkpoint**: 전 endpoint가 같은 봉투로 응답하고 docs/08 §1.3이 실제 동작이 됐다

---

## Phase 2: Foundational (Blocking)

**⚠️ 이 단계가 끝나기 전에는 어떤 User Story도 시작할 수 없다**

- [X] T010 `db/migration/V8__booth_published_layout_version.sql` — `booths.published_layout_version INTEGER NULL` 추가 + **복합 FK** `(id, published_layout_version) → booth_layout_published_versions(booth_id, version_no)`. NULL이면 MATCH SIMPLE로 검사가 면제되어 "공개된 것 없음"이 표현된다 (data-model I-3)
- [X] T011 `db/migration/V9__booth_facade_fields.sql` — `facade_code` → `facade_theme_code` **rename**, `facade_primary_color VARCHAR(7)` · `facade_sign_text VARCHAR(60)` · `facade_logo_url VARCHAR(2048)` 추가 (전부 NULL 허용). **`booth/Booth.java`의 `facadeCode` 필드도 같은 커밋에서 고친다** — 아무도 안 읽는 필드라 빠뜨려도 테스트가 통과해 버린다 (data-model §5)
- [X] T012 [P] `booth/LayoutObjectType.java` — canonical 10종 화이트리스트(`AI_AGENT` `VIDEO_SCREEN` `PROJECT_PANEL` `SURVEY_KIOSK` `RECRUITMENT_BOARD` `CONSULTATION_DESK` `LAPTOP` `LIKE_VOTE` `FURNITURE` `DECORATION`) + 기능형/장식형 구분. **Unity 하위 호환값 `SURVEY`·`CONSULT_DESK`는 저장에 허용하지 않는다** (spec §공통 계약)
- [X] T013 [P] `booth/LayoutTemplate.java` — `DEFAULT` · `PROJECT_EXHIBITION`. C-06 확정 시 목록만 늘린다 *(→ C-06 확정으로 `DEFAULT` 제거, `PROJECT_EXHIBITION` 단독 — T055)*
- [X] T014 `booth/LayoutJson.java` — **요청 원문을 보관**하고 검증용 파싱만 별도로 수행. 좌표는 `BigDecimal`로 읽는다. **`double`로 파싱해 재직렬화하지 않는다** (research R-04). JPA는 `@JdbcTypeCode(SqlTypes.JSON) String`으로 매핑
- [X] T015 [P] `booth/BoothLayoutDraft.java` — `booth_layout_drafts` 매핑. PK가 `booth_id`(I-1). `revision` 증가는 전용 메서드로만
- [X] T016 [P] `booth/BoothLayoutPublishedVersion.java` — `booth_layout_published_versions` 매핑. **생성 후 `layout_json`을 바꾸는 경로를 만들지 않는다** (I-7)
- [X] T017 [P] `booth/BoothStaff.java` + `booth/BoothStaffRepository.java` — `@IdClass`로 복합 PK. **읽기 전용** — 초대·수락은 spec 011 (research R-07)
- [X] T018 [P] `booth/BoothLayoutDraftRepository.java` · `booth/BoothLayoutPublishedVersionRepository.java` — 부스별 Draft 조회, `(boothId, versionNo)` 조회, `MAX(version_no)` 조회, **`revision` 조건부 UPDATE**(영향 행 0이면 충돌 — I-6)
- [X] T019 [P] `booth/LayoutValidationResult.java` + `booth/LayoutValidator.java` — [data-model.md](data-model.md) §3 규칙표대로 `errors`·`warnings` 두 목록 생성. **Draft 저장용과 공개용 진입점을 분리**해 같은 규칙집합을 다른 강도로 적용한다 (research R-05)
- [X] T020 [P] `booth/BoothEditorGuard.java` — `Booth.isOwnedBy(userId) || boothStaffs.existsById(boothId, userId)` (FR-012)
- [X] T021 [P] 예외 3종 — `LayoutValidationFailedException`(errors·warnings 첨부) · `LayoutRevisionConflictException`(현재 revision 첨부) · `BoothEditorForbiddenException`. 전부 `ApiException` 상속
- [X] T052 `test/.../booth/BoothLayoutSchemaIntegrationTest.java` — **구현 중 추가.** Phase 2는 "스키마가 준비됐다"고 선언하는데 그걸 확인하는 것이 하나도 없었다. jsonb 정밀도 왕복 / Draft PK가 곧 I-1 / 포인터 FK가 없는 회차를 막음 / NULL 포인터 허용 / **공개한 소유자의 탈퇴가 성공** / facade 4컬럼 / 회차 카운트가 부스별. 다섯 번째가 핵심 — `AccountDeletionService`에 테스트가 0건이라 V8 FK가 탈퇴를 깨도 아무도 몰랐다

**Checkpoint**: 스키마·엔티티·검증기·권한 판정이 준비됐다 — **회귀 119건 통과(Phase 1 대비 +7), 실패 0**

---

## Phase 3: User Story 1 — 부스를 꾸며서 공개한다 (P0) 🎯 MVP

**Goal**: 작업본을 저장하고, 공개하고, 방문자가 공개본만 본다

**Independent Test**: 오브젝트 3개 배치 저장 → 새로고침해도 남아 있음 → 공개 → 비인증 조회로 보임. **저장만 한 내용은 공개 조회에 나타나지 않는다**

- [X] T022 [US1] `booth/BoothLayoutQueryService.java` — Draft 조회(권한 필요, 없으면 empty → 컨트롤러가 204) · Published 조회. **Published는 004 `BoothLeaseRepository.findValidByBoothId`로 임대 유효성을 먼저 확인**하고 없으면 `BOOTH_LEASE_EXPIRED` (research R-06, I-5). 새 만료 술어를 쓰지 않는다
- [X] T023 [US1] `booth/BoothLayoutService.java` — `saveDraft(boothId, userId, request)`. 권한 → 검증(저장 강도) → `expectedRevision` 조건부 UPDATE → 실패 시 `LayoutRevisionConflictException`. 최초 저장(`expectedRevision=0`)은 INSERT
- [X] T024 [US1] `booth/BoothLayoutService.java` — `publish(boothId, userId)`를 **하나의 `@Transactional`** 로. 순서는 [data-model.md](data-model.md) §4 그대로: 권한 → 임대 유효 → 검증(공개 강도, errors면 중단) → `MAX(version_no)+1` INSERT → `booths.published_layout_version` 갱신. **4만 되고 5가 실패하면 "공개했는데 아무도 못 보는 버전"이 남는다**
- [X] T025 [US1] `booth/BoothLayoutController.java` — `GET/PUT /api/v1/booths/{boothId}/layouts/draft` · `POST …/layouts/publish` · `GET …/layouts/published`. 응답 필드는 [contracts/layout-api.md](../contracts/layout-api.md) §2~§5 그대로. **409 `LAYOUT_REVISION_CONFLICT` 본문에 최신 Draft를 함께 싣는다** (FE 추가 왕복 제거)
- [X] T026 [US1] `auth/SecurityConfiguration.java` — `GET /api/v1/booths/*/layouts/published`를 **permitAll**로. Unity·방문자 경로다. **draft·publish는 인증 유지**
- [X] T027 [P] [US1] `test/.../booth/BoothLayoutRoundTripIntegrationTest.java` — 저장한 좌표가 조회에서 그대로 나오는지. `2.123456789` · 음수 · `0.0` · `-0.0` · `rotationY: 359.9`를 포함하고, **알 수 없는 필드가 섞인 요청의 처리가 명시적인지**(조용히 버리지 않는다 — T-24의 교훈) 확인 (SC-004, quickstart §1)
- [X] T028 [P] [US1] `test/.../booth/BoothLayoutServiceIntegrationTest.java` — 저장→공개→포인터 전이 / 공개 후 Draft를 고쳐도 **공개본이 변하지 않음**(I-7) / 두 번 공개하면 `version_no`가 1→2 / 공개 트랜잭션 중간 실패 시 포인터와 버전이 **함께** 롤백
- [X] T029 [P] [US1] `test/.../booth/BoothLayoutConcurrencyIntegrationTest.java` — 같은 `expectedRevision`으로 동시 저장 시 **하나만 성공**, 나머지는 409 (FR-014, I-6). 004처럼 `@RepeatedTest`로 반복
- [X] T030 [P] [US1] `test/.../booth/BoothLayoutApiIntegrationTest.java` — Draft 없을 때 204 / 비소유자 403 / 없는 부스 404 / **저장만 하고 공개 안 한 상태에서 published가 404**(SC-003 핵심) / 만료 부스 published 409

**Checkpoint**: US1 단독 배포 가능 — Unity가 실물 published 경로를 붙일 수 있다 ✅

---

## Phase 4: User Story 2 — 기능 오브젝트에 내용을 연결한다 (P0)

**Goal**: `configId` 연결이 저장되고, 남의 콘텐츠를 연결할 수 없다

**Independent Test**: AI 오브젝트에 그 부스의 agent를 연결 → 공개 성공. 다른 부스의 agent를 연결 → 공개 거부

- [X] T031 [US2] `booth/LayoutConfigResolver.java` — 타입별 `configId` 소유 검증. `AI_AGENT` → `ai_agents.booth_id` 일치 + `status='ACTIVE'`. **검증 대상이 아직 없는 타입은 통과시키되 warning을 남긴다** — 검증이 없다는 사실이 조용해지지 않게 (data-model §3)
- [X] T032 [US2] `booth/LayoutValidator.java` 확장 — 공개 시점에 `LayoutConfigResolver`를 호출해 소유 불일치를 **error**로, 미연결을 **warning**으로 분류 (헌법 16·17조, C-04 미정)
- [X] T033 [US2] Draft 저장에서는 `configId` 소유 검증을 **하지 않는다** — 편집 중에는 콘텐츠를 아직 안 만들었을 수 있다. 저장 응답의 `warnings`로만 알린다
- [X] T034 [P] [US2] `test/.../booth/BoothLayoutConfigLinkIntegrationTest.java` — 자기 부스 agent 연결 공개 성공 / **다른 부스 agent 연결 시 공개 거부**(errors) / 미연결은 warning이며 공개는 성공(현재 C-04 기본값) / Draft 저장은 남의 configId여도 통과하고 warning만

**Checkpoint**: 부스 경계를 넘는 콘텐츠 연결이 막힌다 ✅

---

## Phase 5: User Story 3 — 실수로 공개되지 않게 한다 (P1)

**Goal**: 재임대해도 이전 공개본이 자동으로 되살아나지 않고, 유효하지 않은 배치는 공개되지 않는다

**Independent Test**: 공개된 부스의 임대를 만료시키고 같은 사용자가 재임대 → 콘텐츠는 남아 있고 `publishedLayoutVersion`은 `null`

- [X] T035 [US3] `booth/Booth.java` — `detachSlot()`에서 `published_layout_version`을 **함께 `null`로** 만든다. 슬롯 해제와 공개 해제가 같은 지점이라 새 스케줄러가 필요 없다 (FR-017, data-model §4)
- [X] T036 [US3] `booth/BoothLeaseService.java` — `releaseStaleLeases()` / 재임대 경로가 `detachSlot()`을 거치는지 확인하고, **거치지 않는 경로가 있으면 그 자리에도 해제를 넣는다**. 004의 T-110이 "한 곳만 빠뜨려 조용히 틀린" 사례다
- [X] T037 [US3] Draft·공개본 이력은 **삭제하지 않는다**는 것을 코드와 주석으로 고정 (FR-011 — 보존하되 자동 공개 금지)
- [X] T038 [P] [US3] `test/.../booth/BoothLayoutReleaseIntegrationTest.java` — 공개 상태에서 만료 → `published_layout_version`이 `null` / Draft와 공개본 **행은 그대로 남아 있음** / 재임대 후 published 조회가 404(`LAYOUT_NOT_PUBLISHED`) / 재공개하면 `version_no`가 **이어서 증가**(1→2, 리셋 아님)
- [X] T039 [P] [US3] `test/.../booth/BoothLayoutValidationTest.java` — 13개 초과 / 중복 `objectId` / 미지원 `type` / `NaN`·`Infinity` 좌표 / 영역 이탈 / `rotationY` 범위 밖 / 미지원 `schemaVersion`이 각각 **errors의 어느 rule로 분류되는지** 단언

**Checkpoint**: 재임대 시나리오가 FR-011의 의도대로 동작한다 ✅

---

## Phase 6: User Story 4 — 외부 Facade (FR-018, 2026-08-20 추가)

**Goal**: 소유자가 부스 외부 표현(테마·색·간판·로고)을 수정하고 방문자 조회에 반영된다

**Independent Test**: facade 수정 → `GET /booths/{id}` 응답의 `facade` 4필드가 바뀐다

- [X] T040 [US4] `booth/BoothFacadeService.java` — 수정·조회. 권한은 `BoothEditorGuard` 재사용. 만료 부스는 수정 거부
- [X] T041 [US4] `booth/BoothFacadeController.java` — `PUT /api/v1/booths/{boothId}/facade`. 검증: `themeCode` 화이트리스트 · `primaryColor`는 `#RRGGBB` · `signText` 60자 · `logoUrl`은 `https://` 2048자 ([contracts/layout-api.md](../contracts/layout-api.md) §6)
- [X] T042 [US4] `booth/BoothQueryService.java` — `PublicBoothView`에 `facade` 4필드와 `publishedLayoutVersion` 추가. **기존 필드는 그대로 둔다** (추가만, 헌법 24조 / contracts §7)
- [X] T043 [P] [US4] `test/.../booth/BoothFacadeApiIntegrationTest.java` — 수정 후 공개 조회 반영 / 잘못된 색 형식 400 / `http://` 로고 거부 / 비소유자 403 / 만료 부스 409

**Checkpoint**: docs/08 §3의 booth 응답이 문서와 실제로 일치한다 ✅ — **전체 회귀 182건 통과, 실패 0**

---

## Phase 7: Polish & 기록

- [X] T044 [P] `backend/bruno/05-booth-layout/` — [quickstart.md](quickstart.md) §2의 18단계를 request로. 003·004처럼 각 request의 Docs 탭에 목적·인증·성공/실패 응답·다음 흐름을 적는다
- [X] T045 [P] `docs/08_Backend_API_명세서.md` — ① `PUT /booths/{boothId}/facade` 신설 반영 ② §1.3 오류 응답을 "제안"에서 **확정·구현됨**으로 ③ layout 응답의 `schemaVersion` 추가 ④ 오류 코드 표에 005 신규 4종 추가
- [X] T046 [P] `docs/09_DB_ERD_DB_설계서.md` — §9를 실물(`booth_layout_drafts` + `booth_layout_published_versions` 2테이블)로 정정하고 §7 facade 4컬럼·`published_layout_version`을 V8·V9 결과와 일치시킨다 (research R-01·R-08)
- [X] T047 **3파트 통보** ⚠️ **사람이 해야 함** — ① 오류 봉투가 005부터 실제 동작(Breaking 아님, 문서와의 정합 회복) ② `PUT /facade` 신설 ③ `schemaVersion`과 `version`을 갈라 쓰기로 한 것(research R-10)과 spec 005 §Layout JSON 예시의 `"version": 2` 정정 제안. AI·FE·Unity 파트에 전달하고 `docs/26`에 결과 기록 (헌법 24조) → **2026-08-21 GitHub 이슈로 통보 완료**: ①②는 #17, 경계·서버 검증은 #19, ③과 잔여 항목은 #36, avatar 필드명은 #24. `docs/26` 결과 기록은 develop 문서 PR에서
- [X] T048 [quickstart.md](quickstart.md) 수동 검증 **수행 완료 (2026-08-21)** — 로컬 Spring 실서버에 curl로 18단계 + 만료 4단계를 전부 실행했다. 로컬 DB v5→v9 마이그레이션·`ddl-auto: validate` 통과, 좌표 원문 보존, 공개 포인터가 걸린 상태의 회원 탈퇴까지 확인. **최초 저장 경합 결함을 여기서 잡았다 (T-114)**
- [X] T049 `docs/HDD/작업일지.md`에 2026-08-20 이후 작업 기록, 문제는 해결 여부와 무관하게 `docs/HDD/트러블슈팅.md`에 T-번호로 등록 (헌법 29조)
- [X] T050 **부스 영역 경계 확정** — **6m × 6m × 6m 확정 (2026-08-20)**. 원점이 바닥 중앙이라 `|x|,|z| ≤ 3` · `0 ≤ y ≤ 6`. 경계 포함/초과 테스트 추가 *(→ 높이는 #19 셸 실측으로 **2.72**로 갱신 — T052)*
- [X] T051 `specs/README.md`의 005 행을 tasks까지 ✅로 갱신
- [X] T052 **셸 유효 높이 반영** (#19 ②, 2026-08-21) — `MAX_HEIGHT` 6 → **2.72** (벽 패널 실측 2.725의 내림). 관련 테스트·문서 갱신
- [X] T053 **실물 영역 검증** (#19 ③) — 타입 10종 실측 bounds를 `LayoutObjectType`에 계약값으로 탑재, 원점 기준 코너 회전 후 AABB 재계산(`LayoutGeometry`), error `AREA_OUT_OF_BOUNDS` (Draft·공개 모두). 경계 딱 맞춤·회전 float 잡음 허용 테스트 포함
- [X] T054 **통행 판정** (#19 ⑤) — `LayoutPassageChecker` 신설: 0.05m 래스터 120×120, 0.22m 유클리드 침식, +z flood fill(4방향), 관람 띠 0.7m 도달<50% → warning `FRONT_BLOCKED`, 고립 ≥1㎡ → warning `ISOLATED_AREA`. 공개 시점만, 공개는 막지 않음
- [X] T055 **템플릿 카탈로그** (#19 ④) — `GET /booth-layout-templates` 신설(footprint 6×6×2.72·maxObjects 12를 검증 상수에서 유도), `DEFAULT` 제거 + V11로 기존 저장분 이관. spec 예시 `"version": 2` → `"schemaVersion": 1` 정정(#36 합의), 계약 문서 §9·§10 신설. 전체 회귀 203/203 통과 — 구현은 back PR #50으로 반입 완료
- [X] T056 **슬롯 기준 published 경로** (#62) — `GET /booth-slots/{slotId}/layouts/published`. 인증 불필요, `슬롯 → 유효 임대 → boothId` 해석을 서버가 흡수하고 body는 §5와 동일. 빈 슬롯·미공개 404 `LAYOUT_NOT_PUBLISHED` / 만료 409 `BOOTH_LEASE_EXPIRED` / 없는 슬롯 404. 계약: `contracts/layout-api.md` §11 — 해석은 `BoothLayoutQueryService.findPublishedBySlot`에 두고 유효 임대가 있으면 **기존 `findPublished`에 위임**한다(§5·§11이 다른 body를 내는 경로가 생기지 않는다). **없는 슬롯의 코드는 `BOOTH_SLOT_NOT_FOUND`로 확정** — 계약 표가 상태코드만 정하고 코드를 비워 둬서 004의 기존 값을 그대로 쓴다. 만료 판정은 `findStaleActiveBySlotId`로 하고 만료 정리는 임대 시점에만 돌므로(FR-017), 정리 전 상태를 "빈 방"으로 읽지 않는다
- [X] T057 **슬롯 12개 시드** (#62) — V12로 `F11-R08`~`F11-R12` 추가하고 `slotId` 1~12가 Unity 앵커 `01~12`와 대응하도록 id 명시 삽입 + `setval`로 시퀀스 정렬. **`ON CONFLICT`를 쓰지 않는다** — 대응이 계약이므로 어긋난 환경은 조용히 건너뛰는 대신 마이그레이션이 실패해야 한다(T-24). **V5 파일 자체는 수정하지 않았다** — 적용된 마이그레이션을 고치면 Flyway checksum이 깨져 기존 환경이 기동하지 못한다. 낡은 층 문구는 V12 주석에서 근거로 갈음. 시드 7 → 12로 기존 `sevenUserRentalSlotsAreSeeded`가 실패해 `twelveUserRentalSlotsAreSeeded`로 갱신(전체 회귀에서 잡힘)
- [X] T058 **`field` 분리** (#58) — `ApiErrorDetail`에 `field`(NON_NULL) 추가 + `ApiErrorDetail.field(name, message)` 팩토리, `GlobalExceptionHandler`의 Bean Validation 경로를 `rule: "FIELD_INVALID"` + `field`로 교체. Layout 경로 변경 0. 계약: `docs/08` §1.3-1 — 기존 `of` 2종을 그대로 둬 호출처 6곳 무수정. **본선에 `@Valid` DTO가 아직 없어** 이 분기를 실제로 태우는 요청이 없었으므로 테스트 전용 프로브 컨트롤러(`BeanValidationProbeController`)로 실경로 고정
- [X] T059 **팔레트 소속 검증·정규화** (#17) — `BoothFacadeService`가 `primaryColor`를 12색 화이트리스트로 검증하고 저장 시 대문자로 정규화. 팔레트 밖은 400 `VALIDATION_FAILED`. 계약: `contracts/layout-api.md` §6 — 검증 순서는 **형식 → 정규화 → 소속**(형식 오류와 팔레트 이탈이 다른 메시지를 받는다). 팔레트 밖이던 예시색 `#1677C8`을 Bruno·`docs/08`·계약 문서 예시에서 `#3B82F6`(BLUE)로 정정

---

## Dependencies & Execution Order

### Phase 의존

- **Phase 1 (오류 봉투)**: 의존 없음. **모든 후속 작업을 블로킹한다** — 005 endpoint가 이 봉투로 응답하므로 나중에 하면 두 번 고친다
- **Phase 2 (Foundational)**: Phase 1 이후. 모든 User Story를 블로킹
- **Phase 3~6 (US1~US4)**: Phase 2 이후. US1이 MVP
- **Phase 7 (Polish)**: 대상 Story 완료 후

### User Story 의존

- **US1 (P0)**: Phase 2 이후 즉시. 다른 Story에 의존하지 않는다
- **US2 (P0)**: US1의 검증기·공개 경로 위에 얹힌다 (T032가 T024를 확장)
- **US3 (P1)**: US1의 포인터 전이 위에 얹힌다 (T035가 T024의 반대 방향)
- **US4 (P0 요구지만 독립)**: **Phase 2 이후 아무 때나.** layout과 겹치는 코드가 없어 US1과 병렬 가능

### 파일 충돌 주의 (병렬 시)

| 파일 | 건드리는 작업 |
|---|---|
| `booth/LayoutValidator.java` | T019 → T032 (US2가 확장) — **순차** |
| `booth/BoothLayoutService.java` | T023 · T024 — 같은 파일, 순차 |
| `booth/Booth.java` | T011(facade 필드) · T035(detachSlot) — 순차 |
| `auth/SecurityConfiguration.java` | T006 · T026 — 순차 |
| `booth/BoothQueryService.java` | T042 — 004 코드 수정, 단독 |

---

## Parallel Example: Phase 2

```text
동시에 가능 (서로 다른 파일):
  T012 LayoutObjectType   T013 LayoutTemplate   T015 BoothLayoutDraft
  T016 BoothLayoutPublishedVersion   T017 BoothStaff   T018 Repositories
  T019 LayoutValidator    T020 BoothEditorGuard   T021 예외 3종

먼저 끝나야 하는 것: T010·T011(마이그레이션) → 엔티티가 그 컬럼을 매핑한다
```

---

## Implementation Strategy

### MVP (US1까지)

1. Phase 1 — 오류 봉투 + 003·004 이관 → **여기서 전체 회귀 1회** (T009)
2. Phase 2 — 마이그레이션·엔티티·검증기
3. Phase 3 — Draft/Publish/Published
4. **정지하고 검증**: quickstart §2의 1~10단계. 특히 5단계(저장만으로는 공개되지 않는다)
5. Unity에 published 경로를 붙여 본다 (quickstart §3) — BE 코드 변경 없이 `useMockApi`만 끄면 된다

### 증분 배포

1. Phase 1+2 → 기반
2. US1 → **Unity가 실물 Layout을 받기 시작한다** (가장 큰 언블록)
3. US4(facade) → US1과 병렬 가능, docs/08 booth 응답이 문서대로 완성
4. US2 → 부스 경계 강제
5. US3 → 재임대 시나리오

### 004에서 가져오는 습관

- 동시성 테스트는 `@RepeatedTest`로 반복 — 1회 통과는 증명이 아니다
- 트랜잭션 경계를 테스트로 고정 — "중간 상태가 표현 불가능"을 단언한다
- 불변식은 주석이 아니라 **DB 제약**으로 (I-1·I-2·I-3)
- 실패는 조용히 기본값으로 되돌리지 않는다 — 거부하고 사유를 응답에 싣는다 (T-24)
