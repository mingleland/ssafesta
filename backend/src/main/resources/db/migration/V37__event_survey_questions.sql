-- 이벤트 설문 문항 시드 (S15P21A604-767, GitLab #173).
--
-- V29 가 설문 행만 넣고 "문항은 기획 문구가 도착하면 별도 마이그레이션으로 얹는다" 로 자리를
-- 비워 뒀다. 그 문구가 2026-09-11 에 도착했다(GitLab #173, docs/26 에도 같은 표). 이 파일이 그 자리다.
--
-- 지금까지 이벤트 설문은 통째로 닫혀 있었다 — SurveyService.findEventRun 이 문항 0개를
-- "아직 공개되지 않은 설문입니다" 404 로 막기 때문이다. 제출 경로도 같은 판정을 쓴다. 문항이
-- 들어오는 순간 두 경로가 함께 열린다.
--
-- 설문 행에는 고정 id 가 없다(IDENTITY). survey_key 가 유일한 손잡이이고, 선택지는 문항의
-- (survey_id, display_order) 로 되짚는다 — 그것이 문항 행을 가리키는 유일한 자연키다.
--
-- ⚠️ 대상을 JOIN 이 아니라 스칼라 서브쿼리로 잡는다. JOIN 이면 설문 행을 못 찾았을 때 결과가
-- 0행이 되어 마이그레이션이 조용히 성공한다 — 문항이 안 들어간 채로 배포가 초록이 된다.
-- 스칼라 서브쿼리는 같은 경우 NULL 을 주고, survey_id·question_id 가 NOT NULL 이라 그 자리에서
-- 죽는다. 빠진 시드가 정상처럼 보이지 않게 하는 것이 이 선택의 전부다.
--
-- 별점 눈금 설명이 문구 안에 들어 있는 것은 실수가 아니다. survey_questions 에는 rating_min·
-- rating_max 만 있고 눈금별 라벨 칼럼이 없으며, FE 도 별 N개와 aria-label 만 그린다. 칼럼을
-- 새로 만들면 스키마·wire·렌더가 함께 바뀌므로 문구로 같은 정보를 전달한다(코드 변경 0).
--
-- is_required 를 모든 행에 명시한다. 컬럼 기본값이 FALSE 라 생략하면 필수 4개가 조용히 선택
-- 문항이 된다 — 화면에는 아무 증상이 없고 응답 데이터에서만 드러난다.

INSERT INTO survey_questions
    (survey_id, question_type, question_text, is_required, display_order, rating_min, rating_max)
SELECT (SELECT id FROM surveys WHERE survey_key = 'SSAFESTA_2026'),
       q.question_type, q.question_text, q.is_required, q.display_order, q.rating_min, q.rating_max
FROM (VALUES
    ('RATING', '전시 부스에서 다른 팀의 프로젝트를 둘러보는 과정이 얼마나 편리했나요? (1 = 매우 불편했다 · 5 = 매우 편리했다)', TRUE, 0, 1, 5),
    ('RATING', 'SSAFESTA를 다시 이용할 의향이 있나요? (1 = 전혀 없다 · 5 = 매우 있다)', TRUE, 1, 1, 5),
    ('MULTIPLE_CHOICE', 'SSAFESTA를 다시 이용한다면 어떤 기능을 가장 기대하시나요? 모두 선택해주세요.', TRUE, 2, NULL, NULL),
    ('MULTIPLE_CHOICE', '이용하면서 아쉽거나 부족하다고 느낀 점을 모두 선택해주세요.', TRUE, 3, NULL, NULL),
    ('LONG_TEXT', '추가로 개선했으면 하는 점이 있다면 자유롭게 적어주세요.', FALSE, 4, NULL, NULL),
    ('SHORT_TEXT', '이벤트 상점에 추가되었으면 하는 경품이나 물품이 있다면 적어주세요.', FALSE, 5, NULL, NULL)
) AS q(question_type, question_text, is_required, display_order, rating_min, rating_max);

-- Q3 선택지 7개. 선택형이 아닌 문항(Q1·Q2·Q5·Q6)에는 선택지를 넣지 않는다 —
-- validateQuestion 이 400 을 낸다.
INSERT INTO survey_options (question_id, option_text, display_order)
SELECT (SELECT q.id FROM survey_questions q JOIN surveys s ON s.id = q.survey_id
        WHERE s.survey_key = 'SSAFESTA_2026' AND q.display_order = 2),
       o.option_text, o.display_order
FROM (VALUES
    ('다른 팀의 프로젝트·부스 둘러보기', 0),
    ('미니게임', 1),
    ('AI NPC·상담', 2),
    ('이벤트·경품 콘텐츠', 3),
    ('캐릭터·월드 탐색', 4),
    ('부스 꾸미기·프로젝트 전시', 5),
    ('다른 참가자와의 소통', 6)
) AS o(option_text, display_order);

-- Q4 선택지 9개.
INSERT INTO survey_options (question_id, option_text, display_order)
SELECT (SELECT q.id FROM survey_questions q JOIN surveys s ON s.id = q.survey_id
        WHERE s.survey_key = 'SSAFESTA_2026' AND q.display_order = 3),
       o.option_text, o.display_order
FROM (VALUES
    ('월드 이동·조작이 불편했다', 0),
    ('원하는 부스나 프로젝트를 찾기 어려웠다', 1),
    ('즐길 콘텐츠가 부족했다', 2),
    ('미니게임이 부족했다', 3),
    ('다른 참가자와 상호작용할 요소가 부족했다', 4),
    ('채팅이나 음성채팅 기능이 필요하다고 생각한다', 5),
    ('로딩·성능이 불편했다', 6),
    ('UI가 이해하기 어려웠다', 7),
    ('특별히 아쉬운 점이 없었다', 8)
) AS o(option_text, display_order);
