-- spec 011 US3 — staff_invitations 의 상태·역할 어휘를 고정한다 (S15P21A604-136).
--
-- 테이블은 V1 부터 있었지만 쓰는 코드가 없어 어휘가 비어 있었다. 행을 만드는 것은 이 티켓부터다.
-- 역할 어휘는 V31 이 booth_staffs 에 건 것과 같다 — 수락이 초대의 role 을 그대로 옮겨 적으므로
-- 두 테이블의 어휘가 갈리면 수락 시점에 CHECK 로 터진다.
ALTER TABLE staff_invitations
  ADD CONSTRAINT ck_staff_invitations_role
  CHECK (role IN ('ADMIN', 'CONTENT_EDITOR', 'CONSULTANT'));

ALTER TABLE staff_invitations
  ADD CONSTRAINT ck_staff_invitations_status
  CHECK (status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'EXPIRED'));

-- 본인에게 온 대기 중 초대 조회(FR-016)와 만료 스윕이 함께 쓰는 경로.
CREATE INDEX ix_staff_invitations_invited_status
  ON staff_invitations (invited_user_id, status);
