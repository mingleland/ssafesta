-- V28__event_slot_type.sql 되돌리기 (S15P21A604-615).
--
-- 1번 슬롯을 다시 일반 임대 자리로 돌린다. 되돌린 뒤에는 임대가 다시 열리므로, Unity 가
-- 이벤트 부스로 쓰고 있다면 씬 설정과 어긋난다 — 되돌릴 때 게임 파트에 알려야 한다.
UPDATE booth_slots SET slot_type = 'USER_RENTAL' WHERE id = 1;
