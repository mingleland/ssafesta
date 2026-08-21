# Data Model: AI 직원 / 문서 파이프라인

## 소유권과 스키마

| 스키마 | 소유 파트 | 주요 테이블 |
|---|---|---|
| `public` | Spring / Backend | `ai_agents`, `ai_documents`, `ai_document_chunks` |
| `ai` | FastAPI / AI | `document_jobs` |

FastAPI migration role은 `ai` 스키마 DDL을 수행한다. runtime role은 Job DML과 필요한 `public` 테이블의 최소 권한만 가진다. FastAPI는 `public.ai_documents`를 직접 갱신하지 않는다.

## AI Agent — Spring 소유

| 필드 | 규칙 |
|---|---|
| `id` | PK |
| `booth_id` | 부스 범위 식별자 |
| `name`, `role`, `tone`, `system_prompt` | Agent 설정 |
| `created_at`, `updated_at` | 감사 시각 |

FastAPI는 처리 요청의 `booth_id`, `agent_id` 조합이 실제 소유 관계와 일치하는지 읽기 검증한다.

## Document — Spring 소유

| 필드 | 규칙 |
|---|---|
| `id` | PK, Job과 Chunk의 기준 식별자 |
| `booth_id`, `agent_id` | 검색 격리 범위, NOT NULL |
| `original_filename` | 사용자 표시용 파일명 |
| `object_key` | Spring이 생성·관리하는 저장소 중립 object key, 클라이언트 임의 지정 금지 |
| `content_sha256` | 동일 Agent 중복 판정 및 개정본 경쟁 방지 |
| `file_size_bytes` | 파일당 20MB 및 Agent 총 100MB 검증 |
| `processing_status` | `QUEUED/PROCESSING/READY/FAILED/DISABLED` |
| `failure_reason` | 사용자 노출용 정제 메시지, nullable |
| `chunk_count` | 마지막 성공 처리 청크 수, nullable |
| `processed_at` | 마지막 성공 처리 시각, nullable |
| `created_at`, `updated_at` | 감사 시각 |

Backend migration은 실제 `origin/back`의 다음 사용 가능한 Flyway 버전으로 추가한다. 기존 migration 번호를 재사용하거나 수정하지 않는다.

## Processing Job — FastAPI 소유

테이블: `ai.document_jobs`

| 필드 | 타입 예시 | 규칙 |
|---|---|---|
| `id` | `BIGINT IDENTITY` | PK. 외부 응답은 `job_{id}` 문자열로 직렬화 |
| `document_id` | `BIGINT` | `public.ai_documents(id) ON DELETE CASCADE` |
| `booth_id` | `BIGINT` | NOT NULL, 요청 snapshot |
| `agent_id` | `BIGINT` | NOT NULL, 요청 snapshot |
| `source_hash` | `CHAR(64)` | 접수 시 문서 SHA-256 snapshot |
| `status` | `VARCHAR(20)` | JobStatus CHECK 제약 |
| `attempt_no` | `INTEGER` | 최초 실행 1, 실행 횟수. 0 이상 |
| `max_retries` | `INTEGER` | 기본 3, 0 이상. 최대 실행은 `1 + max_retries` |
| `worker_id` | `VARCHAR(100)` | `RUNNING`일 때 Worker 식별자 |
| `lease_expires_at` | `TIMESTAMPTZ` | `RUNNING` heartbeat 만료 시각 |
| `next_retry_at` | `TIMESTAMPTZ` | `RETRY_WAIT` 재획득 가능 시각 |
| `last_error_code` | `VARCHAR(50)` | 안정적인 내부 오류 코드 |
| `last_error` | `TEXT` | 운영용 상세 오류. API 사용자에게 미노출 |
| `chunk_count` | `INTEGER` | 성공 처리된 청크 수 |
| `callback_attempt_no` | `INTEGER` | Spring 상태 콜백 시도 횟수 |
| `callback_next_retry_at` | `TIMESTAMPTZ` | 다음 콜백 재시도 시각 |
| `callback_delivered_at` | `TIMESTAMPTZ` | Spring이 터미널 상태를 수락한 시각 |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | 감사 시각 |
| `finished_at` | `TIMESTAMPTZ` | 터미널 상태 도달 시각 |

### 제약과 인덱스

```sql
UNIQUE (document_id)
WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
```

- `ix_document_jobs_pickup(status, next_retry_at)`: Worker의 대기 작업 획득
- `ix_document_jobs_lease(lease_expires_at) WHERE status = 'RUNNING'`: sweeper의 만료 작업 회수
- `ix_document_jobs_callback(callback_next_retry_at) WHERE callback_delivered_at IS NULL AND status IN ('SUCCEEDED', 'DEAD', 'CANCELLED')`: 미전달 콜백 복구
- `status`와 상태별 nullable 필드 조합은 CHECK 제약 또는 migration 테스트로 검증한다.

### Job 상태 전이

| 현재 | 이벤트 | 다음 | 부가 동작 |
|---|---|---|---|
| — | 처리 요청 접수 | `QUEUED` | 활성 Job 충돌 시 기존 Job 반환 |
| `QUEUED` | Worker 획득 | `RUNNING` | `attempt_no + 1`, Worker lease 설정 |
| `RETRY_WAIT` | 재시도 시각 도달 후 획득 | `RUNNING` | `attempt_no + 1`, Worker lease 설정 |
| `RUNNING` | heartbeat | `RUNNING` | `lease_expires_at = now + 90s` |
| `RUNNING` | 성공 | `SUCCEEDED` | 청크 전체 교체와 같은 트랜잭션 |
| `RUNNING` | 재시도 가능 오류 또는 lease 만료 | `RETRY_WAIT` | 1·5·15분 중 해당 backoff 설정 |
| `RUNNING` | 재시도 상한 초과 | `DEAD` | 정제된 실패 콜백 예약 |
| 활성 상태 | 부스 임대 만료 또는 문서 비활성화 | `CANCELLED` | 생성 중 결과 폐기, 비활성화 콜백 예약 |

터미널 상태는 `SUCCEEDED`, `DEAD`, `CANCELLED`다. 터미널 Job은 다시 실행하지 않으며 Spring 콜백만 독립적으로 재시도한다.

## Document Chunk — FastAPI 데이터, Spring 스키마

| 필드 | 규칙 |
|---|---|
| `id` | PK |
| `document_id` | Document FK |
| `booth_id`, `agent_id` | NOT NULL, RAG 필수 필터 |
| `chunk_no` | 문서 내 0 기반 순서 |
| `content` | 정규화된 텍스트 |
| `embedding` | `vector(1536)` |
| `embedding_model_id` | 임베딩 모델 식별자 |
| `page_number` / `section` | 추적 가능한 경우 저장 |

`UNIQUE(document_id, chunk_no)`를 유지한다. 성공 커밋은 아래 원자적 순서를 따른다.

1. 현재 Document가 `DISABLED`가 아니며 `source_hash`가 최신 값인지 확인한다.
2. `document_id`의 기존 Chunk를 모두 삭제한다.
3. 새 Chunk를 전량 삽입한다.
4. Job을 `SUCCEEDED`로 전환하고 `chunk_count`, `finished_at`을 기록한다.
5. 트랜잭션 커밋 후 Spring 상태 콜백을 예약한다.

## 상태 콜백 정합성

- 콜백은 `jobId`, `documentId`, `sourceHash`, 목표 `DocumentStatus`를 포함한다.
- Spring은 동일 `jobId + status` 요청을 여러 번 받아도 같은 결과를 반환한다.
- `sourceHash`가 현재 문서와 다르면 Spring은 오래된 완료 콜백을 적용하지 않고 충돌 응답을 반환한다.
- FastAPI reconciliation은 터미널 Job 중 `callback_delivered_at IS NULL`인 행을 계속 재전송한다.
