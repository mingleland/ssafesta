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
-- NULL 인 행: 회원 응답(세션 만료라는 개념이 없다)과 V23 이전에 쌓인 게스트 응답이다.
-- 후자는 sweeper 가 종전 규칙(submitted_at + TTL)으로 지운다 — 그 규칙은 안정된 설정에서
-- 늦기만 하고 이르지는 않다.
ALTER TABLE survey_responses ADD COLUMN respondent_session_expires_at TIMESTAMPTZ;

-- sweeper 가 매 5분마다 "지울 것이 있는가" 를 묻는다. 대상은 게스트 행 중에서도 아직
-- 지우지 않은 것뿐이라 부분 인덱스로 충분하고, 응답이 쌓여도 스캔 폭이 늘지 않는다.
CREATE INDEX ix_survey_responses_guest_session_expiry
    ON survey_responses (respondent_session_expires_at)
    WHERE respondent_guest_key IS NOT NULL;
