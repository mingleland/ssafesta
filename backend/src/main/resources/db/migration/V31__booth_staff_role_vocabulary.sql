-- spec 011 FR-002·C-09 — booth_staffs.role 어휘를 고정한다.
--
-- 이 제약이 없으면 BoothAccessGuard 의 역할 판정이 "아는 값이면 판정하고 모르는 값이면 거부" 로
-- 동작하는데, 그 거부가 조용하다. 어휘를 DB 에서 막아 두면 잘못된 값은 쓰는 쪽에서 즉시 터진다.
--
-- 기존 행은 없다 — spec 005 는 이 테이블을 읽기만 했고 행을 만드는 코드가 develop 에 없었다.
-- 행을 만드는 것은 S15P21A604-136(초대 API)부터다.
ALTER TABLE booth_staffs
  ADD CONSTRAINT ck_booth_staffs_role
  CHECK (role IN ('ADMIN', 'CONTENT_EDITOR', 'CONSULTANT'));
