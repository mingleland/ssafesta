-- spec 005 FR-018 — 외부 Facade를 설계 문서(docs/08 §3, docs/09 §7)의 4필드로 정렬한다 (U-05).
--
-- V1은 facade_code 하나였고 두 문서는 4필드였다. 아직 아무도 읽지 않는 컬럼이라 지금이 정리 비용이
-- 가장 싸다. 값은 전부 'DEFAULT'이므로 rename으로 그대로 옮겨진다 — 데이터 이관이 없다.

ALTER TABLE booths RENAME COLUMN facade_code TO facade_theme_code;

ALTER TABLE booths ADD COLUMN facade_primary_color VARCHAR(7);
ALTER TABLE booths ADD COLUMN facade_sign_text VARCHAR(60);
ALTER TABLE booths ADD COLUMN facade_logo_url VARCHAR(2048);

COMMENT ON COLUMN booths.facade_primary_color IS '#RRGGBB, NULL이면 테마 기본색';
COMMENT ON COLUMN booths.facade_sign_text IS '외부 간판 문구, 최대 60자';
COMMENT ON COLUMN booths.facade_logo_url IS 'https URL만 허용';
