-- 임대 슬롯 7개 → 12개 (#62, 2026-08-24). Unity 축제 존 12부스·내부 홀 12실과 맞춘다.
--
-- V5가 7개만 넣은 것은 spec 004 research의 BE 자체 결정이었고 기획 확정값이 아니다. 12실이
-- 만들어진 지금은 8~12번 방에 임대도 layout도 생길 수 없어 영구 빈 셸이 된다.
--
-- id를 명시 삽입하는 이유: slotId 1~12가 Unity 앵커 01~12와 대응한다는 것이 계약이다
-- (contracts/layout-api.md §11). V5는 빈 테이블에 7행을 순차 삽입했으므로 기존 환경도 id 1~7이
-- 보장되고, 여기서 8~12를 이어 붙이면 대응이 고정된다.
--
-- ON CONFLICT를 쓰지 않는 것도 의도다. 이 대응은 계약이므로, id가 어긋난 환경이라면 조용히
-- 건너뛰어 "앵커 8번이 남의 방을 가리키는" 상태로 기동하는 대신 마이그레이션이 실패해야 한다
-- (실패를 삼키지 않는다 — T-24).
--
-- V5 파일 자체는 손대지 않는다: 이미 적용된 마이그레이션을 고치면 Flyway checksum이 깨져
-- 기존 환경이 기동하지 못한다. V5 주석의 낡은 층 문구(#31로 11층 단일 확정)는 이 주석으로 갈음한다.

INSERT INTO booth_slots (id, slot_code, floor_no, slot_type, status) VALUES
  (8,  'F11-R08', 11, 'USER_RENTAL', 'AVAILABLE'),
  (9,  'F11-R09', 11, 'USER_RENTAL', 'AVAILABLE'),
  (10, 'F11-R10', 11, 'USER_RENTAL', 'AVAILABLE'),
  (11, 'F11-R11', 11, 'USER_RENTAL', 'AVAILABLE'),
  (12, 'F11-R12', 11, 'USER_RENTAL', 'AVAILABLE');

-- IDENTITY 시퀀스는 명시 삽입을 따라오지 않는다. 맞춰 두지 않으면 다음 자동 발급이 8부터
-- 시작해 PK 충돌로 죽는다.
SELECT setval(pg_get_serial_sequence('booth_slots', 'id'), (SELECT MAX(id) FROM booth_slots));
