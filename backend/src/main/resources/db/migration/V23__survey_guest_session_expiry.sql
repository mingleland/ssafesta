-- 게스트 응답이 어느 세션의 것인지가 아니라, 그 세션이 "언제 끝나는지" 를 적어 둔다.
--
-- 헌법 12조는 게스트 세션 데이터를 종료 시 삭제하라고 한다. 삭제 시점을
-- `submitted_at + 현재 access-token-ttl` 로 추정하면 두 방향으로 틀린다:
--   ① 토큰은 제출보다 먼저 발급됐으므로 실제 만료보다 최대 TTL 만큼 늦게 지운다.
--   ② TTL 을 줄여 배포하면 아직 유효한 기존 토큰의 키를 먼저 지워, 같은 토큰이
--      같은 설문에 한 번 더 답할 수 있다 (부분 유니크 인덱스에서 빠지기 때문이다).
--
-- 토큰의 exp 를 제출 시점에 그대로 적어 두면 둘 다 사라진다. 추정이 아니라 사실이다.
--
-- 이 값 자체도 세션 데이터다. sweeper 가 키를 지울 때 이 칸도 함께 비운다 —
-- 같은 토큰으로 낸 응답들은 exp 가 같아서, 남겨 두면 키를 지운 뒤에도 세션 단위로
-- 묶을 수 있다. 12조가 지우라는 것이 바로 그 연결이다.
ALTER TABLE survey_responses ADD COLUMN respondent_session_expires_at TIMESTAMPTZ;

-- V23 이전에 쌓인 게스트 응답에는 exp 가 없다. 그 행들이 어떤 TTL 로 발급된 토큰의 것인지
-- 이 스크립트는 알 수 없다 -- `submitted_at + 30분` 처럼 값을 박으면 그보다 긴 TTL 을 쓰던
-- 환경에서 아직 유효한 토큰의 키를 먼저 지우게 되고, 환경마다 이 파일을 고치면 이미 적용된
-- DB 와 checksum 이 어긋난다.
--
-- 그래서 과거를 추정하지 않고 미래로 민다: 이 마이그레이션이 도는 시점 + 1일. 배포 이전에
-- 발급된 토큰은 그때면 어떤 TTL 이었든 죽어 있다(access token 은 분 단위이고 지금 값은 30분,
-- 이 상한은 그 48배다). 정리가 하루 늦어질 뿐 이르게 지우지 않는다. 대상은 V22~V23 사이에
-- 쌓인 유한한 행뿐이고, 그 뒤로는 제출이 진짜 exp 를 적는다.
UPDATE survey_responses
   SET respondent_session_expires_at = now() + interval '1 day'
 WHERE respondent_guest_key IS NOT NULL
   AND respondent_session_expires_at IS NULL;

-- sweeper 가 매 5분마다 "지울 것이 있는가" 를 묻는다. 술어를 만료 시각 자체에 두는 이유는
-- 정리가 끝난 행에서 이 칸이 NULL 이 되기 때문이다 — 그 행들은 인덱스에서 빠지므로
-- 응답이 쌓여도 인덱스와 탐색 범위가 함께 자라지 않는다. 회원 응답도 NULL 이라 들어오지
-- 않는다. `respondent_guest_key IS NOT NULL` 을 술어로 쓰면 `expired:` 로 바뀐 행이 영구히
-- 남아 정확히 그 문제가 생긴다.
CREATE INDEX ix_survey_responses_guest_session_expiry
    ON survey_responses (respondent_session_expires_at)
    WHERE respondent_session_expires_at IS NOT NULL;
