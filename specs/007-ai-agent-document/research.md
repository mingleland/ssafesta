# Research: AI 직원 / 문서 파이프라인

**Date**: 2026-09-06
**Decision source**: GitLab Work Item #119의 게시된 댓글, Jira S15P21A604-397~400, `V21__ai_document_jobs_and_chunk_staging.sql`, project constitution

## 1. 데이터 소유권

**Decision**: Spring Business DB가 `ai_documents`, `ai_document_jobs`, `ai_document_chunk_staging`, `ai_document_chunks`와 pgvector를 단독 소유한다. FastAPI는 문서 DB 자격증명, ORM, Repository, Alembic migration을 갖지 않는다.

**Rationale**: Job·Document·Chunk를 한 DB와 한 로컬 트랜잭션 경계에 두면 삭제 cascade, finalize, 검색 공개 여부를 분산 callback과 reconciliation 없이 보장할 수 있다. 이는 별도 AI DB를 정본으로 삼았던 S15P21A604-262 결정을 대체한다.

## 2. 비동기 처리 시작

**Decision**: Spring이 Job을 먼저 `QUEUED`로 영속화한 뒤 `jobId + attemptNo`와 검증된 문서 snapshot을 FastAPI에 push한다. FastAPI는 `202 Accepted`를 반환하고 파싱·청킹·임베딩을 비동기로 수행한다.

## 3. Lease, fencing, retry

heartbeat 30초, lease 90초를 사용한다. lease가 만료되면 Spring이 `attemptNo`를 증가시켜 1·5·15분 간격으로 최대 3회 재시도한다. 모든 결과 요청은 `jobId + attemptNo`를 포함한다. 오래된 attempt는 `409`, 삭제·취소된 Job은 `410`이다.

## 4. 배치 적재와 멱등성

FastAPI는 한 요청당 최대 200 Chunk 또는 8 MiB인 배치로 결과를 보낸다. staging PK는 `(job_id, batch_seq, chunk_no)`이며 같은 배치 재전송은 멱등이다. 배치 도착 순서는 처리 결과에 영향을 주지 않는다.

## 5. finalize 원자성

Spring은 attempt, source hash, 배치 연속성, chunk 수, embedding 차원을 검증한다. 성공 시 기존 Chunk 삭제, staging→final 복사, `searchable=true`, Job `SUCCEEDED`, Document `READY`를 한 로컬 트랜잭션에서 수행한다.

## 6. 실패와 취소

Spring이 재시도 가능 실패를 `RETRY_WAIT`로 관리한다. 상한 초과 또는 비재시도 실패는 Job `DEAD`, Document `FAILED`다. 문서 삭제·비활성화는 활성 Job을 `CANCELLED`로 만들고 staging/Chunk를 정리하며 반복 요청은 멱등이다.

## 7. RAG 검색 경계

FastAPI는 질의 임베딩만 생성하고 `POST /internal/ai/chunk-search`로 Spring에 검색을 요청한다. Spring은 `boothId + agentId + searchable=true + Document READY`를 강제한다. `topK` 상한은 20, timeout은 3초다. 코사인 `distance`를 오름차순으로 반환하고 threshold는 적용하지 않는다.

응답 필드는 `content`, `chunkNo`, `pageNumber`, `section`, `documentId`, `originalFilename`, `distance`다.

## 8. Agent 설정 조회

Endpoint, 응답 필드, 호출 시점, 캐시 정책은 Jira S15P21A604-399와 spec 007 C-16에서 BE·AI 합의 후 확정한다. 구현자가 임의로 정하지 않는다.

## 9. 인증·관측·저장소

방향별 Service Token과 Security Group 제한은 #102 합의를 유지한다. 로그에는 `jobId`, `attemptNo`, `batchSeq`, 처리 시간, 오류 코드만 남기고 Authorization, object key, 문서 원문, embedding은 남기지 않는다. R2/MinIO 전환과 업로드 만료 계약도 유지하되 FastAPI는 snapshot에 지정된 원본을 읽을 뿐 저장소 메타데이터를 소유하지 않는다.
