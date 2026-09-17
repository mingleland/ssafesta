# Data Model: 설문 (010 BE분)

**Spec**: [../spec.md](../spec.md) | **Plan**: [plan.md](plan.md) | **Contract**: [../contracts/survey-api.md](../contracts/survey-api.md) | **Date**: 2026-09-07

> ⚠️ **`docs/09` §16~§18은 낡았다.** 그 문서는 `type`·`title`·`required`·`order_no`·`text_value`·`selected_option_ids JSONB`를 적었지만 실제 컬럼은 아래와 다르다. **`V1__initial_schema.sql`이 정본**이고 `ddl-auto: validate`가 엔티티를 그것에 맞춰 검사한다 (R-10).

---

## 1. 기존 스키마 — `V1__initial_schema.sql:114-143`

6테이블이 이미 있다. **신규 테이블 0.**

```sql
surveys(id, booth_id→booths, title VARCHAR(200), description TEXT,
        reward_coin INTEGER DEFAULT 0 CHECK(>=0), status VARCHAR(20) DEFAULT 'DRAFT',
        starts_at, ends_at, created_by_user_id→users, created_at, updated_at,
        CHECK(ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at))
survey_questions(id, survey_id→surveys, question_type VARCHAR(30), question_text TEXT,
        is_required BOOLEAN DEFAULT FALSE, display_order SMALLINT,
        UNIQUE(survey_id, display_order))
survey_options(id, question_id→survey_questions, option_text VARCHAR(500),
        display_order SMALLINT, UNIQUE(question_id, display_order))
survey_responses(id, survey_id→surveys, respondent_user_id→users NOT NULL,
        reward_ledger_entry_id→coin_ledger_entries UNIQUE, submitted_at,
        UNIQUE(survey_id, respondent_user_id))
survey_answers(id, response_id→survey_responses, question_id→survey_questions,
        text_answer TEXT, rating_value SMALLINT, UNIQUE(response_id, question_id))
survey_answer_options(answer_id→survey_answers, option_id→survey_options,
        PRIMARY KEY(answer_id, option_id))
```

**로컬 DB(V21 상태)에서 확인한 것**:
- V1의 자동 명명 유니크 제약 이름은 `survey_responses_survey_id_respondent_user_id_key`다.
- `survey_answers.question_id`·`response_id`, `survey_answer_options.option_id` FK는 모두 **`ON DELETE` 없음(RESTRICT)**이다 → 답이 달린 문항·선택지는 DB가 삭제를 막는다. 문항 구조 잠금(R-11)의 근거다.
- `surveys.status`에 CHECK가 없고 `question_type`에도 없다.
- 6테이블 모두 **행이 0개**다(쓰기 경로가 없었다) → `V22`의 `NOT NULL`·CHECK 추가가 dedupe 없이 즉시 통과한다. 행이 있으면 시끄럽게 실패하는 것이 의도다(V17·V21 관례).

---

## 2. `V22__survey_guest_and_rating_scale.sql` — delta

```sql
-- ① 부스당 설문 1개 (C-06) — R-01
CREATE UNIQUE INDEX ux_surveys_booth ON surveys(booth_id);

-- ② 별점 척도 — FE 가 scale{min,max} 를 기대하는데 담을 칸이 없었다
ALTER TABLE survey_questions ADD COLUMN rating_min SMALLINT, ADD COLUMN rating_max SMALLINT;
ALTER TABLE survey_questions ADD CONSTRAINT ck_survey_questions_type CHECK (question_type IN
  ('SINGLE_CHOICE','MULTIPLE_CHOICE','RATING','SHORT_TEXT','LONG_TEXT','APPLICATION'));
ALTER TABLE survey_questions ADD CONSTRAINT ck_survey_questions_rating CHECK
  (question_type <> 'RATING' OR (rating_min IS NOT NULL AND rating_max IS NOT NULL
                                 AND rating_min < rating_max));

-- ③ 게스트 응답 (C-05, 2026-09-07 사용자 결정) — R-02
ALTER TABLE survey_responses ALTER COLUMN respondent_user_id DROP NOT NULL;
ALTER TABLE survey_responses ADD COLUMN respondent_guest_key VARCHAR(100);
ALTER TABLE survey_responses DROP CONSTRAINT survey_responses_survey_id_respondent_user_id_key;
ALTER TABLE survey_responses ADD CONSTRAINT ck_survey_responses_respondent CHECK
  ((respondent_user_id IS NULL) <> (respondent_guest_key IS NULL));
CREATE UNIQUE INDEX ux_survey_responses_member ON survey_responses(survey_id, respondent_user_id)
  WHERE respondent_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_survey_responses_guest ON survey_responses(survey_id, respondent_guest_key)
  WHERE respondent_guest_key IS NOT NULL;

-- ④ 집계·잠금 판정이 문항 기준으로 도는 인덱스
CREATE INDEX ix_survey_answers_question ON survey_answers(question_id);
```

**`status` CHECK는 넣지 않는다.** 저장은 항상 `'OPEN'`을 쓰고 판정은 `ends_at`으로 한다(C-07). `DRAFT`·`CLOSED`를 쓰는 코드가 없는데 CHECK로 못 박으면 존재하지 않는 상태 기계를 스키마에 새기는 셈이다.

**`starts_at`은 쓰지 않는다.** V1의 `CHECK(ends_at > starts_at)`는 `starts_at`이 `NULL`이면 통과하므로 **과거 마감일을 막지 못한다** → `closesAt > now`를 애플리케이션이 검증한다(§4).

### 롤백 — `backend/db/rollback/V22__rollback.sql`

`SET NOT NULL` 복원이 게스트 행 때문에 실패하므로 **자식부터 지운 뒤** 되돌린다.

```sql
DELETE FROM survey_answer_options WHERE answer_id IN (
  SELECT sa.id FROM survey_answers sa JOIN survey_responses sr ON sr.id = sa.response_id
  WHERE sr.respondent_guest_key IS NOT NULL);
DELETE FROM survey_answers WHERE response_id IN (
  SELECT id FROM survey_responses WHERE respondent_guest_key IS NOT NULL);
DELETE FROM survey_responses WHERE respondent_guest_key IS NOT NULL;
DROP INDEX ux_survey_responses_guest; DROP INDEX ux_survey_responses_member;
ALTER TABLE survey_responses DROP CONSTRAINT ck_survey_responses_respondent;
ALTER TABLE survey_responses DROP COLUMN respondent_guest_key;
ALTER TABLE survey_responses ALTER COLUMN respondent_user_id SET NOT NULL;
ALTER TABLE survey_responses ADD CONSTRAINT survey_responses_survey_id_respondent_user_id_key
  UNIQUE (survey_id, respondent_user_id);
DROP INDEX ix_survey_answers_question;
ALTER TABLE survey_questions DROP CONSTRAINT ck_survey_questions_rating;
ALTER TABLE survey_questions DROP CONSTRAINT ck_survey_questions_type;
ALTER TABLE survey_questions DROP COLUMN rating_max, DROP COLUMN rating_min;
DROP INDEX ux_surveys_booth;
```
`README.md`에 표 행과 `DELETE FROM flyway_schema_history WHERE version = '22'`를 함께 적는다 — 그 줄을 빼먹으면 다음 `migrate`가 V22를 적용된 것으로 보고 건너뛴다.

---

## 3. 엔티티 5개 — 평면 `Long` 참조

`@OneToMany` 없음(R-03). SMALLINT는 `short`/`Short`(R-12).

| 엔티티 | 테이블 | 필드 |
|---|---|---|
| `Survey` | `surveys` | `Long id` · `Long boothId` · `String title` · `String description` · `int rewardCoin` · `String status`(항상 `"OPEN"`) · `Instant endsAt` · `Long createdByUserId`(**항상 부스 소유자** — R-09) · `Instant createdAt/updatedAt` |
| `SurveyQuestion` | `survey_questions` | `Long id` · `Long surveyId` · `SurveyQuestionType questionType`(`@Enumerated(STRING)`) · `String questionText` · `boolean required` · `short displayOrder` · `Short ratingMin/ratingMax` |
| `SurveyOption` | `survey_options` | `Long id` · `Long questionId` · `String optionText` · `short displayOrder` |
| `SurveyResponse` | `survey_responses` | `Long id` · `Long surveyId` · `Long respondentUserId`(nullable) · `String respondentGuestKey`(nullable) · `Long rewardLedgerEntryId`(nullable) · `Instant submittedAt` |
| `SurveyAnswer` | `survey_answers` | `Long id` · `Long responseId` · `Long questionId` · `String textAnswer` · `Short ratingValue` |

`survey_answer_options`는 **매핑하지 않는다** — native `INSERT`/조회(`ProjectRepository`의 `project_likes` 모양, R-03).

`SurveyQuestionType` enum 6종: `SINGLE_CHOICE`·`MULTIPLE_CHOICE`·`RATING`·`SHORT_TEXT`·`LONG_TEXT`·`APPLICATION`. 헬퍼 `isChoice()`(앞 둘) · `isText()`(뒤 셋) · `isRating()`.

### 불변식

1. **부스당 설문 1개** — `ux_surveys_booth`가 지킨다 (C-06).
2. **응답자는 회원 xor 게스트** — `ck_survey_responses_respondent`. 회원은 설문당 1응답(`ux_survey_responses_member`), 게스트는 토큰 주체당 1응답(`ux_survey_responses_guest`).
3. **보상은 응답 1건에 원장 1건** — `reward_ledger_entry_id UNIQUE` + 멱등키 `SURVEY_REWARD:{surveyId}:{userId}`. 게스트 응답은 항상 `NULL`.
4. **응답이 있으면 문항 구조는 불변** — 애플리케이션이 409로 거부하고, 뚫려도 `survey_answers.question_id` FK(RESTRICT)가 막는다 (R-11).
5. **`created_by_user_id` = 부스 소유자** — 탈퇴 삭제 SQL이 `booth_id IN (owner)`로 키를 잡는 것과 같은 것을 가리켜야 한다 (R-09).
6. **부스 설문 결과 DTO에는 응답자 식별 정보가 없다** — FR-009·SC-003. `responseId`만 나가고 그것은 같은 사람의 답을 묶는 열쇠일 뿐이다. 예외는 전역 Admin 전용 이벤트 참여자 목록(`S15P21A604-742`) 하나로, 이벤트 `surveyKey` 경로에서만 회원 id·닉네임을 낸다.
7. **문항 `displayOrder`는 0부터 연속** — 배열 순서와 항상 같다.

### 상태

`status`는 저장 시 `'OPEN'` 하나만 쓴다. **마감 판정은 `ends_at`이 한다.**

```
closed = endsAt != null && !endsAt.isAfter(Instant.now())
```

`closed`는 저장하지 않고 조회할 때마다 계산한다 — 저장하면 만료 시점에 누군가 갱신해야 하고, 그 스케줄러가 없다. Lease가 같은 방식으로 판정한다(`BoothLeaseRepository.findValidByBoothId`가 `endsAt > :moment`).

---

## 4. 검증 규칙 — 적용 순서대로

### `PUT /booths/{boothId}/survey`

순서가 중요하다. 권한 → 임대 → 존재·잠금 → 필드. 남의 부스인 사람에게 "그 설문은 잠겼습니다"를 알려주지 않는다.

| 순서 | 검사 | 실패 |
|---|---|---|
| #0 | 게스트 여부 | 403 `MEMBER_ONLY` |
| #1 | `requireActiveEditor(boothId, userId)` | 404 `BOOTH_NOT_FOUND` → 403 `BOOTH_EDITOR_FORBIDDEN` → 409 `BOOTH_LEASE_EXPIRED` |
| #2 | 부스 행 락 `findWithLockById` (R-08) | — |
| #3 | 기존 설문 조회 + 응답 수 | — |
| #4 | 응답 ≥1이고 문항 구조가 다르다 | 409 `SURVEY_LOCKED` |
| #5 | 필드 검증 (아래) | 400 `VALIDATION_FAILED` + `errors[0].field` |
| #6 | 저장 (신규는 `saveAndFlush` + `ux_surveys_booth` 번역) | — |

필드 검증은 **얕은 것부터**: `title` → `description` → `rewardCoin` → `closesAt` → `questions` 개수 → 각 문항(`type` → `prompt` → 유형별 부속).

| field | 규칙 | 근거 |
|---|---|---|
| `title` | 비공백, ≤ 200 | `VARCHAR(200)` |
| `description` | ≤ 2,000 (`null` 허용) | `TEXT`지만 화면 상한 |
| `rewardCoin` | `0 ≤ n ≤ maxRewardCoin`(설정, 기본 10) | `CHECK(reward_coin >= 0)` + C-02 |
| `closesAt` | `> now` | V1 CHECK가 못 잡는다(`starts_at` NULL) |
| `questions` | `1 ≤ n ≤ maxQuestions`(설정, 기본 30) | C-01 |
| `questions[i].type` | enum 6종 | `ck_survey_questions_type` |
| `questions[i].prompt` | 비공백, ≤ 500 | 화면 상한 |
| `questions[i].options` | 선택형: `2 ≤ n ≤ maxOptions`(기본 10), 각 `label` 비공백·≤ 500. **비선택형: 비어 있어야 한다** | `VARCHAR(500)` + C-01 |
| `questions[i].scale` | `RATING`: 필수, `1 ≤ min < max ≤ 10`. **비 `RATING`: `null`이어야 한다** | `ck_survey_questions_rating` |

**"비어 있어야 한다"를 검사하는 이유**: `SHORT_TEXT`에 `options`가 실려 오면 조용히 버리는 것이 편하지만, 그러면 FE 버그(유형 변경 시 옵션 초기화 누락)가 저장 성공으로 보이고 나중에 화면에서 사라진 옵션을 찾게 된다. 400으로 돌려주면 FE가 그 자리에서 안다.

### `POST /surveys/{surveyId}/responses`

| 순서 | 검사 | 실패 |
|---|---|---|
| #0 | 토큰 주체 판정 (MEMBER→id / GUEST→subject / 그 외) | 401 `UNAUTHORIZED` |
| #1 | 설문 조회 | 404 `SURVEY_NOT_FOUND` |
| #2 | `requireVisitorVisible(survey.boothId)` (R-07) | 404 `BOOTH_NOT_FOUND` → 409 `BOOTH_LEASE_EXPIRED` → 404 `LAYOUT_NOT_PUBLISHED` |
| #3 | 부스 행 공유 락 (R-08) | — |
| #4 | 마감 | 409 `SURVEY_CLOSED` |
| #5 | 게스트 && `rewardCoin > 0` | 403 `MEMBER_ONLY` |
| #6 | 답 검증 (아래) | 400 `VALIDATION_FAILED` |
| #7 | 사전 중복 검사 | 409 `SURVEY_ALREADY_RESPONDED` |
| #8 | `saveAndFlush` + `ux_survey_responses_` 번역 | 409 `SURVEY_ALREADY_RESPONDED` |
| #9 | 답 저장 → **보상** (R-04. 이 순서 고정) | 404 `WALLET_NOT_FOUND`(깨진 상태) |

답 검증:

| 상황 | field |
|---|---|
| 이 설문의 문항이 아닌 `questionId` | `answers[i].questionId` |
| 같은 `questionId` 중복 | `answers[i].questionId` |
| 필수 문항 미응답 (빈 배열·공백 = 미응답) | `answers` (`message`에 누락 `questionId`) |
| 유형에 맞지 않는 키 | 실린 키 (`answers[i].rating` 등) |
| 다른 문항의 `optionId` | `answers[i].selectedOptionIds` |
| `SINGLE_CHOICE`에 2개 이상 | `answers[i].selectedOptionIds` |
| `MULTIPLE_CHOICE`에 중복 `optionId` | `answers[i].selectedOptionIds` |
| `rating`이 `[ratingMin, ratingMax]` 밖 | `answers[i].rating` |
| `text` 초과 (단답 200 / 장문·지원서 2,000) | `answers[i].text` |

**"빈 배열·공백 = 미응답"은 FE와 같은 판정이다** — `features/survey/model/run.ts`의 `isEmptyAnswer`가 같은 규칙이다. 다르면 FE가 제출 버튼을 열어 준 요청이 400으로 돌아온다.

선택 문항 미응답은 **행을 만들지 않는다** → 집계 `answeredCount`에서 자연히 빠진다.

---

## 4-1. V23 delta — 게스트 세션 만료 시각 (2026-09-08)

헌법 12조 후단("게스트 세션은 종료 시 데이터를 삭제한다")을 지키기 위해 추가했다. 착수 때 12조의 앞부분(임대·결제·영구 자산)만 근거로 삼아 이 조항을 짚지 못했다 — `docs/26` row 20 참조.

| 변경 | 이유 |
|---|---|
| `survey_responses.respondent_session_expires_at TIMESTAMPTZ` (nullable) | 제출 때 게스트 토큰의 `exp` 를 적는다. 회원은 `null`(세션 만료라는 개념이 없다), V23 이전 게스트 행도 `null` |
| `ix_survey_responses_guest_session_expiry` (부분, **`respondent_session_expires_at IS NOT NULL`**) | sweeper 가 5분마다 "지울 것이 있는가" 를 묻는다. 정리된 행은 이 칸이 `NULL` 이 되어 인덱스에서 빠지므로 응답이 쌓여도 인덱스와 탐색 범위가 함께 자라지 않는다. 술어를 `respondent_guest_key IS NOT NULL` 로 두면 `expired:` 로 바뀐 행이 영구히 남아 정확히 그 문제가 생긴다 |
| V23 이전 게스트 행 백필 (**적용 시점 + 1일**) | 그 행들이 어떤 TTL 로 발급된 토큰의 것인지 마이그레이션은 알 수 없다. `submitted_at + 30분` 처럼 값을 박으면 더 긴 TTL 을 쓰던 환경에서 아직 유효한 토큰의 키를 먼저 지우고, 환경마다 파일을 고치면 이미 적용된 DB 와 checksum 이 어긋난다. 과거를 추정하지 않고 미래로 밀어 **이르게 지우지 않는 쪽**을 택했다 — 정리가 하루 늦어질 뿐이고, 대상은 V22~V23 사이의 유한한 행뿐이다 |

**왜 추정으로는 안 되는가.** `submitted_at + 현재 TTL` 은 두 방향으로 틀린다. ① 토큰은 제출보다 먼저 발급되므로 실제 만료보다 최대 TTL 만큼 늦게 지운다. ② TTL 을 줄여 배포하면 아직 유효한 기존 토큰의 키를 먼저 지우고, 키가 지워진 행은 `ux_survey_responses_guest` 에서 빠지므로 **같은 토큰이 같은 설문에 한 번 더 답할 수 있다.**

**지우는 것은 두 칸이다.** `respondent_guest_key` 는 `expired:{id}` 로 **대체**한다 — `ck_survey_responses_respondent` 가 XOR 이라 `NULL` 로 비우면 위반하고, 행마다 유일한 값이라 부분 유니크 인덱스도 그대로다. `respondent_session_expires_at` 은 **`NULL` 로 비운다** — 같은 토큰으로 낸 응답들은 `exp` 가 마이크로초까지 같아서, 남겨 두면 키를 지운 뒤에도 한 사람의 답을 설문 간에 묶을 수 있다. 12조가 지우라는 것이 그 연결이다. **응답 행과 답은 지우지 않는다**: 응답은 그것을 수집한 부스의 것이고, 방문자 세션이 끝났다고 운영자의 집계가 줄어들면 안 된다.

**언제 지우는가 — `exp + 허용 오차 + 유예`다.** 세 조각이 각각 다른 것을 막는다.

| 조각 | 막는 것 |
|---|---|
| `exp` | 세션 자체 |
| `+ app.auth.jwt-clock-skew`(60초) | 디코더가 `exp` 뒤에도 그만큼 토큰을 받아 준다(`JwtTimestampValidator`) — 그 사이에 지우면 **아직 인증되는 토큰**이 중복 방지 없이 다시 답한다. skew 를 설정으로 꺼내 `JwtConfiguration` 과 sweeper 가 **같은 값**을 읽는다. Spring 기본값에 기대면 두 숫자가 조용히 어긋난다 |
| `+ app.survey.guest-key-grace`(10분) | 만료 직전에 인증된 요청이 부스 락 등에서 대기하다 스큐가 지난 뒤 커밋할 수 있다. 그 전에 키가 사라지면 **실행 중이던 요청**이 중복 응답을 넣는다. 이 값이 의미를 가지려면 **요청 수명에 상한이 있어야 한다** — `app.survey.submit-timeout-seconds`(30초)를 `@Transactional(timeoutString)` 으로 걸어 제출이 JDBC query timeout 에서 끊기게 했고, `SurveyProperties` 가 **유예 > 제출 상한**을 기동 시 강제한다. 그 차이가 트랜잭션이 열리기 전 대기를 덮는 여유다 |


롤백 `db/rollback/V23__rollback.sql` — 컬럼과 인덱스만 없어지고 응답·답·집계는 그대로다.

## 5. 집계 쿼리 (C-04 실시간)

`GET /surveys/{surveyId}/results`가 부르는 것들. 모두 `GROUP BY` 한 번이고 원본 응답은 서비스 밖으로 나가지 않는다(FR-007).

| 값 | 쿼리 |
|---|---|
| `totalResponses` · `firstRespondedAt` · `lastRespondedAt` | `SELECT count(*), min(submitted_at), max(submitted_at) FROM survey_responses WHERE survey_id = ?` |
| 문항별 `answeredCount` | `SELECT question_id, count(*) FROM survey_answers a JOIN survey_responses r ON r.id = a.response_id WHERE r.survey_id = ? GROUP BY question_id` |
| 선택지별 `count` | `… survey_answer_options ao JOIN survey_answers a … GROUP BY a.question_id, ao.option_id` |
| 별점 `average` · `distribution` | `SELECT question_id, avg(rating_value), rating_value, count(*) … GROUP BY question_id, rating_value` — 평균은 소수 첫째자리까지 |
| 주관식 페이지 | `… WHERE text_answer IS NOT NULL [AND question_id = ?] ORDER BY a.id ASC LIMIT ? OFFSET ?` |

**응답 0건**: 모든 집계가 빈 결과다. 서비스가 **문항 목록을 기준으로** 결과를 조립하므로 모든 문항이 `answeredCount: 0`·`counts` 각 0·`average: null`로 나간다 — 0으로 나누는 자리가 없다(FR-012·SC-002). 선택지 `count`도 **응답이 없는 선택지까지 0으로** 싣는다(문항의 `options`를 왼쪽으로 두고 채운다).

**정렬 고정**: 주관식은 `a.id ASC`. 새 응답은 항상 뒤에 붙으므로 페이지를 넘기는 중 제출이 들어와도 경계에서 중복·누락이 없다 (C-09).
