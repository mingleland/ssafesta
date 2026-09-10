-- 설문에 게스트 응답과 별점 척도를 받아들이게 한다 (S15P21A604-130, spec 010).
--
-- V1 이 6테이블을 만들어 두었지만 Java 코드가 0줄이라 한 번도 쓰인 적이 없다. 그 사이
-- FE 가 mock 으로 화면을 완성했고(S15P21A604-368·-369) 두 자리가 스키마와 어긋난 채
-- 드러났다.
--
-- 1. 별점 척도를 담을 칸이 없다. FE 는 scale{min,max} 를 기대하는데 survey_questions 에
--    컬럼이 없어 1~5 인지 1~10 인지 저장할 방법이 없었다.
-- 2. 게스트 응답이 구조적으로 불가능하다. respondent_user_id 가 NOT NULL 이고 users(id)
--    를 참조하는데 게스트는 users 행이 없다(헌법 12조 — 비영속). 코드로 우회할 수 없다.
--    FE 가 "보상이 설정된 설문만 게스트 차단" 을 제안했고(GitLab #133, docs/26 row 20)
--    보상 지급만 막으면 응답 자체는 헌법 12조가 묶는 대상이 아니다. 게스트가 대다수인
--    09-08 사용자 테스트에서 전면 차단하면 설문 동선이 통째로 사라진다.
--
-- 6테이블 모두 행이 0개다 — dedupe 단계가 없고, 행이 있으면 시끄럽게 실패하는 것이
-- 의도다 (V17·V21 과 같은 전제).
--
-- surveys.status 에 CHECK 를 걸지 않는다. 저장은 항상 'OPEN' 이고 마감 판정은 ends_at
-- 이 한다 (C-07 — FE Builder 에 게시·마감 버튼이 없다). DRAFT·CLOSED 를 쓰는 코드가
-- 없는데 CHECK 로 못 박으면 존재하지 않는 상태 기계를 스키마에 새기는 셈이다.
--
-- 되돌리기: db/rollback/V22__rollback.sql (Flyway Community 에는 undo 가 없다).

-- ── 1. 부스당 설문 1개 ────────────────────────────────────────────────────────────
-- C-06. 애플리케이션 검사만 두면 동시 PUT 두 건이 각각 "없음" 을 보고 둘 다 만든다.
-- V7__booth_one_per_owner · V14__project_one_per_booth 와 같은 이유다.
CREATE UNIQUE INDEX ux_surveys_booth ON surveys(booth_id);

-- ── 2. 별점 척도 · 문항 유형 어휘 고정 ────────────────────────────────────────────
-- 유형 6종은 spec 010 FR-002(객관식·복수선택·별점·단답·장문·지원서)이고 어휘는
-- docs/09 §16 을 따른다. APPLICATION(지원서)의 저장은 LONG_TEXT 와 같다 — 제출자별
-- 상세 조회는 C-03 미결이라 만들지 않고, 주관식 응답에 responseId 를 실어 열쇠만 남긴다.
ALTER TABLE survey_questions
  ADD COLUMN rating_min SMALLINT,
  ADD COLUMN rating_max SMALLINT;

ALTER TABLE survey_questions ADD CONSTRAINT ck_survey_questions_type CHECK (
  question_type IN ('SINGLE_CHOICE','MULTIPLE_CHOICE','RATING','SHORT_TEXT','LONG_TEXT','APPLICATION'));

-- 별점이면 두 값이 있어야 하고 min < max 여야 한다. 별점이 아니면 둘 다 NULL 인지는
-- 애플리케이션이 본다 — CHECK 로 강제하면 유형 변경 마이그레이션이 필요해진다.
ALTER TABLE survey_questions ADD CONSTRAINT ck_survey_questions_rating CHECK (
  question_type <> 'RATING'
  OR (rating_min IS NOT NULL AND rating_max IS NOT NULL AND rating_min < rating_max));

-- ── 3. 게스트 응답 ───────────────────────────────────────────────────────────────
-- 응답자는 회원 xor 게스트다. 둘 다 NULL 인 행은 주인 없는 응답이고 둘 다 채운 행은
-- 주인이 둘이다 — 어느 쪽도 의미가 없어 DB 가 거부한다.
--
-- 게스트 키는 접속 토큰의 주체("guest:<uuid>", 42자)다. 그 토큰은 발급마다 새 주체이고
-- 게스트 refresh 가 없으므로 브라우저를 새로 열면 다른 사람으로 한 번 더 답할 수 있다.
-- 계정 없는 사람을 그 이상 식별할 방법이 없고, 그 한계를 감수하는 것이 C-05 의 전제다.
ALTER TABLE survey_responses ALTER COLUMN respondent_user_id DROP NOT NULL;
ALTER TABLE survey_responses ADD COLUMN respondent_guest_key VARCHAR(100);

-- V1 이 자동 명명한 이름이다 (로컬 DB pg_constraint 에서 확인). 이 제약은 NULL 을 서로
-- 다른 값으로 보므로 그대로 두면 게스트 응답 전체가 중복 검사를 빠져나간다.
ALTER TABLE survey_responses DROP CONSTRAINT survey_responses_survey_id_respondent_user_id_key;

ALTER TABLE survey_responses ADD CONSTRAINT ck_survey_responses_respondent CHECK (
  (respondent_user_id IS NULL) <> (respondent_guest_key IS NULL));

-- 1인 1응답을 두 축에 각각 건다. 부분 인덱스여야 NULL 쪽이 서로를 막지 않는다.
CREATE UNIQUE INDEX ux_survey_responses_member ON survey_responses(survey_id, respondent_user_id)
  WHERE respondent_user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_survey_responses_guest ON survey_responses(survey_id, respondent_guest_key)
  WHERE respondent_guest_key IS NOT NULL;

-- ── 4. 집계·잠금 판정이 문항 기준으로 돈다 ────────────────────────────────────────
-- 결과 집계는 문항별 GROUP BY 이고, 응답 있는 설문의 문항 구조 잠금도 문항 기준으로
-- 답의 존재를 본다. survey_answers 에는 response_id 인덱스만 있었다.
CREATE INDEX ix_survey_answers_question ON survey_answers(question_id);
