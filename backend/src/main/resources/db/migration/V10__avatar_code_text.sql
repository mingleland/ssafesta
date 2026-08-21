-- 헌법 23조 "저장은 TEXT"와의 정합 (#24, 2026-08-21).
--
-- fa 인코딩 실측 최대 414자라 varchar(3800)도 당장은 넉넉하지만, 상한이라는 개념 자체를
-- 스키마에서 없앤다 — 의상 색 슬롯이 늘어나는 종류의 변경마다 상한을 재계산해 마이그레이션을
-- 다시 태우는 일이 생기지 않도록. PostgreSQL에서 varchar(n) → text는 테이블 재작성 없이 끝난다.
-- 서버는 이 값을 파싱하지 않는다 (BE.md — 외형 해석은 클라이언트).
ALTER TABLE users ALTER COLUMN avatar_code TYPE TEXT;
