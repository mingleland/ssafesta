# Implementation Plan: AI 직원 / 문서 파이프라인

**Branch**: `ai` | **Date**: 2026-08-20 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/007-ai-agent-document/spec.md`
**Decision record**: [research.md](./research.md), [GitHub Issue #11](https://github.com/kanghyunsoon/ssafesta/issues/11)

## Summary

부스 소유자가 등록한 PDF를 비동기로 파싱·청킹·임베딩하고 `boothId + agentId`로 격리된 pgvector 청크로 저장한다. 처리 요청은 실행 전에 동일 RDS의 FastAPI 전용 `ai.document_jobs`에 영속화한다. Worker는 DB lease와 heartbeat로 작업을 소유하고, 재시작 후 sweeper가 만료된 Job을 회수한다. 사용자 문서 상태는 Spring이, 내부 Job 상태는 FastAPI가 소유하며 FastAPI가 Spring 내부 API로 결과를 비동기 전달한다.

## Technical Context

**Language/Version**: Python 3.12 이상
**Primary Dependencies**: FastAPI, Uvicorn, SQLAlchemy 2.x async, psycopg 3, Alembic, Pydantic Settings, boto3, PDF parser, 관리형 Embedding Provider adapter
**Storage**: 동일 PostgreSQL RDS + pgvector. Spring `public` 스키마와 FastAPI `ai` 스키마 분리, 원본은 S3
**Testing**: pytest, pytest-asyncio, HTTPX ASGI client, PostgreSQL+pgvector 통합 Fixture/Testcontainers, S3·Embedding adapter fake
**Target Platform**: Linux Docker container, 개발환경 EC2/ECS 후보
**Project Type**: FastAPI web service + process-internal background Worker
**Performance Goals**: Agent당 문서 10개·총 100MB에서 검색 P95 1초 이하, 정답 근거 Top-K 포함률 95% 이상
**Constraints**: PDF 20MB, vector 1536차원, 다른 Booth/Agent 청크 유출 0건, AI 장애가 비AI 기능에 영향 없음, 영구 `PROCESSING` 0건
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

- `public.ai_documents` 직접 UPDATE를 FastAPI 권한에서 제외해 Spring SoT를 유지한다.
- terminal Job callback을 영속 재시도해 FastAPI 재시작이 Spring 문서 상태를 고착시키지 않는다.
- 같은 RDS를 사용하지만 schema·migration role·runtime role을 분리해 배포 독립성과 최소 권한을 유지한다.
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
    └── spring-document-status-api.yaml
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
│   │   ├── document_repository.py
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

### 1. 상태와 소유권

- Spring `DocumentStatus`: `QUEUED / PROCESSING / READY / FAILED / DISABLED`
- FastAPI `JobStatus`: `QUEUED / RUNNING / RETRY_WAIT / SUCCEEDED / DEAD / CANCELLED`
- 사용자 문서 목록·상태 조회는 Spring API가 `ai_documents`에서 제공한다.
- `GET /ai/v1/documents/{documentId}/status`는 운영·내부 진단용으로만 유지한다.
- 상태 전이와 필드는 [data-model.md](./data-model.md)를 따른다.

### 2. 처리 요청 접수

1. Spring이 S3 업로드와 Document 메타데이터 저장을 완료한다.
2. Spring이 Service Token으로 `POST /ai/v1/documents/process`를 호출한다.
3. FastAPI는 DB에서 `documentId + boothId + agentId + sourceHash`를 다시 검증한다. 요청의 S3 Key만 신뢰하지 않는다.
4. `ai.document_jobs`에 `QUEUED`를 INSERT한 뒤 202를 반환한다.
5. 활성 Job 부분 유니크 인덱스 충돌은 오류로 노출하지 않고 기존 Job을 조회해 `existing: true`로 반환한다.

계약: [document-processing-api.yaml](./contracts/document-processing-api.yaml)

### 3. Worker 획득과 heartbeat

- Worker는 `QUEUED`, 또는 `next_retry_at <= now()`인 `RETRY_WAIT` 행을 `FOR UPDATE SKIP LOCKED`로 한 건 획득한다.
- 획득 트랜잭션에서 `RUNNING`, `worker_id`, `attempt_no + 1`, `lease_expires_at = now() + 90s`를 기록한다.
- 처리 중 30초마다 별도 짧은 DB 트랜잭션으로 자신의 `worker_id`와 `RUNNING` 상태를 조건으로 lease를 90초 연장한다.
- heartbeat UPDATE가 0행이면 Worker는 소유권을 잃은 것으로 판단하고 결과를 커밋하지 않는다.
- 여러 Uvicorn Worker가 loop를 실행해도 row lock과 lease 조건으로 한 Job만 소유한다.

### 4. 파싱·청킹·임베딩

- S3 object metadata/크기와 Document snapshot을 검증한 뒤 PDF를 내려받는다.
- 스캔 PDF처럼 추출 텍스트가 없으면 `UNSUPPORTED_SCAN_PDF`로 실패 처리한다.
- chunk size와 overlap은 환경 설정으로 주입하며 코드에 고정하지 않는다.
- Embedding adapter는 batch 입력을 사용하고 모든 결과가 1536차원인지 저장 전에 검증한다.
- 각 Chunk는 `document_id`, `booth_id`, `agent_id`, `chunk_no`, `embedding_model_id`를 필수로 가진다.
- 중간 청크는 검색 테이블에 부분 저장하지 않고 메모리 또는 작업 임시 영역에 유지한 뒤 마지막 트랜잭션에서 전량 반영한다.

### 5. 성공 커밋의 원자성

하나의 PostgreSQL 트랜잭션에서 다음 순서로 처리한다.

1. Job 행을 `FOR UPDATE`하고 현재 Worker 소유권·`RUNNING`·유효 lease를 확인한다.
2. Spring 소유 Document를 읽어 `DISABLED` 여부와 `source_hash` 최신성을 확인한다.
3. 정책상 취소되었으면 Job을 `CANCELLED`로 종료하고 새 Chunk를 버린다.
4. 기존 `document_id` Chunk 전량 DELETE.
5. 새 Chunk 전량 INSERT.
6. Job을 `SUCCEEDED`, `chunk_count`, `finished_at`으로 갱신.
7. 커밋 후 `READY` callback을 시도한다.

트랜잭션이 실패하면 기존 Chunk 집합이 그대로 남고 새 결과는 노출되지 않는다.

### 6. 재시작 복구와 재시도

- sweeper는 기동 시 즉시, 이후 60초마다 실행한다.
- `RUNNING AND lease_expires_at < now()`인 Job을 잠금 획득해 회수한다.
- 남은 재시도가 있으면 `RETRY_WAIT`로 전환하고 실패 순서에 따라 1분, 5분, 15분 backoff를 설정한다.
- 세 번의 재시도를 모두 사용하면 `DEAD`로 전환하고 `PROCESSING_INTERRUPTED` 또는 마지막 정제 오류를 Spring에 전달한다.
- 최초 실행 1회 + 재시도 3회로 최대 실행 횟수는 4회다.
- 재시도 가능한 오류: Worker 상실, 네트워크/S3 일시 오류, Embedding timeout/5xx.
- 즉시 `DEAD` 가능한 오류: 손상 PDF, 지원하지 않는 스캔 PDF, 권한·scope 불일치, 원본 없음처럼 재시도로 해결되지 않는 입력 오류. 이 경우 사용하지 않은 재시도 횟수를 소모하지 않는다.

### 7. Spring 상태 callback

- `RUNNING` 획득 후 Spring에 `PROCESSING`, terminal 전환 후 `READY/FAILED/DISABLED`를 비동기 전달한다.
- terminal callback payload는 `jobId`, `sourceHash`, `chunkCount`, `failureCode`, 정제된 `failureReason`, 발생 시각을 포함한다.
- callback 성공 전까지 `callback_delivered_at`을 비워 두고 지수 backoff로 다시 시도한다.
- 기동/주기 reconciliation은 미전달 terminal Job을 찾아 재전송한다.
- Spring은 `jobId + status` 멱등성을 보장하고 `sourceHash`가 현재 Document와 다르면 409로 거부한다. 409 stale 결과는 전달 완료로 기록하되 현재 문서 상태를 덮지 않는다.

계약: [spring-document-status-api.yaml](./contracts/spring-document-status-api.yaml)

### 8. 부스 임대 만료

- Spring이 부스 임대 만료에 따라 Document를 `DISABLED`로 전환한다.
- FastAPI Worker는 처리 시작과 성공 커밋 직전에 Document 상태를 확인한다.
- `DISABLED`를 확인하면 Job을 `CANCELLED`로 종료하고 새 Chunk를 공개하지 않는다.
- 재임대 후 Spring이 문서를 `QUEUED`로 활성화하고 새 처리 요청을 보내면 신규 Job으로 처음부터 처리한다.
- Worker heartbeat lease 만료는 이 흐름과 무관하며 `RETRY_WAIT` 복구 대상이다.

### 9. 인증과 권한

- FastAPI↔Spring 내부 호출은 `Authorization: Bearer <service-token>`을 사용한다.
- 서비스 간 네트워크는 Security Group으로 제한하고 public ALB route에서 `/internal/*`를 노출하지 않는다.
- Service Token과 DB/Provider 자격증명은 Secrets Manager 또는 CI secret으로 주입한다.
- 로그는 Authorization header, S3 Key 전체, 원문 문서 내용, Provider raw 오류를 마스킹한다.
- DB migration role과 runtime role을 분리한다. runtime role은 `public.ai_documents` UPDATE 권한을 갖지 않는다.
- mTLS는 P0 이후 보안 강화 항목으로 남긴다.

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
| `SPRING_SERVICE_TOKEN` | secret 주입, 기본값 없음 |
| `DATABASE_URL` | secret 주입, 기본값 없음 |
| `S3_BUCKET` | 환경별 값 |

부팅 시 `lease > heartbeat`, backoff 개수와 `max_retries` 일치, embedding dimension 1536을 검증하고 잘못된 설정이면 health ready를 실패시킨다.

### 11. 관측성

- 구조화 로그 공통 필드: `job_id`, `document_id`, `booth_id`, `agent_id`, `worker_id`, `attempt_no`, `status`, `error_code`.
- 지표: Job 상태별 개수, pickup 지연, 처리 시간, heartbeat 실패, lease 회수, retry 횟수, DEAD 비율, callback 미전달 개수·지연, 문서별 chunk 수, Embedding latency/cost.
- 경고: 미전달 terminal callback, 오래된 `PROCESSING`, 반복 DEAD, queue age 증가.
- 원문 문서와 Stack Trace는 사용자 응답에 포함하지 않는다.

## Migration and Deployment Order

1. **BE/Infra**: 동일 RDS에 `ai` 스키마와 AI migration/runtime role을 생성하고 최소 권한을 부여한다.
2. **BE**: `ai_documents`의 누락 필드(`failure_reason`, `content_sha256`, `chunk_count`, `processed_at` 등)를 현재 schema와 비교해 다음 사용 가능한 Flyway migration으로 추가한다.
3. **BE**: Spring 내부 상태 callback과 사용자용 문서 상태 조회를 배포한다. callback은 기존 클라이언트에 영향 없는 신규 내부 API다.
4. **AI**: Alembic으로 `ai.document_jobs`와 인덱스를 배포한다.
5. **AI**: Job 접수 API를 배포하되 Worker 시작 feature flag는 끈다.
6. **통합 검증**: DB 권한, Service Token, callback 멱등성, stale sourceHash 거부를 확인한다.
7. **AI**: Worker와 sweeper를 활성화한다.
8. **운영 확인**: 강제 종료 복구와 callback 재전송 시험 통과 후 기존 임시 처리 경로를 제거한다.

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
- `ON DELETE CASCADE` 후 고아 Job 0건
- runtime role의 `ai_documents` UPDATE 거부

### Contract

- 두 OpenAPI schema에 대한 요청·응답 검증
- Spring callback 중복 요청의 멱등 성공
- sourceHash 불일치 409와 최신 문서 상태 보존
- 내부 진단 API가 FE 사용자 경로에서 호출되지 않는지 route/consumer 검사

### Failure Injection

- Parsing, Embedding, Chunk commit 각 단계에서 프로세스 강제 종료
- heartbeat 3회 누락 후 sweeper 회수
- Spring callback 전·후 강제 종료와 reconciliation 재전송
- S3/Embedding 5xx 후 1·5·15분 backoff
- 재시도 상한 후 `DEAD → FAILED`
- 처리 중 Spring Document가 `DISABLED`가 될 때 `CANCELLED` 및 READY 미전환

### Security/Isolation

- 요청 boothId/agentId 위조 거부
- 다른 Booth/Agent Chunk 검색 0건
- 로그와 사용자 failureReason에 token, Stack Trace, S3 Key, Provider raw 오류가 없는지 검사

검증 절차는 [quickstart.md](./quickstart.md)를 따른다.

## Phase Plan

### Phase 0 — 계약과 환경 준비

- Issue #11 합의와 본 contracts를 BE/Infra가 확인한다.
- 실제 back schema와 다음 Flyway 번호를 확인한다.
- `ai` schema, DB roles, Service Token 전달 경로를 준비한다.

### Phase 1 — Job 기반 처리 골격

- FastAPI scaffold, 설정 검증, DB session, Alembic 구성.
- `document_jobs` migration과 repository 구현.
- 멱등 `POST /documents/process` 및 내부 status API 구현.

### Phase 2 — Worker와 문서 처리

- DB pickup, heartbeat, sweeper, retry/backoff 구현.
- S3/PDF/Embedding adapter와 처리 pipeline 구현.
- Chunk 전체 교체 트랜잭션 구현.

### Phase 3 — Spring 연동과 정합성

- Spring callback client와 영속 재전송/reconciliation 구현.
- BE 내부 callback, 사용자 상태 조회, Flyway 변경과 통합.
- 부스 임대 만료·개정본 sourceHash 경쟁 처리.

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
| 같은 RDS의 파트 간 결합 | schema/migration/role 분리, 계약 기반 callback |
| 인프로세스 Worker 부하가 API에 영향 | 동시성 제한, CPU-heavy parsing 격리, 실측 후 SQS/별도 Worker 전환 |
| 내부 API 토큰 노출 | Secrets Manager, 로그 마스킹, SG 제한, 최소 권한 |

## Complexity Tracking

Constitution 위반 없음. P0에서 외부 Queue와 mTLS를 미루고, 재시작 복구에 필요한 DB Job·lease·callback 재전송만 도입한다.
