-- spec 011 US1 P1 — 상담 요청·수락·종료 (S15P21A604-137).
--
-- consultations 표는 V1 부터 있었고 컬럼도 그대로 쓴다. 더하는 것은 어휘와 두 제약뿐이다.

-- 상태 어휘. REJECTED 는 자리를 두되 P1 에서 만들지 않는다 — 확정 계약에 거절 경로가 없다
-- (초대가 Owner 취소만 제공하는 것과 같은 판단, C-10).
ALTER TABLE consultations
  ADD CONSTRAINT ck_consultations_status
  CHECK (status IN ('REQUESTED', 'ACCEPTED', 'ENDED', 'EXPIRED', 'CANCELLED', 'REJECTED'));

-- SC-006 / FR-021 — 직원 한 명에게 활성 상담은 1건이다.
--
-- 애플리케이션 판정이 아니라 DB 가 막는다. FR-021 이 "동시 요청에서도 거부" 를 명시하는데,
-- 읽고-판단하고-쓰는 경로는 두 요청이 같은 순간에 읽으면 둘 다 통과한다. 부분 유니크 인덱스는
-- 저장소에 이미 같은 관례가 있다 — ux_booth_leases_active_slot, ux_staff_invitations_pending.
CREATE UNIQUE INDEX ux_consultations_active_staff
  ON consultations (staff_user_id) WHERE status = 'ACCEPTED';

-- 대기열 조회 경로 (부스별 REQUESTED).
CREATE INDEX ix_consultations_booth_status ON consultations (booth_id, status);

-- 방문자의 대기 중 요청 중복을 막는다. 같은 사람이 한 부스에 요청을 쌓아 두면 대기열이 한
-- 사람으로 채워진다.
CREATE UNIQUE INDEX ux_consultations_pending_visitor
  ON consultations (booth_id, visitor_user_id) WHERE status = 'REQUESTED';
