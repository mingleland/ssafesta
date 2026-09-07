# Document Result Contract — 합의된 의미 경계

FastAPI → Spring 결과 API의 **endpoint 경로와 DTO 이름은 Jira S15P21A604-400에서 BE·AI가 확정한다.** 헌법 30조에 따라 임의의 경로를 OpenAPI로 고정하지 않는다.

| Operation | Required identity | Rule |
|---|---|---|
| heartbeat | `jobId + attemptNo` | 30초 주기, lease 90초 연장 |
| chunk batch | `jobId + attemptNo + batchSeq` | 최대 200 Chunk/8 MiB, 같은 batch 멱등 |
| finalize | `jobId + attemptNo + sourceHash + expected counts` | 검증 후 Chunk 교체·공개, Job 성공, Document READY를 한 트랜잭션으로 처리 |
| failed | `jobId + attemptNo + failureCode` | Spring이 재시도 또는 DEAD/FAILED를 결정 |

- 오래된 attempt는 `409 Conflict`다.
- 삭제·취소된 Job은 `410 Gone`이다.
- staging PK는 `(job_id, batch_seq, chunk_no)`다.
- Agent 설정 조회는 Jira S15P21A604-399에서 결정한다.

