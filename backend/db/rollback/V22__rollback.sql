-- V22__survey_guest_and_rating_scale.sql 되돌리기 (S15P21A604-130).
--
-- respondent_user_id 에 SET NOT NULL 을 되걸려면 게스트 응답 행이 없어야 한다. 그래서
-- 게스트 응답을 자식부터 지운다 — survey_answers.response_id 와
-- survey_answer_options.answer_id 가 ON DELETE 없이 걸려 있어 순서가 강제된다.
--
-- 게스트 응답은 복구되지 않는다. 그 응답으로 집계된 수치도 함께 줄어든다.
-- 회원 응답과 설문·문항은 그대로 남는다.
--
-- 실행 후 flyway_schema_history 에서 버전을 지워야 한다 (README 실행 절차).

DELETE FROM survey_answer_options WHERE answer_id IN (
  SELECT sa.id FROM survey_answers sa
  JOIN survey_responses sr ON sr.id = sa.response_id
  WHERE sr.respondent_guest_key IS NOT NULL);

DELETE FROM survey_answers WHERE response_id IN (
  SELECT id FROM survey_responses WHERE respondent_guest_key IS NOT NULL);

DELETE FROM survey_responses WHERE respondent_guest_key IS NOT NULL;

DROP INDEX ix_survey_answers_question;

DROP INDEX ux_survey_responses_guest;
DROP INDEX ux_survey_responses_member;
ALTER TABLE survey_responses DROP CONSTRAINT ck_survey_responses_respondent;
ALTER TABLE survey_responses DROP COLUMN respondent_guest_key;
ALTER TABLE survey_responses ALTER COLUMN respondent_user_id SET NOT NULL;
ALTER TABLE survey_responses
  ADD CONSTRAINT survey_responses_survey_id_respondent_user_id_key UNIQUE (survey_id, respondent_user_id);

ALTER TABLE survey_questions DROP CONSTRAINT ck_survey_questions_rating;
ALTER TABLE survey_questions DROP CONSTRAINT ck_survey_questions_type;
ALTER TABLE survey_questions DROP COLUMN rating_max, DROP COLUMN rating_min;

DROP INDEX ux_surveys_booth;
