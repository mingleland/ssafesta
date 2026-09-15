# Quickstart: 설문 (010 BE분)

**Spec**: [../spec.md](../spec.md) | **Plan**: [plan.md](plan.md) | **Contract**: [../contracts/survey-api.md](../contracts/survey-api.md) | **Date**: 2026-09-07

---

## 1. 전제

| 항목 | 값 |
|---|---|
| 빌드 | Maven — `backend/mvnw` (Windows `backend\mvnw.cmd`) |
| 통합 테스트 | Testcontainers — **Docker 필요**. `pgvector/pgvector:pg17` + `redis:7.2-alpine` |
| 프로필 | `spring.profiles.default=local`. 필수 env는 `src/test/resources/application-local.properties`가 채운다 |
| 로컬 스택 | `ssafesta-local-postgres-1`(5432) · `ssafesta-local-redis-1`(6379) — 손 왕복(§4)에 쓴다 |
| 마이그레이션 | 다음 번호 **V22**. V19는 없다(V18 → V20 → V21) |

Docker가 꺼져 있으면 통합 테스트가 전부 실패한다 — T-97과 같은 자리다.

---

## 2. 회귀 기준선 — 착수 직후 재고 적어 둔다

```bash
cd backend && ./mvnw test
```

**2026-09-07 origin/develop(`75baf555`) 기준: `Tests run: 843, Failures: 0, Errors: 0, Skipped: 0` · BUILD SUCCESS.**

MR별로 이 수치 위에 증가분만 본다. 작업일지의 이전 수치(09-03 729건)는 낡았다 — 그 사이 AI 문서·게임 에셋·SSAFY 로그인이 들어왔다.

---

## 3. 자동 검증

### 3-1. `SurveyApiIntegrationTest` (MR ①)이 덮어야 하는 것

| # | 케이스 | 기대 |
|---|---|---|
| 1 | 소유자 `PUT` → `GET` 왕복 | 200, 문항·선택지·척도가 보낸 대로 |
| 2 | 스태프 `PUT` | 200 (편집자 범위가 facade·layout과 같다) |
| 3 | 남의 부스 소유자 `PUT`·`GET` | 403 `BOOTH_EDITOR_FORBIDDEN` |
| 4 | 게스트 `PUT`·`GET` | 403 `MEMBER_ONLY` |
| 5 | 없는 부스 | 404 `BOOTH_NOT_FOUND` |
| 6 | 임대 만료 부스 `PUT` | 409 `BOOTH_LEASE_EXPIRED` |
| 7 | 임대 만료 부스 `GET` | **200** — 소유자는 자기 설문을 되읽는다 (FR-011) |
| 8 | 설문 없는 부스 `GET` | 404 `SURVEY_NOT_FOUND` |
| 9 | `PUT` 두 번 (문항 교체) | 200, 두 번째 내용만 남고 `display_order` 유니크 위반 없음 |
| 10 | `PUT`에 `rewardCoin` 키 없이 재저장 | 기존 `rewardCoin` **유지** (R-05) |
| 11 | `PUT`에 `description: null` 명시 | 비워진다 |
| 12 | 방문자 `run` — 게시된 부스, 회원 | 200, `surveyId`·`closed: false`·`rewardCoin` |
| 13 | 방문자 `run` — 게스트 | 200 (게스트도 본다) |
| 14 | 방문자 `run` — 미게시 부스 | 404 `LAYOUT_NOT_PUBLISHED` |
| 15 | 방문자 `run` — 임대 만료 | 409 `BOOTH_LEASE_EXPIRED` |
| 16 | 방문자 `run` — 설문 없음 | 404 `SURVEY_NOT_FOUND` |
| 17 | 문항 0개로 `PUT` | 400, `errors[0].field = "questions"` |
| 18 | `title` 공백 | 400, `field = "title"` |
| 19 | `SINGLE_CHOICE` 선택지 1개 | 400, `field = "questions[0].options"` |
| 20 | `RATING`에 `scale` 없음 | 400, `field = "questions[0].scale"` |
| 21 | `RATING` `min >= max` | 400, `field = "questions[0].scale"` |
| 22 | `SHORT_TEXT`에 `options` 실림 | 400, `field = "questions[0].options"` |
| 23 | 알 수 없는 `type` | 400, `field = "questions[0].type"` |
| 24 | `prompt` 501자 | 400 |
| 25 | `closesAt` 과거 | 400, `field = "closesAt"` |
| 26 | 문항 31개 (설정 상한 30 초과) | 400, `field = "questions"` |
| 27 | 선택지 11개 (상한 10 초과) | 400, `field = "questions[0].options"` |
| 28 | `rewardCoin` 11 (상한 10 초과) | 400, `field = "rewardCoin"` |
| 29 | 응답 있는 설문 — 문항 구조 변경 `PUT` | **409 `SURVEY_LOCKED`** (응답은 JDBC로 심는다) |
| 30 | 응답 있는 설문 — 제목만 변경 `PUT` | **200** (C-08) |
| 31 | **스태프가 만든 설문이 있는 부스의 소유자 탈퇴** | 성공 — `AccountDeletionService` 수정 확인 (R-09) |

17~28이 -190의 "유형별 정상/불량 12케이스"에 해당한다.

### 3-2. `SurveyResponseApiIntegrationTest` (MR ②)

| # | 케이스 | 기대 |
|---|---|---|
| 1 | 6유형 전부 채운 정상 제출 (회원) | 201, `responseId`·`rewardedCoin` |
| 2 | 회원 + `rewardCoin: 5` | 201 `rewardedCoin: 5`, **지갑 +5**, 원장 1건 `REWARD`/`SURVEY_REWARD`, `assertBalanceMatchesLedger` |
| 3 | 회원 + `rewardCoin: 0` | 201 `rewardedCoin: 0`, 원장 0건 |
| 4 | 같은 회원 재제출 | 409 `SURVEY_ALREADY_RESPONDED`, **지급 총 1회** |
| 5 | 게스트 + 무보상 설문 | 201 `rewardedCoin: 0` |
| 6 | 게스트 + 보상 설문 | 403 `MEMBER_ONLY` |
| 7 | 같은 게스트 토큰 재제출 | 409 `SURVEY_ALREADY_RESPONDED` |
| 8 | 다른 게스트 토큰 제출 | 201 (토큰 단위 한계 — 계약 §6에 명시) |
| 9 | 마감된 설문 (`closesAt` 과거) | 409 `SURVEY_CLOSED` |
| 10 | 필수 문항 미응답 | 400, `field = "answers"` |
| 11 | 선택 문항 미응답 (빈 텍스트) | 201, 그 문항 답 행 없음 |
| 12 | 다른 문항의 `optionId` | 400, `field = "answers[0].selectedOptionIds"` |
| 13 | `SINGLE_CHOICE`에 2개 | 400 |
| 14 | `MULTIPLE_CHOICE`에 중복 id | 400 |
| 15 | `rating` 범위 밖 (`scale 1~5`에 6) | 400, `field = "answers[1].rating"` |
| 16 | `RATING` 문항에 `text` | 400 |
| 17 | 단답 201자 | 400 |
| 18 | 없는 `questionId` | 400, `field = "answers[0].questionId"` |
| 19 | 임대 만료 부스 | 409 `BOOTH_LEASE_EXPIRED` |
| 20 | 미게시 부스 | 404 `LAYOUT_NOT_PUBLISHED` |
| 21 | 없는 `surveyId` | 404 `SURVEY_NOT_FOUND` |

### 3-3. `SurveyResultApiIntegrationTest` (MR ③)

| # | 케이스 | 기대 |
|---|---|---|
| 1 | **20응답 fixture 수기 계산 대조** | `totalResponses`·선택지별 `count`·별점 `average`·`distribution`이 손으로 센 값과 일치 (-132 완료조건, SC-001) |
| 2 | 응답 0건 | 200, `totalResponses: 0`, `first/lastRespondedAt: null`, 모든 문항 `answeredCount: 0`·`average: null`, 선택지 `count: 0`까지 실림 (FR-012·SC-002) |
| 3 | 선택 문항을 건너뛴 응답 포함 | `answeredCount < totalResponses` |
| 4 | 복수선택 합이 100% 초과 | `counts` 합 > `answeredCount` — 그대로 나간다 |
| 5 | 텍스트 55건 3페이지 순회 | 중복·누락 0, `totalElements: 55`, 마지막 `page`에서 `totalPages` 경계 (-193) |
| 6 | 페이지 순회 중 새 응답 제출 | `id ASC` 고정이라 이미 본 페이지가 밀리지 않는다 |
| 7 | `questionId` 필터 | 그 문항 답만 |
| 8 | `page` 음수 / `size` 101 | 400 `VALIDATION_FAILED` |
| 9 | 남의 부스 결과 조회 | 403 `BOOTH_EDITOR_FORBIDDEN` (SC-004) |
| 10 | 게스트 결과 조회 | 403 `MEMBER_ONLY` |
| 11 | 임대 만료 부스 결과 조회 | **200** (FR-011) |
| 12 | **응답 JSON에 `respondent`·`userId`·`nickname` 문자열 0건** | SC-003 — 전체 응답 본문을 문자열로 훑어 단정 |
| 13 | 봉투 규격 — 각 오류 경로 | `code`·`message`·`requestId` 존재, 필드 오류는 `errors[0].rule = "FIELD_INVALID"` (-191) |

### 3-4. 저장소 전역 invariant

새 컨트롤러가 자동으로 걸리는 것들 — 처음부터 맞춰 쓴다.

- `OpenApiDocumentationTest` — 모든 op에 `summary`+`description`, 4xx 문서화, `@SecurityRequirement`(→401 자동), 4xx 본문 `$ref: ApiErrorResponse`, **`@Tag`가 `OpenApiConfiguration.tags()`에 선언돼 있어야 한다** → `tag("Survey", …)` 추가.
- `DomainExceptionEnvelopeTest` — 모든 `*Exception`은 `ApiException` 상속. **새 예외 클래스를 만들지 않으므로 자동 통과**(R-06).
- `ErrorEnvelopeIntegrationTest` — 기본 메시지 한글.

---

## 4. 손으로 한 번 — API 왕복

MR ② 머지 후 로컬에서. 회원·게스트 두 경로를 각각 한 번 태운다.

### 4-1. 준비

```bash
cd backend && ./mvnw spring-boot:run     # 로컬 postgres·redis 가동 상태에서
```

토큰은 Swagger UI(`/swagger-ui.html`)나 `POST /api/v1/auth/guest`로 얻는다. 회원 토큰은 소셜 로그인이 필요하므로 통합 테스트의 `MemberSessionService.issue(userId)` 경로가 더 빠르다 — 여기서는 게스트 왕복만 필수로 두고 회원 경로는 테스트로 대신한다.

**막히는 자리 셋**
1. 부스가 **게시**돼 있어야 방문자 경로가 열린다(`LAYOUT_NOT_PUBLISHED`) — Studio에서 Publish 한 번.
2. 임대가 살아 있어야 한다(24시간). 만료면 `BOOTH_LEASE_EXPIRED`.
3. 게스트 토큰은 30분이고 refresh가 없다 — 만료되면 다시 발급받는다.

### 4-2. 왕복

```bash
B=7; TOKEN=<member access token>; API=http://localhost:8080/api/v1

# ① 설문 저장 (= 공개)
curl -s -X PUT "$API/booths/$B/survey" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "title":"A604 부스 설문","rewardCoin":5,
    "questions":[
      {"type":"SINGLE_CHOICE","prompt":"어떻게 알았나요?","required":true,
       "options":[{"label":"돌아다니다가"},{"label":"추천"}]},
      {"type":"RATING","prompt":"만족도","required":true,"scale":{"min":1,"max":5}},
      {"type":"LONG_TEXT","prompt":"개선할 점","required":false}]}'
# → 200, surveyId 기록

# ② 편집자 조회
curl -s "$API/booths/$B/survey" -H "Authorization: Bearer $TOKEN"

# ③ 방문자 조회 — surveyId 가 여기서 나온다
curl -s "$API/booths/$B/survey/run" -H "Authorization: Bearer $TOKEN"

# ④ 제출 (S=surveyId, Q/O=위 응답의 questionId·optionId)
curl -s -X POST "$API/surveys/$S/responses" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"answers":[
    {"questionId":'$Q1',"selectedOptionIds":['$O1']},
    {"questionId":'$Q2',"rating":4},
    {"questionId":'$Q3',"text":"무대 일정 안내가 더 잘 보이면 좋겠습니다"}]}'
# → 201 {"responseId":..,"rewardedCoin":5}

# ⑤ 지갑 확인 — +5
curl -s "$API/wallets/me" -H "Authorization: Bearer $TOKEN"

# ⑥ 재제출 → 409 SURVEY_ALREADY_RESPONDED, 지갑 변화 없음
# ⑦ 결과
curl -s "$API/surveys/$S/results" -H "Authorization: Bearer $TOKEN"
# ⑧ 게스트 왕복 — 보상 설문이면 ④가 403 MEMBER_ONLY
GUEST=$(curl -s -X POST "$API/auth/guest" | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -s "$API/booths/$B/survey/run" -H "Authorization: Bearer $GUEST"     # 200
curl -s -X POST "$API/surveys/$S/responses" -H "Authorization: Bearer $GUEST" \
  -H 'Content-Type: application/json' -d '{"answers":[...]}'              # 403 MEMBER_ONLY
```

### 4-3. 결과

**2026-09-07 실행 — 부분 통과. 아래가 돌린 것과 돌리지 못한 것의 전부다.**

돌린 것 (로컬 postgres `ssafesta-local-postgres-1`, V21 + 실제 데이터 위에서):

| # | 확인 | 결과 |
|---|---|---|
| 1 | V22 를 **살아 있는 V21 DB에** Flyway 로 적용 | ✅ `Successfully applied 1 migration … now at version v22` |
| 2 | 적용된 스키마에 대해 `ddl-auto: validate` | ✅ `Initialized JPA EntityManagerFactory` — SMALLINT↔`short` 가 틀렸으면 여기서 죽는다 |
| 3 | `backend/db/rollback/V22__rollback.sql` 실행 | ✅ 14문장 전부 성공. 게스트 응답 자식부터 삭제 → `SET NOT NULL` 복구 순서가 실제로 통한다 |
| 4 | 기동 후 `GET /surveys/12/results` 무인증 | ✅ `401 UNAUTHORIZED` |
| 5 | 게스트 토큰(`POST /auth/guest`)으로 `results` · `text-answers` | ✅ 둘 다 `403 MEMBER_ONLY`, message `회원 계정만 설문 결과를 조회할 수 있습니다.` |
| 6 | `text-answers?page=-1` 을 게스트로 | ✅ `403` — **권한이 필드 검증보다 먼저**다(계약 순서), 400 이 아니다 |
| 7 | `/v3/api-docs` 에 두 endpoint 등재 | ✅ summary·tag `Survey`·4xx 문서화. 전체 operation 57개 |

**돌리지 못한 것 — ④~⑦의 회원 curl 왕복.** 회원 Access Token 은 소셜 로그인(OAuth) 경로밖에 없다 — `auth` 에 로컬 전용 발급 endpoint 가 없고(`GuestAuthController` 는 게스트만, 나머지는 `/oauth/{provider}` + `/complete`) §4-1 이 예고한 그대로다. 그 경로는 `SurveyApiIntegrationTest`(35) · `SurveyResponseApiIntegrationTest`(25) · `SurveyResultApiIntegrationTest`(15) 가 실제 Postgres(Testcontainers)에서 덮는다. 게스트 제출 왕복도 부스 게시·임대·설문 픽스처를 SQL 로 심어야 하는데 같은 경로를 통합 테스트가 이미 지나므로, 그 대신 위 4~7을 돌렸다.

**중간에 나온 것 하나** — 첫 기동이 `ERROR: relation "ux_surveys_booth" already exists` 로 실패했다. MR ① 개발 중 V22 SQL 을 `psql` 로 손으로 밀어 넣은 자리에 Flyway 이력만 없었기 때문이고 마이그레이션 결함이 아니다(`ux_surveys_booth` 를 만드는 곳은 V22 한 곳뿐임을 확인). 롤백 스크립트로 되돌린 뒤 Flyway 가 정상 적용했다 — 그 덕에 3번이 검증됐다.

---

## 5. 변이 확인 — 단정이 실제로 물리는가

새 기능은 "구현 전 실패"가 자동 성립하므로, **지키려는 규칙 쪽을 깨서** 테스트가 잡는지 본다. MR별 머지 전에 돌린다.

| 변이 | 깨져야 하는 것 |
|---|---|
| `requireEditor` 호출 제거 | 3-1 #3·#4 (403) |
| `requireActiveLease` 제거 | 3-1 #6, 3-2 #19 |
| 마감 판정 `isAfter` 부호 뒤집기 | 3-2 #9 (409 `SURVEY_CLOSED`) |
| 게스트 × 보상 분기 제거 | 3-2 #6 (403) |
| `ConstraintViolations.isViolationOf("ux_survey_responses_")` 번역 제거 | 3-2 #4·#7 (409 → 500) |
| `wallets.credit` 호출 제거 | 3-2 #2 (지갑 잔액) |
| 멱등키에 타임스탬프 추가 | 3-2 #4 ("지급 총 1회") |
| `scale` 범위 검증 제거 | 3-1 #21, 3-2 #15 |
| 문항 구조 비교 제거 | 3-1 #29 (409 `SURVEY_LOCKED`) |
| `PresenceField` → 일반 필드 | 3-1 #10 (`rewardCoin` 유지) |
| 주관식 `ORDER BY a.id ASC` 제거 | 3-3 #5·#6 |
| 문항 목록 기준 조립 → 응답 기준 조립 | 3-3 #2 (0건 빈 집계) |
| `AccountDeletionService` 원복 | 3-1 #31 (탈퇴 성공) |
| `short` → `Integer` 매핑 | **기동 실패** — `ddl-auto: validate` |

### 5-1. 실행 결과

- **MR ①** (2026-09-07): 7종 전부 잡혔다.
- **MR ②** (2026-09-07): 11종. 처음엔 9종만 잡혀 **멱등키에 `nanoTime` 추가**와 **제약 번역 제거**가 통과했다 — 사전 중복 검사가 순차 요청을 다 막아 두 방어가 테스트에서 실행되지 않는 코드였다. `SurveyResponseConcurrencyIntegrationTest` 를 추가해 11/11.
- **MR ③** (2026-09-07): 12종 중 **11종 잡힘**. 각 변이가 깨뜨린 테스트까지 남긴다.

| 변이 | 결과 | 깨진 테스트 |
|---|---|---|
| `requireEditor` 호출 제거 | ✅ | `anotherMembersSurveyResultsAreForbidden` |
| 주관식 `ORDER BY a.id ASC` → `DESC` | ✅ | `fiftyFiveTextAnswersPageWithoutDuplicateOrGap`, `aSubmissionDuringPagingDoesNotShiftPagesAlreadyRead` |
| 문항 목록 기준 → 집계 행 기준 조립 | ✅ | `aSurveyWithNoResponsesReportsEveryQuestionAtZero` |
| `average` 의 0건 `null` 가드 제거 | ✅ | 같은 테스트 (`average` 가 `0.0` 이 된다) |
| 선택지 0 채움 제거 | ✅ | 같은 테스트 |
| 별점 눈금 0 채움 제거 | ✅ | 위 + `theAggregateOfTwentyResponsesMatchesTheHandCount` |
| offset `page * size` → `page` | ✅ | 페이지 테스트 2종 |
| `size` 상한 검증 제거 | ✅ | `pageAndSizeOutOfRangeAreRejected` |
| `questionId` 소유 검증 제거 | ✅ | `aQuestionFromAnotherSurveyIsRejected` |
| `totalPages` `ceil` → `floor` | ✅ | 페이지 + 수기대조 |
| `answeredCount` → `totalResponses` | ✅ | `theAggregateOfTwentyResponsesMatchesTheHandCount` |
| projection alias `question_id` → `questionId` | ❌ **안 잡힘** | 없음 — 아래 |

**안 잡힌 것의 정체**: 통과한 것이 맞고, 그게 정보다. native projection 의 alias 를 snake_case 로 맞추면서 나는 "camelCase alias 는 아무것도 바인딩하지 못한다"고 주석에 썼다 — PostgreSQL 이 `AS questionId` 를 `questionid` 로 접고 Spring Data 의 fallback(`TupleBackedMap.FallbackTupleWrapper`)은 property → `under_score` 한 방향만 시도한다는 추론이었다. 이 변이가 통과함으로써 **그 주장이 틀렸다**는 것이 확인됐다(tuple 조회가 대소문자를 가리지 않는다). alias 는 컬럼명과 같아서 읽기 좋으므로 snake_case 로 두되 **주석의 거짓 주장을 지우고 "규칙이 아니라 표기 선택이며 변이로 확인했다"로 고쳤다.** 잡히지 않는 변이는 테스트 구멍일 때도 있지만 이번처럼 내 주장이 틀렸다는 신호일 때도 있다.

**표의 마지막 두 줄은 MR ①~② 에서 빠져 있어 MR ③ 때 따로 돌렸다.**

| 변이 | 결과 | 관찰 |
|---|---|---|
| `AccountDeletionService` 원복 (`booth_id IN (owner)` → `created_by_user_id`) | ✅ 잡힘 | `ownerWithdrawalRemovesASurveyCreatedByStaff` 가 **1 error** — 스태프가 만든 설문이 남아 `surveys_booth_id_fkey` 로 탈퇴가 깨진다 |
| `short` → `Integer` 매핑 | ⚠️ **다른 자리에서 잡혔다** | 필드만 `Integer` 로 바꾸면 생성자·getter 가 안 맞아 **컴파일**에서 죽는다 — 기동까지 가지 않는다. 그래서 아래처럼 DB 쪽에서 확인했다 |

**`ddl-auto: validate` 를 직접 확인했고, 한쪽 방향만 잡는다.**

- DB `smallint` + Java `int` → **기동 실패**. 로컬에서 `surveys.reward_coin`(INTEGER, Java `int`)을 SMALLINT 로 좁혀 보았다:
  `Schema validation: wrong column type encountered in column [reward_coin] in table [surveys]; found [int2 (Types#SMALLINT)], but expecting [integer (Types#INTEGER)]`.
  V22 가 만든 `rating_min`·`rating_max`(SMALLINT)를 `Integer` 로 매핑하면 정확히 이 실패다 — 표의 주장이 성립한다.
- **반대 방향은 잡히지 않는다.** `survey_questions.rating_min` 을 INTEGER 로 넓히고 엔티티는 `Short` 그대로 두면 **정상 기동한다.** 즉 나중에 누가 마이그레이션으로 SMALLINT 컬럼을 넓혀도 `validate` 는 말해 주지 않는다. 좁히는 쪽만 지켜 준다는 뜻이고, 넓히는 변경은 엔티티 수정을 사람이 기억해야 한다.

**변이 실행 자체에서 두 번 헛돌았다** (T-135·T-136 참조): `cmd /c mvnw.cmd` 가 인자를 못 받아 12종이 전부 "compile/boot failure = 잡힘" 으로 보였고, 다음 시도에서는 revert 용 pristine 경로(`/tmp`)가 MSYS 와 Windows Python 사이에서 다른 곳을 가리켜 **변이가 누적**됐다(실패 수가 1→3→4→…7 로 늘어난 것이 그 흔적이다). 둘 다 결과를 버리고 revert 를 매 회 `diff` 로 확인하는 방식으로 다시 돌렸다. 자동 변이 스크립트는 **되돌림을 단정하지 않으면 결과 전체가 무의미하다.**

---

## 6. 흔히 막히는 자리

| 증상 | 원인 |
|---|---|
| 기동 시 `Schema-validation: wrong column type ... found int2` | SMALLINT를 `Integer`로 매핑했다 → `short`/`Short` (R-12) |
| 중복 제출 테스트가 500 | `saveAndFlush` catch 뒤에 쿼리를 실행했다 — PG는 제약 위반 후 트랜잭션이 abort다 (R-04) |
| `PUT` 재저장이 `display_order` 유니크 위반 | 삭제와 삽입 사이에 `flush`가 없다 → `@Modifying(clearAutomatically, flushAutomatically)` (R-03) |
| `OpenApiDocumentationTest` 실패 | `@Tag("Survey")`를 `OpenApiConfiguration.tags()`에 선언하지 않았다 |
| 통합 테스트 전부 실패 | Docker 미기동 |
| 마이그레이션이 조용히 건너뛰어짐 | 롤백 후 `flyway_schema_history`에서 버전을 지우지 않았다 |
