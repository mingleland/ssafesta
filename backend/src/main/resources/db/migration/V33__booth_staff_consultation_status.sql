-- spec 011 FR-004 — 직원 상담 상태 (S15P21A604-136).
--
-- 기본값이 OFFLINE 인 것은 US2 의 전제다: "담당자가 항상 있을 수 없다. 오프라인이 기본 상태라고
-- 봐야 한다." 그래서 행이 생기는 순간의 값이 곧 정상 경로다.
--
-- BUSY 는 서버가 관리한다 — 상담 수락이 넣고 종료가 되돌린다. 직원이 직접 지정하는 값이 아니다.
ALTER TABLE booth_staffs
  ADD COLUMN consultation_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE';

ALTER TABLE booth_staffs
  ADD CONSTRAINT ck_booth_staffs_consultation_status
  CHECK (consultation_status IN ('AVAILABLE', 'BUSY', 'AWAY', 'OFFLINE'));
