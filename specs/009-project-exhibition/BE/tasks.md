# Tasks: 프로젝트 전시 (009 BE분)

**Jira**: S15P21A604-110 | **Branch**: `feat/S15P21A604-110-project-api` | **Date**: 2026-08-28

**Input**: [../spec.md](../spec.md)(공동 정본) · [plan.md](plan.md) · [research.md](research.md) ·
[data-model.md](data-model.md) · [../contracts/project-api.md](../contracts/project-api.md) ·
[quickstart.md](quickstart.md)

**Tests**: 포함한다. Jira 완료 조건 3개가 테스트를 명시하고, quickstart §3-1이 12 케이스를 지정한다.

---

## ⛔ 금지 작업 — 이 목록을 먼저 읽어라

정본 문구와 브리프 일부가 낡아 **잘못된 task가 생성되기 쉬운 자리들**이다. 아래를 하는 task는
만들지도, 실행하지도 않는다.

| 하지 마라 | 왜 |
|---|---|
| `project_links` 테이블 신설 | C-05가 V1 3칼럼으로 닫았고 정본 Key Entities도 2026-08-28 정정됐다 (R-01) |
| URL allowlist (YouTube·GitHub 등) | `spec 004 §D09`·`009 FR-004`는 **형식 검증만** 말한다. allowlist는 정상 배포·포트폴리오 URL을 거부해 **SC-002(링크 도달률 100%)를 깬다.** Jira `S15P21A604-110` 설명의 *"allow 정책"* 문구는 정본과 어긋난 것이고 GitLab #110에서 정정 통보했다 |
| 대표 이미지 업로드 · S3 경로 | C-03 — 업로드 미지원, URL 참조. **`docs/sdd/parts/BE.md:18`의 `CRUD + S3`는 낡은 서술**이고 T030이 지운다 (R-10) |
| 프로젝트를 Layout JSON에 넣기 | spec 005 계약과 무관하다 |
| 방문자 조회 · published 게이트 · 좋아요 수 | **S15P21A604-177 몫** |
| `videoUrl` 제공자 제한 | **C-02 미결(기획).** 지금은 형식 검증만. 소급 삭제·숨김도 하지 않는다 (R-08) |
| `specs/009` 리뷰 서명 채우기 | C-02가 아직 열려 있다 |
| `BE/spec.md` 스텁 생성 | #43 — 정본 2벌은 drift. 명세는 상위 `../spec.md`를 읽는다 |
| **직원 역할 게이트** (`ADMIN`/`CONTENT_EDITOR`만 편집) | spec 011 C-09가 2026-08-28 확정됐지만 **`BoothEditorGuard` 한 곳에서 011 구현 때 일괄로 닫는다.** 009만 걸면 같은 "편집자"가 endpoint마다 다른 뜻이 되고 005·016과 어긋난다. 그때까지 `CONSULTANT`도 편집 가능 — **알고 여는 창**이다 (R-11) |

> ⚠️ `setup-tasks.sh`·`setup-plan.sh`·`check-prerequisites.sh`는 `FEATURE_SPEC=BE/spec.md`를
> 조립하므로 **`BE/spec.md`가 없으면 에러로 멈춘다.** 스텁을 만들어 우회하지 말고, 명세 경로를
> 직접 지정해 진행한다. `speckit-analyze`도 상위 `spec.md` + `BE/plan.md` + `BE/tasks.md`를
> 명시적으로 대조시킨다.

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일이고 선행 task에 안 걸려 병렬 가능
- **[US1]/[US2]**: spec 009의 User Story. Setup·Foundational·Polish에는 라벨 없음

## Path Conventions

- 소스: `backend/src/main/java/com/example/ssafesta/`
- 테스트: `backend/src/test/java/com/example/ssafesta/`
- 마이그레이션: `backend/src/main/resources/db/migration/`
- **빌드는 Maven이다** — `cd backend && ./mvnw test` (Git Bash) · `backend\mvnw.cmd test` (PowerShell).
  **Gradle 아니다**

---

## Phase 1: Setup

- [x] T001 `cd backend && ./mvnw test`로 **회귀 기준선을 실측**하고 아래 표에 적는다. 참고값은 2026-08-28 016 머지 시점의 43 클래스 / 393 테스트지만 **그대로 믿지 말고 다시 잰다** — T007이 기존 코드를 건드리므로 이 숫자가 없으면 나중 실패가 내 것인지 원래 것인지 판정할 수 없다

  | | 클래스 | 테스트 | 실패 | 오류 | 스킵 |
  |---|---:|---:|---:|---:|---:|
  | 착수 시 `origin/develop` (`502fefc`, 2026-08-28 15:38) | **43** | **393** | **0** | 0 | 0 |
  | 완료 시 (2026-08-28, 리뷰 5라운드 반영 후) | **47** | **459** | **0** | 0 | 0 |

  > 참고값과 일치했다. surefire-reports 43개 파일 집계, `mvnw` exit 0.

- [x] T002 `backend/src/main/resources/db/migration/`에 **V14가 이미 없는지** 확인한다 (현재 V13까지). 다른 브랜치가 V14를 선점했으면 번호를 올리고 `data-model.md` §4와 plan의 파일명을 함께 고친다

  > 확인: `V1`~`V13`만 존재. **V14 비어 있음** — 계획대로 진행.

---

## Phase 2: Foundational (US1·US2 공통 선행 — 여기가 끝나야 스토리 시작)

- [x] T003 `common/ErrorCode.java`에 `PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.")` · `PROJECT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 부스에는 이미 프로젝트가 있습니다. 수정으로 변경해 주세요.")` 2건 추가. 기존 도메인 코드(`BOOTH_NOT_FOUND`·`GAME_NOT_FOUND`) 근처에 배치
- [x] T004 [P] `project/ProjectNotFoundException.java` 생성 — `projectId`를 담고 `ErrorCode.PROJECT_NOT_FOUND`로 매핑. `BoothNotFoundException` 형태를 따른다
- [x] T005 [P] `project/ProjectAlreadyExistsException.java` 생성 — `boothId`와 기존 `projectId`를 담고 `ErrorCode.PROJECT_ALREADY_EXISTS`로 매핑. `ActiveLeaseLimitException` 형태를 따른다
- [x] T006 `common/HttpUrlValidator.java` 신규 — 시그니처 `String validate(String value, String jsonField, String displayName)`. **`null`은 검사 없이 그대로 반환**하고 빈 문자열만 거부한다. 순서는 빈문자열 → 길이 2048 → `new URI` → `isAbsolute()` → **scheme(`http`/`https`, 대소문자 무시)** → host. **scheme이 host보다 반드시 먼저** — `javascript:`는 host가 없어 순서가 뒤바뀌면 스킴 위반 사유가 사라진다. 값은 trim·정규화 없이 원문 반환. 거부는 `ApiException(VALIDATION_FAILED, message, List.of(ApiErrorDetail.field(jsonField, message)))`. 문장 표는 `data-model.md` §3 #2
- [x] T007 `booth/BoothHomepageService.java`가 T006의 검증기를 쓰도록 바꾼다 — `validate(url, "homepageUrl", "홈페이지")`. **완료 조건: 기존 `BoothHomepageApiIntegrationTest` 19개가 그대로 통과한다.** 홈페이지 한국어 문구를 **바이트 단위로 보존**해야 하며, 한 글자라도 달라지면 그 테스트가 잡는다. `presence`(`{}` 거부) 판정은 서비스에 남기고 검증기로 옮기지 않는다

  > **게이트 통과** — `BoothHomepageApiIntegrationTest` **19/19, 실패 0** (2026-08-28 15:42).
  > `displayName="홈페이지"` 가 기존 네 문장을 바이트 그대로 만든다. 안 쓰게 된
  > `MAX_URL`·`URI` import·`isAllowedScheme` 는 제거했다.
- [x] T008 `backend/src/main/resources/db/migration/V14__project_one_per_booth.sql` 생성 — `CREATE UNIQUE INDEX ux_projects_booth ON projects(booth_id);` 한 줄 + 근거 주석. **중복 정리 단계 없음**(`projects`는 쓰기 경로 0건이라 행이 없다). 부분 인덱스 아님. V7 주석 형식을 따른다
- [x] T009 `project/Project.java` 엔티티 — `data-model.md` §1의 11칼럼 매핑. FK는 `@ManyToOne`이 아니라 `Long boothId` + `updatable = false` (`BoothLease.java:33` 스타일). `createdAt`은 `updatable = false`, `updatedAt`은 애플리케이션이 갱신(§5)
- [x] T010 [P] `project/ProjectRepository.java` — `Optional<Project> findByBoothId(Long boothId)`

**Checkpoint**: 여기까지 `./mvnw test`가 T001 기준선과 **같은 결과**여야 한다. 늘어난 실패가 있으면 T007이 원인이다.

---

## Phase 3: User Story 1 — 내 프로젝트를 전시한다 (P0) 🎯 MVP

**목표**: 소유자가 프로젝트를 등록·수정하고 자기 값을 되읽는다.

**Independent Test**: 임대된 부스에 `POST`로 등록 → `GET`으로 되읽기 → `PATCH`로 일부 교체 →
다시 `GET`. 저장한 값이 그대로 돌아오고, 같은 부스에 두 번째 `POST`는 거절된다.

### 구현

- [x] T011 [US1] `project/ProjectService.java`에 **presence 추적 명령 클래스**를 둔다 — `record` 금지. 7필드(`name`·`description`·URL 5종) 각각 값과 `present` 비트를 세우는 `@JsonProperty` setter. Jackson은 키가 있을 때만 setter를 부르므로 **키 누락 = 유지, 명시적 `null` = 삭제**가 성립한다 (C-06, R-03). `BoothHomepageService.HomepageCommand`를 7필드로 넓힌 형태
- [x] T012 [US1] `ProjectService.create(boothId, userId, command)` — `BoothEditorGuard.requireEditor` → `BoothLeaseRepository.findValidByBoothId`(없으면 `BoothExpiredException`) → `name` 제약 → URL 5종 검증 → `findByBoothId` 사전 검사(있으면 `ProjectAlreadyExistsException`) → **`saveAndFlush()`를 `try/catch` 안에서** 호출하고 `DataIntegrityViolationException`을 같은 예외로 번역. `save()`만 감싸면 유니크 위반이 커밋 시점에 터져 번역을 우회하고 500이 나간다 (`BoothLeaseService.java:97-106` 선례, R-02)
- [x] T013 [US1] `ProjectService.update(projectId, userId, command)` — `findById`(없으면 `ProjectNotFoundException`) → 그 행의 `boothId`로 `requireEditor`(**타 부스 차단**) → 유효 임대 확인 → **본문이 `{}`면 400** → presence별 적용(`name: null`은 400) → 값이 하나라도 바뀌면 `updatedAt` 갱신
- [x] T014 [US1] `ProjectService.findByBooth(boothId, userId)` — `requireEditor`만 통과하면 **published 게이트 없이** 저장값 반환. **만료 부스도 읽을 수 있다**(FR-008). 결과는 0~1개 **배열**(C-01 파생 ⑵)
- [x] T015 [US1] `project/ProjectController.java` — `POST`·`GET /api/v1/booths/{boothId}/projects`, `PATCH /api/v1/projects/{projectId}`. 인증 주체는 **`MemberPrincipal.requireMemberId(jwt, "회원 계정만 프로젝트를 편집할 수 있습니다.")`** — `booth/BoothPrincipal`은 package-private라 이 패키지에서 못 쓴다. `POST`는 `201`, 나머지 `200`. 응답 DTO는 `record`(키가 조건부로 사라지지 않게)

### 테스트 — `backend/src/test/java/com/example/ssafesta/project/ProjectApiIntegrationTest.java`

`BoothHomepageApiIntegrationTest` 구조를 따른다 (`@SpringBootTest` + `TestcontainersConfiguration`
+ `MockMvc`, `BoothTestSupport.createMemberWithWallet`, `BoothLayoutTestSupport.grantLease`,
`@BeforeEach releaseAllSlots`).

- [x] T016 [P] [US1] 등록 성공 — `name`만 보낸 `POST` → `201`, URL 5필드가 **키는 있고 값은 `null`**. 키 존재를 함께 단언한다 (C-03, I-4)
- [x] T017 [P] [US1] 빈 목록 — 프로젝트 없는 부스의 `GET` → `200 {"projects": []}`. **404가 아니다**
- [x] T018 [P] [US1] 왕복 무손실 — 스킴을 `HtTpS`로 저장하고 바이트 그대로 반환되는지. 정규화가 끼어들면 이 케이스만 잡는다 (I-3)
- [x] T019 [P] [US1] C-06 세 갈래 — `{"description": null}` 삭제 / 키 누락 유지 / `{}` → 400. ⚠️ **`jsonPath().doesNotExist()`를 쓰지 마라** — 명시적 `null`도 통과한다(T-97). `value(nullValue())` + 키 존재를 함께 단언한다
- [x] T020 [P] [US1] URL 5필드 삭제 — **`@ParameterizedTest`로 5개 필드**를 돌려, 값이 있는 상태에서 `{"<필드>": null}` `PATCH` → 그 필드만 `null`이 되고 나머지 4개는 그대로
- [x] T021 [P] [US1] 부스당 1개 — 같은 부스에 `POST` 두 번 → 두 번째 `409 PROJECT_ALREADY_EXISTS` **(Jira 완료 조건)**
- [x] T022 [P] [US1] 타 부스 차단 — A 부스 소유자 토큰으로 B 부스 프로젝트에 `PATCH` → `403 BOOTH_EDITOR_FORBIDDEN` **(Jira 완료 조건)**
- [x] T023 [P] [US1] 만료 부스 — 임대 만료 후 `PATCH` → `409 BOOTH_LEASE_EXPIRED`, 그러나 **`GET`은 값을 돌려준다** (FR-008, R-06)
- [x] T024 [P] [US1] 게스트 거부 — 게스트 토큰으로 세 endpoint 전부 → `403 MEMBER_ONLY` (헌법 12조)
- [x] T025 [P] [US1] `project/ProjectConcurrencyIntegrationTest.java` — 같은 부스에 `POST` 2건 동시 → 하나만 `201`, 나머지 `409 PROJECT_ALREADY_EXISTS`. **유니크 제약과 `saveAndFlush` 번역이 실제로 도는지** 본다. `BoothLeaseConcurrencyIntegrationTest` 형태

**Checkpoint**: US1 단독으로 배포 가능. FE가 등록·수정 폼을 만들 수 있다.

> **구현 중 계획과 달라진 것 3가지** (전부 테스트 쪽이고 계약은 안 바뀌었다)
>
> 1. **US2 를 별도 클래스로 뺐다** — `ProjectValidationApiIntegrationTest`. 계획은 한 클래스였는데
>    검증 케이스가 커졌다. 그래서 신규 **3 클래스**(계획 2), 기준선 43 → 46.
> 2. **`BoothTestSupport`·`BoothLayoutTestSupport` 를 `public` 으로 열었다** — package-private 라
>    `project` 테스트에서 못 부른다. 클래스 + 쓰는 메서드 4 개만. 프로덕션 영향 0.
> 3. **닉네임 prefix 예산은 7 자다** — `nickname VARCHAR(30)` 인데 헬퍼가
>    `prefix + seq + "_" + nanoTime` 을 쓴다. 파라미터화 테스트에서 필드명을 prefix 에 붙이면 터진다.
>
> 덤으로: 이 저장소 Jackson 은 **3(`tools.jackson`)** 이라 `com.fasterxml…ObjectMapper` 는 빈이 아니다.
> `JsonMapper` 를 주입한다 — 애너테이션(`@JsonProperty`)만 `com.fasterxml` 이라 헷갈린다.

---

## Phase 4: User Story 2 — 잘못된 링크로 사고가 나지 않는다 (P1)

**목표**: 형식이 잘못된 값이 거부되고 **무엇이 왜 틀렸는지**가 전달된다.

**Independent Test**: 5개 URL 필드 각각에 `javascript:alert(1)`을 넣으면 `400`이 나오고,
`errors[0].field`가 그 필드이며, 메시지가 "형식 오류"가 아니라 **스킴 위반**을 말한다.

- [x] T026 [P] [US2] URL 검증 거부 계약 — **`@ParameterizedTest`로 5개 URL 필드**에 `javascript:alert(1)` 투입 → `400 VALIDATION_FAILED` + `errors[0].rule == "FIELD_INVALID"` + `errors[0].field == <그 필드명>`. **메시지가 `형식이 올바르지 않습니다`가 아니라 `http 또는 https로 시작해야 합니다`인지까지 단언한다** — 검증 순서(scheme > host)가 살아 있는지 잡는 **유일한 자리**다 **(Jira 완료 조건)**
- [x] T027 [P] [US2] `name` 경계 — 100자 통과 / 101자 `400` / `null` `400` / 공백만 `400`. 각각 `field == "name"`
- [x] T028 [P] [US2] URL 길이·형식 나머지 — 2049자 `400`, `not a url` `400`, `data:text/html,x` `400`(스킴 사유). 5필드 중 대표 1개로만 확인해도 된다 (T026이 필드 매핑을 이미 덮는다)

**Checkpoint**: SC-004(형식이 잘못된 링크 등록 0건)의 서버 몫이 성립한다.

---

## Phase 5: Polish & Cross-Cutting

문서 반영은 **구현과 같은 커밋**이다 (016 선례 `fa50d91`).

- [x] T029 [P] `docs/08_Backend_API_명세서.md` **§5 Project** — endpoint 4개의 이름뿐인 서술을 실제 shape·오류·게이트로 교체. homepage 절(`:443`)처럼 **endpoint별 실패 목록**을 적는다. 내용은 `../contracts/project-api.md`에서 가져온다
- [x] T030 [P] `docs/08_Backend_API_명세서.md` **§18 주요 오류 코드**(`:867` 표)에 `PROJECT_NOT_FOUND` · `PROJECT_ALREADY_EXISTS` 추가. ⚠️ **§1.3-1 전역 `rule` 목록에는 넣지 마라** — 그 표는 `errors[].rule` 전용이고 이 둘은 봉투 **최상위 `code`**다. 이 API가 내는 rule은 기존 `FIELD_INVALID` 하나뿐이라 전역 표에 추가할 것이 없다 (R-07)
- [x] T031 [P] `docs/sdd/parts/BE.md:18` — 009 행의 `CRUD + S3`에서 **S3를 지운다.** C-03이 업로드 미지원으로 닫았고, 이 낡은 한 줄이 "S3 업로드" task를 다시 만들어 낸다 (R-10)
- [x] T032 `docs/24_작업일지.md`에 2026-08-28 항목 기록. 문제가 생겼으면 **해결 여부와 무관하게** `docs/25_트러블슈팅.md`에 T-번호로 등록하고 일지에서는 번호로 링크 (헌법 29조)
- [x] T033 전체 회귀 — `cd backend && ./mvnw test`. **T001 기준선 + 2 클래스, 실패 0.** 특히 `BoothHomepageApiIntegrationTest` 19개가 살아 있는지 확인하고 T001 표의 "완료 시" 행을 채운다
- [x] T034 quickstart §4의 **손 왕복 8단계**를 실제 스택으로 한 번 돌린다. 4번(스킴 사유 문장)이 사용자에게 어떻게 보이는지 사람이 한 번 읽는 것이 이 task의 목적이다

  > **2026-08-28 실행 — 12건 전부 통과** (8단계 + 포트·한글도메인·401·404 추가 4건).
  > 실제 HTTP 응답: `"영상 주소는 http 또는 https로 시작해야 합니다."` + `field: "videoUrl"` —
  > "형식 오류"가 아니다. 한글 도메인은 punycode 변환 없이 원문 그대로 저장·반환됐다.
  >
  > **quickstart §4 가 준비 절차를 생략하고 있었다.** 실제로 돌려 보니 셋에서 막힌다 —
  > ⑴ `JWT_SECRET` 은 base64 라 평문이면 기동 실패 ⑵ 회원 토큰을 HTTP 로 얻을 경로가
  > OAuth 뿐이라 직접 발행해야 한다 ⑶ `SessionRevocationFilter` 가 `auth:session:{userId}`
  > 를 Redis 와 대조한다. 셋 다 §4-1 에 적었다. Git Bash 의 `curl -d` 한글 깨짐도 함께.
  >
  > 곁다리: `ddl-auto: validate` 부팅 성공 = 엔티티 매핑 일치, `flyway_schema_history` 최신
  > `14 | project one per booth | t`, `pg_indexes` 에 `ux_projects_booth` — **Testcontainers
  > 밖에서 V14 를 처음 확인**했다.
- [x] T035 MR `[S15P21A604-110][BE] 프로젝트 전시 등록·수정 API` → `develop`, Default 템플릿. **`Closes S15P21A604-110`이 develop에 도달하는 커밋 메시지에 있는지 머지 시점에 눈으로 확인한다** — MR 설명에만 있으면 전환이 발화하지 않는다. squash 커밋과 merge 커밋 메시지를 **둘 다** 본다 (109 실측: squash `2545eb3`에는 없었고 merge `4169e79` 본문에 있어 발화)

  > **2026-08-28 게시 — [MR !120](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/merge_requests/120)**.
  > 커밋 10개, `origin/develop` 리베이스 후 회귀 재확인(47 클래스 / 459 테스트, 실패 0).
  > **`Closes S15P21A604-110` 은 아직 어느 커밋에도 없다** — 머지 다이얼로그에서 squash·merge
  > 메시지에 넣어야 전이가 발화한다. MR 본문 "머지 시 확인" 절에 그 사실을 적어 뒀다.

---

## Dependencies & Execution Order

### Phase 순서

```
Phase 1 (T001~T002)
   ↓
Phase 2 (T003~T010)  ← 여기가 끝나야 스토리 시작
   ↓
Phase 3 US1 (T011~T025)  ← MVP
   ↓
Phase 4 US2 (T026~T028)  ← US1의 서비스·컨트롤러 위에서 검증만 확인
   ↓
Phase 5 (T029~T035)
```

### Phase 2 내부 순서 — 이것만 지키면 나머지는 자유

```
T003 (ErrorCode) ─┬─ T004 [P]
                  └─ T005 [P]
T006 (검증기) → T007 (홈페이지 위임) ← 게이트: 기존 19개 통과
T008 (V14) → T009 (엔티티) → T010 [P] (Repository)
```

**T006 → T007을 T009보다 먼저** 두는 것이 이 순서의 유일한 비자명한 선택이다. 기존 코드를
건드리는 유일한 작업이고 안전망이 이미 있다 — 뒤로 미루면 새 코드의 실패와 회귀가 같은 실행에서
섞인다 (plan Implementation Order).

### Phase 3 내부

```
T011 (명령 클래스) → T012·T013·T014 (서비스) → T015 (컨트롤러) → T016~T024 [P] → T025
```

T016~T024는 컨트롤러가 선 뒤 **전부 병렬**이다. 같은 파일(`ProjectApiIntegrationTest`)이라
한 사람이 쓴다면 순차지만, 케이스끼리 의존이 없다.

### User Story 의존

- **US1 (P0)**: Phase 2에만 의존. 단독 배포 가능
- **US2 (P1)**: US1의 서비스·컨트롤러 위에서 **검증 결과만 확인**한다. 검증기 자체는 Phase 2에
  있으므로 US2는 순수 테스트 phase다

### 병렬 기회

| 묶음 | task |
|---|---|
| 예외 2개 | T004 · T005 |
| US1 테스트 9개 | T016 ~ T024 |
| US2 테스트 3개 | T026 ~ T028 |
| 문서 3개 | T029 · T030 · T031 |

---

## Implementation Strategy

### MVP — US1만 (T001~T025)

여기까지가 "소유자가 프로젝트를 등록·수정하고 되읽는다"의 전부다. FE가 수정 폼을 만들 수 있는
지점이고, 단독으로 develop에 올려도 다른 파트를 깨지 않는다(전부 가산적).

### 증분

1. Phase 1·2 → 커밋. 기존 기능 무변화, 검증기만 공용화
2. Phase 3 → 커밋. **US1 동작**
3. Phase 4·5 → 커밋. 거부 계약 + 문서

셋을 한 MR로 묶는다 — 문서 반영이 구현과 같은 커밋이어야 하고(016 선례), 나눠 올리면
`docs/08`이 구현 없는 계약을 먼저 말하게 된다.

---

## Notes

- **Maven이다.** `cd backend && ./mvnw test`. `./gradlew`는 이 저장소에 없다
- 실패는 **조용히 기본값으로 되돌리지 않는다.** 사유별로 다른 문장으로 거부한다 (T-24)
- `jsonPath().doesNotExist()`는 **명시적 `null`도 통과**한다 (T-97). 키 존재 단언에 쓰지 마라
- `saveAndFlush()`를 `try/catch` 밖에 두면 유니크 위반이 커밋 시점에 터져 500이 나간다 (R-02)
- 커밋마다 `(S15P21A604-110)` 키를 넣는다. `Closes`는 **마지막 develop행 커밋에만**
