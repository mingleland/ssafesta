-- 수정본 교체 (spec 007 FR-019 · FR-027a, S15P21A604-386).
--
-- 두 칸이 늘고 상태 값은 늘지 않는다. 교체로 밀려난 원본은 EXPIRED 이며, 같은 EXPIRED 라도
-- "아직 안 올라온 것"(복구 가능, FR-027)과 "이미 다른 원본으로 대체된 것"(복구 불가, FR-027a)을
-- replaced_at 하나가 가른다. REPLACED 라는 상태를 새로 만들면 V24 CHECK·스윕 predicate·OpenAPI·
-- FE 상태표·기존 테스트가 전부 따라온다 — 갈라야 할 것은 "복구 가능한가" 하나뿐이다.
--
-- replaces_document_id 는 spec 의 데이터 모델 목록에 없지만 교체가 성립하려면 있어야 한다.
-- 퇴역은 /complete 가 아니라 새 문서가 실제로 READY 된 뒤에 일어나고(FR-040 단일 트랜잭션),
-- 그 시점에 "이 문서가 어느 원본을 밀어냈는가" 를 아는 곳은 행밖에 없다.
ALTER TABLE ai_documents
  -- SET NULL 이지 CASCADE 가 아니다. CASCADE 면 대상 문서를 지울 때 교체본까지 따라 사라진다 —
  -- 교체본은 사용자가 올린 최신 원본이고, 밀려난 쪽이 지워졌다고 없어질 이유가 없다.
  ADD COLUMN replaces_document_id BIGINT REFERENCES ai_documents(id) ON DELETE SET NULL,
  -- 교체로 밀려난 원본에만 찍힌다. expired_at 은 두 경우 모두에 찍히므로 구분자가 될 수 없다.
  -- 원본 삭제 스윕(FR-028)은 이 칸을 보지 않는다 — 대상 판정은 EXPIRED + 경과 시간뿐이고,
  -- 복구 경로가 없는 행이 삭제 경로에서도 빠지면 원본만 무기한 남는다.
  ADD COLUMN replaced_at TIMESTAMPTZ;

-- 한 원본을 노리는 살아 있는 교체본은 하나다.
--
-- 최후 방어선이다. 발급 경로는 agent 행을 잠그고 이 원본을 가리키는 활성 후속을 먼저 조회해
-- 분기하므로, 여기까지 오는 것은 그 잠금을 건너뛴 writer 가 생겼다는 뜻이다 — V17 의
-- ux_ai_documents_agent_active_sha 를 "the schema's own statement of the rule" 로 둔 것과 같은
-- 자리이고, 서비스가 위반을 성공으로 번역하지 않는 이유도 같다.
--
-- 부분 인덱스라 끝난 교체 시도(EXPIRED·FAILED·DISABLED)는 몇 개든 남는다. 버려진 대기 후속이
-- 다음 시도를 막으면 사용자가 빠져나갈 길이 없다.
CREATE UNIQUE INDEX ux_ai_documents_active_replacement
  ON ai_documents(replaces_document_id)
  WHERE replaces_document_id IS NOT NULL
    AND processing_status IN ('QUEUED', 'PROCESSING', 'READY');
