# Data Model: AI 직원 / 문서 파이프라인

## 소유권과 database 경계

| Database | 소유 파트 | 주요 테이블 |
|---|---|---|
| `festa_{env}_business` | Spring / Backend | `ai_agents`, `ai_documents`, `storage_reconciliation_log` |
| `festa_{env}_ai` | FastAPI / AI | `document_jobs`, `document_chunks` |

두 database는 같은 PostgreSQL 17 + pgvector 인스턴스를 공유하지만 login role과 CONNECT 권한을 분리한다. FastAPI migration/runtime role은 AI DB에만 접속하며 Business DB를 직접 조회하거나 갱신하지 않는다. Spring role도 AI DB에 접속하지 않는다. database 간 FK·cascade·cross-database query는 사용하지 않는다.

## AI Agent — Spring 소유 (Business DB `ai_agents`, V1부터 존재)

| 컬럼 (V1) | 타입 | 규칙 |
|---|---|---|
| `id` | `BIGINT IDENTITY PK` | |
| `booth_id` | `BIGINT NOT NULL REFERENCES booths(id)` | `updatable=false`. **부스당 1행** (C-13) |
| `name` | `VARCHAR(100) NOT NULL` | 필수, 1~100자 |
| `role_code` | `VARCHAR(50) NOT NULL` | 화이트리스트 `PROJECT_DOCENT`·`GUIDE` (C-12) |
| `tone_code` | `VARCHAR(30) NOT NULL` | `FRIENDLY`(기본)·`PROFESSIONAL`·`ENTHUSIASTIC` |
| `system_prompt` | `TEXT NOT NULL` | 필수 non-blank |
| `response_length` | `VARCHAR(20) NOT NULL DEFAULT 'MEDIUM'` | `SHORT`·`MEDIUM`·`LONG`. 문장 수 기준 |
| `service_price` | `INTEGER NOT NULL DEFAULT 0 CHECK(>=0)` | 상한 정의 없음 |
| `handoff_enabled` | `BOOLEAN NOT NULL DEFAULT FALSE` | |
| `forbidden_topics` | `JSONB` | 문자열 배열. 어휘 화이트리스트 없음(항목 non-blank 만). **저장 전 빈 배열은 `null` 로 정규화**한다 — 응답에서는 둘 다 `[]` 인데 DB 표현이 갈리면 "같은 값 수정"이 변경으로 오판된다. 개수·항목 길이 상한은 미결(`docs/26`) |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` | 생명주기 미구현. `LayoutConfigResolver` 가 `ACTIVE` 만 센다 |
| `created_at` | `TIMESTAMPTZ NOT NULL` | `updatable=false` |
| `updated_at` | `TIMESTAMPTZ NOT NULL` | **애플리케이션이 갱신**(DB 기본값은 INSERT 때만 먹는다). 값이 실제로 바뀐 수정에서만 |

응답에는 `agent_id`·`booth_id` + 위 설정 8필드를 싣고 `status`·감사 시각은 내보내지 않는다
(소비자 없음, 추가는 가산적).

### 불변식

| # | 불변식 | 무엇이 지키나 |
|:--:|---|---|
| A-1 | **부스당 AI 직원은 최대 1명** (C-13) | `ux_ai_agents_booth` 유니크 인덱스 + 사전 조회 + 제약 번역 |
| A-2 | 참조가 있는 직원은 삭제되지 않는다 (C-14) | 사전검사 3종(`ai_documents`·`consultations`·Draft/현재 Published Layout) + `delete+flush` 의 FK 번역 |
| A-3 | **공개된 배치는 존재하지 않는 직원을 가리키지 않는다** | Agent 삭제와 Layout Publish 가 `booths` 행 잠금을 공유 — Layout 은 JSON 참조라 FK 최후 방어가 없다 |
| A-4 | 저장된 `system_prompt`·`forbidden_topics` 바이트 = 반환 바이트 | 검증기가 정규화하지 않는다(빈 배열→null 은 저장 표현 통일이지 값 변형이 아니다) |

Spring은 처리 요청 전에 `booth_id`, `agent_id` 조합과 문서 소유권·임대·상태를 Business DB에서 검증한다. FastAPI는 Spring이 전달한 snapshot을 Job에 저장한다.

## Document — Spring 소유

| 필드 | 규칙 |
|---|---|
| `id` | PK, Job과 Chunk의 기준 식별자 |
| `booth_id`, `agent_id` | 검색 격리 범위, NOT NULL |
| `original_filename` | 사용자 표시용 파일명 |
| `object_key` | Spring이 생성·관리하는 저장소 중립 object key, 클라이언트 임의 지정 금지 |
| `storage_provider` | `R2/MINIO_LOCAL`, 업로드 grant 발급 시점의 쓰기 Provider |
| `storage_bucket` | 해당 문서 원본이 저장된 bucket. 전역 활성 Provider와 독립적으로 읽기 대상 결정 |
| `content_sha256` | 동일 Agent 중복 판정 및 개정본 경쟁 방지 |
| `file_size_bytes` | 파일당 20MB 및 Agent 총 100MB 검증 |
| `processing_status` | `QUEUED/PROCESSING/READY/FAILED/DISABLED/EXPIRED` |
| `failure_reason` | 사용자 노출용 정제 메시지, nullable |
| `chunk_count` | 마지막 성공 처리 청크 수, nullable |
| `processed_at` | 마지막 성공 처리 시각, nullable |
| `created_at`, `updated_at` | 감사 시각 |

Backend migration은 `develop` 기준 최신 Flyway migration의 다음 사용 가능한 버전으로 추가한다. 현재 기준은 V12 다음 V13이며, 기존 migration 번호를 재사용하거나 수정하지 않는다.

### 업로드 만료 상태 전이

| 현재 | 이벤트 | 다음 | 부가 동작 |
|---|---|---|---|
| `QUEUED` | 생성 후 1시간 동안 업로드 미완료 | `EXPIRED` | 사용자에게 업로드 만료 표시, 처리·검색 제외 |
| `EXPIRED` | 24시간 유예 중 완료 요청, R2 원본 존재 | `QUEUED` | 동일 `document_id`로 정상 처리 재개 |
| `EXPIRED` | 완료 요청 시 R2 원본 없음 | `EXPIRED` | 410 응답, 새 업로드 권한 발급 필요 |
| `EXPIRED` | 전환 후 24시간 경과 | `EXPIRED` | Spring이 R2 원본 삭제. 실패 시 `object_key`를 유지하고 재시도 |

`EXPIRED`는 Spring만 전환하는 사용자 문서 상태이며 FastAPI Job 상태나 callback enum에 추가하지 않는다. 활성 SHA-256 중복 판정 인덱스에서도 제외한다.

업로드 재개 시 활성 쓰기 Provider가 기존 `storage_provider`와 같으면 같은 `document_id`로 presigned URL을 재발급할 수 있다. 다르면 기존 행을 `EXPIRED`로 전환하고 새 `document_id + object_key`를 생성한다. 기존 객체는 해당 Provider의 만료·정리 또는 reconcile 경로로 남긴다.

### 저장소 Provider 불변식

- 신규 업로드 grant는 Spring의 `active-write-provider`를 사용해 `storage_provider`, `storage_bucket`, `object_key`를 함께 기록한다.
- FastAPI와 Spring의 기존 객체 읽기·HEAD·삭제는 전역 활성 Provider가 아니라 문서 행의 세 필드를 따른다.
- `LOCAL_ACTIVE` 이후 생성된 문서는 `MINIO_LOCAL`, 전환 전 문서는 계속 `R2`를 가리킨다.
- reconcile에서 size·감지 MIME·SHA-256 검증을 통과한 객체만 `storage_provider=R2`와 대상 bucket으로 변경한다.
- MinIO는 단일 노드 임시 가용성 수단이며 백업·복제본·고가용성 저장소가 아니다.
- 저장소 장애로 `DEAD → FAILED`가 된 문서는 저장소 복구 후에도 자동으로 재처리하지 않는다. reconcile 완료 확인 후 명시적 재처리 요청만 새 Job을 만든다.

## Storage Reconciliation Log — Spring 소유

테이블: Business DB `storage_reconciliation_log`

| 필드 | 규칙 |
|---|---|
| `run_id` | Infra reconcile 실행 식별자 |
| `document_id` | Document FK |
| `object_key` | 검증 대상 object key |
| `source_provider` | 검증 시작 시점 Provider (`R2`/`MINIO_LOCAL`) |
| `target_provider` | 검증 통과 시 전환할 Provider |
| `expected_size`, `actual_size` | 원본과 실측 크기 |
| `expected_content_type`, `actual_content_type` | 원본과 실측 감지 MIME |
| `expected_sha256`, `actual_sha256` | 원본과 실측 SHA-256 |
| `status` | `VERIFIED` / `MISMATCH` / `MISSING` |
| `attempt_count` | 검증 재시도 횟수 |
| `failure_reason` | 불일치·누락 사유, nullable |
| `checked_at` | 검증 수행 시각 |
| `resolved_at` | Provider 반영 시각, nullable |

`(run_id, document_id)`에 `UNIQUE` 제약을 두어 재전송을 멱등하게 만든다. `status = VERIFIED`인 행만 대상 Document의 `storage_provider`를 `target_provider`로 변경하며, `MISMATCH`·`MISSING`은 로그만 남기고 Document Provider를 바꾸지 않는다. Infra는 `INTERNAL_INFRA_TO_SPRING_TOKENS`로 인증하며 이 토큰은 `INTERNAL_AI_TO_SPRING_TOKENS`와 credential·scope가 분리된다.

계약: [spring-storage-reconciliation-api.yaml](./contracts/spring-storage-reconciliation-api.yaml)

## Processing Job — FastAPI 소유

테이블: AI DB `document_jobs`

| 필드 | 타입 예시 | 규칙 |
|---|---|---|
| `id` | `BIGINT IDENTITY` | PK. 외부 응답은 `job_{id}` 문자열로 직렬화 |
| `document_id` | `BIGINT` | Business DB 문서의 논리 참조. FK 없음 |
| `booth_id` | `BIGINT` | NOT NULL, 요청 snapshot |
| `agent_id` | `BIGINT` | NOT NULL, 요청 snapshot |
| `source_hash` | `CHAR(64)` | 접수 시 문서 SHA-256 snapshot |
| `original_filename` | `TEXT` | Spring 검증 snapshot |
| `content_type` | `VARCHAR(100)` | PDF·MD·TXT parser 선택 및 검증 snapshot |
| `file_size_bytes` | `BIGINT` | 원본 크기 snapshot |
| `storage_provider` | `VARCHAR(20)` | `R2/MINIO_LOCAL` snapshot |
| `storage_bucket` | `TEXT` | 원본 bucket snapshot |
| `object_key` | `TEXT` | 원본 object key snapshot |
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
| `callback_delivered_at` | `TIMESTAMPTZ` | Spring이 수락했거나 stale 409로 전달이 해소된 시각 |
| `callback_terminated_at` | `TIMESTAMPTZ` | 재시도 불가 응답 또는 재시도 소진으로 callback을 종료한 시각. 정상 전달과 분리 |
| `callback_terminal_code` | `VARCHAR(50)` | 종료 원인(`DOCUMENT_NOT_FOUND`, `JOB_DOCUMENT_MISMATCH`, `JOB_NOT_REGISTERED_RETRY_EXHAUSTED`) |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | 감사 시각 |
| `finished_at` | `TIMESTAMPTZ` | 터미널 상태 도달 시각 |

### 제약과 인덱스

```sql
UNIQUE (document_id)
WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
```

- `ix_document_jobs_pickup(status, next_retry_at)`: Worker의 대기 작업 획득
- `ix_document_jobs_lease(lease_expires_at) WHERE status = 'RUNNING'`: sweeper의 만료 작업 회수
- `ix_document_jobs_callback(callback_next_retry_at) WHERE callback_delivered_at IS NULL AND callback_terminated_at IS NULL AND status IN ('SUCCEEDED', 'DEAD', 'CANCELLED')`: 미전달·미종료 콜백 복구
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
| `RUNNING` | 원본 SHA-256 불일치 | `DEAD` | Chunk를 저장하지 않고 `SOURCE_HASH_MISMATCH` 실패 콜백 예약 |
| `RUNNING` | 재시도 상한 초과 | `DEAD` | 정제된 실패 콜백 예약 |
| 활성 상태 | 부스 임대 만료 또는 문서 비활성화 | `CANCELLED` | 생성 중 결과 폐기, 비활성화 콜백 예약 |

터미널 상태는 `SUCCEEDED`, `DEAD`, `CANCELLED`다. 터미널 Job은 다시 실행하지 않으며 Spring 콜백만 독립적으로 재시도한다.

## Document Chunk — FastAPI 소유

테이블: AI DB `document_chunks`

| 필드 | 규칙 |
|---|---|
| `id` | PK |
| `document_id` | Business DB 문서의 논리 참조. FK 없음 |
| `booth_id`, `agent_id` | NOT NULL, RAG 필수 필터 |
| `chunk_no` | 문서 내 0 기반 순서 |
| `content` | 정규화된 텍스트 |
| `embedding` | `vector(1536)` |
| `embedding_model_id` | 임베딩 모델 식별자 |
| `page_number` / `section` | 추적 가능한 경우 저장 |
| `searchable` | 기본 `FALSE`. Spring이 `READY` callback을 수락한 뒤에만 `TRUE` |

`UNIQUE(document_id, chunk_no)`를 유지한다. 성공 커밋은 아래 원자적 순서를 따른다.

1. Job이 현재 Worker 소유의 `RUNNING`이며 cleanup으로 취소되지 않았는지 확인한다.
2. `document_id`의 기존 Chunk를 모두 삭제한다.
3. 새 Chunk를 `searchable = FALSE`로 전량 삽입한다.
4. Job을 `SUCCEEDED`로 전환하고 `chunk_count`, `finished_at`을 기록한다.
5. AI DB 트랜잭션 커밋 후 Spring 상태 콜백을 예약한다.

Business DB의 Document 상태는 이 트랜잭션에 포함하지 않는다. Spring은 callback의 `jobId`, `documentId`, `sourceHash`를 현재 Business DB 행과 비교한 뒤 `READY`를 반영한다. FastAPI는 `READY` callback 204를 받은 뒤 별도 AI DB 트랜잭션으로 해당 Job의 Chunk를 `searchable = TRUE`로 바꾼다. RAG 쿼리는 `booth_id + agent_id + searchable = TRUE`를 강제한다.

## Cleanup과 database 간 reconciliation

- Spring은 문서 삭제·`DISABLED` 전환 시 `document_id` 기반 cleanup을 FastAPI에 발행하고 성공할 때까지 재시도한다.
- FastAPI cleanup은 AI DB 트랜잭션에서 활성 Job을 `CANCELLED`로 전환하고 해당 문서 Chunk를 삭제한다. Job/Chunk가 이미 없으면 성공으로 처리한다.
- cleanup과 성공 커밋은 같은 AI DB의 Job/Chunk 행 잠금으로 직렬화한다.
- 주기적 reconciliation 입력은 Spring이 Business DB의 단일 snapshot에서 생성한 전체 활성 문서 inventory(`runId`, `documentId`, `boothId`, `agentId`, `sourceHash`, `status`)다. FastAPI는 같은 `runId` 재전송을 멱등하게 처리하고 AI DB와 비교해 Business DB에 없는 문서 및 `READY`가 아닌 문서의 Chunk를 cleanup한다.
- scope/sourceHash 불일치는 자동으로 문서 내용을 추정·수정하지 않고 운영 경고와 명시적 재처리 대상으로 남긴다.

## 상태 콜백 정합성

- 콜백은 `jobId`, `documentId`, `sourceHash`, 목표 `DocumentStatus`를 포함한다.
- Spring은 FastAPI 처리 요청 응답의 `jobId`와 `documentId` 대응 관계를 다른 후속 처리보다 먼저 저장한다.
- Spring의 callback 404는 `JOB_NOT_REGISTERED`, `DOCUMENT_NOT_FOUND`, `JOB_DOCUMENT_MISMATCH`를 구분한다. FastAPI는 첫 코드만 1초·3초·10초 간격으로 최대 3회 재시도하고, 나머지는 즉시 `callback_terminated_at`과 `callback_terminal_code`를 기록한다. Spring은 mismatch를 계약 오류로 경고 기록한다.
- Spring은 동일 `jobId + status` 요청을 여러 번 받아도 같은 결과를 반환한다.
- `sourceHash`가 현재 문서와 다르면 Spring은 오래된 완료 콜백을 적용하지 않고 충돌 응답을 반환한다.
- FastAPI reconciliation은 터미널 Job 중 `callback_delivered_at IS NULL AND callback_terminated_at IS NULL`인 행만 재전송한다.
