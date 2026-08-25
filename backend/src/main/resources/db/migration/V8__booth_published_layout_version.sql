-- spec 005 — 공개 중인 Layout 회차를 가리키는 포인터 (research R-02, data-model I-3)
--
-- NULL은 "공개된 것이 없음"이다. 이 표현이 없으면 FR-011/FR-017(재임대 시 자동 공개 금지)을
-- 데이터로 나타낼 수 없다 — MAX(version_no)로 유도하면 이전 소유자의 마지막 공개본이 되살아난다.

ALTER TABLE booths ADD COLUMN published_layout_version INTEGER;

-- 이미 있는 UNIQUE(booth_id, version_no)를 참조한다. 포인터가 실재하지 않는 회차를 가리키는 것을
-- DB가 막고, NULL일 때는 MATCH SIMPLE 규칙으로 검사 자체가 면제된다 — 두 요구가 제약 하나로 성립한다.
--
-- ON DELETE SET NULL에 컬럼 목록이 붙은 이유:
--   ① 목록이 없으면 PostgreSQL이 FK의 **모든** 컬럼을 NULL로 만든다. 여기에는 booths.id(PK)가
--      포함되므로 NOT NULL 위반으로 실패한다. 목록 지정은 PostgreSQL 15+ 기능이고 우리는 pg17이다.
--   ② 이게 없으면 회원 탈퇴가 깨진다. AccountDeletionService는 booth_layout_published_versions를
--      booths보다 **먼저** 지우는데, 그때 부스가 그 회차를 가리키고 있으면 FK가 삭제를 막는다.
--      삭제 순서를 코드에서 관리하는 대신 제약이 스스로 정리하게 한다.
ALTER TABLE booths
    ADD CONSTRAINT fk_booths_published_layout_version
    FOREIGN KEY (id, published_layout_version)
    REFERENCES booth_layout_published_versions (booth_id, version_no)
    ON DELETE SET NULL (published_layout_version);
