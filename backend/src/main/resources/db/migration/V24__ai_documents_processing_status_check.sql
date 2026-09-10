-- ai_documents.processing_status 의 값 도메인을 스키마가 직접 말하게 한다 (spec 007 FR-006).
--
-- 이 컬럼을 쓰는 곳이 두 세계에 걸쳐 있다. JPA 엔티티(AiDocument 의 생성자·expire·recover)와
-- internal.ai 의 raw JdbcTemplate(AiDocumentJobRepository.markDocumentReady)이다. 후자는 엔티티를
-- 로드하지 않고 AiDocumentRepository 는 package-private 이라, ai 패키지에 어떤 Java 상수 표를 둬도
-- 그 UPDATE 에 닿지 못한다. 두 세계가 실제로 만나는 지점은 SQL 뿐이다.
--
-- 그래서 걸리는 것이 오타다. 텍스트 블록에 'REDAY' 라고 적으면 VARCHAR(30) 은 그대로 받고, 그
-- 문서는 조용히
--   · 중복 판정(ux_ai_documents_agent_active_sha)
--   · 쿼터 집계(countActive · sumActiveBytes)
--   · 검색 게이트(READY 문서의 chunk 만 공개)
-- 세 곳에서 동시에 사라진다. 아무 오류도 나지 않는다. 자바 표로는 잡을 수 없고 이 제약으로만
-- 잡힌다 — V17 의 부분 유니크 인덱스를 "the schema's own statement of the rule" 로 둔 것과 같은
-- 자리다.
--
-- 전이 규칙은 여기서 표현하지 않는다. CHECK 는 UPDATE 에도 걸리지만 변경 후 행만 보므로
-- OLD·NEW 조합("EXPIRED 는 READY 로 갈 수 없다")은 쓸 수 없다. 그건 각 문장의 WHERE 절이
-- 맡는다 — sweep 은 uploaded_at IS NULL, markDocumentReady 는 IN ('QUEUED','PROCESSING').
-- 트리거로 올리지 않는 이유는 이 저장소 마이그레이션에 TRIGGER 가 하나도 없기 때문이고,
-- 상태 대입 한 줄 때문에 첫 사례가 될 이유가 없다.
--
-- NULL 은 문제가 되지 않는다. CHECK 는 NULL 결과를 통과시키지만 컬럼이 V1 부터 NOT NULL 이다.
ALTER TABLE ai_documents
  ADD CONSTRAINT ck_ai_documents_processing_status
  CHECK (processing_status IN ('QUEUED', 'PROCESSING', 'READY', 'FAILED', 'DISABLED', 'EXPIRED'));
