-- S15P21A604-981 — READY 문서 RAG 후처리로 만든 프로젝트 정형답변과 생성 근거.
-- 기존 target_audience/tech_stack 값은 그대로 두는 additive migration이다.
ALTER TABLE projects
  ADD COLUMN ai_introduction TEXT,
  ADD COLUMN facts_sources JSONB,
  ADD COLUMN facts_generation_version VARCHAR(50);
