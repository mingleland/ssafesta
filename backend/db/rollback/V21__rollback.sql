-- V21 되돌리기 — 적용 후 스키마를 V18 상태로 복구한다 (S15P21A604-397).
--
-- Flyway Community 에는 undo 가 없다. 이 파일은 손으로 실행하는 스크립트이고, 실행 후
-- flyway_schema_history 에서 V21 행을 지워야 다음 migrate 가 다시 적용한다:
--
--   psql "$DATABASE_URL" -f backend/db/rollback/V21__rollback.sql
--   psql "$DATABASE_URL" -c "DELETE FROM flyway_schema_history WHERE version = '21'"
--
-- ⚠️ 되돌리면 Job·staging 행이 사라진다. 처리 중이던 문서는 이력 없이 사라지고,
-- searchable 로 나간 청크의 출처(job_id)도 함께 없어진다. 청크 본문과 임베딩은 남는다.
-- V21 가 metadata 컬럼을 드롭했으므로 그 값은 복구되지 않는다 — 애초에 쓰는 코드가 없어
-- 비어 있었다는 전제 위에 있다.

BEGIN;

DROP INDEX IF EXISTS ix_ai_document_chunks_embedding_cosine;

-- 문서 삭제 연쇄를 V1 상태(NO ACTION)로 되돌린다.
ALTER TABLE ai_document_chunks
  DROP CONSTRAINT IF EXISTS ai_document_chunks_document_id_fkey;
ALTER TABLE ai_document_chunks
  ADD CONSTRAINT ai_document_chunks_document_id_fkey
    FOREIGN KEY (document_id) REFERENCES ai_documents(id);

ALTER TABLE ai_document_chunks
  ALTER COLUMN embedding DROP NOT NULL;

ALTER TABLE ai_document_chunks
  ALTER COLUMN embedding_model_id TYPE VARCHAR(100),
  ADD COLUMN IF NOT EXISTS metadata JSONB;

ALTER TABLE ai_document_chunks
  DROP COLUMN IF EXISTS searchable,
  DROP COLUMN IF EXISTS section,
  DROP COLUMN IF EXISTS page_number,
  DROP COLUMN IF EXISTS job_id;

-- staging 을 먼저 지운다 — job 을 참조한다.
DROP TABLE IF EXISTS ai_document_chunk_staging;
DROP TABLE IF EXISTS ai_document_jobs;

COMMIT;

-- 복구 확인
--   \d ai_document_chunks   → job_id·page_number·section·searchable 이 없고 metadata 가 있다
--   \dt ai_document_*       → ai_documents · ai_document_chunks 둘만 남는다
