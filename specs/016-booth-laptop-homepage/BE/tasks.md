# Tasks: 부스 홈페이지 URL (016 BE분)

**Input**: `specs/016-booth-laptop-homepage/BE/` — [plan.md](plan.md) · [research.md](research.md) · [data-model.md](data-model.md) · [quickstart.md](quickstart.md) / 계약 정본 [contracts/homepage-api.md](../contracts/homepage-api.md) / 공동 정본 [spec.md](../spec.md)

**Tests**: 포함한다. plan.md §Source Code가 `BoothHomepageApiIntegrationTest`를 산출물로 명시했고 quickstart §3이 시나리오를 고정했다. 003·004·005·013a와 같은 Testcontainers 패턴이다.

**Organization**: spec의 사용자 스토리 기준. **US3(열리지 않는 사이트)은 BE 몫이 0이라 구현 Phase를 만들지 않는다** — Phase 5에 근거를 남긴다.

> ## ⚠️ 계약 정본 우선순위 — spec.md는 이 항목들에서 stale이다
>
> [#97](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/97)과 [contracts/homepage-api.md](../contracts/homepage-api.md) §6이 **확정 정본**이다 (2026-08-26 리드 확정 + FE 동의). `spec.md`는 리드가 아직 정본화하지 않아 아래 두 문구가 남아 있고, **둘 다 #97에서 폐기됐다**:
>
> | spec.md 현재 문구 | 확정값 |
> |---|---|
> | FR-001 `[NEEDS CLARIFICATION: 여러 개를 허용할 것인가]` | **부스당 1개** (C-02) |
> | FR-011 "Layout 계약 확장이 필요하다" | **Layout 불변** — URL은 `booths.homepage_url` (C-01) |
>
> **이 목록에 없는 작업 — 절대 생성·수행하지 않는다**: Layout schema 확장 · `booth_homepages` 등 별도 테이블 신설 · `LAPTOP`에 `configId` 추가 · `LayoutObjectType` 변경 · 신규 마이그레이션 · 신규 오류 코드/rule. 리드 정본화 요청은 게시 대기(플랜 [0]).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능 (다른 파일, 미완 작업에 의존하지 않음)
- **[Story]**: US1 / US2 (spec.md 사용자 스토리)

## Path Conventions

Web API 단일 모듈 — `backend/src/main/java/com/example/ssafesta/booth/`, `backend/src/test/java/com/example/ssafesta/booth/`

---

## Phase 1: Setup

**Purpose**: 전제 확인. **신규 스키마·신규 의존성이 0**이라 초기화 작업이 없다.

- [x] T001 회귀 기준선 확보 — `cd backend && ./mvnw test`. **기준선 42 클래스 / 370 테스트 / 실패 0 (origin/develop, 2026-08-28 실측)**. 구현 후 이 수치와 대조한다
- [x] T002 [P] 전제 실측 재확인 — `backend/src/main/resources/db/migration/V1__initial_schema.sql:38`의 `homepage_url VARCHAR(2048)`와 `backend/src/main/java/com/example/ssafesta/booth/Booth.java:76-77` 매핑이 그대로인지 확인한다. **신규 마이그레이션을 만들지 않는다**(data-model.md §1). 컬럼이 없으면 즉시 중단하고 보고한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1(읽기)과 US2(쓰기)가 **같은 파일**(`Booth.java`)을 건드린다. 먼저 끝내야 두 스토리가 병렬로 갈 수 있다.

- [x] T003 `backend/src/main/java/com/example/ssafesta/booth/Booth.java`에 `getHomepageUrl()`(현재 getter 부재)과 패키지 프라이빗 `changeHomepageUrl(String homepageUrl)`을 추가한다. `changeFacade`(`:125-131`)의 형태를 따라 **`updatedAt = Instant.now()`를 직접 갱신**한다(`@PreUpdate` 없음). **값을 변형하지 않는다** — trim·정규화 금지(data-model.md §2 왕복 무손실 불변식)

**Checkpoint**: 엔티티가 homepageUrl을 읽고 쓸 수 있다. US2·US1 병렬 착수 가능.

---

## Phase 3: User Story 2 — 부스 주인이 홈페이지를 등록한다 (Priority: P0)

**Goal**: 소유자·스태프가 URL을 등록·수정·해제하고, 잘못된 값은 사유와 함께 거부된다.

**Independent Test**: 소유자 토큰으로 `PUT /api/v1/booths/{id}/homepage` 200 + echo → `GET /booths/mine`에서 같은 바이트 확인. `javascript:` 등은 400 + 사유 문장.

> **US1보다 먼저 두는 이유는 우선순위가 아니라 의존이다** — 둘 다 P0이지만, 쓰기 경로가 있어야 US1의 노출 게이트를 API로 검증할 수 있다.

- [x] T004 [US2] `backend/src/main/java/com/example/ssafesta/booth/BoothHomepageService.java` 신설 — `BoothFacadeService`(`:37-63`) 대칭: `editorGuard.requireEditor(boothId, userId)` → `leases.findValidByBoothId(boothId, Instant.now())` 없으면 `BoothExpiredException` → 검증 → `booth.changeHomepageUrl` → echo.
  **요청 DTO는 `record`를 쓰지 않는다** — `homepageUrl` **키의 존재 여부를 추적**하는 클래스로 만든다(`@JsonProperty` setter에서 `present = true`). `record`는 `{}`와 `{"homepageUrl":null}`을 똑같이 `null`로 읽어 FE 직렬화 실수가 조용한 해제가 된다(data-model.md §3 #0, research.md R-04 ⓪).
  검증은 data-model.md §3 순서 그대로 첫 위반의 **사유별 다른 문장**으로 400: ⓪ 필드 부재 → ① blank → ② 길이 2048 → ③ `java.net.URI` 파싱·절대 → ④ scheme ∈ {http, https}(대소문자 무시) → ⑤ host 존재. **④를 ⑤보다 먼저** 둔다 — `javascript:`·`data:`는 host가 없어 순서가 뒤바뀌면 스킴 위반 사유가 전달되지 않는다.
  거부 봉투는 `ApiException(ErrorCode.VALIDATION_FAILED, msg, List.of(ApiErrorDetail.field("homepageUrl", msg)), null)` — rule은 `FIELD_INVALID` 고정(#58). **신규 `ErrorCode`를 만들지 않는다**
- [x] T005 [US2] `backend/src/main/java/com/example/ssafesta/booth/BoothHomepageController.java` 신설 — `BoothFacadeController` 미러. `@RequestMapping("/api/v1/booths/{boothId}/homepage")` + `@PutMapping` + `@SecurityRequirement(name = "bearerAuth")`, `BoothPrincipal.requireMemberId(jwt)`로 게스트 차단. **`SecurityConfiguration`은 건드리지 않는다**(PUT은 `anyRequest().authenticated()`에 이미 걸린다)
- [x] T006 [US2] `backend/src/main/java/com/example/ssafesta/booth/BoothQueryService.java`의 `MyBoothView` record에 `String homepageUrl`을 추가하고 `MyBoothView.of`에서 **게이트 없이 항상 저장값**을 담는다 — 미공개 상태의 스튜디오 폼 프리필용(research.md R-06). ⚠️ T010과 같은 파일이라 **병렬 불가**
- [x] T007 [US2] `backend/src/test/java/com/example/ssafesta/booth/BoothHomepageApiIntegrationTest.java` 신설 — `BoothFacadeApiIntegrationTest` 1:1 미러(`@Import(TestcontainersConfiguration.class)` + `@SpringBootTest` + `@AutoConfigureMockMvc`, 헬퍼 `leasedOwner`/`bearerFor`(`MemberSessionService.issue`)/`BoothTestSupport.createMemberWithWallet`/`BoothLayoutTestSupport.grantLease`·`expireLease` 재사용, `@BeforeEach`에 `BoothTestSupport.releaseAllSlots`). 커버:
  소유자 PUT 200 + echo → `/mine` 왕복 **바이트 동일**(`HtTpS://…` 혼합 대소문자로 원문 보존 입증) · 스태프 PUT 200 · `http`·`https` 통과 · **`javascript:`·`data:`·`ftp:` → 스킴 문장** · 상대경로·host 없는 `http:///` → 형식 문장 · 경계 2048 통과/2049 400 · `""` 400 · **`{}` 400(필드 부재)** · `null` 200 해제 후 미등록 복귀 · 400 봉투 `VALIDATION_FAILED` + `errors[0].rule=FIELD_INVALID`·`errors[0].field=homepageUrl` · 미인증 401 · 비편집자 403 `BOOTH_EDITOR_FORBIDDEN` · 없는 부스 404 `BOOTH_NOT_FOUND` · 만료 부스 409 `BOOTH_LEASE_EXPIRED`
- [x] T008 [US2] `LayoutConfigResolver.java`에 `boolean boothHomepageRegistered(Long boothId)` 추가(`SELECT count(*) FROM booths WHERE id = ? AND homepage_url IS NOT NULL` — count 쿼리라 없는 부스도 예외 없이 `false`. `BoothLayoutValidationTest.java:128-140`이 존재하지 않을 수 있는 `boothId=1L`을 넘기므로 이 성질이 필요하다). `LayoutValidator.java`의 `checkContentLinks`에서 **`LAPTOP`만 분기**해 `CONFIG_NOT_LINKED` 판정 근거를 `configId` 부재 → **URL 미등록**으로 바꾸고 문구를 `"홈페이지 주소가 등록되지 않았습니다."`로 한다(계약 §3-1). 조회는 문서당 1회 lazy.
  ⚠️ **`LayoutObjectType.LAPTOP`의 `requiresConfig`는 `true`로 유지한다** — `LayoutPassageChecker.java:80`이 같은 플래그로 시야 확보 검사를 켠다. `false`로 내리면 LAPTOP이 그 검사에서 통째로 빠진다(research.md R-10 실측 정정).
  ⚠️ LAPTOP 분기 뒤 **기존 `configId` 체인은 그대로 통과시킨다** — LAPTOP에 `configId`가 실려 오면 `isVerifiable(LAPTOP)=false`라 `CONFIG_UNVERIFIED`가 붙어야 한다(계약 §3-1 FE 통보 1과 일치). `LayoutValidator` 생성자 시그니처는 바꾸지 않는다
- [x] T009 [US2] `backend/src/test/java/com/example/ssafesta/booth/BoothLayoutConfigLinkIntegrationTest.java`에 LAPTOP 3케이스 추가 — ⑴ LAPTOP 있음 + URL 미등록 → publish warning `CONFIG_NOT_LINKED`(문구 확인) ⑵ LAPTOP 있음 + URL 등록 → **경고 없음**(기존 `configId` 기준 오탐 소멸) ⑶ LAPTOP 없음 + URL 미등록 → 경고 없음. **기존 AI_AGENT `CONFIG_NOT_LINKED`(`:98`)·PROJECT_PANEL `CONFIG_UNVERIFIED`(`:114`) 테스트는 수정하지 않고 green이어야 한다**

**Checkpoint**: 등록·수정·해제·검증·권한·만료·Publish 경고가 전부 동작한다. US2 독립 검증 가능.

---

## Phase 4: User Story 1 — 방문자가 노트북으로 홈페이지를 연다 (Priority: P0)

**Goal**: 공개된 부스의 URL을 인증 없이 조회할 수 있고, 미공개 부스는 노출하지 않는다.

**Independent Test**: URL 등록 + Layout 미공개 → `GET /booths/{id}`의 `homepageUrl`이 `null`. Publish 후 같은 조회에서 저장값.

> BE 몫은 **노출 표면 하나**다. 노트북 클릭 트리거는 Unity 기존 브리지(`BOOTH_LAPTOP_INTERACT`), iframe 표시·닫기·월드 연결 유지는 React 레이어다(헌법 25조, FR-005~FR-008).

- [x] T010 [US1] `backend/src/main/java/com/example/ssafesta/booth/BoothQueryService.java`의 `PublicBoothView` record에 `String homepageUrl`을 추가하고 `findPublicBooth`에서 **published 게이트**를 적용한다 — `booth.getPublishedLayoutVersion() != null ? booth.getHomepageUrl() : null`(FR-003, research.md R-05). 미등록도 `null`이라 FE는 `null` 하나로 "미등록/미공개" 분기를 끝낸다(FR-009). 만료 부스는 이 조회 자체가 이미 `409`라 만료 분기를 추가하지 않는다. ⚠️ T006과 같은 파일이라 **병렬 불가**
- [x] T011 [US1] `BoothHomepageApiIntegrationTest`에 노출 시나리오 추가 — 미공개 부스: 등록돼 있어도 public view `homepageUrl` **null** · 공개 부스: 저장값 노출 · 공개 부스라도 `/mine`은 항상 저장값 · **`GET /booths/{id}/layouts/published` 응답에 URL이 없음**(Layout 계약 불변, data-model.md §4)

**Checkpoint**: 방문자 노출 게이트가 동작한다. US1 독립 검증 가능. **여기까지가 MVP.**

---

## Phase 5: User Story 3 — 열리지 않는 사이트를 만나도 막히지 않는다 (Priority: P0) — **BE 작업 0건**

구현 Phase를 만들지 않는다. 근거:

- iframe 삽입 거부 **감지**·새 창 fallback·타임아웃 안내·재시도는 전부 React 레이어다(FR-005·FR-007, 헌법 25조).
- 서버는 URL의 **도달성·iframe 삽입 가능 여부를 판정하지 않는다** — 사전 판정이 기술적으로 불가능하고 spec §기술 리스크 2가 이를 명시했다(research.md R-04 Alternatives ②).
- 스킴 화이트리스트(T004 ④)까지가 서버 몫이다. 콘텐츠 수준 대응(악성 링크 신고·강제 비공개)은 ADMIN 권한 모델 U-01에 종속돼 **범위 제외**다(research.md R-09).

---

## Phase 6: Polish & Cross-Cutting

- [x] T012 [P] `docs/08_Backend_API_명세서.md` 갱신 — §3의 `GET /booths/mine`·`GET /booths/{boothId}` 응답 예시에 `homepageUrl` 추가(public 쪽은 게이트 설명 포함), §4에 `PUT /booths/{boothId}/homepage` 절 신설(`PUT /booths/{boothId}/facade` 절 형식 미러). 헌법 24조 — facade endpoint가 같은 관례를 `BoothFacadeController` Javadoc에 남겼다
- [x] T013 전체 회귀 — `cd backend && ./mvnw test`. **T001 기준선(370 테스트 / 실패 0) 대비 기존 실패 0**이어야 한다. 신규분만 증가
- [x] T014 [P] 기록(헌법 29조) — `docs/24_작업일지.md` 오늘 날짜 섹션(AGENTS.md §5 의무 위치)과 `docs/HDD/작업일지.md`에 동일 항목. 문제 발생 시 `docs/25_트러블슈팅.md`에 T-번호로 등록(**해결 못 했어도 등록**)
- [ ] T015 통보·서명 초안 (**게시는 사용자 승인 후** — quickstart §4) — ⑴ FE: endpoint 신설 + `homepageUrl` 2곳 추가(가산적)·published 게이트·`null` 의미, 스튜디오 2건(`LAPTOP`에 `configId` 미전송 · `objectTypes.ts:36` `warnOnMissingConfig` → `false`), 오버레이 조회 전환 3건 ⑵ Unity: 변경 없음 참고 통보 ⑶ spec 016 리뷰칸 BE 몫 기입(docs/26 ①-17 서명 절차) ⑷ 리드: `spec.md` FR-001·FR-011 정본화 요청(확정 문구는 계약 §6)

---

## Dependencies

```text
Phase 1 (T001·T002)
  └─ Phase 2 (T003 Booth.java)          ← US1·US2 공통 차단
       ├─ Phase 3 US2 (T004→T005→T007, T006, T008→T009)
       └─ Phase 4 US1 (T010→T011)
            └─ Phase 6 (T012~T015)
```

- **T006 ↔ T010은 같은 파일**(`BoothQueryService.java`) — 순차로 처리한다.
- **T011은 T007이 만든 테스트 파일에 추가**하므로 T007 뒤다.
- T004 → T005(컨트롤러가 서비스 타입을 참조) → T007(테스트가 endpoint 호출).
- T008 → T009. T008은 US2의 다른 작업과 독립(다른 파일)이라 T004~T007과 병렬 가능.

## Parallel Opportunities

- Phase 1: T002는 T001과 병렬.
- Phase 3: **T008(Validator 계열)** 과 **T004~T007(endpoint 계열)** 은 파일이 겹치지 않아 병렬.
- Phase 6: T012·T014는 서로 병렬(T013 이후 무관).

## Implementation Strategy

**MVP = Phase 1~4** (T001~T011). 여기까지면 계약 §2·§3·§3-1이 전부 동작하고 FE가 착수할 수 있다.

증분 순서: Phase 2로 엔티티를 열고 → US2로 쓰기·검증·Publish 경고를 닫고 → US1로 노출 게이트를 얹는다. 각 Phase 끝에서 해당 통합 테스트만 돌려 독립 검증하고, Phase 6 T013에서 전체 회귀로 확정한다.
