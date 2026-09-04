# Implementation Plan: AI 직원 / 문서 파이프라인

**Branch**: `docs/S15P21A604-262-spec007-db-boundary` | **Date**: 2026-08-27 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/007-ai-agent-document/spec.md`
**Decision record**: [research.md](./research.md), [GitHub Issue #11](https://github.com/kanghyunsoon/ssafesta/issues/11)

## Summary

부스 소유자가 등록한 문서를 비동기로 파싱·청킹·임베딩하고 `boothId + agentId`로 격리된 pgvector 청크로 저장한다. Spring은 Business DB에서 문서와 권한을 검증한 뒤 저장소 snapshot을 전달하고, FastAPI는 별도 AI DB의 Job과 Chunk만 사용한다. Worker는 DB lease와 heartbeat로 작업을 소유하고, 재시작 후 sweeper가 만료된 Job을 회수한다. 사용자 문서 상태는 Spring이, 내부 Job·Chunk는 FastAPI가 소유하며 두 DB의 정합성은 callback·cleanup·reconciliation으로 수렴시킨다.

spec 007의 완료 경계는 문서가 `READY`가 되고 해당 `boothId + agentId` 범위의 RAG 검색에서 사용 가능한 상태까지다. 실제 질문·LLM 답변 생성과 SSE 전달은 spec 008에서 구현한다.

## Technical Context

**Language/Version**: Python 3.12 이상
**Primary Dependencies**: FastAPI, Uvicorn, SQLAlchemy 2.x async, psycopg 3, Alembic, Pydantic Settings, boto3, PDF parser(MD·TXT는 UTF-8 디코딩만 사용), 관리형 Embedding Provider adapter
**Storage**: 동일 PostgreSQL 17 + pgvector 인스턴스 안의 환경별 Business DB(`festa_{env}_business`)와 AI DB(`festa_{env}_ai`) 분리. `vector` extension은 AI DB에만 설치한다. 원본은 Cloudflare R2가 기본이며 장기 장애 시 운영자 승인 기반 단일 노드 MinIO fallback을 사용하고, 기존 읽기는 문서별 Provider를 따른다.
**Testing**: pytest, pytest-asyncio, HTTPX ASGI client, PostgreSQL+pgvector 통합 Fixture/Testcontainers, object storage·Embedding adapter fake
**Target Platform**: Linux Docker container, 개발환경 EC2/ECS 후보
**Project Type**: FastAPI web service + process-internal background Worker
**Performance Goals**: Agent당 문서 10개·총 100MB에서 검색 P95 1초 이하, 정답 근거 Top-K 포함률 95% 이상
**Constraints**: 문서(PDF·MD·TXT) 20MB, vector 1536차원, 다른 Booth/Agent 청크 유출 0건, AI 장애가 비AI 기능에 영향 없음, 영구 `PROCESSING` 0건
**Scale/Scope**: P0 인프로세스 Worker, 문서별 청크 수십~수백 건, 부하 실측 후 SQS 전환 가능

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 설계 후 재확인.*

| 헌법 조항 | 검증 | 결과 |
|---|---|---|
| 1. Source of Truth | Document 메타데이터·사용자 상태는 Spring만 갱신하고 FastAPI는 Job·Chunk를 소유 | PASS |
| 3. AI 장애 격리 | 사용자 상태 조회는 Spring DB에서 제공하고 Spring은 AI 결과를 동기 대기하지 않음 | PASS |
| 10. 개별 CI/CD | AI migration·Worker는 `ai`, Spring callback·Flyway는 `back`에서 독립 배포 | PASS |
| 15. Secret 분리 | Service Token·DB·Provider 자격증명은 Secrets Manager/CI 변수로 주입 | PASS |
| 17. Vector 격리 | 모든 Chunk에 `booth_id`, `agent_id`; 검색 쿼리에서 필수 조건 강제 | PASS |
| 18. Embedding 차원 | `vector(1536)`과 `embedding_model_id` 유지 | PASS |
| 24. 계약 변경 | FastAPI↔Spring OpenAPI 계약을 문서화하고 양 파트 합의 후 구현 | PASS |
| 27. 기준선 동결 | 기존 POC 범위를 재구현하지 않고 신규 AI 문서 파이프라인만 추가 | PASS |

### 설계 후 재검증

- FastAPI role의 Business DB CONNECT 자체를 차단해 Spring SoT를 유지한다.
- terminal Job callback을 영속 재시도해 FastAPI 재시작이 Spring 문서 상태를 고착시키지 않는다.
- 같은 PostgreSQL 인스턴스를 사용하지만 database·login role·migration 소유권을 분리하고 cross-DB query/FK를 금지한다.
- Constitution 위반 없음. Complexity Tracking 항목 없음.

## Project Structure

### Documentation

```text
specs/007-ai-agent-document/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/
│   └── requirements.md
└── contracts/
    ├── document-processing-api.yaml
    ├── spring-document-status-api.yaml
    └── spring-storage-reconciliation-api.yaml
```

### Source Code

AI 서비스 scaffold는 Git convention의 파트 디렉터리명에 따라 `festa-ai/`에 생성한다.

```text
festa-ai/
├── app/
│   ├── api/v1/documents.py
│   ├── core/config.py
│   ├── db/
│   │   ├── session.py
│   │   └── models/document_job.py
│   ├── repositories/
│   │   ├── document_job_repository.py
│   │   └── chunk_repository.py
│   ├── services/
│   │   ├── document_processing_service.py
│   │   ├── job_recovery_service.py
│   │   └── spring_status_callback.py
│   ├── workers/document_worker.py
│   └── providers/
│       ├── storage.py
│       ├── document_parser.py
│       └── embedding.py
├── migrations/
│   └── versions/
├── tests/
│   ├── contract/
│   ├── integration/
│   └── unit/
├── Dockerfile
└── pyproject.toml
```

Backend 연동 구현은 `back` 브랜치의 기존 `backend/` 구조를 따른다.

```text
backend/src/main/
├── java/.../internal/ai/        # 상태 callback controller/service/DTO
└── resources/db/migration/      # failure_reason 등 다음 Flyway migration
```

**Structure Decision**: AI는 API·Worker·Provider를 같은 Docker 이미지에 두되 실행 책임을 모듈로 분리한다. P0에서는 FastAPI lifespan이 Worker loop를 시작한다. 이후 SQS 도입 시 API·Worker 배포 단위를 분리해도 repository와 processing service는 유지한다.

## Detailed Design

### 0. AI Agent CRUD — Spring 단독 (S15P21A604-105, 2026-08-30 추가)

문서 파이프라인의 **선행**이다. 이 절만 FastAPI 가 관여하지 않는다 — Business DB `ai_agents` 의
저장·검증·편집 API 까지가 범위이고, 설정을 FastAPI 로 전달하는 경로는 spec 008 의
access/Conversation 계약이 소유한다(현 계약은 `agentId`·`status`·`leaseEndsAt` 만 나른다).

**권한** — `BoothAccessGuard`(소유자·스태프, C-15). 005·016·009 와 같은 편집자 범위다. 쓰기는
유효 임대를 요구하고(만료 부스는 아무에게도 보이지 않으므로 편집이 무의미하다) 읽기는 만료돼도
허용한다. 직원 역할별 제한(011 C-09)은 011 구현 때 가드 한 곳에서 일괄로 닫는다.

**부스당 1명(C-13)** — 설정값 + `ux_ai_agents_booth` 유니크 인덱스. 사전 조회만 두면 동시 요청
둘이 각각 "없음"을 보고 둘 다 만든다(V7·V14 와 같은 사고). 설정만 2 로 바꾸면 인덱스와 모순되므로
**부팅 시 `perBoothLimit == 1` 을 검증해 기동을 실패**시킨다.

**삭제와 참조(C-14)** — 검사 3종은 `ai_documents`·`consultations`·**Draft/현재 Published Layout 의
`AI_AGENT.configId`**. `ai_document_chunks` 는 **검사하지 않는다**(C-11 — AI DB 소유라 Spring 이
접근할 수 없고, Business `ai_documents` 가 이미 막는다). "현재 Published" 는
`booths.published_layout_version` 포인터로 판정한다.

**경쟁 방어 2종**

1. 사전검사 통과 후 문서·상담이 생기는 경우 — `delete + flush` 를 감싸고 알려진 FK 제약
   (`ai_documents_agent_id_fkey`·`consultations_agent_id_fkey`)만 같은 409 로 번역한다. 모르는
   위반은 삼키지 않고 그대로 올린다.
2. **Layout 은 FK 가 없어 최후 방어가 없다** — Publish 가 Agent 검증을 마친 직후 삭제가 끼면
   공개 배치가 사라진 직원을 가리킨다. Agent 삭제와 Layout Publish 가 `booths` 행 잠금을 공유해
   직렬화한다(불변식 A-3).


### 1. 상태와 소유권

- Spring `DocumentStatus`: `QUEUED / PROCESSING / READY / FAILED / DISABLED / EXPIRED`
- FastAPI `JobStatus`: `QUEUED / RUNNING / RETRY_WAIT / SUCCEEDED / DEAD / CANCELLED`
- 사용자 문서 목록·상태 조회는 Spring API가 `ai_documents`에서 제공한다.
- `GET /ai/v1/documents/{documentId}/status`는 운영·내부 진단용으로만 유지한다.
- 상태 전이와 필드는 [data-model.md](./data-model.md)를 따른다.

### 2. 처리 요청 접수

1. Spring이 활성 쓰기 Provider에 object storage 업로드를 완료하고 Document에 `storageProvider + bucket + objectKey`를 저장한다.
2. Spring이 Business DB에서 문서 소유권·임대·상태를 검증하고 전체 문서·저장소 snapshot을 구성해 Service Token으로 `POST /ai/v1/documents/process`를 호출한다.
3. FastAPI는 요청 schema와 원본 metadata를 검증하고, 다운로드한 원본 바이트의 SHA-256을 다시 계산해 요청 `sourceHash`와 대조하되 Business DB를 조회하지 않는다. 불일치는 재시도 없이 Job을 `DEAD`로 종료하고 Chunk를 저장하지 않으며 Spring에 `FAILED + SOURCE_HASH_MISMATCH`를 callback한다.
4. AI DB `document_jobs`에 snapshot과 `QUEUED`를 INSERT한 뒤 202를 반환한다.
5. 활성 Job 부분 유니크 인덱스 충돌은 오류로 노출하지 않고 기존 Job을 조회해 `existing: true`로 반환한다.

계약: [document-processing-api.yaml](./contracts/document-processing-api.yaml)

### 2-1. 업로드 미완료 만료와 원본 정리

- Presigned PUT URL TTL 기본값은 15분이다.
- Spring sweeper는 기본 5분마다 문서 생성 후 1시간 동안 완료되지 않은 행을 `EXPIRED`로 전환한다. `EXPIRED`는 Spring 내부 상태이자 문서 목록 응답 상태이며 FastAPI callback 대상이 아니다.
- `EXPIRED` 전환 후 24시간은 복구 유예 기간이다. 이 기간에 완료 요청이 오면 Spring이 R2 객체 존재를 확인해 남아 있으면 `QUEUED`로 되돌리고 정상 처리 요청을 보낸다. 객체가 없으면 410을 반환해 새 업로드 권한을 받도록 한다.
- 유예 기간이 지나면 Spring이 별도 HEAD 없이 R2 `DeleteObject`를 호출한다. 실패하면 `EXPIRED`와 `objectKey`를 유지하고 비동기로 재시도한다.
- Spring R2 자격증명은 문서 버킷 또는 지정 prefix에 한정한 `DeleteObject` 권한이 필요하다. Infra는 기존 서명 자격증명에 최소 권한을 추가하거나 삭제 전용 자격증명을 분리해 제공할 수 있으며, 실제 Secret은 저장소에 기록하지 않는다.

### 2-2. R2 장애와 수동 MinIO fallback

- 자동 failover·이중 쓰기·자동 원복은 구현하지 않는다. P0에서는 probe timeout·5xx·latency를 운영 판단 evidence로만 수집하고, 운영자가 `UPLOAD_BLOCKED`를 수동 적용한다. 자동 장애 판정과 자동 상태 전환은 후속 이슈에서 기준이 확정될 때까지 구현하지 않는다.
- 운영 상태는 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE`이며, `LOCAL_ACTIVE` 전환과 `R2_ACTIVE` 복귀에는 운영자 승인과 검증 근거가 필요하다.
- Spring의 `upload-gate`와 `active-write-provider`는 신규 upload grant만 제어한다. Usage Guard의 용량·stale 상태와 저장소 전환 상태 머신은 별도 개념이며 **Spring은 그 상태 머신을 모른다** — 운영자가 두 기계를 읽고 판정 하나를 `upload-gate`에 적는다 (GitLab #100, 2026-09-01 확정).
- `upload-gate`는 `OPEN`·`QUOTA_BLOCKED`·`UNAVAILABLE` 셋이고 기본값이 없다. 정상·경고와 검증 완료된 `LOCAL_ACTIVE`는 `OPEN`, 사용량 90% 초과는 `QUOTA_BLOCKED`(507), stale 지표·R2 장애·`FALLBACK_VALIDATING`·`R2_RECONCILING`은 `UNAVAILABLE`(503)이다. 두 차단을 한 값으로 뭉치지 않는 이유는 재시도 안내가 갈리기 때문이다 — 용량이 찬 사용자에게 "잠시 후 다시"를 주면 영원히 재시도한다.
- Spring과 FastAPI에는 R2·MinIO의 endpoint·bucket·credential을 모두 주입한다. FastAPI는 활성 쓰기 Provider를 선택하지 않고 문서 행의 `storage_provider + storage_bucket + object_key`로 다운로드 adapter를 고른다.
- 활성 쓰기 Provider가 바뀐 뒤 미완료 업로드를 재개하면 기존 행을 `EXPIRED`로 전환하고 새 문서·새 object key를 만든다. 같은 Provider일 때만 같은 문서로 presigned URL을 재발급한다.
- 저장소 일시 장애는 1·5·15분 backoff로 최대 3회 재시도한 뒤 `DEAD → FAILED`로 종료한다. MinIO를 백업·복제본·고가용성 저장소로 간주하지 않는다.
- Infra는 MinIO 전환 검증과 R2 reconcile을 실행하고, Spring은 검증 결과에 따라 문서 Provider 메타데이터를 변경한다. size·감지 MIME·SHA-256 검증을 모두 통과한 객체만 R2로 전환한다.
- 저장소 장애로 `DEAD → FAILED`가 된 문서는 저장소 복구 후에도 자동 재처리하지 않는다. `DEAD`는 AI 내부 Job 상태로만 유지하고 Spring 콜백 경계(`FAILED` + `failureCode`)는 바꾸지 않는다. reconcile이 끝난 뒤에는 기존 `POST /documents/process`를 명시적으로 다시 호출해 새 Job을 만든다 — 별도 재처리 전용 endpoint는 두지 않는다.
- `R2_RECONCILING` 중 신규 업로드 허용 여부와 R2 API 장애 자동 판정 수치는 `docs/26_팀_결정_필요사항.md`에 등록하고 후속 이슈에서 확정한다. P0에서는 probe timeout·5xx·latency를 evidence로 수집하고 운영자가 수동으로 `UPLOAD_BLOCKED`를 적용한다.

### 2-3. Reconcile 결과 반영

- Infra가 실행한 reconcile 결과는 Spring 내부 endpoint `POST /internal/storage/reconciliation-runs`로 전달한다. 계약: [spring-storage-reconciliation-api.yaml](./contracts/spring-storage-reconciliation-api.yaml)
- 인증은 #102와 동일한 방향별 Bearer Service Token 방식을 재사용하되, `INTERNAL_INFRA_TO_SPRING_TOKENS`로 별도 환경 변수를 두어 AI→Spring 토큰과 credential·scope를 분리한다.
- Spring은 `runId + documentId`로 멱등성을 보장한다 — 같은 `runId + documentId` 재전송은 상태를 다시 반영하지 않고 성공을 반환한다.
- Spring은 결과를 `storage_reconciliation_log`(`runId·documentId·objectKey·sourceProvider·targetProvider·expected/actual size·type·sha256·status·attemptCount·failureReason·checkedAt·resolvedAt`)에 적재한다.
- `status = VERIFIED`인 행만 대상 Document의 `storage_provider`를 `targetProvider`로 변경한다. `MISMATCH`·`MISSING`은 로그만 남기고 Document Provider를 바꾸지 않는다.

### 3. Worker 획득과 heartbeat

- Worker는 `QUEUED`, 또는 `next_retry_at <= now()`인 `RETRY_WAIT` 행을 `FOR UPDATE SKIP LOCKED`로 한 건 획득한다.
- 획득 트랜잭션에서 `RUNNING`, `worker_id`, `attempt_no + 1`, `lease_expires_at = now() + 90s`를 기록한다.
- 처리 중 30초마다 별도 짧은 DB 트랜잭션으로 자신의 `worker_id`와 `RUNNING` 상태를 조건으로 lease를 90초 연장한다.
- heartbeat UPDATE가 0행이면 Worker는 소유권을 잃은 것으로 판단하고 결과를 커밋하지 않는다.
- 여러 Uvicorn Worker가 loop를 실행해도 row lock과 lease 조건으로 한 Job만 소유한다.

### 4. 처리 snapshot·파싱·청킹·임베딩

- Spring은 Business DB에서 소유권·임대·`DocumentStatus`를 검증하고 `document_id`, `booth_id`, `agent_id`, 파일명·형식·크기·SHA-256, `storage_provider + storage_bucket + object_key` snapshot을 처리 요청으로 보낸다.
- FastAPI는 Business DB를 조회하지 않고 snapshot 전체를 Job에 영속화한다. Worker는 영속 snapshot으로 해당 S3-compatible adapter(R2 또는 MinIO)에서 metadata/크기/SHA-256을 다시 확인한 뒤 원본(PDF·MD·TXT)을 내려받는다.
- PDF는 페이지별 텍스트를 추출하고, MD·TXT는 UTF-8로 디코딩해 그대로 사용한다.
- 스캔 PDF처럼 추출 텍스트가 없으면 `UNSUPPORTED_SCAN_PDF`로 실패 처리한다. MD·TXT가 UTF-8로 디코딩되지 않으면 `PARSE_FAILED`로 처리한다.
- chunk size와 overlap은 환경 설정으로 주입하며 코드에 고정하지 않는다.
- Embedding adapter는 batch 입력을 사용하고 모든 결과가 1536차원인지 저장 전에 검증한다.
- 각 Chunk는 `document_id`, `booth_id`, `agent_id`, `chunk_no`, `embedding_model_id`, `searchable`을 필수로 가진다. 새 Chunk는 `searchable = false`다.
- 중간 청크는 검색 테이블에 부분 저장하지 않고 메모리 또는 작업 임시 영역에 유지한 뒤 마지막 트랜잭션에서 전량 반영한다.

### 5. 성공 커밋의 원자성

하나의 AI DB 트랜잭션에서 다음 순서로 처리한다.

1. Job 행을 `FOR UPDATE`하고 현재 Worker 소유권·`RUNNING`·유효 lease를 확인한다.
2. Job의 취소 상태와 처리 snapshot의 `source_hash`를 확인한다.
3. 기존 `document_id` Chunk 전량 DELETE.
4. 새 Chunk 전량 INSERT.
5. Job을 `SUCCEEDED`, `chunk_count`, `finished_at`으로 갱신.
6. 커밋 후 `READY` callback을 시도한다.

트랜잭션이 실패하면 기존 Chunk 집합이 그대로 남고 새 결과는 노출되지 않는다. Business DB의 `DocumentStatus` 전환은 이 트랜잭션에 포함하지 않는다.

### 6. 재시작 복구와 재시도

- sweeper는 기동 시 즉시, 이후 60초마다 실행한다.
- `RUNNING AND lease_expires_at < now()`인 Job을 잠금 획득해 회수한다.
- 남은 재시도가 있으면 `RETRY_WAIT`로 전환하고 실패 순서에 따라 1분, 5분, 15분 backoff를 설정한다.
- 세 번의 재시도를 모두 사용하면 `DEAD`로 전환하고 `PROCESSING_INTERRUPTED` 또는 마지막 정제 오류를 Spring에 전달한다.
- 최초 실행 1회 + 재시도 3회로 최대 실행 횟수는 4회다.
- 재시도 가능한 오류: Worker 상실, 네트워크/object storage 일시 오류, Embedding timeout/5xx.
- 즉시 `DEAD` 가능한 오류: 손상되거나 디코딩할 수 없는 문서, 지원하지 않는 스캔 PDF, 권한·scope 불일치, 원본 없음, 실제 원본 SHA-256과 요청 `sourceHash` 불일치처럼 재시도로 해결되지 않는 입력 오류. 해시 불일치는 `SOURCE_HASH_MISMATCH`로 기록하고 Chunk·Embedding을 생성하지 않으며, 사용하지 않은 재시도 횟수를 소모하지 않는다.

### 7. Spring 상태 callback

- `RUNNING` 획득 후 Spring에 `PROCESSING`, terminal 전환 후 `READY/FAILED/DISABLED`를 비동기 전달한다.
- terminal callback payload는 `jobId`, `sourceHash`, `chunkCount`, `failureCode`, 정제된 `failureReason`, 발생 시각을 포함한다.
- callback이 수락되거나 stale 409로 해소되기 전까지 `callback_delivered_at`을 비워 둔다. 재시도 불가 응답 또는 `JOB_NOT_REGISTERED` 재시도 소진은 `callback_terminated_at`과 `callback_terminal_code`에 별도로 기록한다.
- 기동/주기 reconciliation은 `callback_delivered_at`과 `callback_terminated_at`이 모두 비어 있는 terminal Job만 찾아 재전송한다.
- Spring은 처리 요청 응답으로 받은 `jobId`를 다른 후속 처리보다 먼저 저장하고 `jobId`와 `documentId` 대응 관계를 확인한다. 404 응답은 `JOB_NOT_REGISTERED`, `DOCUMENT_NOT_FOUND`, `JOB_DOCUMENT_MISMATCH`로 구분한다.
- FastAPI는 `JOB_NOT_REGISTERED`만 1초·3초·10초 간격으로 최대 3회 재시도한다. `DOCUMENT_NOT_FOUND`와 `JOB_DOCUMENT_MISMATCH`는 즉시 종료하며, Spring은 mismatch를 계약 오류로 경고 기록한다. 이 짧은 callback 재시도는 문서 처리 Job의 1분·5분·15분 재시도와 별개다.
- Spring은 `jobId + status` 멱등성을 보장하고 `sourceHash`가 현재 Document와 다르면 409로 거부한다. 중복 callback은 상태를 다시 반영하지 않고 멱등 성공하며, 409 stale 결과는 전달 완료로 기록하되 현재 문서 상태를 덮지 않는다.
- FastAPI는 Spring의 `READY` callback 204 이후 별도 AI DB 트랜잭션으로 해당 Job의 Chunk만 `searchable = true`로 전환한다. callback 미전달·실패 중인 Chunk는 RAG 검색에 노출하지 않는다.

계약: [spring-document-status-api.yaml](./contracts/spring-document-status-api.yaml)

### 8. 부스 임대 만료

- Spring이 부스 임대 만료에 따라 Document를 `DISABLED`로 전환한다.
- Spring은 Document 삭제·비활성화 시 `DELETE /documents/{documentId}/artifacts` cleanup을 발행하고 성공할 때까지 재시도한다.
- FastAPI cleanup은 멱등하게 활성 Job을 `CANCELLED`로 전환하고 해당 `document_id` Chunk를 삭제한다. 이미 정리된 문서는 다시 요청해도 성공한다.
- 처리와 cleanup이 경쟁하면 Job/Chunk 행 잠금으로 한쪽만 커밋하며, cleanup 이후 늦은 성공 callback은 Spring의 최신 Document 상태·sourceHash 검증에서 거부된다.
- 재임대 후 Spring이 문서를 `QUEUED`로 활성화하고 새 처리 요청을 보내면 신규 Job으로 처음부터 처리한다.
- Worker heartbeat lease 만료는 이 흐름과 무관하며 `RETRY_WAIT` 복구 대상이다.

주기적 reconciliation은 Spring이 한 시점의 전체 활성 문서 inventory(`documentId`, `boothId`, `agentId`, `sourceHash`, status)를 `POST /documents/reconciliation`으로 전달하고 FastAPI가 AI DB와 비교하는 방식으로 수행한다. 같은 `runId` 재전송은 멱등하며, Business DB에 없는 문서 또는 `READY`가 아닌 문서의 Chunk는 cleanup 대상으로 수렴시키고 sourceHash·scope 불일치는 운영 경고 후 재처리를 요구한다.

### 9. 인증과 권한

- Spring→FastAPI는 `INTERNAL_SPRING_TO_AI_TOKENS`, FastAPI/Worker→Spring은 `INTERNAL_AI_TO_SPRING_TOKENS`를 사용하는 방향별 `Authorization: Bearer <service-token>` 계약으로 분리한다.
- 각 설정은 콤마로 구분한 비어 있지 않은 고유 토큰 1~2개다. 송신자는 첫 값을 사용하고 수신자는 목록의 모든 값을 검증한다. 반대 방향 토큰은 허용하지 않는다.
- 토큰은 해당 방향의 송신자와 수신자에만 주입한다. Spring→AI 토큰은 Spring과 FastAPI, AI→Spring 토큰은 FastAPI/Worker와 Spring이 사용한다.
- Spring은 `MessageDigest.isEqual`, FastAPI는 `secrets.compare_digest`로 비교한다. 토큰 누락·불일치·반대 방향 사용은 401로 거부한다.
- 서비스 간 네트워크는 Security Group으로 제한하고 public ALB route에서 `/internal/*`를 노출하지 않는다.
- 토큰 검증은 Security Group·ALB 설정과 독립적으로 모든 내부 요청에 항상 적용한다.
- Service Token과 DB/Provider 자격증명은 Secrets Manager 또는 CI secret으로 주입한다.
- 로그는 Authorization header, object key 전체, 원문 문서 내용, Provider raw 오류를 마스킹한다.
- 환경별 Spring role은 Business DB에만, FastAPI role은 AI DB에만 CONNECT할 수 있다. runtime role은 자기 DB의 최소 DML/sequence 권한만 가지며 database/role/extension 생성 권한은 없다.
- mTLS는 인증서 발급·주입·갱신·폐기 자동화를 준비한 뒤 적용하는 P2 보안 강화 항목으로 남긴다.

### 10. 설정

저장소에는 값이 비어 있거나 안전한 기본값만 있는 `.env.example`을 둔다.

| 설정 키 | 기본/규칙 |
|---|---|
| `JOB_HEARTBEAT_SECONDS` | `30` |
| `JOB_LEASE_SECONDS` | `90`, heartbeat보다 커야 함 |
| `JOB_SWEEPER_SECONDS` | `60` |
| `JOB_MAX_RETRIES` | `3` |
| `JOB_RETRY_BACKOFF_SECONDS` | `60,300,900` |
| `DOCUMENT_MAX_BYTES` | `20971520` |
| `AGENT_DOCUMENT_MAX_COUNT` | `10` |
| `AGENT_DOCUMENT_MAX_TOTAL_BYTES` | `104857600` |
| `EMBEDDING_DIMENSION` | `1536`, 변경 금지 |
| `SPRING_INTERNAL_BASE_URL` | 환경별 내부 주소 |
| `INTERNAL_SPRING_TO_AI_TOKENS` | 콤마 구분 1~2개, 첫 값 송신·전체 값 검증, secret 주입, 기본값 없음 |
| `INTERNAL_AI_TO_SPRING_TOKENS` | 콤마 구분 1~2개, 첫 값 송신·전체 값 검증, secret 주입, 기본값 없음 |
| `INTERNAL_INFRA_TO_SPRING_TOKENS` | 콤마 구분 1~2개, reconcile 결과 전달 전용, AI→Spring 토큰과 별도 credential·scope, secret 주입, 기본값 없음 |
| `DATABASE_URL` | 환경별 AI DB(`festa_{env}_ai`) 전용 URL, secret 주입, 기본값 없음 |
| `R2_ENDPOINT`, `R2_BUCKET` | R2 S3-compatible endpoint와 문서 bucket |
| `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | R2 secret 주입, 기본값 없음 |
| `MINIO_ENDPOINT`, `MINIO_BUCKET` | MinIO endpoint와 문서 bucket. 외부 직접 노출 금지 |
| `MINIO_ACCESS_KEY_ID`, `MINIO_SECRET_ACCESS_KEY` | MinIO secret 주입, 기본값 없음 |
| Spring `app.ai.storage.upload-gate` | `OPEN`/`QUOTA_BLOCKED`(507)/`UNAVAILABLE`(503). 기본값 없음 — 빠지면 기동 실패 |
| Spring `app.ai.storage.active-write-provider` | `R2/MINIO_LOCAL`, 신규 업로드에만 사용. 기본값 없음 |

Spring의 업로드·삭제 설정은 Presigned URL 15분, 미완료 만료 1시간, 정리 유예 24시간, sweeper 5분을 기본값으로 두며 환경 설정으로 조정한다. Spring에 주입되는 R2 자격증명의 `DeleteObject` 범위는 문서 버킷 또는 지정 prefix로 제한한다.

부팅 시 `lease > heartbeat`, backoff 개수와 `max_retries` 일치, embedding dimension 1536을 검증하고 잘못된 설정이면 health ready를 실패시킨다.

#### Service Token 무중단 회전

1. 양쪽 서비스를 `[old,new]`로 배포해 기존 토큰을 계속 송신하면서 수신자는 신규 토큰도 허용한다.
2. 양쪽 서비스를 `[new,old]`로 배포해 신규 토큰을 송신하도록 전환한다.
3. 인증 실패가 없는지 확인한 뒤 `[new]`로 배포해 기존 토큰을 폐기한다.

첫 단계 없이 바로 `[new,old]`로 바꾸면 먼저 배포된 송신자가 아직 신규 토큰을 모르는 수신자를 호출해 401이 발생할 수 있다. 비상 대응 외에는 3개 이상 토큰을 병행하지 않는다.

### 11. 관측성

- 구조화 로그 공통 필드: `job_id`, `document_id`, `booth_id`, `agent_id`, `worker_id`, `attempt_no`, `status`, `error_code`.
- 지표: Job 상태별 개수, pickup 지연, 처리 시간, heartbeat 실패, lease 회수, retry 횟수, DEAD 비율, callback 미전달 개수·지연, 문서별 chunk 수, Embedding latency/cost.
- 경고: 미전달 terminal callback, 오래된 `PROCESSING`, 반복 DEAD, queue age 증가.
- 원문 문서와 Stack Trace는 사용자 응답에 포함하지 않는다.

## Migration and Deployment Order

1. **Infra**: 환경별 `festa_{env}_business`·`festa_{env}_ai` database와 분리된 login/migration/runtime role을 생성하고 4×4 CONNECT matrix를 검증한다. AI DB에만 `vector` extension을 설치한다.
2. **BE**: `ai_documents`의 누락 필드(`failure_reason`, `content_sha256`, `storage_provider`, `storage_bucket`, `chunk_count`, `processed_at` 등)와 신규 `storage_reconciliation_log` 테이블을 현재 schema와 비교해 다음 사용 가능한 Flyway migration으로 추가한다.
3. **BE/AI/Infra**: 방향별 Service Token 세 목록(Spring↔FastAPI 양방향, Infra→Spring)을 송신자와 수신자에 주입하고 Security Group·public route 차단을 적용한다. Secret 값은 배포 설정과 로그에 노출하지 않는다.
4. **BE**: Spring 내부 상태 callback, reconcile 결과 수신 endpoint, 사용자용 문서 상태 조회를 배포한다. 세 endpoint 모두 기존 클라이언트에 영향 없는 신규 내부 API다.
5. **AI**: Alembic으로 AI DB의 `document_jobs`·`document_chunks`와 인덱스를 배포한다.
6. **AI/BE**: snapshot 처리 요청과 멱등 cleanup endpoint를 배포하되 Worker 시작 feature flag는 끈다.
7. **통합 검증**: DB CONNECT 격리, snapshot 완결성, cleanup 멱등성, 방향별 Service Token의 정상·누락·오류·반대 방향 401, callback 404 원인별 처리(`JOB_NOT_REGISTERED` 1/3/10초 재시도, 나머지 즉시 종료)·멱등성·stale sourceHash 409를 확인한다.
8. **AI**: Worker와 sweeper를 활성화한다.
9. **운영 확인**: 강제 종료 복구와 callback 재전송 시험 통과 후 기존 임시 처리 경로를 제거한다.

Rollback 시 Worker pickup을 먼저 중단한다. 이미 `RUNNING`인 Job의 lease가 만료되도록 두고 이전 버전이 이해하지 못하는 상태가 있으면 배포를 되돌리지 말고 forward-fix한다. 적용된 migration 파일은 수정하지 않는다.

## Test Strategy

### Unit

- 상태 전이 허용/거부와 retry backoff 계산
- 오류 코드와 사용자 메시지 정제
- chunk size/overlap 설정 검증
- heartbeat 소유권 상실 시 커밋 중단

### Repository/Integration — 실제 PostgreSQL + pgvector

- 활성 Job 부분 유니크 인덱스와 기존 Job 반환
- 두 Worker의 `SKIP LOCKED` 경쟁에서 단일 소유
- lease 만료 회수와 attempt 증가
- Chunk DELETE+INSERT+SUCCEEDED 원자성, 중간 오류 rollback
- 청크 수 감소 재처리 시 오래된 꼬리 청크 0건
- AI DB 내부 Chunk DELETE+INSERT+SUCCEEDED 단일 트랜잭션
- 4개 runtime role × 4개 database CONNECT matrix의 대각선만 성공
- Business DB 직접 조회 없이 snapshot만으로 처리 성공
- cleanup 재전송과 inventory reconciliation 후 고아 Chunk 0건

### Contract

- 세 OpenAPI schema에 대한 요청·응답 검증
- 처리 요청 snapshot 필수 필드와 멱등 cleanup의 반복 204
- 방향별 Service Token 정상 승인과 누락·오류·반대 방향 토큰 401
- `[old] → [old,new] → [new,old] → [new]` 회전 중 정상 호출 성공
- callback 404의 `JOB_NOT_REGISTERED`·`DOCUMENT_NOT_FOUND`·`JOB_DOCUMENT_MISMATCH` 구분과 원인별 재시도·종료 정책
- `JOB_NOT_REGISTERED` 1초·3초·10초 최대 3회 재시도 후 종료, 나머지 404 즉시 종료 및 mismatch 경고 기록
- Spring callback 중복 요청의 멱등 성공
- sourceHash 불일치 409와 최신 문서 상태 보존
- 내부 진단 API가 FE 사용자 경로에서 호출되지 않는지 route/consumer 검사

### Failure Injection

- Parsing, Embedding, Chunk commit 각 단계에서 프로세스 강제 종료
- heartbeat 3회 누락 후 sweeper 회수
- Spring callback 전·후 강제 종료와 reconciliation 재전송
- Object storage/Embedding 5xx 후 1·5·15분 backoff
- R2 장애 시 자동 전환·이중 쓰기 없이 `UPLOAD_BLOCKED` 유지
- 운영자 승인 MinIO 전환 후 과거 R2 문서와 신규 MinIO 문서를 문서별 Provider로 각각 읽기
- Provider 변경 중 미완료 업로드를 `EXPIRED`로 전환하고 새 문서로 재시작
- size·감지 MIME·SHA-256 불일치 객체가 R2 Provider로 변경되지 않는지 검증
- 재시도 상한 후 `DEAD → FAILED`
- `DEAD → FAILED` 종료 후 저장소가 복구돼도 자동 재처리가 발생하지 않는지 검증
- reconcile 결과 전달의 `runId + documentId` 중복 요청이 멱등 성공하고 로그가 중복 적재되지 않는지 검증
- Infra→Spring 토큰 누락·오류·AI 방향 토큰 재사용이 401로 거부되는지 검증
- 처리 중 Spring Document가 `DISABLED`가 될 때 `CANCELLED` 및 READY 미전환
- cleanup 전달 전·후 FastAPI 강제 종료와 재전송 후 Chunk 0건
- Business/AI inventory 불일치 주입 후 reconciliation 수렴 또는 운영 경고

### Security/Isolation

- 요청 boothId/agentId 위조 거부
- 다른 Booth/Agent Chunk 검색 0건
- FastAPI role의 Business DB CONNECT 및 Spring role의 AI DB CONNECT 거부
- 로그와 사용자 failureReason에 token, Stack Trace, object key, Provider raw 오류가 없는지 검사

검증 절차는 [quickstart.md](./quickstart.md)를 따른다.

## Phase Plan

### Phase 0 — 계약과 환경 준비

- Issue #11 합의와 본 contracts를 BE/Infra가 확인한다.
- 실제 back schema와 다음 Flyway 번호를 확인한다.
- 환경별 Business/AI database, 분리 role, AI DB의 `vector` extension, Service Token 전달 경로를 준비한다.

### Phase 1 — Job 기반 처리 골격

- FastAPI scaffold, 설정 검증, DB session, Alembic 구성.
- `document_jobs`·`document_chunks` migration과 repository 구현.
- snapshot 기반 멱등 `POST /documents/process`, cleanup 및 내부 status API 구현.

### Phase 2 — Worker와 문서 처리

- DB pickup, heartbeat, sweeper, retry/backoff 구현.
- Object storage/문서 parser(PDF·MD·TXT)/Embedding adapter와 처리 pipeline 구현.
- Chunk 전체 교체 트랜잭션 구현.

### Phase 3 — Spring 연동과 정합성

- Spring callback client와 영속 재전송/reconciliation 구현.
- BE 내부 callback, 사용자 상태 조회, Flyway 변경과 통합.
- 삭제·비활성화 cleanup, inventory reconciliation, 개정본 sourceHash 경쟁 처리.

### Phase 4 — 검증과 운영화

- PostgreSQL+pgvector 통합 테스트와 장애 주입 테스트.
- 격리 Critical Test, 성능·품질 기준 검증.
- 지표·로그·경고와 runbook 확정.

## Risks and Mitigations

| 위험 | 대응 |
|---|---|
| Spring callback 실패로 문서가 `PROCESSING` 고착 | callback 전달 상태 영속화 + reconciliation |
| Worker 중복 처리 | 부분 유니크 인덱스 + row lock + worker_id 조건부 heartbeat |
| 재처리 후 오래된 Chunk 잔존 | DELETE+전량 INSERT+Job 성공을 단일 트랜잭션으로 처리 |
| 개정본보다 오래된 Job이 늦게 완료 | Job sourceHash snapshot + Spring stale callback 거부 |
| 같은 PostgreSQL 인스턴스의 파트 간 결합 | database/login role/CONNECT 분리, snapshot·callback·cleanup 계약 |
| 인프로세스 Worker 부하가 API에 영향 | 동시성 제한, CPU-heavy parsing 격리, 실측 후 SQS/별도 Worker 전환 |
| 내부 API 토큰 노출 | Secrets Manager, 로그 마스킹, SG 제한, 최소 권한 |

## Complexity Tracking

Constitution 위반 없음. P0에서 외부 Queue와 mTLS를 미루고, 재시작 복구에 필요한 DB Job·lease·callback 재전송만 도입한다.
