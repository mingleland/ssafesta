# Tasks: 설문 (010 BE분)

**Jira**: S15P21A604-130 · -190 · -131 · -192 · -132 · -193 · -191 | **Branch**: `feat/S15P21A604-130-survey-domain` → `-131-survey-responses` → `-132-survey-results` | **Date**: 2026-09-07

**Input**: [../spec.md](../spec.md)(공동 정본) · [plan.md](plan.md) · [research.md](research.md) ·
[data-model.md](data-model.md) · [../contracts/survey-api.md](../contracts/survey-api.md) ·
[quickstart.md](quickstart.md)

**Tests**: 포함한다. Jira 완료 조건 7개가 테스트를 명시하고(`-130` 생성·조회 통합, `-190` 12케이스, `-131` 6유형·409, `-192` 지급 1회, `-132` fixture 대조, `-193` 경계 0건, `-191` 봉투 규격), quickstart §3이 케이스를 전부 지정한다.

---

## ⛔ 금지 작업 — 이 목록을 먼저 읽어라

정본 문구·티켓 본문·낡은 문서가 **잘못된 task를 만들기 쉬운 자리들**이다. 아래를 하는 task는 만들지도, 실행하지도 않는다.

| 하지 마라 | 왜 |
|---|---|
| `BE/spec.md` 스텁 생성 | #43 — 정본 2벌은 drift. 명세는 상위 `../spec.md`를 읽는다. 스크립트가 `FEATURE_SPEC=BE/spec.md`를 조립해 멈추면 경로를 직접 지정한다 |
| `../spec.md` 리뷰 3칸 서명 채우기 | **C-01·C-02·C-03·C-05가 기획 대기다.** 009가 같은 이유로 비워 뒀다("C-02가 아직 열려 있다") |
| `docs/09` §16~§18 컬럼명 따라가기 (`type`·`title`·`order_no`·`text_value`·`selected_option_ids JSONB`) | **V1 DDL이 정본**이다. `question_type`·`question_text`·`is_required`·`display_order`·`text_answer`·`rating_value` + 조인 테이블 `survey_answer_options` (R-10) |
| 검증 실패에 **422** 사용 | 전역 봉투는 `400 VALIDATION_FAILED` + `errors[].field`(`docs/08` §1.3). `-190` 본문의 422는 Jira에 정정 통보했다 |
| **커서** 페이지네이션 | 전역 규약은 `page`/`size`이고 FE Port도 `page: number`다. `-193` 본문의 커서는 Jira에 정정 통보했다. 경계 0건은 `id ASC` 고정으로 충족 (C-09) |
| 새 `*Exception` 클래스 신설 | `ApiException(ErrorCode.SURVEY_*)` 인라인. 클래스를 만들면 `DomainExceptionEnvelopeTest` 대상이 4개 늘고 값이 없다 (R-06) |
| 중첩 `@OneToMany(cascade=ALL, orphanRemoval=true)` | 삭제·삽입 순서가 `UNIQUE(survey_id, display_order)`·`survey_options` FK에 걸린다. main에 `@OneToMany` 0건이 그 규칙이다 (R-03) |
| SMALLINT를 `Integer`로 매핑 | `ddl-auto: validate`가 기동을 막는다. `short`/`Short` (R-12) |
| 상태 전환 API(`OPEN`↔`CLOSED`) · `surveys.status` CHECK | FE Builder에 게시·마감 버튼이 없다. 마감은 `closesAt` 경과 (C-07). 아무것도 쓰지 않는 상태를 스키마에 새기지 않는다 |
| `DELETE /booths/{boothId}/survey` | FE에 리셋 화면이 없다. 응답이 쌓인 뒤 갈아엎을 길이 없는 것은 **알고 두는 천장** — 코드에 `ponytail:` 주석만 남긴다 |
| 익명 여부·1인1응답 **토글** (FR-003) | 스키마 컬럼도 FE 토글도 없다. 항상 1인1응답·항상 익명 고정으로 FR-009·SC-003을 구조적으로 충족한다 |
| 지원서 제출자별 상세 조회 | **C-03 미결** + 티켓 없음. 주관식 항목에 `responseId`만 실어 열쇠를 남긴다 |
| 집계 캐시·집계 테이블 | C-04 — MVP는 원본 실시간. 캐시는 SC-001을 지킬 자리를 하나 늘린다 |
| `LayoutConfigResolver`에 `SURVEY_KIOSK` 검증 추가 | 설문 바인딩이 부스 기준(C-06)이라 `configId`를 쓰지 않는다. `CONFIG_UNVERIFIED` 경고 그대로 |
| `booth_daily_metrics.survey_response_count` 갱신 | 대시보드 `S15P21A604-501` 몫 |
| 고급 분석 (교차·추이·AI·상관·전환율·내보내기) | **FR-013이 명시적으로 제외** |
| `ProjectService`·`BoothQueryService`·`BoothLayoutQueryService`의 방문자 게이트 복제본 리팩터링 | 가드에 `requireVisitorVisible`을 **추가**하고 설문만 쓴다. 동결된 세 곳을 건드리면 회귀 위험만 늘어난다 (R-07) |
| `docs/09` 정합화 | 별 작업이다. `data-model.md`에 낡았다는 사실만 적어 다음 사람이 같은 혼동을 하지 않게 한다 |

---

## Format: `[ID] [P?] [Story] Description (JIRA)`

- **[P]**: 다른 파일이고 선행 task에 안 걸려 병렬 가능
- **[US1]/[US2]**: spec 010의 User Story. Setup·Foundational·Polish에는 라벨 없음

## Path Conventions

- 소스: `backend/src/main/java/com/example/ssafesta/`
- 테스트: `backend/src/test/java/com/example/ssafesta/`
- 마이그레이션: `backend/src/main/resources/db/migration/`
- 롤백: `backend/db/rollback/`

---

## Phase 0: SDD 산출물 (MR ①)

- [x] T001 `specs/010-survey/contracts/survey-api.md` — endpoint 6개·오류표·FE Mapper 표 (S15P21A604-130)
- [x] T002 `specs/010-survey/BE/plan.md` (S15P21A604-130)
- [x] T003 `specs/010-survey/BE/research.md` R-01~R-12 (S15P21A604-130)
- [x] T004 `specs/010-survey/BE/data-model.md` — V22 delta·엔티티 5·불변식 7·검증 순서 (S15P21A604-130)
- [x] T005 `specs/010-survey/BE/quickstart.md` — 기준선 843·케이스 목록·변이 표 (S15P21A604-130)
- [x] T006 이 파일 (S15P21A604-130)

## Phase 1: Setup (MR ①)

- [ ] T007 `db/migration/V22__survey_guest_and_rating_scale.sql` — data-model §2 그대로. V21 헤더 관례(무엇·왜·되돌리기 경로) (S15P21A604-130)
- [ ] T008 `backend/db/rollback/V22__rollback.sql` + `README.md` 표 행 — 게스트 응답 자식부터 삭제 후 복원 (S15P21A604-130)
- [ ] T009 [P] `survey/SurveyProperties.java` — `record` `@ConfigurationProperties("app.survey")`, compact ctor에서 `maxQuestions`·`maxOptions`·`maxRewardCoin` 양수 검증. `survey/SurveyConfiguration.java`로 등록 (S15P21A604-130)
- [ ] T010 [P] `application.yml` `app.survey` 블록 + 주석(C-01·C-02 미결이라 설정으로 둔다는 근거) (S15P21A604-130)
- [ ] T011 [P] `common/ErrorCode.java` — `SURVEY_NOT_FOUND`(404) · `SURVEY_CLOSED`(409) · `SURVEY_ALREADY_RESPONDED`(409) · `SURVEY_LOCKED`(409), 한글 기본 메시지 (S15P21A604-130)
- [ ] T012 [P] `common/OpenApiConfiguration.java` `tags()`에 `tag("Survey", …)` — Project 뒤. **빠뜨리면 `OpenApiDocumentationTest`가 실패한다** (S15P21A604-130)

## Phase 2: Foundational (MR ①) — 여기가 선행이다

- [ ] T013 `booth/BoothAccessGuard.java` — `public Booth requireVisitorVisible(Long boothId)` 추가(부스 → `requireActiveLease` → `isPublished` 아니면 `LayoutNotPublishedException`). 기존 세 복제본은 건드리지 않는다 (R-07) (S15P21A604-130)
- [ ] T014 [P] `booth/BoothRepository.java` — `@Lock(PESSIMISTIC_READ) Optional<Booth> findWithSharedLockById(Long id)` + 왜 공유 락인지 주석 (R-08) (S15P21A604-130)
- [ ] T015 **`user/AccountDeletionService.java` 설문 6줄 키 수정** — `created_by_user_id` → `booth_id IN (SELECT id FROM booths WHERE owner_user_id = ?)`, 응답은 `OR respondent_user_id = ?` 유지. **지금 스태프가 만든 설문이 있으면 소유자 탈퇴가 FK 위반으로 실패한다** (R-09) (S15P21A604-130)
- [ ] T016 [P] `survey/SurveyQuestionType.java` — enum 6종 + `isChoice()`·`isText()`·`isRating()` (S15P21A604-130)
- [ ] T017 `survey/Survey.java`·`SurveyQuestion.java`·`SurveyOption.java` — 평면 `Long` 참조, SMALLINT는 `short`/`Short`, `createdByUserId`는 항상 부스 소유자 (data-model §3) (S15P21A604-130)
- [ ] T018 `survey/SurveyRepository.java`(`findByBoothId`) · `SurveyQuestionRepository.java`(`findBySurveyIdOrderByDisplayOrder`, `@Modifying(clearAutomatically, flushAutomatically) deleteBySurveyId`) · `SurveyOptionRepository.java`(`findByQuestionIdInOrderByDisplayOrder`, 벌크 `deleteByQuestionIdIn`) — `GameAssetRepository:110` 모양 (R-03) (S15P21A604-130)

## Phase 3: US1 — 부스 운영자가 설문을 만들고 방문자가 답한다

**Goal**: 운영자가 6유형 문항으로 설문을 저장하면 방문자가 그것을 열어 답하고 보상을 받는다.
**Independent Test**: 소유자 `PUT` → 방문자 `run` → `POST` → 지갑 +N 이 한 번에 돈다.

### MR ① — 편집·조회 (-130 · -190)

- [ ] T019 [US1] `survey/SurveyService.java` — `findForEditor(boothId, userId)`(`requireEditor`만, 임대 무관) (S15P21A604-130)
- [ ] T020 [US1] `SurveyService.upsert(boothId, userId, command)` — data-model §4 순서: 게스트 → `requireActiveEditor` → `findWithLockById` → 기존 조회·응답 수 → 구조 비교 잠금 → 필드 검증 → 저장. 문항은 옵션·문항 벌크 삭제 후 `saveAll` (S15P21A604-130 · S15P21A604-190)
- [ ] T021 [US1] `SurveyService` nested DTO — `SurveyCommand`(**클래스**, `description`·`rewardCoin`·`closesAt`은 `PresenceField`) · `SurveyView` · `RunView`(record). `PresenceField` 없으면 FE 저장마다 보상이 0으로 지워진다 (R-05) (S15P21A604-130)
- [ ] T022 [US1] `SurveyService.findRun(boothId, now)` — `requireVisitorVisible`, `closed = endsAt != null && !endsAt.isAfter(now)`, `rewardCoin` 포함(게스트 사전 안내용) (S15P21A604-130)
- [ ] T023 [US1] `survey/SurveyController.java` — endpoint 3개(§3·§4·§5). `@Tag("Survey")`·`@Operation(summary, description)`·`@ApiResponses` 4xx 전부·`@SecurityRequirement`. `run`에도 `@SecurityRequirement`(401 자동 문서화 조건) (S15P21A604-130)
- [ ] T024 [US1] `survey/SurveyApiIntegrationTest.java` — quickstart §3-1 케이스 31개. 픽스처 `BoothTestSupport.createMemberWithWallet`·`BoothLayoutTestSupport.grantLease/publishLayout/expireLease`, 토큰 `MemberSessionService.issue`·`AccessTokenService.issueGuestToken` (S15P21A604-130 · S15P21A604-190)

### MR ② — 제출·보상 (-131 · -192)

- [ ] T025 [US1] `wallet/CoinReason.java` — `SURVEY_REWARD` 상수 + javadoc(spec 010). 그 클래스가 그러라고 만들어졌다 (S15P21A604-192)
- [ ] T026 [US1] `survey/SurveyResponse.java`·`SurveyAnswer.java` — 응답자 회원 xor 게스트, `rewardLedgerEntryId` (S15P21A604-131)
- [ ] T027 [US1] `survey/SurveyResponseRepository.java`(`existsBySurveyIdAndRespondentUserId`·`…GuestKey`, `countBySurveyId`) · `SurveyAnswerRepository.java` + `survey_answer_options` native insert (`ProjectRepository` 모양) (S15P21A604-131)
- [ ] T028 [US1] `survey/SurveyResponseService.submit` — data-model §4 순서. 토큰 주체 판정은 `WorldSessionService.identityOf` 모양(MEMBER→id / GUEST→subject / 그 외 401). **`saveAndFlush` catch 뒤에는 아무 쿼리도 두지 않는다** (R-04) (S15P21A604-131)
- [ ] T029 [US1] 보상 — 회원 && `rewardCoin > 0`이면 `wallets.credit(...)`, 멱등키 `SURVEY_REWARD:{surveyId}:{userId}`, `reward_ledger_entry_id` 연결. **응답 flush 뒤, 같은 트랜잭션** (S15P21A604-192)
- [ ] T030 [US1] `SurveyController` — `POST /surveys/{surveyId}/responses` (§6). 201 `{responseId, rewardedCoin}`, 보상 없으면 `0` (S15P21A604-131)
- [ ] T031 [US1] `survey/SurveyResponseApiIntegrationTest.java` — quickstart §3-2 케이스 21개. `BoothTestSupport.assertBalanceMatchesLedger` 포함 (S15P21A604-131 · S15P21A604-192)

## Phase 4: US2 — 운영자가 결과를 수치로 확인한다 (MR ③)

**Goal**: 응답 수·선택지별 수·별점 평균·분포·주관식 목록을 서버가 계산해 준다.
**Independent Test**: 20응답 fixture의 집계값이 손으로 센 값과 일치한다.

- [ ] T032 [US2] `survey/SurveyResultService.results(surveyId, userId)` — `requireEditor`(임대 무관). 집계 5쿼리(data-model §5). **문항 목록을 기준으로 조립**해 응답 0건에도 모든 문항이 실린다 (S15P21A604-132)
- [ ] T033 [US2] `SurveyResultService.textAnswers(surveyId, userId, questionId?, page, size)` — `id ASC` 고정, `page`/`size` 검증(0 이상 / 1~100) (S15P21A604-193)
- [ ] T034 [US2] `ResultsView`·`QuestionAggregateView`·`TextAnswerPage` records — `counts`·`average`·`distribution` 키가 유형과 무관하게 항상 존재(값 없으면 `[]`·`null`). 비율은 싣지 않는다 (S15P21A604-132)
- [ ] T035 [US2] `SurveyController` — endpoint 2개(§7·§8) (S15P21A604-132 · S15P21A604-193)
- [ ] T036 [US2] `survey/SurveyResultApiIntegrationTest.java` — quickstart §3-3 케이스 13개. **20응답 수기 대조**와 **응답 본문에 `respondent`·`userId`·`nickname` 문자열 0건**(SC-003) 포함 (S15P21A604-132 · S15P21A604-193 · S15P21A604-191)

## Phase 5: Polish & Cross-Cutting (MR ③)

- [ ] T037 [P] `docs/08_Backend_API_명세서.md` §9 — stub 6줄을 실제 endpoint 6개로 교체. §18에 `SURVEY_NOT_FOUND`·`SURVEY_LOCKED` 행 추가(`SURVEY_CLOSED`·`SURVEY_ALREADY_RESPONDED`는 이미 있다) (S15P21A604-191)
- [ ] T038 [P] `specs/README.md:26` — 010 행의 plan·tasks 열을 `✅ BE분`으로 (S15P21A604-130)
- [ ] T039 [P] `docs/26_팀_결정_필요사항.md` row 20·21 — 구현 기본값 기록(게스트 허용·보상 설문 차단 / 상한 10), 결정 로그에 2026-09-07 행 (S15P21A604-130)
- [ ] T040 [P] `docs/HDD/작업일지.md` — 010 BE 착수·완료 기록. 문제 발생 시 `docs/HDD/트러블슈팅.md` T-135부터 (헌법 29조) (S15P21A604-191)
- [ ] T041 변이 확인 — quickstail §5 표 14개를 각 MR 머지 전에 돌린다 (S15P21A604-191)
- [ ] T042 quickstart §4 손 왕복 실행 후 §4-3에 결과 기록 (S15P21A604-191)

---

## Dependencies & Execution Order

```
Phase 0 (문서) ─┐
Phase 1 (Setup) ┼→ Phase 2 (Foundational) → Phase 3 MR① → Phase 3 MR② → Phase 4 MR③ → Phase 5
                │
        T007 V22 ── T017 엔티티 (ddl-auto: validate 라 스키마가 먼저)
```

- **T007(V22)이 T017(엔티티)보다 먼저다** — `ddl-auto: validate`라서 컬럼이 없으면 기동이 실패한다.
- **T013·T015가 T019~T024보다 먼저다** — 가드와 탈퇴 SQL이 없으면 테스트가 통과할 수 없다.
- **MR ②는 MR ① 머지 후 develop에서 분기**한다(T026이 `Survey`를 읽는다). **MR ③은 MR ② 머지 후**(T032가 `SurveyResponse`를 읽는다).
- `-192`는 `-131` 위에 얹히고 `-193`은 `-132` 위에 얹힌다 — Jira blocked-by와 같다.

## Parallel Examples

- Phase 1: T009·T010·T011·T012가 서로 다른 파일이고 의존이 없다.
- Phase 2: T014·T016이 T013·T015와 병렬.
- Phase 5: T037·T038·T039·T040이 전부 다른 문서.

## Implementation Strategy

1. **MR ①이 가장 중요하다** — 계약이 develop에 닿는 순간 FE가 어댑터를 짜기 시작할 수 있다. 문서(Phase 0)를 코드와 같은 MR에 넣는 이유가 그것이다.
2. 각 MR은 **그 자체로 회귀 없이 통과**해야 한다. 기준선 843 위에서 증가분만 본다.
3. MR이 develop에 닿을 때마다 #133에 한 줄 남긴다 — FE가 단계별로 붙일 수 있게.
4. **FE에 알릴 것 3건**(MR ① 통보에 포함): `submitAnswers` 반환이 `void` → `{responseId, rewardedCoin}` / 합성 id `booth-{boothId}` → `run` 응답의 `surveyId` / **Builder에 `rewardCoin` 입력이 없어 보상은 서버만 준비된 상태**(FE 티켓 없음).
