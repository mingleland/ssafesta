-- V29__event_survey.sql 되돌리기 (S15P21A604-621).
--
-- 순서가 중요하다. 이벤트 설문 행을 먼저 지워야 booth_id 에 NOT NULL 을 다시 걸 수 있다 —
-- 그 행이 booth_id NULL 이기 때문이다. 응답이 달려 있으면 자식부터 지운다.
--
-- ⚠️ 이벤트 설문에 이미 참여한 응답이 있으면 이 스크립트가 그것을 지운다. 추첨 대상 데이터를
-- 남기는 것이 이 기능의 목적이므로, 되돌리기 전에 응답을 내보내 두어야 한다.
DELETE FROM survey_answer_options WHERE answer_id IN (
  SELECT sa.id FROM survey_answers sa
  JOIN survey_responses sr ON sr.id = sa.response_id
  JOIN surveys s ON s.id = sr.survey_id
  WHERE s.survey_key IS NOT NULL);
DELETE FROM survey_answers WHERE response_id IN (
  SELECT sr.id FROM survey_responses sr
  JOIN surveys s ON s.id = sr.survey_id
  WHERE s.survey_key IS NOT NULL);
DELETE FROM survey_responses WHERE survey_id IN (SELECT id FROM surveys WHERE survey_key IS NOT NULL);
DELETE FROM survey_options WHERE question_id IN (
  SELECT sq.id FROM survey_questions sq
  JOIN surveys s ON s.id = sq.survey_id
  WHERE s.survey_key IS NOT NULL);
DELETE FROM survey_questions WHERE survey_id IN (SELECT id FROM surveys WHERE survey_key IS NOT NULL);
DELETE FROM surveys WHERE survey_key IS NOT NULL;

DROP INDEX IF EXISTS ux_surveys_key;
ALTER TABLE surveys DROP CONSTRAINT IF EXISTS ck_surveys_scope;
ALTER TABLE surveys DROP COLUMN IF EXISTS survey_key;
ALTER TABLE surveys ALTER COLUMN created_by_user_id SET NOT NULL;
ALTER TABLE surveys ALTER COLUMN booth_id SET NOT NULL;
