-- 관리자 부스는 무상·영구 임대다 (S15P21A604-905, 오프라인 확정).
--
-- 두 가지가 일반 회원과 다르다.
--   1. 코인을 내지 않는다 — 원장에 LEASE_PAYMENT 행을 아예 만들지 않는다.
--   2. 만료가 없다 — ends_at 을 먼 미래로 둔다.
--
-- ends_at 을 nullable 로 내리지 않은 것은 의도적이다. 만료 판정이 status = 'ACTIVE' AND
-- ends_at > now (spec 004 C-02) 한 줄로 서 있고, 스위퍼·경고 발행·슬롯 목록이 모두 그 형태를
-- 공유한다. NULL 을 도입하면 그 전부가 "NULL 이면 만료 아님" 분기를 따로 들어야 하고, 하나라도
-- 빠뜨리면 영구 부스가 조용히 만료된 것으로 읽힌다. 먼 미래 값은 기존 술어를 그대로 두고도 같은
-- 뜻을 낸다 — 비교 대상이 하나 늘지 않는다.
--
-- 두 UNIQUE 인덱스는 지우지 않고 좁힌다. 일반 회원의 보호(T-110·V6, T-117·V7)는 그대로 남는다.

ALTER TABLE booths ADD COLUMN admin_owned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE booth_leases ADD COLUMN permanent BOOLEAN NOT NULL DEFAULT FALSE;

-- V7 의 "회원당 부스 1개" 는 관리자에게는 성립하지 않는다 — 관리자는 슬롯마다 부스를 하나씩 든다.
-- 부분 인덱스로 좁혀 일반 회원 쪽 보호만 남긴다. BoothRepository 가 관리자 부스를 세지 않는
-- 조회(findByOwnerUserIdAndAdminOwnedFalse)만 쓰므로 Optional 계약도 그대로 성립한다.
DROP INDEX ux_booths_owner;
CREATE UNIQUE INDEX ux_booths_owner ON booths(owner_user_id) WHERE NOT admin_owned;

-- V6 의 "회원당 활성 임대 1건" 도 같은 이유로 영구 임대를 제외한다.
DROP INDEX ux_booth_leases_active_lessee;
CREATE UNIQUE INDEX ux_booth_leases_active_lessee
  ON booth_leases(lessee_user_id) WHERE status = 'ACTIVE' AND NOT permanent;

-- ux_booth_leases_active_slot(V1) 은 건드리지 않는다. 슬롯 하나를 두 임대가 동시에 갖지 못하는
-- 규칙은 관리자에게도 그대로 적용된다 — 관리자 둘이 같은 슬롯을 가질 수는 없다.
