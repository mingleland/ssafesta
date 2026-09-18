-- 응모형 경품 — 마감 시각과 추첨 (S15P21A604-922).
--
-- 응모권은 별도의 개념이 아니라 경품의 한 종류다. winner_count > 0 이면 응모형이고,
-- 0 이면 지금까지와 같은 즉시 구매 상품이다. 그래서 테이블이 늘지 않는다 —
-- 응모 한 건은 event_purchases 의 한 행이고, 응모권 잔량은 event_prizes.stock 이다.
ALTER TABLE event_prizes
  ADD COLUMN closes_at TIMESTAMPTZ,
  -- 뽑을 당첨자 수. 0 = 응모형이 아니다(추첨하지 않는다).
  ADD COLUMN winner_count INT NOT NULL DEFAULT 0 CHECK (winner_count >= 0),
  -- 추첨을 실제로 돌린 시각. 두 번 뽑는 것을 막는 유일한 근거다 — active=false 만으로는
  -- 판매 종료와 추첨 완료를 구분할 수 없다.
  ADD COLUMN drawn_at TIMESTAMPTZ;

-- 축제 종료와 함께 모든 경품이 닫힌다.
UPDATE event_prizes SET closes_at = TIMESTAMPTZ '2026-09-23 15:00:00+09';

-- 당락. NULL = 추첨 전이거나 애초에 응모형이 아니다, TRUE = 당첨, FALSE = 낙첨.
--
-- fulfillment 로 당락을 표현하지 않는다. CANCELLED 는 '취소'(코인이 돌아갔다는 뉘앙스)이고
-- 낙첨은 코인이 소멸한 정상 종료라, 같은 값에 담으면 관리자 화면에서 오독된다.
-- 당첨자만 이후 PURCHASED -> PENDING -> FULFILLED 의 지급 절차를 탄다.
ALTER TABLE event_purchases ADD COLUMN won BOOLEAN;

-- 추첨(응모자 전수 조회)과 관리자 당첨자 조회가 같이 쓴다.
CREATE INDEX ix_event_purchases_prize_won ON event_purchases (prize_id, won);
