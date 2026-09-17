-- GitLab #239 / S15P21A604-885 — 이벤트 상점 구매의 받는 자 정보.
--
-- 경품이 기프티콘이라 지급에 "누구한테 줄지"가 필요하다. FE 는 교환 모달에서 세 값을 받아
-- 구매 요청 본문에 실어 보낸다 (!1095, shared/contracts/purchaseRecipient.ts).
--
-- 세 컬럼 모두 nullable 이다. 이미 들어간 구매 행에 ''(빈 문자열)을 채우면 "받는 자를 받지
-- 않던 시절의 구매" 와 "빈 값을 받은 구매" 가 구분되지 않는다 — 조용히 그럴듯한 기본값으로
-- 덮는 것이 T-24 의 형태다. 필수 여부는 애플리케이션이 신규 구매에만 적용하고
-- (EventShopService.purchase), 여기서는 과거를 과거로 남긴다.
ALTER TABLE event_purchases ADD COLUMN campus VARCHAR(20);
ALTER TABLE event_purchases ADD COLUMN team_name VARCHAR(50);
ALTER TABLE event_purchases ADD COLUMN recipient_name VARCHAR(50);

-- 캠퍼스 어휘는 앱 검증이 정본이고 이 제약은 마지막 그물이다 (V31·V32·V33 의 어휘 제약과 같은
-- 관례). NULL 을 허용해야 과거 행이 살아남는다.
ALTER TABLE event_purchases ADD CONSTRAINT ck_event_purchases_campus
  CHECK (campus IS NULL OR campus IN ('서울', '대전', '광주', '구미', '부울경'));
