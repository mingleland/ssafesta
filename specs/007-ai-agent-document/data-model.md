# Data Model: AI 직원 / 문서 파이프라인

## 소유권

모든 영속 데이터는 Spring Business DB에 있다. FastAPI는 DB 연결 설정과 영속 모델을 갖지 않는다.

| Entity | Owner | Purpose |
|---|---|---|
| `ai_agents` | Spring | 부스 AI 직원 설정 |
| `ai_documents` | Spring | 업로드 원본과 사용자 노출 상태 |
| `ai_document_jobs` | Spring | durable 처리 상태, lease, retry, fencing |
| `ai_document_chunk_staging` | Spring | 배치 결과의 멱등 임시 적재 |
| `ai_document_chunks` | Spring | READY 문서의 검색 Chunk/Embedding |

## AI Document Job — V21

- PK `id BIGINT`; `document_id`는 `ON DELETE CASCADE`다.
- Scope는 `booth_id`, `agent_id`다.
- Snapshot은 hash, 파일 메타데이터, storage provider/bucket/object key를 가진다.
- 상태는 `QUEUED | RUNNING | RETRY_WAIT | SUCCEEDED | DEAD | CANCELLED`다.
- `attempt_no`, `max_retries`, `worker_id`, lease/retry 시각과 오류·chunk 수를 가진다.
- 한 문서의 활성 Job(`QUEUED`, `RUNNING`, `RETRY_WAIT`)은 최대 1개다.

## Chunk Staging — V21

- PK `(job_id, batch_seq, chunk_no)`; Job 삭제 시 cascade된다.
- `content`, `embedding VECTOR(1536)`, `embedding_model_id`는 필수다.
- `page_number`, `section`은 선택이다.
- 같은 PK 재전송은 내용이 같을 때 멱등 성공하고 다르면 계약 오류다.

## Final Chunk — V21

- Document/Agent/Booth scope와 content, embedding, model ID를 가진다.
- `job_id`는 감사용 값이며 FK를 걸지 않는다.
- `page_number`, `section`, `searchable`을 가진다.
- Document FK는 `ON DELETE CASCADE`다.
- 검색은 `searchable=true`와 부모 Document `READY`를 동시에 강제한다.
- 코사인 HNSW 인덱스를 사용하며 `distance`가 작을수록 가깝다.

## 상태 전이와 원자성

```text
QUEUED -> RUNNING -> SUCCEEDED
                  -> RETRY_WAIT -> RUNNING
                  -> DEAD
                  -> CANCELLED
```

Spring만 상태를 변경한다. `RUNNING`의 `attempt_no`가 실행 fence다. finalize는 Chunk 교체·공개, Job 성공, Document READY를 한 트랜잭션에서 수행한다. 실패·취소 시 staging을 제거한다.

## 경계 DTO

처리 요청은 `jobId`, `attemptNo`, document/booth/agent ID, 파일 메타데이터, 저장소 위치, `sourceHash`를 포함한다. 결과 배치는 `jobId`, `attemptNo`, `batchSeq`, 최대 200개의 Chunk를 포함하고 body는 최대 8 MiB다. Agent 설정 DTO는 Jira S15P21A604-399 합의 전까지 정의하지 않는다.
