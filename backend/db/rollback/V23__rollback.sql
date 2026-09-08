-- V23__survey_guest_session_expiry.sql 되돌리기.
--
-- 잃는 것: 게스트 응답의 세션 만료 시각. 되돌린 뒤 sweeper 는 다시 추정
-- (submitted_at + access-token-ttl)으로 돌아가므로 삭제가 늦어지고, TTL 을 줄이면
-- 아직 유효한 토큰의 키를 먼저 지울 수 있다 (V23 주석 ①·②).
--
-- 응답·답·집계는 그대로다. 지워지는 것은 컬럼 하나와 그 인덱스뿐이다.
DROP INDEX IF EXISTS ix_survey_responses_guest_session_expiry;
ALTER TABLE survey_responses DROP COLUMN IF EXISTS respondent_session_expires_at;
