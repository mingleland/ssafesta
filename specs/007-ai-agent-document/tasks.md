# Tasks: AI 직원 / 문서 파이프라인

**Input**: `specs/007-ai-agent-document/`의 `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Prerequisites**: spec과 plan 확정, GitLab Work Item #100의 P0 저장소 운영 범위·#102의 내부 API 인증·#106의 callback 404 처리 계약 합의, R2·PostgreSQL 접근 환경 준비

**Tests**: spec의 Critical Test, 장애 복구, 계약 검증 및 성공 기준이 명시되어 있으므로 테스트 작업을 포함한다. 각 테스트 작업은 구현 전에 작성하고 실패를 확인한다.

**Organization**: 사용자 스토리별로 독립 구현·검증할 수 있도록 구성하며, 설명의 `[AI]`, `[BE]`, `[FE]`, `[INFRA]`는 담당 파트를 뜻한다.

## Jira Traceability

| Jira | 대응 범위 | 진행 조건 |
|---|---|---|
| `S15P21A604-96` | 이 `tasks.md` 생성·의존 순서 확정 | 가장 먼저 완료 |
| `S15P21A604-92` | PDF→Embedding→검색 미니 스파이크, T048 청킹 설정과 T067 성능·품질 기준의 입력값 확보 | `S15P21A604-96` 완료 후, 구현 착수 전에 수행 |
| `S15P21A604-93` | FastAPI 스캐폴드와 실행·테스트·컨테이너 기반(T001~T007) | 공통 스캐폴드는 완료. 스파이크와 독립적이며 튜닝 값은 포함하지 않음 |
| `S15P21A604-94` | Provider protocol·fake·Embedding adapter(T007, T018, T047) | T001 및 스파이크 결과 확인 후 시작 |
| `S15P21A604-95` | Alembic·DB session·Job 모델과 migration(T004, T012~T014) | T001 완료 후 시작 |

`S15P21A604-92`의 실측값은 `research.md`에 기록하고, 청킹 기본값을 구현하는 T048과 최대 허용량 품질을 검증하는 T067에서 사용한다. 실측 전에는 chunk size·overlap을 코드나 spec에 고정하지 않는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업 완료 후 다른 파일에서 병렬 진행 가능
- **[Story]**: `spec.md`의 사용자 스토리 매핑
- 모든 작업에 구현 또는 검증 대상 파일 경로 포함

---

## Phase 1: Setup (공통 개발 기반)

**Purpose**: FastAPI 서비스와 테스트·배포의 최소 골격을 만든다.

- [X] T001 [AI] Python 3.12 FastAPI 프로젝트와 애플리케이션 패키지 구조를 `festa-ai/pyproject.toml` 및 `festa-ai/app/__init__.py`에 구성한다
- [X] T002 [P] [AI] Uvicorn 실행 진입점과 API v1 라우터를 `festa-ai/app/main.py` 및 `festa-ai/app/api/v1/router.py`에 구성한다
- [X] T003 [P] [AI] pytest marker(`failure_injection`, `isolation`)와 async 테스트 설정을 `festa-ai/pyproject.toml` 및 `festa-ai/tests/conftest.py`에 구성한다
- [X] T004 [P] [AI] Alembic 환경과 `ai` 스키마 migration 경로를 `festa-ai/alembic.ini` 및 `festa-ai/migrations/env.py`에 구성한다 → S15P21A604-262에서 환경별 AI DB migration 경로로 계약 변경, T012·T014에서 구현 정합화
- [X] T005 [P] [AI] Linux 컨테이너 실행 환경과 비root 사용자를 `festa-ai/Dockerfile`에 구성한다
- [X] T006 [P] [AI] Secret 없이 필요한 설정 키만 문서화한 예시 환경 파일을 `festa-ai/.env.example`에 작성한다
- [X] T007 [P] [AI] R2·Embedding·Spring callback fake의 공통 fixture를 `festa-ai/tests/fakes/storage.py`, `festa-ai/tests/fakes/embedding.py`, `festa-ai/tests/fakes/spring_callback.py`에 구성한다

---

## Phase 2: Foundational (모든 스토리의 선행 조건)

**Purpose**: 상태·DB·보안·외부 연동의 공통 기반을 구현한다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 시작하지 않는다.

- [ ] T008 [BE] `develop` 기준 최신 Flyway migration 다음 버전으로 Business DB의 `ai_agents`, `ai_documents` 구조를 보완하고 `EXPIRED`, `failure_reason`, `content_sha256`, `storage_provider`, `storage_bucket`, `file_size_bytes`, `object_key`, `chunk_count`, `processed_at` 및 필요한 제약을 추가한다. `document_chunks`는 Business DB에 만들지 않는다
- [ ] T009 [BE] Business DB의 `storage_reconciliation_log`(`run_id·document_id·object_key·source_provider·target_provider·expected/actual size·type·sha256·status·attempt_count·failure_reason·checked_at·resolved_at`, `UNIQUE(run_id, document_id)`)를 T008과 같은 Flyway 파일 또는 다음 사용 가능한 버전에 추가한다
- [ ] T084 [P] [INFRA] 환경별 Business/AI database·login role·CONNECT matrix와 AI DB 전용 vector extension을 Infra PostgreSQL 계약대로 구성하고 검증 증거를 남긴다
- [ ] T010 [P] [AI] heartbeat·lease·backoff·용량·청킹·1536차원과 방향별 Service Token 콤마 목록(비어 있지 않은 고유 값 1~2개) 설정 및 부팅 검증을 `festa-ai/app/core/config.py`에 구현한다
- [ ] T011 [P] [AI] 구조화 로그의 공통 식별자와 Authorization·object key·문서 원문·Provider 오류 마스킹을 `festa-ai/app/core/logging.py`에 구현한다
- [X] T012 [P] [AI] 환경별 AI DB 전용 async SQLAlchemy engine과 migration/runtime role 연결을 `festa-ai/app/db/session.py`에 구현하고 Business DB URL·credential은 설정에 두지 않는다 (`S15P21A604-121`)
- [ ] T013 [AI] `JobStatus`, callback 필드와 Spring 검증 snapshot(`document_id·booth_id·agent_id·original_filename·content_type·file_size_bytes·source_hash·storage_provider·storage_bucket·object_key`)을 포함한 `document_jobs` 모델 및 상태별 제약을 `festa-ai/app/db/models/document_job.py`에 구현한다
- [ ] T014 [AI] AI DB에 `document_jobs`·`document_chunks(searchable 기본 false)` 테이블, 활성 Job 부분 유니크 인덱스와 pickup·lease·callback·`booth_id + agent_id + searchable` 인덱스를 `festa-ai/migrations/versions/001_create_document_pipeline.py`에 구현한다. Business DB FK와 `ON DELETE CASCADE`는 만들지 않는다
- [X] T015 [P] [AI] Spring이 전달한 처리 snapshot의 필수 필드·형식·크기·scope·SHA-256·저장소 식별자를 검증하고 Job 생성 입력으로 변환하는 validator를 `festa-ai/app/services/document_snapshot_validator.py`에 구현한다. Business DB repository는 만들지 않는다 (`S15P21A604-121`)
- [ ] T016 [P] [AI] Job 생성·상태 전이·조건부 heartbeat와 `callback_delivered_at`·`callback_terminated_at`·`callback_terminal_code` 전달/종료 상태 repository를 `festa-ai/app/repositories/document_job_repository.py`에 구현한다 (`S15P21A604-121`: 활성 Job 조회·snapshot 비교·`QUEUED` 생성 완료, 나머지 상태 전이·callback 상태는 후속)
- [ ] T017 [P] [AI] `booth_id + agent_id + searchable = true` 필터를 강제하고 검색 불가 상태의 청크 전체 교체·callback 승인 후 활성화를 지원하는 repository를 `festa-ai/app/repositories/chunk_repository.py`에 구현한다
- [ ] T018 [P] [AI] 문서별 Provider로 R2·MinIO adapter를 선택할 수 있는 object storage protocol과 PDF parser·Embedding Provider protocol을 `festa-ai/app/providers/storage.py`, `festa-ai/app/providers/document_parser.py`, `festa-ai/app/providers/embedding.py`에 정의한다
- [X] T018a [P] [AI] LLM Provider protocol과 결정적 LLM·Embedding Mock Provider를 `festa-ai/app/providers/llm.py` 및 `festa-ai/app/providers/mock.py`에 구현한다 (`S15P21A604-94`)
- [X] T019 [P] [AI] 모든 Spring→FastAPI 내부 요청에서 `INTERNAL_SPRING_TO_AI_TOKENS` 전체를 `secrets.compare_digest`로 검증하고 누락·오류·반대 방향 토큰을 401로 거부하도록 `festa-ai/app/api/dependencies/internal_auth.py` 및 `festa-ai/app/api/errors.py`에 구현한다 (`S15P21A604-121`)
- [ ] T020 [P] [BE] 모든 AI→Spring callback에서 `INTERNAL_AI_TO_SPRING_TOKENS` 전체를 `MessageDigest.isEqual`로 검증하고 누락·오류·반대 방향 토큰을 401로 거부하며 `/internal/*` 공개 경로를 차단하도록 `backend/src/main/java/com/example/ssafesta/internal/ai/AiInternalSecurityConfiguration.java`에 구현한다
- [ ] T021 [P] [AI] 안정적인 실패 코드와 사용자용 한국어 사유 매핑을 `festa-ai/app/services/failure_policy.py`에 구현한다

**Checkpoint**: FastAPI가 AI DB 전용 설정으로 기동하고, Business/AI database CONNECT matrix와 최소 권한을 지키며 테스트 DB를 사용할 수 있다.

---

## Phase 3: User Story 1 — AI 직원을 만든다 (Priority: P0) 🎯 MVP-A

**Goal**: 부스 소유자가 AI 직원의 이름·역할·말투·지시문을 생성·조회·수정하고 다른 부스의 Agent에는 접근하지 못한다.

**Independent Test**: 소유자가 Agent를 생성하고 수정한 뒤 다시 조회하면 값이 유지되며, 다른 부스 계정의 조회·수정은 403으로 거부된다.

### Tests for User Story 1

- [ ] T022 [P] [US1] [BE] Agent 생성·조회·수정·**삭제**, 부스당 1명 거부, **참조 있는 삭제 거부**, 같은 부스 스태프 성공 경로, 타 부스 접근 거부 통합 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiAgentApiIntegrationTest.java`에 먼저 작성하고 실패를 확인한다 (C-14·C-15, 2026-08-30 확대)
- [ ] T023 [P] [US1] [FE] Agent 편집 폼의 생성·수정·오류 표시 컴포넌트 테스트를 `festa-frontend/src/features/ai-agent/components/AiAgentEditor.test.tsx`에 먼저 작성하고 실패를 확인한다

### Implementation for User Story 1

- [ ] T024 [P] [US1] [BE] `ai_agents` 상태와 이름·역할·말투·지시문 매핑 entity를 `backend/src/main/java/com/example/ssafesta/ai/AiAgent.java`에 구현한다
- [ ] T025 [P] [US1] [BE] Agent 영속 조회와 booth 범위 쿼리를 `backend/src/main/java/com/example/ssafesta/ai/AiAgentRepository.java`에 구현한다
- [ ] T026 [US1] [BE] `BoothEditorGuard`(소유자·스태프, C-15)로 권한을 검증하는 Agent 생성·조회·수정·**삭제** 서비스를 `backend/src/main/java/com/example/ssafesta/ai/AiAgentService.java`에 구현한다. 삭제는 참조 3종 사전검사 + FK 번역 + booth 잠금 (C-14)
- [ ] T027 [US1] [BE] Agent 생성·조회·수정·**삭제**(`DELETE /agents/{agentId}` → 204) REST API와 요청·응답 DTO를 `backend/src/main/java/com/example/ssafesta/ai/AiAgentController.java`에 구현한다
- [ ] T027a [US1] [BE] `V15__agent_one_per_booth.sql`(유니크 인덱스)과 `AiAgentProperties`(perBoothLimit==1·documentCountLimit>0·documentTotalBytes>0 부팅 검증), `ErrorCode` 3종(`AGENT_NOT_FOUND`·`AGENT_LIMIT_EXCEEDED`·`AGENT_DELETE_CONFLICT`)을 구현한다 (C-13, 2026-08-30 추가)
- [ ] T027b [US1] [BE] booth 패키지에 `agentReferencedInLayouts`(Draft + `published_layout_version` 포인터 기준 현재 Published) 헬퍼와 `BoothRepository.findWithLockById`를 추가하고, `BoothLayoutService` publish가 검증 전에 같은 잠금을 잡도록 한다 (불변식 A-3, 2026-08-30 추가)
- [ ] T027c [P] [US1] [BE] 동시 생성 1건만 성공, **Publish↔삭제 잠금 순서 2종 latch 재현**을 `backend/src/test/java/com/example/ssafesta/ai/AiAgentConcurrencyIntegrationTest.java`에, Properties 부팅 검증·바인딩을 `AiAgentPropertiesTest`에, FK 번역을 `AiAgentDeleteTranslationTest`에 구현한다 (2026-08-30 추가)
- [ ] T028 [P] [US1] [FE] Spring Agent API client와 DTO를 `festa-frontend/src/features/ai-agent/api/aiAgentApi.ts`에 구현한다
- [ ] T029 [US1] [FE] Agent 생성·수정 폼과 저장 후 재조회 흐름을 `festa-frontend/src/features/ai-agent/components/AiAgentEditor.tsx`에 구현한다

**Checkpoint**: 문서 처리 없이도 AI 직원 설정 기능을 독립적으로 시연하고 권한 격리를 검증할 수 있다.

---

## Phase 4: User Story 2 — 문서를 올리면 AI가 검색에 사용할 수 있다 (Priority: P0) 🎯 MVP-B

**Goal**: PDF를 Spring이 선택한 S3-compatible Provider에 직접 업로드한 뒤 비동기 처리하여 격리된 청크를 만들고, 중단된 Job을 자동 회수하며 Spring에서 최종 상태를 확인한다.

**Independent Test**: 테스트 PDF 업로드 완료 요청 후 `QUEUED → PROCESSING → READY`가 되고, 생성된 모든 청크가 같은 `document_id`, `booth_id`, `agent_id`, `embedding_model_id`와 1536차원 벡터를 가진다. Worker 강제 종료 시에도 Job이 재시도 또는 최종 실패로 종료된다.

### Tests for User Story 2

- [ ] T030 [P] [US2] [BE] PDF 20MB·Agent 10개/100MB·SHA-256 중복·명시적 교체, 15분 URL·1시간 `EXPIRED`·24시간 복구 유예, 활성 쓰기 Provider 기록과 Provider 변경 중 재개 시 기존 문서 `EXPIRED`·새 문서 생성 흐름을 `backend/src/test/java/com/example/ssafesta/ai/AiDocumentUploadIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T031 [P] [US2] [BE] AI→Spring 방향 Service Token의 정상 승인과 누락·오류·반대 방향 401, `JOB_NOT_REGISTERED`·`DOCUMENT_NOT_FOUND`·`JOB_DOCUMENT_MISMATCH` 404 분리와 mismatch 경고, 중복 `jobId + status` 멱등 성공과 stale `sourceHash` 409 테스트를 `backend/src/test/java/com/example/ssafesta/internal/ai/AiDocumentStatusCallbackIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T032 [P] [US2] [AI] 세 OpenAPI 요청·응답 스키마를 `festa-ai/tests/contract/test_document_processing_api.py`, `festa-ai/tests/contract/test_spring_status_api.py`, `festa-ai/tests/contract/test_spring_storage_reconciliation_api.py`에서 검증한다. 처리 snapshot 필수 필드, cleanup 반복 204, callback 404 원인 코드와 방향별 Service Token 401, reconcile metadata를 먼저 검증하고 실패를 확인한다 (`S15P21A604-121`: document-processing 접수 계약 자동 비교 완료, 나머지 두 계약은 후속)
- [ ] T033 [P] [US2] [AI/INFRA] 활성 Job 멱등성·`SKIP LOCKED` 단일 소유·상태 전이와 4개 runtime role × 4개 database CONNECT matrix의 대각선만 성공하는 권한 테스트를 `festa-ai/tests/integration/test_document_job_repository.py` 및 Infra contract test에 먼저 작성하고 실패를 확인한다 (`S15P21A604-121`: 실제 HTTP→PostgreSQL 활성 Job 멱등·동시성 E2E 완료, `SKIP LOCKED`·상태 전이·CONNECT matrix는 후속)
- [ ] T034 [P] [US2] [AI] Worker 강제 종료·lease 회수·1/5/15분 backoff·최대 3회 재시도 테스트를 `festa-ai/tests/integration/test_job_recovery.py`에 먼저 작성하고 실패를 확인한다
- [ ] T035 [P] [US2] [AI] AI DB 안의 검색 불가 Chunk 전체 교체+Job `SUCCEEDED` 원자성·청크 감소·중간 실패 rollback과 READY callback 204 이후에만 `searchable=true`가 되는 테스트를 `festa-ai/tests/integration/test_chunk_replacement.py`에 먼저 작성하고 실패를 확인한다
- [ ] T036 [P] [US2] [AI] 부스/Agent 위조와 교차 검색 누출 0건 Critical Test를 `festa-ai/tests/integration/test_vector_isolation.py`에 먼저 작성하고 실패를 확인한다
- [ ] T037 [P] [US2] [AI] Spring snapshot만으로 문서별 R2/MinIO 읽기, 저장소·Embedding 일시 장애의 1·5·15분 유한 재시도, PDF 파싱·스캔 PDF 오류와 FastAPI Business DB 무접근 테스트를 `festa-ai/tests/unit/test_document_processing_service.py`에 먼저 작성하고 실패를 확인한다
- [ ] T038 [P] [US2] [FE] 업로드 성공과 RAG 준비 완료를 구분하고 `EXPIRED`를 업로드 만료로 표시하는 문서 상태 UI 테스트를 `festa-frontend/src/features/ai-agent/components/DocumentManager.test.tsx`에 먼저 작성하고 실패를 확인한다

### Implementation for User Story 2

- [ ] T039 [P] [US2] [BE] Spring의 활성 쓰기 Provider에 따라 15분 TTL presigned URL을 발급하고 문서별 Provider의 HEAD 메타데이터를 검증하는 서비스를 `backend/src/main/java/com/example/ssafesta/ai/DocumentStorageService.java`에 구현한다
- [ ] T040 [P] [US2] [BE] `storageProvider + storageBucket + objectKey`를 포함한 Document entity와 Agent별 활성 문서 수·총량·hash 조회를 `backend/src/main/java/com/example/ssafesta/ai/AiDocument.java` 및 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentRepository.java`에 구현한다
- [ ] T041 [US2] [BE] PDF·MD·TXT 형식·20MB·10개/100MB·SHA-256 중복 및 `documentId` 교체 정책을 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentService.java`에 구현한다
- [ ] T042 [P] [US2] [BE] Business DB에서 소유권·임대·상태를 검증한 뒤 전체 문서·저장소 snapshot을 구성하고 `INTERNAL_SPRING_TO_AI_TOKENS`를 부착해 FastAPI 처리 요청을 보내는 client와 202/401/409/422 처리를 `backend/src/main/java/com/example/ssafesta/internal/ai/AiDocumentProcessingClient.java`에 구현한다
- [ ] T043 [US2] [BE] presigned 업로드 URL·업로드 완료·명시적 교체 API를 구현하고, 같은 Provider의 `EXPIRED` 완료 요청은 원본이 있으면 `QUEUED`로 복구하되 Provider가 바뀌었으면 기존 문서를 `EXPIRED`로 유지하고 새 문서·새 object key를 만들도록 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentController.java`에 연결한다
- [ ] T044 [US2] [BE] 처리 요청 응답의 `jobId + documentId`를 다른 후속 처리보다 먼저 저장하고, callback에서 `JOB_NOT_REGISTERED`·`DOCUMENT_NOT_FOUND`·`JOB_DOCUMENT_MISMATCH`를 구분하며 mismatch를 계약 오류로 경고하고, `jobId + status` 멱등성과 `sourceHash` 최신성을 검증하도록 `backend/src/main/java/com/example/ssafesta/internal/ai/AiDocumentStatusController.java` 및 `backend/src/main/java/com/example/ssafesta/internal/ai/AiDocumentStatusService.java`에 구현한다
- [ ] T045 [P] [US2] [AI] Provider별 endpoint·bucket을 사용하는 boto3 기반 R2·MinIO storage adapter와 문서별 원본 metadata 검증을 `festa-ai/app/providers/s3_compatible_storage.py`에 구현한다
- [ ] T046 [P] [US2] [AI] PDF 텍스트·페이지 추출·텍스트 없음 판정과 MD·TXT의 UTF-8 디코딩을 확장자 기준으로 분기해 `festa-ai/app/providers/document_parser.py`에 구현한다
- [X] T047 [P] [US2] [AI] batch Embedding 호출과 결과 1536차원 검증을 `festa-ai/app/providers/managed_embedding.py`에 구현한다
- [ ] T048 [P] [US2] [AI] `S15P21A604-92`의 실측값을 초기 배포 설정에 반영하고 설정 기반 chunk size·overlap과 page/section 추적을 `festa-ai/app/services/text_chunker.py`에 구현한다
- [ ] T049 [US2] [AI] 영속된 Spring snapshot과 원본 metadata·SHA-256을 검증하고 문서별 `storageProvider + bucket + objectKey` 저장소에서 다운로드하며, 검색 불가 Chunk 교체와 Job `SUCCEEDED`를 AI DB 단일 트랜잭션으로 처리하는 파이프라인을 `festa-ai/app/services/document_processing_service.py`에 구현한다
- [ ] T050 [P] [US2] [AI] snapshot 기반 멱등 처리 요청, 반복 204 cleanup, 내부 Job 상태 조회 endpoint를 `festa-ai/app/api/v1/documents.py`에 구현한다 (`S15P21A604-121`: 멱등 처리 요청 완료, cleanup·상태 조회는 후속)
- [ ] T051 [US2] [AI] `FOR UPDATE SKIP LOCKED` pickup과 30초 heartbeat 및 소유권 상실 시 결과 폐기를 `festa-ai/app/workers/document_worker.py`에 구현한다
- [ ] T052 [US2] [AI] 기동 즉시 및 60초 주기의 만료 Job 회수와 재시도 상한 처리를 `festa-ai/app/services/job_recovery_service.py`에 구현한다
- [ ] T053 [P] [US2] [AI] `INTERNAL_AI_TO_SPRING_TOKENS` 첫 값을 Bearer로 부착하는 `PROCESSING/READY/FAILED/DISABLED` callback client와 401·409 및 404 원인 코드 처리·사용자 오류 정제, READY 204 이후 해당 Job Chunk의 `searchable=true` 전환을 `festa-ai/app/services/spring_status_callback.py`에 구현한다
- [ ] T054 [US2] [AI] 미전달 terminal callback 중 `callback_delivered_at`과 `callback_terminated_at`이 모두 비어 있는 Job만 재전송하고, `JOB_NOT_REGISTERED`를 1초·3초·10초 간격으로 최대 3회 재시도하며, 소진 및 영구 404는 별도 종료 시각·사유를 기록하고 stale 409는 기존 합의대로 종료하도록 `festa-ai/app/services/callback_reconciliation_service.py`에 구현한다
- [ ] T055 [US2] [AI] API lifespan에서 Worker·sweeper·callback reconciliation을 시작하고 안전하게 종료하도록 `festa-ai/app/main.py`에 연결한다
- [ ] T056 [P] [US2] [FE] `EXPIRED`를 포함한 Spring 업로드 URL·완료·교체·상태 조회 API client와 DTO를 `festa-frontend/src/features/ai-agent/api/aiDocumentApi.ts`에 구현한다
- [ ] T057 [US2] [FE] Spring이 발급한 S3-compatible URL의 직접 업로드 진행률과 `QUEUED/PROCESSING/READY/FAILED/DISABLED/EXPIRED` 상태를 표시하고 `EXPIRED`를 업로드 만료로 안내하도록 `festa-frontend/src/features/ai-agent/components/DocumentManager.tsx`에 구현한다

**Checkpoint**: 문서 업로드부터 READY까지의 P0 흐름, 강제 종료 복구, callback 복구 및 Vector 격리 Critical Test를 독립적으로 통과한다.

---

## Phase 5: User Story 3 — 문서를 관리한다 (Priority: P1)

**Goal**: 소유자가 파일명·상태·업로드 시각·실패 사유를 조회하고 삭제한 문서를 즉시 검색 대상에서 제외한다.

**Independent Test**: 문서 목록에 마지막 Spring 상태가 표시되고 AI 서비스 중단 중에도 조회가 가능하다. 삭제 후 Business DB Document는 제거되고 cleanup 재전송 뒤 검색 가능 Chunk는 0건이며, Job은 `CANCELLED` 감사 이력으로 남고 문서별 Provider 원본 삭제는 재시도된다.

### Tests for User Story 3

- [ ] T058 [P] [US3] [BE] AI 서비스 중단 상태의 목록 조회와 타 부스 접근 거부 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiDocumentQueryIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T059 [P] [US3] [BE] 사용자 삭제와 `EXPIRED` 24시간 경과 시 즉시 검색 제외·cleanup 영속 재시도·문서별 Provider `DeleteObject` 재시도 테스트를 `backend/src/test/java/com/example/ssafesta/ai/AiDocumentDeletionIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T080 [P] [US3] [BE] 문서 삭제·비활성화 시 FastAPI cleanup을 발행하고 장애 시 영속 재시도하며, 전체 활성 문서 inventory를 생성하는 통합 테스트를 `backend/src/test/java/com/example/ssafesta/internal/ai/AiDocumentCleanupIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T081 [P] [US3] [AI] cleanup 반복 호출·처리 경쟁·FastAPI 재시작 후 Chunk 0건과 Business/AI inventory 불일치 reconciliation 테스트를 `festa-ai/tests/integration/test_document_cleanup.py`에 먼저 작성하고 실패를 확인한다
- [ ] T060 [P] [US3] [FE] 목록·실패 사유·삭제 확인 UI 테스트를 `festa-frontend/src/features/ai-agent/components/DocumentList.test.tsx`에 먼저 작성하고 실패를 확인한다

### Implementation for User Story 3

- [ ] T061 [US3] [BE] Spring DB만 사용해 문서 목록과 마지막 상태를 제공하는 조회 로직을 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentQueryService.java`에 구현한다
- [ ] T062 [US3] [BE] 소유권 검증 후 Business DB Document를 삭제하고 FastAPI cleanup outbox와 문서별 Provider·bucket·objectKey 삭제 작업을 기록하는 로직을 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentDeletionService.java`에 구현한다
- [ ] T063 [US3] [BE] 5분 주기로 미완료 문서를 `EXPIRED`로 전환하고 24시간 유예 후 문서별 Provider 원본을 HEAD 없이 삭제하며, 실패 시 `EXPIRED + storageProvider + storageBucket + objectKey`를 유지해 재시도하는 작업을 `backend/src/main/java/com/example/ssafesta/ai/ObjectDeletionWorker.java`에 구현한다. 배포 전 Infra와 각 Provider 자격증명의 대상 bucket/prefix `DeleteObject` 최소 권한을 확인한다
- [ ] T064 [US3] [BE] 목록·상태·삭제 endpoint를 `backend/src/main/java/com/example/ssafesta/ai/AiDocumentController.java`에 연결한다
- [ ] T065 [P] [US3] [FE] 문서 목록·상태별 한국어 표시·실패 사유·삭제 확인 UI를 `festa-frontend/src/features/ai-agent/components/DocumentList.tsx`에 구현한다
- [ ] T082 [US3] [BE] 문서 삭제·비활성화 cleanup outbox/retry와 단일 Business DB snapshot에서 전체 활성 문서 inventory를 만들어 FastAPI reconciliation API로 보내는 client를 `backend/src/main/java/com/example/ssafesta/internal/ai/`에 구현해 T080을 통과시킨다
- [ ] T083 [US3] [AI] AI DB 트랜잭션으로 활성 Job 취소·Chunk 삭제를 수행하는 멱등 cleanup service와 inventory reconciliation service를 `festa-ai/app/services/document_cleanup_service.py`, `festa-ai/app/services/document_inventory_reconciliation_service.py`에 구현해 T081을 통과시킨다

**Checkpoint**: FastAPI가 중단돼도 Spring에서 문서를 관리할 수 있고, cleanup 재전송·reconciliation 후 삭제·비활성화 문서의 검색 가능 Chunk가 0건이다.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 보안·성능·운영·문서 검증을 완료한다.

- [ ] T066 [P] [AI] 사용자 응답과 로그에서 Secret·Stack Trace·object key·Provider 원문이 노출되지 않는지 `festa-ai/tests/integration/test_sensitive_data_redaction.py`로 검증한다
- [ ] T067 [P] [AI] Agent당 문서 10개·100MB에서 검색 P95 1초와 정답 근거 Top-K 포함률 95%를 `festa-ai/tests/performance/test_rag_retrieval.py`로 검증한다
- [ ] T068 [P] [AI] Job 상태별 수·queue age·처리 시간·lease 회수·callback 지연 지표를 `festa-ai/app/core/metrics.py`에 구현한다
- [ ] T069 [P] [AI] health/live·ready endpoint와 설정·DB·Worker 준비 상태를 `festa-ai/app/api/health.py`에 구현한다
- [ ] T070 [P] [AI] 중앙 Jenkins의 Component Pipeline Contract가 호출할 FastAPI `ci/validate·test·build·package·verify` adapter를 `festa-ai/ci/`에 구성하고 단위·계약·통합·장애 주입·격리 테스트와 machine-readable report를 연결한다
- [ ] T071 [P] [BE] 중앙 Jenkins의 Component Pipeline Contract가 호출할 Backend `ci/validate·test·build·package·verify` adapter를 `backend/ci/`에 구성하고 Agent·문서·callback 통합 테스트와 machine-readable report를 연결한다
- [ ] T072 [AI] Worker 중단·rollback·callback 적체·DEAD 증가와 방향별 Service Token `[old] → [old,new] → [new,old] → [new]` 회전·롤백 대응 절차를 `festa-ai/docs/document-pipeline-runbook.md`에 작성한다
- [ ] T073 [AI] `specs/007-ai-agent-document/quickstart.md`의 migration, DB CONNECT matrix, snapshot 처리, 중복, 강제 종료, callback 복구·404 원인별 재시도/종료, 청크 교체, cleanup·inventory reconciliation, stale 개정본, 내부 인증·회전, 수동 Provider 전환, 격리 검증을 순서대로 실행하고 결과를 `festa-ai/tests/validation/quickstart-results.md`에 기록한다
- [ ] T074 [INFRA] FastAPI를 중단한 상태에서도 로그인·부스·월드 smoke와 Spring 문서 목록·마지막 상태 조회가 성공하는 AI 장애 격리 검증을 `infra/tests/integration/test-ai-failure-isolation.sh`에 추가한다
- [ ] T075 [P] [INFRA] 방향별 Service Token Secret Reference와 내부 전용 network 주입을 `infra/deploy/compose/dev/`에 구성하고, Security Group·public route 차단 절차를 `infra/deploy/runbooks/internal-api-boundary.md`에 명시하며 외부 요청 차단 증거를 `infra/tests/security/test-internal-api-boundary.sh`에 기록한다
- [ ] T076 [P] [INFRA] 자동 Provider 변경 없이 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE`를 운영자 승인으로만 전환하고 MinIO 비공개 접근·PUT/HEAD/본문/SHA-256/CORS를 검증하는 스크립트와 runbook을 `infra/deploy/scripts/storage-failover.sh` 및 `infra/deploy/runbooks/storage-failover.md`에 구현한다. `R2_RECONCILING` 중 신규 업로드 허용 여부와 장애 자동 판정 수치는 `docs/26_팀_결정_필요사항.md`에 등록된 P0 범위 제외 항목이므로 구현하지 않는다
- [ ] T077 [P] [BE] Infra 전용 토큰 정상·누락·오류·AI 방향 토큰 재사용 401, `runId + documentId` 멱등 저장, `VERIFIED`만 Document Provider 변경, `MISMATCH/MISSING` 로그 전용 처리를 `backend/src/test/java/com/example/ssafesta/internal/storage/StorageReconciliationControllerIntegrationTest.java`에 먼저 작성하고 실패를 확인한다
- [ ] T078 [BE] Infra 전용 `INTERNAL_INFRA_TO_SPRING_TOKENS`로 인증하는 `POST /internal/storage/reconciliation-runs`를 [spring-storage-reconciliation-api.yaml](../../specs/007-ai-agent-document/contracts/spring-storage-reconciliation-api.yaml) 계약대로 구현해 T077을 통과시킨다. `runId + documentId` 멱등 저장, `VERIFIED` 객체만 Document `storage_provider` 반영, `MISMATCH`/`MISSING`은 로그만 적재한다
- [ ] T079 [P] [INFRA] reconcile 스크립트가 검증 결과를 T078 endpoint로 전송하도록 `infra/deploy/scripts/storage-failover.sh`에 연결하고, Infra→Spring 토큰 누락·오류·AI 방향 토큰 재사용이 401로 거부되는지 `infra/tests/security/test-internal-api-boundary.sh`에 검증 케이스를 추가한다

---

## Dependencies & Execution Order

### Planning-to-Implementation Gate

1. `S15P21A604-96`에서 이 작업 목록과 의존 순서를 확정한다.
2. GitLab Work Item #102 기준으로 방향별 Service Token을 Secret 저장소에 준비하고, 송신자·수신자 주입 및 Security Group·public route 차단 담당을 BE·AI·Infra가 확인한다.
3. `S15P21A604-92` 미니 스파이크를 수행해 청킹·Top-K·처리 시간·비용 실측값을 `specs/007-ai-agent-document/research.md`에 기록한다.
4. 실측값을 T048의 초기 배포 설정과 T067의 성능·품질 검증 입력에 반영한다.
5. 이미 완료된 Phase 1 스캐폴드는 유지하고, 이후 Phase 2와 사용자 스토리 구현을 시작한다.

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
BE: T022 → T024/T025 → T026 → T027
FE: T023 → T028 → T029
```

### User Story 2

```text
BE: T030/T031 → T039/T040/T042 → T041/T043/T044
AI: T032~T037 → T045~T048/T050/T053 → T049/T051/T052/T054/T055
FE: T038 → T056 → T057
```

### User Story 3

```text
BE: T058/T059 → T061/T062 → T063/T064
FE: T060 → T065
```

---

## Implementation Strategy

### MVP First

1. Phase 1과 Phase 2를 완료한다.
2. US1의 Agent 생성·수정·권한 검증을 완료한다.
3. US2를 fake object storage·Embedding·Spring callback으로 먼저 완성한다.
4. 실제 S3-compatible Provider와 Spring을 연결하고 Worker 강제 종료 및 Vector 격리 Critical Test를 통과한다.
5. US1+US2를 P0 MVP로 배포한다.

### Incremental Delivery

1. **Foundation**: FastAPI scaffold, DB 역할·migration, 공통 계약
2. **MVP-A**: AI 직원 생성·수정
3. **MVP-B**: PDF 업로드·비동기 처리·READY 상태·RAG 청크
4. **P1**: 문서 목록·실패 사유·삭제와 문서별 Provider 정리
5. **운영화**: 장애 주입, 보안, 성능·품질, 지표와 runbook

### Part Coordination

- Backend는 Spring 영구 상태와 공개 API·활성 쓰기 Provider 업로드·내부 callback을 담당한다.
- AI는 Job·Worker·파싱·청킹·Embedding·Chunk·callback 재전송을 담당한다.
- Frontend는 Spring 공개 API만 호출하며 FastAPI 내부 진단 API를 호출하지 않는다.
- 문서 삭제·비활성화는 Spring의 멱등 cleanup 발행과 FastAPI AI DB 정리로 처리한다. database 간 FK cascade는 사용하지 않으며 inventory reconciliation으로 고아 데이터를 수렴시킨다.
- 신규 업로드의 활성 쓰기 Provider는 Spring이 결정하고, FastAPI는 문서별 Provider를 기준으로 R2·MinIO 중 읽기 adapter를 선택한다. 자동 Provider 변경·이중 쓰기·자동 원복은 구현하지 않는다.
- 방향별 Service Token 값은 해당 방향의 송신자와 수신자에 주입하며, AI·BE는 애플리케이션 검증과 부착을, Infra는 Secret 주입과 Security Group·public route 차단을 담당한다.

---

## Notes

- `[P]`는 같은 파일을 동시에 수정하지 않고 미완료 작업에 의존하지 않는 작업만 표시한다.
- FastAPI runtime/migration role은 Business DB에 CONNECT하지 않는다.
- 모든 Vector 검색은 `booth_id + agent_id + searchable = true` 필터를 강제한다.
- Secret은 저장소에 커밋하지 않고 `.env.example`에는 키 이름만 둔다.
- 적용된 Flyway·Alembic migration은 수정하지 않고 다음 migration으로 forward-fix한다.
