-- 부스에 속하지 않는 이벤트 설문 (S15P21A604-621, GitLab #173).
--
-- SSAFESTA 공용 이벤트 설문이다. 부스 설문은 부스 주인이 만들고 그 부스 방문자가 답하지만,
-- 이것은 운영이 하나 만들고 축제 참가자 전체가 답한다. 경품 상점의 [설문 참여하기] 가 여는
-- 대상이고 FE 는 !622 로 이미 붙어 있다.
--
-- 가짜 부스로 태우지 않는 이유: BoothAccessGuard.requireVisitorVisible 이 부스 존재 + ACTIVE
-- 임대 + 게시 레이아웃 셋을 요구하고 booths.owner_user_id 가 NOT NULL 이라, 가짜 유저·부스·
-- 임대·레이아웃 네 개가 필요하다. 그중 하나가 만료되는 순간 이벤트가 조용히 404 가 된다.
--
-- 별도 event_surveys 표를 만들지 않는 이유: 문항·선택지·응답·답변 네 표와 validate·submit 을
-- 통째로 복제하게 되고, 문항 유형이 늘면 두 곳을 고쳐야 한다.
--
-- scope 칼럼을 두지 않는다 — booth_id IS NULL 이 이미 같은 말이다.

ALTER TABLE surveys ALTER COLUMN booth_id DROP NOT NULL;
ALTER TABLE surveys ALTER COLUMN created_by_user_id DROP NOT NULL;
ALTER TABLE surveys ADD COLUMN survey_key VARCHAR(50);

-- 두 축이 배타적이라는 것과, 부스 설문의 기존 불변식이 그대로라는 것 둘 다 DB 가 지킨다.
--
-- xor 로 적은 것은 V22 의 ck_survey_responses_respondent(회원 xor 게스트)와 같은 모양이라서다.
-- 뒤쪽 절이 없으면 이벤트 설문 하나 때문에 부스 설문의 "작성자는 반드시 있다" 가 약해진다 —
-- created_by_user_id 를 nullable 로 내린 것은 이벤트 행 때문이지 부스 행 때문이 아니다.
ALTER TABLE surveys ADD CONSTRAINT ck_surveys_scope CHECK (
  ((booth_id IS NULL) <> (survey_key IS NULL))
  AND (booth_id IS NULL OR created_by_user_id IS NOT NULL));

-- 이벤트 하나당 설문 하나. 부스 쪽 ux_surveys_booth 와 같은 역할이고, events 표를 만들지
-- 않으므로 survey_key 가 곧 이벤트 식별자다.
--
-- ux_surveys_booth 는 재생성하지 않는다. Postgres 는 NULL 을 서로 다른 값으로 보므로 이벤트
-- 행이 아무리 늘어도 그 인덱스에 걸리지 않는다.
CREATE UNIQUE INDEX ux_surveys_key ON surveys (survey_key) WHERE survey_key IS NOT NULL;

-- 이벤트 설문 본체. 편집 화면을 만들지 않으므로 시드가 유일한 생성 경로다.
--
-- ends_at 은 NULL(무기한)이다 — 행사 종료 시각이 아직 확정이 아니고, 정해지면 UPDATE 한 번이다
-- (GitLab #173 Q3). reward_coin 0 이지만 회원 전용이다: 추첨이 참여자를 특정해야 해서이고,
-- 그 판정은 reward 가 아니라 survey_key 유무로 한다.
--
-- 문항은 넣지 않는다. 기획 문구가 아직 오지 않았고, 없는 채로 지어내면 그것이 그대로 실서비스
-- 문항이 된다. 도착하면 별도 마이그레이션으로 얹는다 — 그때까지 run 응답의 questions 는 [] 다.
INSERT INTO surveys (booth_id, survey_key, title, description, reward_coin, status, ends_at,
                     created_by_user_id)
VALUES (NULL, 'SSAFESTA_2026', 'SSAFESTA 이벤트 설문',
        '참여해 주신 분들 중 추첨을 통해 경품을 드립니다.', 0, 'OPEN', NULL, NULL);
