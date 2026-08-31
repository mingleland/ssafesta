-- Upload grant columns for ai_documents (spec 007 US2, FR-019a~c · FR-026~032).
--
-- V1 created the table for a pipeline that had no write path: it knows where the object is
-- (s3_key) but not which storage it is in, nor what the client claimed the bytes hash to. Both
-- became requirements after V1 — FR-030 makes the provider per-document because reads must follow
-- the row rather than the currently active provider, and FR-019a makes the hash mandatory.
--
-- No backfill and no DEFAULT on the NOT NULL adds: ai_documents has never had a writer, so the
-- table is empty everywhere. If a row does exist this fails loudly, which is the correct outcome
-- (same reasoning as V15).

ALTER TABLE ai_documents
  ADD COLUMN content_sha256 VARCHAR(64) NOT NULL,
  ADD COLUMN storage_provider VARCHAR(20) NOT NULL,
  ADD COLUMN storage_bucket VARCHAR(255) NOT NULL,
  -- When the upload was verified against storage, not when the grant was issued. NULL means the
  -- URL was handed out and nothing has confirmed the bytes arrived — that is what the 1-hour
  -- EXPIRED sweep looks for (S15P21A604-174), and created_at cannot answer it.
  ADD COLUMN uploaded_at TIMESTAMPTZ,
  -- When the row went EXPIRED. The 24-hour recovery window (FR-027) and the delete-after-grace
  -- rule (FR-028) are both measured from here; updated_at moves on any write and cannot be it.
  ADD COLUMN expired_at TIMESTAMPTZ;

-- The object key embeds the document id (docs/08 §7: booths/{b}/agents/{a}/documents/{id}/{file}),
-- so the row has to exist before the key can be written. The column stays NULL only inside the
-- issuing transaction; every committed row has a key. UNIQUE is kept — Postgres allows repeated
-- NULLs, so it does not stand in the way.
ALTER TABLE ai_documents ALTER COLUMN s3_key DROP NOT NULL;

-- Last line of defence for the duplicate race (FR-019b, #84). The application checks first and
-- answers politely; this catches two requests that both read "no duplicate" and both insert.
--
-- Partial on purpose: FAILED and DISABLED are excluded so re-uploading a file that failed is
-- allowed, and EXPIRED is excluded so an abandoned grant does not block the next attempt. That
-- means several rows may share (agent_id, content_sha256) — uniqueness holds only among active
-- documents, which is exactly what the contract says.
CREATE UNIQUE INDEX ux_ai_documents_agent_active_sha
  ON ai_documents(agent_id, content_sha256)
  WHERE processing_status IN ('QUEUED', 'PROCESSING', 'READY');
