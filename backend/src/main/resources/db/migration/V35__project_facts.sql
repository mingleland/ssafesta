-- spec 009 / spec 008 — 프로젝트 정형 정보와 그 출처 (S15P21A604-597, GitLab #169).
--
-- AI 가 문서 임베딩을 끝낸 뒤 추출해 보내는 두 값이다. 사람이 입력하는 필드가 아니라서
-- 프로젝트 생성·수정 API 는 건드리지 않는다.

-- TEXT 다. AI 가 뽑아 보내는 자유 텍스트라 상한을 DB 에 박으면 언젠가 조용히 잘린다 — 아바타
-- 코드에서 겪은 것과 같은 실패다(헌법 23조, T-24). 길이는 서버가 보고 넘치면 400 으로 답한다.
ALTER TABLE projects
  ADD COLUMN target_audience TEXT,
  ADD COLUMN tech_stack TEXT;

-- 출처. "오래된 작업 결과가 최신 값을 덮지 않는다" 를 판정하려면 지금 값이 어느 Job 에서 왔는지
-- 알아야 한다. jobId 는 단조 증가하므로 그 자체가 순서다.
--
-- facts_document_id 는 ON DELETE SET NULL 이다. 문서가 지워져도 추출된 값은 남는다 — 그 값이
-- 틀렸다는 뜻이 아니라 근거 문서가 사라진 것뿐이고, 지우면 AI 가 답할 근거를 잃는다.
ALTER TABLE projects
  ADD COLUMN facts_document_id BIGINT REFERENCES ai_documents(id) ON DELETE SET NULL,
  ADD COLUMN facts_job_id BIGINT,
  ADD COLUMN facts_updated_at TIMESTAMPTZ;
