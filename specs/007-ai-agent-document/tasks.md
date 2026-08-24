# Tasks: AI 직원 / 문서 파이프라인

**Input**: `specs/007-ai-agent-document/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Prerequisites**: spec과 plan 확정, FastAPI↔Spring 내부 API 계약 합의, R2·PostgreSQL 접근 환경 준비

**Tests**: spec의 Critical Test, 장애 복구, 계약 검증 및 성공 기준이 명시되어 있으므로 테스트 작업을 포함한다. 각 테스트 작업은 구현 전에 작성하고 실패를 확인한다.

**Organization**: 사용자 스토리별로 독립 구현·검증할 수 있도록 구성하며, 설명의 `[AI]`, `[BE]`, `[FE]`는 담당 파트를 뜻한다.

## Jira Traceability

| Jira | 대응 범위 | 진행 조건 |
|---|---|---|
| `S15P21A604-96` | 이 `tasks.md` 생성·의존 순서 확정 | 가장 먼저 완료 |
| `S15P21A604-92` | PDF→Embedding→검색 미니 스파이크, T047 청킹 설정과 T066 성능·품질 기준의 입력값 확보 | `S15P21A604-96` 완료 후, 구현 착수 전에 수행 |
| `S15P21A604-93` | FastAPI 스캐폴드와 실행·테스트·컨테이너 기반(T001~T007) | `S15P21A604-92` 완료 후 시작 |
| `S15P21A604-94` | Provider protocol·fake·Embedding adapter(T007, T017, T046) | T001 및 스파이크 결과 확인 후 시작 |
| `S15P21A604-95` | Alembic·DB session·Job 모델과 migration(T004, T011~T013) | T001 완료 후 시작 |

`S15P21A604-92`의 실측값은 `research.md`에 기록하고, 청킹 기본값을 구현하는 T047과 최대 허용량 품질을 검증하는 T066에서 사용한다. 실측 전에는 chunk size·overlap을 코드나 spec에 고정하지 않는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업 완료 후 다른 파일에서 병렬 진행 가능
- **[Story]**: `spec.md`의 사용자 스토리 매핑
- 모든 작업에 구현 또는 검증 대상 파일 경로 포함

---

## Phase 1: Setup (공통 개발 기반)

**Purpose**: FastAPI 서비스와 테스트·배포의 최소 골격을 만든다.

- [ ] T001 [AI] Python 3.12 FastAPI 프로젝트와 애플리케이션 패키지 구조를 `festa-ai/pyproject.toml` 및 `festa-ai/app/__init__.py`에 구성한다
- [ ] T002 [P] [AI] Uvicorn 실행 진입점과 API v1 라우터를 `festa-ai/app/main.py` 및 `festa-ai/app/api/v1/router.py`에 구성한다
- [ ] T003 [P] [AI] pytest marker(`failure_injection`, `isolation`)와 async 테스트 설정을 `festa-ai/pyproject.toml` 및 `festa-ai/tests/conftest.py`에 구성한다
- [ ] T004 [P] [AI] Alembic 환경과 `ai` 스키마 migration 경로를 `festa-ai/alembic.ini` 및 `festa-ai/migrations/env.py`에 구성한다
- [ ] T005 [P] [AI] Linux 컨테이너 실행 환경과 비root 사용자를 `festa-ai/Dockerfile`에 구성한다
- [ ] T006 [P] [AI] Secret 없이 필요한 설정 키만 문서화한 예시 환경 파일을 `festa-ai/.env.example`에 작성한다
- [ ] T007 [P] [AI] R2·Embedding·Spring callback fake의 공통 fixture를 `festa-ai/tests/fakes/storage.py`, `festa-ai/tests/fakes/embedding.py`, `festa-ai/tests/fakes/spring_callback.py`에 구성한다

---

## Phase 2: Foundational (모든 스토리의 선행 조건)

**Purpose**: 상태·DB·보안·외부 연동의 공통 기반을 구현한다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 시작하지 않는다.

- [ ] T008 [BE] 현재 `public.ai_agents`, `public.ai_documents`, `public.ai_document_chunks` 구조를 보완하고 `failure_reason`, `content_sha256`, `file_size_bytes`, `object_key`, `chunk_count`, `processed_at` 및 필요한 제약을 다음 Flyway 파일 `backend/src/main/resources/db/migration/V11__ai_document_pipeline.sql`에 추가한다
- [ ] T009 [P] [AI] heartbeat·lease·backoff·용량·청킹·1536차원 설정과 부팅 검증을 `festa-ai/app/core/config.py`에 구현한다
- [ ] T010 [P] [AI] 구조화 로그의 공통 식별자와 Authorization·object key·문서 원문·Provider 오류 마스킹을 `festa-ai/app/core/logging.py`에 구현한다
- [ ] T011 [P] [AI] async SQLAlchemy engine과 migration/runtime role 연결을 `festa-ai/app/db/session.py`에 구현한다
- [ ] T012 [AI] `JobStatus`와 `ai.document_jobs` 모델 및 상태별 제약을 `festa-ai/app/db/models/document_job.py`에 구현한다
- [ ] T013 [AI] 활성 Job 부분 유니크 인덱스와 pickup·lease·callback 인덱스를 `festa-ai/migrations/versions/001_create_document_jobs.py`에 구현한다
- [ ] T014 [P] [AI] Spring 소유 Agent·Document를 읽기 전용으로 검증하는 repository를 `festa-ai/app/repositories/document_repository.py`에 구현한다
- [ ] T015 [P] [AI] Job 생성·상태 전이·조건부 heartbeat·callback 전달 상태 repository를 `festa-ai/app/repositories/document_job_repository.py`에 구현한다
- [ ] T016 [P] [AI] `booth_id + agent_id + READY` 필터를 강제하고 청크 전체 교체를 지원하는 repository를 `festa-ai/app/repositories/chunk_repository.py`에 구현한다
- [ ] T017 [P] [AI] object storage·PDF parser·Embedding Provider의 교체 가능한 protocol을 `festa-ai/app/providers/storage.py`, `festa-ai/app/providers/document_parser.py`, `festa-ai/app/providers/embedding.py`에 정의한다
- [ ] T018 [P] [AI] 내부 Service Token 인증과 정제된 오류 응답을 `festa-ai/app/api/dependencies/internal_auth.py` 및 `festa-ai/app/api/errors.py`에 구현한다
- [ ] T019 [P] [BE] AI 내부 callback Service Token 검증과 `/internal/*` 공개 경로 차단 설정을 `backend/src/main/java/com/example/ssafesta/internal/ai/AiInternalSecurityConfiguration.java`에 구현한다
- [ ] T020 [P] [AI] 안정적인 실패 코드와 사용자용 한국어 사유 매핑을 `festa-ai/app/services/failure_policy.py`에 구현한다

**Checkpoint**: FastAPI가 안전한 설정으로 기동하고, 두 스키마의 소유권과 최소 권한을 지키며 테스트 DB를 사용할 수 있다.

---

## Phase 3: User Story 1 — AI 직원을 만든다 (Priority: P0) 🎯 MVP-A

**Goal**: 부스 소유자가 AI 직원의 이름·역할·말투·지시문을 생성·조회·수정하고 다른 부스의 Agent에는 접근하지 못한다.

**Independent Test**: 소유자가 Agent를 생성하고 수정한 뒤 다시 조회하면 값이 유지되며, 다른 부스 계정의 조회·수정은 403으로 거부된다.

### Tests for User Story 1

- [ ] T021 [P] [US1] [BE] Agent 생성·조회·수정과 타 부스 접근 거부 통합 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiAgentApiIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T022 [P] [US1] [FE] Agent 편집 폼의 생성·수정·오류 표시 컴포넌트 테스트를 `festa-frontend/src/features/ai-agent/components/AiAgentEditor.test.tsx`에 먼저 작성하고 실패를 확인한다

### Implementation for User Story 1

- [ ] T023 [P] [US1] [BE] `ai_agents` 상태와 이름·역할·말투·지시문 매핑 entity를 `backend/src/main/java/com/example/ssafesta/ai/AiAgent.java`에 구현한다
- [ ] T024 [P] [US1] [BE] Agent 영속 조회와 booth 범위 쿼리를 `backend/src/main/java/com/example/ssafesta/ai/AiAgentRepository.java`에 구현한다
- [ ] T025 [US1] [BE] Booth 소유권을 검증하는 Agent 생성·조회·수정 서비스를 `backend/src/main/java/com/example/ssafesta/ai/AiAgentService.java`에 구현한다
- [ ] T026 [US1] [BE] Agent 생성·조회·수정 REST API와 요청·응답 DTO를 `backend/src/main/java/com/example/ssafesta/ai/AiAgentController.java`에 구현한다
- [ ] T027 [P] [US1] [FE] Spring Agent API client와 DTO를 `festa-frontend/src/features/ai-agent/api/aiAgentApi.ts`에 구현한다
- [ ] T028 [US1] [FE] Agent 생성·수정 폼과 저장 후 재조회 흐름을 `festa-frontend/src/features/ai-agent/components/AiAgentEditor.tsx`에 구현한다

**Checkpoint**: 문서 처리 없이도 AI 직원 설정 기능을 독립적으로 시연하고 권한 격리를 검증할 수 있다.

---

## Phase 4: User Story 2 — 문서를 올리면 AI가 검색에 사용할 수 있다 (Priority: P0) 🎯 MVP-B

**Goal**: PDF를 R2에 직접 업로드한 뒤 비동기 처리하여 격리된 청크를 만들고, 중단된 Job을 자동 회수하며 Spring에서 최종 상태를 확인한다.

**Independent Test**: 테스트 PDF 업로드 완료 요청 후 `QUEUED → PROCESSING → READY`가 되고, 생성된 모든 청크가 같은 `document_id`, `booth_id`, `agent_id`, `embedding_model_id`와 1536차원 벡터를 가진다. Worker 강제 종료 시에도 Job이 재시도 또는 최종 실패로 종료된다.

### Tests for User Story 2

- [ ] T029 [P] [US2] [BE] PDF 20MB·Agent 10개/100MB·SHA-256 중복·명시적 교체 및 R2 업로드 완료 흐름 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiDocumentUploadIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T030 [P] [US2] [BE] Spring callback 멱등 처리와 stale `sourceHash` 409 테스트를 `backend/src/test/java/com/example/ssafesta/internal/ai/AiDocumentStatusCallbackIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T031 [P] [US2] [AI] 두 OpenAPI 요청·응답과 Service Token을 검증하는 계약 테스트를 `festa-ai/tests/contract/test_document_processing_api.py` 및 `festa-ai/tests/contract/test_spring_status_api.py`에 먼저 작성하고 실패를 확인한다
- [ ] T032 [P] [US2] [AI] 활성 Job 멱등성·`SKIP LOCKED` 단일 소유·상태 전이·DB 권한 테스트를 `festa-ai/tests/integration/test_document_job_repository.py`에 먼저 작성하고 실패를 확인한다
- [ ] T033 [P] [US2] [AI] Worker 강제 종료·lease 회수·1/5/15분 backoff·최대 3회 재시도 테스트를 `festa-ai/tests/integration/test_job_recovery.py`에 먼저 작성하고 실패를 확인한다
- [ ] T034 [P] [US2] [AI] 청크 전체 교체 원자성·청크 감소·중간 실패 rollback 테스트를 `festa-ai/tests/integration/test_chunk_replacement.py`에 먼저 작성하고 실패를 확인한다
- [ ] T035 [P] [US2] [AI] 부스/Agent 위조와 교차 검색 누출 0건 Critical Test를 `festa-ai/tests/integration/test_vector_isolation.py`에 먼저 작성하고 실패를 확인한다
- [ ] T036 [P] [US2] [AI] PDF 파싱 실패·스캔 PDF·R2 및 Embedding 일시 장애의 오류 분류와 처리 시작·성공 commit 직전 `DISABLED → CANCELLED` 전환 테스트를 `festa-ai/tests/unit/test_document_processing_service.py`에 먼저 작성하고 실패를 확인한다
- [ ] T037 [P] [US2] [FE] 업로드 성공과 RAG 준비 완료를 구분하는 문서 상태 UI 테스트를 `festa-frontend/src/features/ai-agent/components/DocumentManager.test.tsx`에 먼저 작성하고 실패를 확인한다

### Implementation for User Story 2

- [ ] T038 [P] [US2] [BE] R2 S3-compatible presigned URL 발급과 HEAD 메타데이터 검증을 `backend/src/main/java/com/example/ssafesta/ai/R2DocumentStorageService.java`에 구현한다
- [ ] T039 [P] [US2] [BE] Document entity와 Agent별 활성 문서 수·총량·hash 조회를 `backend/src/main/java/com/example/ssafesta/ai/AiDocument.java` 및 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentRepository.java`에 구현한다
- [ ] T040 [US2] [BE] PDF 형식·20MB·10개/100MB·SHA-256 중복 및 `documentId` 교체 정책을 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentService.java`에 구현한다
- [ ] T041 [P] [US2] [BE] FastAPI 처리 요청 client와 202/409/422 처리를 `backend/src/main/java/com/example/ssafesta/internal/ai/AiDocumentProcessingClient.java`에 구현한다
- [ ] T042 [US2] [BE] presigned 업로드 URL·업로드 완료·명시적 교체 API를 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentController.java`에 구현한다
- [ ] T043 [US2] [BE] `jobId + status` 멱등성과 `sourceHash` 최신성 검증을 포함한 callback controller/service를 `backend/src/main/java/com/example/ssafesta/internal/ai/AiDocumentStatusController.java` 및 `backend/src/main/java/com/example/ssafesta/internal/ai/AiDocumentStatusService.java`에 구현한다
- [ ] T044 [P] [US2] [AI] R2 endpoint·bucket을 사용하는 boto3 storage adapter와 원본 metadata 검증을 `festa-ai/app/providers/r2_storage.py`에 구현한다
- [ ] T045 [P] [US2] [AI] PDF 텍스트·페이지 추출과 텍스트 없음 판정을 `festa-ai/app/providers/pdf_parser.py`에 구현한다
- [ ] T046 [P] [US2] [AI] batch Embedding 호출과 결과 1536차원 검증을 `festa-ai/app/providers/managed_embedding.py`에 구현한다
- [ ] T047 [P] [US2] [AI] `S15P21A604-92`의 실측값을 초기 배포 설정에 반영하고 설정 기반 chunk size·overlap과 page/section 추적을 `festa-ai/app/services/text_chunker.py`에 구현한다
- [ ] T048 [US2] [AI] 처리 시작과 성공 commit 직전에 문서 snapshot·`DISABLED` 상태·`sourceHash`를 검증하고, 비활성 문서는 `CANCELLED`로 종료하며 R2 다운로드·파싱·청킹·임베딩·원자적 청크 교체를 수행하는 파이프라인을 `festa-ai/app/services/document_processing_service.py`에 구현한다
- [ ] T049 [P] [US2] [AI] 멱등 처리 요청 및 내부 Job 상태 조회 endpoint를 `festa-ai/app/api/v1/documents.py`에 구현한다
- [ ] T050 [US2] [AI] `FOR UPDATE SKIP LOCKED` pickup과 30초 heartbeat 및 소유권 상실 시 결과 폐기를 `festa-ai/app/workers/document_worker.py`에 구현한다
- [ ] T051 [US2] [AI] 기동 즉시 및 60초 주기의 만료 Job 회수와 재시도 상한 처리를 `festa-ai/app/services/job_recovery_service.py`에 구현한다
- [ ] T052 [P] [US2] [AI] `PROCESSING/READY/FAILED/DISABLED` callback client와 사용자 오류 정제를 `festa-ai/app/services/spring_status_callback.py`에 구현한다
- [ ] T053 [US2] [AI] 미전달 terminal callback의 영속 재시도와 stale 409 종료 처리를 `festa-ai/app/services/callback_reconciliation_service.py`에 구현한다
- [ ] T054 [US2] [AI] API lifespan에서 Worker·sweeper·callback reconciliation을 시작하고 안전하게 종료하도록 `festa-ai/app/main.py`에 연결한다
- [ ] T055 [P] [US2] [FE] Spring 업로드 URL·완료·교체·상태 조회 API client와 DTO를 `festa-frontend/src/features/ai-agent/api/aiDocumentApi.ts`에 구현한다
- [ ] T056 [US2] [FE] R2 직접 업로드 진행률과 `QUEUED/PROCESSING/READY/FAILED/DISABLED` 표시를 `festa-frontend/src/features/ai-agent/components/DocumentManager.tsx`에 구현한다

**Checkpoint**: 문서 업로드부터 READY까지의 P0 흐름, 강제 종료 복구, callback 복구 및 Vector 격리 Critical Test를 독립적으로 통과한다.

---

## Phase 5: User Story 3 — 문서를 관리한다 (Priority: P1)

**Goal**: 소유자가 파일명·상태·업로드 시각·실패 사유를 조회하고 삭제한 문서를 즉시 검색 대상에서 제외한다.

**Independent Test**: 문서 목록에 마지막 Spring 상태가 표시되고, AI 서비스 중단 중에도 조회가 가능하며, 삭제 직후 관련 Document·Chunk·Job이 남지 않고 R2 원본 삭제가 재시도된다.

### Tests for User Story 3

- [ ] T057 [P] [US3] [BE] AI 서비스 중단 상태의 목록 조회와 타 부스 접근 거부 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiDocumentQueryIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T058 [P] [US3] [BE] 삭제 즉시 검색 제외·Chunk/Job cascade·R2 삭제 재시도 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiDocumentDeletionIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T059 [P] [US3] [FE] 목록·실패 사유·삭제 확인 UI 테스트를 `festa-frontend/src/features/ai-agent/components/DocumentList.test.tsx`에 먼저 작성하고 실패를 확인한다

### Implementation for User Story 3

- [ ] T060 [US3] [BE] Spring DB만 사용해 문서 목록과 마지막 상태를 제공하는 조회 로직을 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentQueryService.java`에 구현한다
- [ ] T061 [US3] [BE] 소유권 검증 후 Document를 삭제해 FK cascade로 Chunk·Job을 정리하고 R2 삭제 작업을 기록하는 로직을 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentDeletionService.java`에 구현한다
- [ ] T062 [US3] [BE] 실패한 R2 원본 삭제를 비동기로 재시도하는 작업을 `backend/src/main/java/com/example/ssafesta/ai/R2ObjectDeletionWorker.java`에 구현한다
- [ ] T063 [US3] [BE] 목록·상태·삭제 endpoint를 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentController.java`에 연결한다
- [ ] T064 [P] [US3] [FE] 문서 목록·상태별 한국어 표시·실패 사유·삭제 확인 UI를 `festa-frontend/src/features/ai-agent/components/DocumentList.tsx`에 구현한다

**Checkpoint**: FastAPI가 중단돼도 Spring에서 문서를 관리할 수 있고 삭제된 문서가 이후 검색에 사용되지 않는다. Spring→FastAPI 삭제 API는 추가하지 않는다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 보안·성능·운영·문서 검증을 완료한다.

- [ ] T065 [P] [AI] 사용자 응답과 로그에서 Secret·Stack Trace·object key·Provider 원문이 노출되지 않는지 `festa-ai/tests/integration/test_sensitive_data_redaction.py`로 검증한다
- [ ] T066 [P] [AI] Agent당 문서 10개·100MB에서 검색 P95 1초와 정답 근거 Top-K 포함률 95%를 `festa-ai/tests/performance/test_rag_retrieval.py`로 검증한다
- [ ] T067 [P] [AI] Job 상태별 수·queue age·처리 시간·lease 회수·callback 지연 지표를 `festa-ai/app/core/metrics.py`에 구현한다
- [ ] T068 [P] [AI] health/live·ready endpoint와 설정·DB·Worker 준비 상태를 `festa-ai/app/api/health.py`에 구현한다
- [ ] T069 [P] [AI] FastAPI 단위·계약·통합·장애 주입·격리 테스트 단계를 저장소 루트 `.github/workflows/ai-ci.yml`에 구성한다
- [ ] T070 [P] [BE] Backend Agent·문서·callback 통합 테스트 단계를 `.github/workflows/backend-ci.yml`의 기존 Backend job에 연결한다
- [ ] T071 [AI] Worker 중단·rollback·callback 적체·DEAD 증가 대응 절차를 `festa-ai/docs/document-pipeline-runbook.md`에 작성한다
- [ ] T072 [AI] `specs/007-ai-agent-document/quickstart.md`의 migration, 성공 처리, 중복, 강제 종료, callback 복구, 청크 교체, 임대 만료, stale 개정본, 격리 검증을 순서대로 실행하고 결과를 `festa-ai/tests/validation/quickstart-results.md`에 기록한다

---

## Dependencies & Execution Order

### Planning-to-Implementation Gate

1. `S15P21A604-96`에서 이 작업 목록과 의존 순서를 확정한다.
2. `S15P21A604-92` 미니 스파이크를 수행해 청킹·Top-K·처리 시간·비용 실측값을 `specs/007-ai-agent-document/research.md`에 기록한다.
3. 실측값을 T047의 초기 배포 설정과 T066의 성능·품질 검증 입력에 반영한다.
4. 이후 Phase 1과 Phase 2 구현을 시작한다.

### Phase Dependencies

- **Phase 1 — Setup**: 즉시 시작 가능
- **Phase 2 — Foundational**: Phase 1 완료 후 진행하며 모든 사용자 스토리를 차단
- **Phase 3 — US1**: Phase 2 완료 후 시작 가능
- **Phase 4 — US2**: Phase 2와 Agent 기본 데이터 구조 완료 후 시작 가능하며 P0 핵심 경로
- **Phase 5 — US3**: Phase 2 및 Document 구조 완료 후 시작 가능
- **Phase 6 — Polish**: 배포할 사용자 스토리 완료 후 진행

### User Story Dependencies

- **US1 (P0)**: 다른 스토리 의존성 없이 Agent 설정·권한을 독립 검증 가능
- **US2 (P0)**: Agent와 Document 식별자가 필요하므로 US1의 Backend Agent 기반에 의존하지만 Worker 파이프라인은 fake Document로 병렬 개발 가능
- **US3 (P1)**: US2의 Document 저장 구조에 의존하지만 목록 UI와 조회 API는 Spring fixture로 병렬 개발 가능

### Within Each User Story

- 테스트를 먼저 작성하고 실패를 확인한 뒤 구현한다
- DB migration과 모델을 repository보다 먼저 완료한다
- repository와 Provider adapter를 service보다 먼저 완료한다
- service를 endpoint·Worker·UI 연동보다 먼저 완료한다
- 통합 후 각 Checkpoint를 통과해야 다음 배포 범위로 진행한다

### Parallel Opportunities

- Phase 1의 `[P]` 작업은 T001 이후 병렬 가능
- Phase 2에서 BE migration·보안과 AI 설정·DB·Provider protocol 작업은 파트별 병렬 가능
- Phase 3에서 BE API와 FE 컴포넌트 테스트·client 작업은 계약을 기준으로 병렬 가능
- Phase 4에서 BE 업로드/callback, AI Worker/Provider, FE 문서 UI를 fake와 OpenAPI 계약으로 병렬 개발 가능
- Phase 5에서 BE 조회·삭제와 FE 목록 UI는 Spring API DTO 확정 후 병렬 가능
- 서로 다른 테스트 파일의 `[P]` 작업은 동시에 진행 가능

---

## Parallel Examples

### User Story 1

```text
BE: T021 → T023/T024 → T025 → T026
FE: T022 → T027 → T028
```

### User Story 2

```text
BE: T029/T030 → T038/T039/T041 → T040/T042/T043
AI: T031~T036 → T044~T047/T049/T052 → T048/T050/T051/T053/T054
FE: T037 → T055 → T056
```

### User Story 3

```text
BE: T057/T058 → T060/T061 → T062/T063
FE: T059 → T064
```

---

## Implementation Strategy

### MVP First

1. Phase 1과 Phase 2를 완료한다.
2. US1의 Agent 생성·수정·권한 검증을 완료한다.
3. US2를 fake R2·Embedding·Spring callback으로 먼저 완성한다.
4. 실제 R2와 Spring을 연결하고 Worker 강제 종료 및 Vector 격리 Critical Test를 통과한다.
5. US1+US2를 P0 MVP로 배포한다.

### Incremental Delivery

1. **Foundation**: FastAPI scaffold, DB 역할·migration, 공통 계약
2. **MVP-A**: AI 직원 생성·수정
3. **MVP-B**: PDF 업로드·비동기 처리·READY 상태·RAG 청크
4. **P1**: 문서 목록·실패 사유·삭제와 R2 정리
5. **운영화**: 장애 주입, 보안, 성능·품질, 지표와 runbook

### Part Coordination

- Backend는 Spring 영구 상태와 공개 API·R2 업로드·내부 callback을 담당한다.
- AI는 Job·Worker·파싱·청킹·Embedding·Chunk·callback 재전송을 담당한다.
- Frontend는 Spring 공개 API만 호출하며 FastAPI 내부 진단 API를 호출하지 않는다.
- 문서 삭제는 Spring DB의 FK cascade와 R2 비동기 정리로 처리하며 Spring→FastAPI 삭제 API를 신설하지 않는다.

---

## Notes

- `[P]`는 같은 파일을 동시에 수정하지 않고 미완료 작업에 의존하지 않는 작업만 표시한다.
- FastAPI runtime role은 `public.ai_documents`를 직접 UPDATE하지 않는다.
- 모든 Vector 검색은 `booth_id + agent_id + READY` 필터를 강제한다.
- Secret은 저장소에 커밋하지 않고 `.env.example`에는 키 이름만 둔다.
- 적용된 Flyway·Alembic migration은 수정하지 않고 다음 migration으로 forward-fix한다.
