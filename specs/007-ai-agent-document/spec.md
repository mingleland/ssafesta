# Feature Specification: AI 직원 / 문서 파이프라인

**Spec**: `007-ai-agent-document`
**Created**: 2026-08-12
**Status**: Ready for Planning
**주 담당**: AI (FastAPI) + Backend(메타데이터·저장)
**선행 spec**: 001, 004
**근거 문서**: docs/02 §2.6(AI-01~02), `.specify/memory/constitution.md` Article IV

---

## User Scenarios & Testing

### User Story 1 — AI 직원을 만든다 (Priority: P0)

부스 소유자가 AI 직원의 이름·역할·말투·지시문을 설정해 저장한다.

**Why this priority**: 008(AI 상담)의 전제. 이게 없으면 대화할 대상이 없다.

**Independent Test**: AI 직원 생성 → 설정 저장 → 다시 열었을 때 설정이 유지됨.

**Acceptance Scenarios**:

1. **Given** 부스 소유자, **When** AI 직원을 만들면, **Then** 이름·역할·지시문이 저장된다.
2. **Given** 저장된 AI 직원, **When** 설정을 수정하면, **Then** 변경이 반영된다.
3. **Given** 다른 부스의 소유자, **When** 남의 AI 직원에 접근하면, **Then** 거부된다.

### User Story 2 — 문서를 올리면 AI가 검색에 사용할 수 있다 (Priority: P0)

소유자가 문서를 업로드하면 처리 과정을 거쳐 해당 AI 직원의 RAG 검색 범위에서 사용할 수 있게 된다.

**Why this priority**: RAG의 입력 준비 단계다. 실제 질문·답변 생성과 SSE 전달은 spec 008이 담당한다.

**Independent Test**: 문서 업로드 → 상태가 처리중→준비완료로 변함 → 해당 `boothId + agentId` 범위 검색의 Top-K에 문서 조각이 포함됨.

**Acceptance Scenarios**:

1. **Given** AI 직원이 있는 소유자, **When** 문서를 업로드하면, **Then** 업로드가 접수되고 처리 상태를 볼 수 있다.
2. **Given** 처리 중인 문서, **When** 상태를 조회하면, **Then** 진행 상황(대기/처리중/준비완료/실패/업로드 만료)을 알 수 있다.
3. **Given** 처리에 실패한 문서, **When** 상태를 보면, **Then** **실패 사유**를 알 수 있다.
4. **Given** 문서가 처리 중, **When** 사용자가 다른 기능을 쓰면, **Then** 정상 동작한다 (비동기).
5. **Given** 준비완료된 문서, **When** 해당 `boothId + agentId` 범위로 검색하면, **Then** 관련 문서 조각이 Top-K 결과에 포함된다.
6. **Given** 문서 처리 중 실행 서버가 중단됨, **When** 서비스가 복구되면, **Then** 중단된 작업은 자동으로 재시도되거나 재시도 상한 초과 사유와 함께 실패로 종료된다.
7. **Given** AI 처리 서비스가 일시적으로 응답하지 않음, **When** 소유자가 문서 목록과 상태를 조회하면, **Then** 기존 문서 상태를 계속 확인할 수 있다.
8. **Given** 업로드를 완료하지 않은 문서, **When** 생성 후 1시간이 지나면, **Then** 문서는 `EXPIRED`로 표시되고 RAG 처리 대상에 포함되지 않는다.
9. **Given** `EXPIRED` 전환 후 24시간 안에 업로드 완료를 요청함, **When** 원본 객체가 남아 있으면, **Then** 동일 문서가 `QUEUED`로 복구되어 정상 처리된다.

### User Story 3 — 문서를 관리한다 (Priority: P1)

**Acceptance Scenarios**:

1. **Given** 업로드한 문서 목록, **When** 조회하면, **Then** 파일명·상태·업로드 시각이 보인다.
2. **Given** 등록된 문서, **When** 삭제하면, **Then** 이후 답변에 그 문서 내용이 사용되지 않는다.

### Edge Cases

- MVP는 PDF·MD·TXT를 허용하며 파일당 20MB를 초과하면 명확한 사유와 함께 거부한다.
- 텍스트를 추출할 수 없는 스캔 이미지 PDF는 OCR 지원 범위 밖이므로 `FAILED`와 구체적인 실패 사유를 제공한다.
- 같은 문서를 두 번 올린 경우 동일 AI 직원에 등록된 활성 문서와 파일 SHA-256이 같으면 중복으로 판정하고 재처리하지 않는다. 수정본 교체는 기존 문서를 지정하는 별도 교체 요청으로 처리한다.
- 업로드 URL은 발급 후 15분간 유효하다. 문서 생성 후 1시간 동안 업로드가 완료되지 않으면 `EXPIRED`로 전환하고, 전환 후 24시간 동안 늦은 완료 요청을 복구할 수 있도록 원본을 보존한다. 이후에도 미완료면 Spring이 원본 삭제를 재시도한다.
- `EXPIRED` 문서의 늦은 완료 요청에서 원본이 남아 있으면 `QUEUED`로 복구하고, 이미 삭제되었으면 새 업로드 권한을 받도록 안내한다.
- 처리 중 임대가 만료되면 문서 원본·메타데이터는 보존하고 Spring은 `DocumentStatus`를 `DISABLED`로 전환한 뒤 멱등 cleanup을 발행한다. FastAPI는 실행 중인 `JobStatus`를 `CANCELLED`로 전환하고 Chunk를 제거하며, 늦은 `READY` callback은 Spring의 최신 상태 검증에서 거부한다. 재임대 후 활성화할 때는 `QUEUED`부터 다시 처리한다.
- 처리 도중 서버가 재시작되거나 Worker heartbeat가 끊기면 만료된 실행 작업을 회수해 재시도한다. 최대 3회의 재시도 후에도 완료하지 못하면 실행 작업은 `DEAD`, 문서는 정제된 실패 사유와 함께 `FAILED`가 되며 영원히 `PROCESSING`에 머물지 않는다.
- 문서가 많은 경우에도 검색 전에 `boothId + agentId + READY`로 범위를 제한한다. AI 직원당 문서는 최대 10개·총 100MB로 제한하고, 최대 허용량에서 검색 응답 P95 1초 이하 및 정답 근거 문서의 Top-K 포함률 95% 이상을 검증한다.
- 내부 Service Token 교체 중에는 수신자가 기존·신규 토큰을 먼저 함께 허용한 뒤 송신 토큰을 전환하고, 안정화 확인 후 기존 토큰을 제거해 배포 순서 차이로 `401`이 발생하지 않게 한다.
- R2 장애 시 자동으로 MinIO로 전환하거나 양쪽에 동시에 쓰지 않는다. 운영자가 검증·승인한 뒤 활성 **쓰기** Provider만 바꾸며, 기존 문서는 문서별 `storageProvider + bucket + objectKey`가 가리키는 저장소에서 계속 읽는다.
- 업로드를 재개하려는 동안 활성 쓰기 Provider가 바뀌었으면 기존 미완료 문서를 `EXPIRED`로 전환하고 새 문서·새 object key로 업로드를 시작한다.
- 저장소 장애로 `FAILED`가 된 문서는 저장소가 복구돼도 자동으로 다시 처리되지 않는다. reconcile이 끝난 뒤 명시적 재처리 요청을 받아야 새 Job이 시작된다.
- Business DB의 문서가 삭제되거나 비활성화될 때 AI DB가 일시적으로 응답하지 않으면 Spring은 cleanup 요청을 재시도하며, FastAPI는 같은 요청을 여러 번 받아도 동일한 정리 결과를 반환한다.
- Business DB와 AI DB 사이에는 FK·cascade가 없으므로 주기적 reconciliation이 Business DB에 없는 AI Job·Chunk와 `READY` 문서에 대응하지 않는 Chunk를 찾아 정리 또는 경고해야 한다.

---

## Requirements

### Functional Requirements

- **FR-001**: 부스 소유자는 AI 직원을 생성·수정할 수 있어야 한다.
- **FR-002**: AI 직원은 이름·역할·말투·지시문을 가져야 한다.
- **FR-003**: 다른 부스의 AI 직원에 접근할 수 없어야 한다.
- **FR-004**: 소유자는 AI 직원에 문서를 업로드할 수 있어야 한다.
- **FR-005**: 문서 처리는 **비동기**여야 하며 다른 기능을 막아서는 안 된다.
- **FR-006**: 문서는 **`DocumentStatus`(`QUEUED`/`PROCESSING`/`READY`/`FAILED`/`DISABLED`/`EXPIRED`)** 를 가져야 하고 조회할 수 있어야 한다. `EXPIRED`는 사용자에게 **업로드 만료**로 표시한다.
- **FR-007**: 실패한 문서는 사용자가 이해하고 대응할 수 있도록 정제된 **실패 사유**를 제공해야 하며, 내부 예외·Stack Trace·외부 Provider 원문 오류를 노출해서는 안 된다.
- **FR-008**: 처리된 문서 조각에는 **boothId, agentId, 임베딩 모델 식별자**가 기록되어야 한다 (헌법 18조).
- **FR-009**: 임베딩 차원은 **1536으로 고정**한다 (헌법 18조).
- **FR-010**: 문서 원본은 **Cloudflare R2(S3-compatible)** 를 기본으로 사용하고 장기 장애 시 운영자 승인 기반의 단일 노드 MinIO fallback을 적용한다. 자동 failover·이중 쓰기·자동 원복은 금지하며, 문서 메타데이터의 Source of Truth는 **Spring**이 소유하고 처리·검색은 FastAPI가 담당한다 (헌법 1조).
- **FR-011**: MVP는 PDF·MD·TXT를 허용하며 파일당 최대 크기는 20MB다. 허용 형식과 크기를 초과하면 명확한 사유와 함께 거부해야 한다.
- **FR-012**: 소유자는 문서를 삭제할 수 있어야 하며, 삭제 후 답변에 사용되지 않아야 한다.
- **FR-013**: 처리 실패나 중단이 **월드·부스·비AI 기능을 중단시켜서는 안 된다** (헌법 3조).
- **FR-014**: LLM/Embedding 호출은 **어댑터 뒤에** 두어 제공자 교체가 구현 교체로 끝나야 한다 (헌법 15조).
- **FR-015**: 임대 만료 시 문서 원본·메타데이터는 보존하되 문서를 `DISABLED`, 실행 중인 처리 작업을 `CANCELLED`로 전환해야 하며, 만료된 문서가 `READY`로 전환되어서는 안 된다.
- **FR-016**: 문서 처리 작업은 **`JobStatus`(`QUEUED`/`RUNNING`/`RETRY_WAIT`/`SUCCEEDED`/`DEAD`/`CANCELLED`)** 를 가져야 한다.
- **FR-017**: RAG 검색은 `boothId + agentId`와 AI DB의 검색 가능 projection으로 범위를 제한해야 한다. projection은 Spring이 `READY` callback을 수락한 뒤에만 활성화되고 cleanup 시 제거되어 Business DB `DocumentStatus.READY`와 같은 검색 경계를 보장해야 한다.
- **FR-018**: AI 직원 한 명이 등록할 수 있는 문서는 최대 10개, 총 원본 크기는 100MB로 제한해야 한다.
- **FR-019**: 동일 AI 직원의 활성 문서 중 파일 SHA-256이 같은 문서가 있으면 중복으로 판정하고 재처리하지 않아야 한다. 수정본 교체는 기존 `documentId`를 지정하는 명시적 요청으로 처리해야 한다.
- **FR-020**: 청킹 크기와 overlap은 배포 설정으로 조정 가능한 튜닝 값이어야 하며 spec에 고정하거나 코드에 하드코딩하지 않아야 한다.
- **FR-021**: 처리 요청은 실행 전에 영속화해야 한다. 처리 서버가 재시작되거나 실행 작업의 heartbeat가 만료되면 작업을 회수해 최대 3회 재시도하고, 재시도 상한을 초과하면 실행 작업을 `DEAD`, 문서를 `FAILED`로 전환해야 한다.
- **FR-022**: MVP에서는 스캔 이미지 PDF의 OCR을 지원하지 않으며, 텍스트를 추출할 수 없으면 구체적인 실패 사유와 함께 `FAILED`로 전환해야 한다.
- **FR-023**: 사용자에게 노출되는 문서 상태와 내부 실행 작업 상태는 분리해야 하며, AI 처리 서비스가 중단되어도 사용자는 문서 목록과 마지막 상태를 조회할 수 있어야 한다.
- **FR-024**: 실행 작업을 재처리할 때 기존 문서 조각이 남거나 중복되지 않아야 하며, 완료된 새 조각 집합만 검색에 사용해야 한다.
- **FR-025**: 동일 문서에 활성 실행 작업은 하나만 존재해야 하며, 중복 처리 요청에는 기존 활성 작업을 반환해야 한다.
- **FR-026**: 업로드 URL의 기본 유효시간은 15분이며, 생성 후 1시간 동안 완료되지 않은 문서는 `EXPIRED`로 전환해야 한다. 만료 판정 주기는 5분을 기본값으로 하되 운영 설정으로 조정할 수 있어야 한다.
- **FR-027**: `EXPIRED` 전환 후 24시간 동안 원본을 보존해야 한다. 이 기간의 늦은 완료 요청은 원본이 있으면 `QUEUED`로 복구하고, 원본이 없으면 새 업로드 권한이 필요함을 알려야 한다.
- **FR-028**: Spring은 `EXPIRED` 전환 후 24시간이 지난 미완료 원본을 R2에서 삭제해야 한다. 삭제 실패 시 `EXPIRED` 상태와 object key를 유지하고 성공할 때까지 재시도해야 한다.
- **FR-029**: Spring↔FastAPI 내부 호출은 방향별로 분리된 Bearer Service Token을 사용해야 한다. 수신자는 네트워크 차단 설정과 독립적으로 토큰을 항상 검증하고, 누락·오류·반대 방향 토큰은 `401`로 거부해야 한다. Secret은 해당 방향의 송신자와 수신자에만 주입하고 저장소·로그에 기록해서는 안 된다.
- **FR-030**: 각 문서는 `storageProvider(R2/MINIO_LOCAL) + bucket + objectKey`를 가져야 한다. 신규 업로드는 Spring의 활성 쓰기 Provider를 사용하고, FastAPI는 전역 활성 Provider가 아니라 문서 메타데이터의 저장소 식별자로 원본을 읽어야 한다.
- **FR-031**: P0의 R2 장애 probe는 운영 판단을 위한 evidence만 수집하며 `UPLOAD_BLOCKED` 전환은 운영자가 수동으로 수행해야 한다. 자동 장애 판정과 자동 상태 전환은 후속 이슈에서 기준이 확정될 때까지 구현하지 않는다. MinIO 전환과 R2 원복은 운영자의 검증·승인이 있어야 하며, 검증된 객체만 reconcile 후 Provider를 R2로 변경해야 한다.
- **FR-032**: 업로드 미완료 문서를 재개할 때 활성 쓰기 Provider가 기존 문서의 Provider와 다르면 기존 문서를 `EXPIRED`로 전환하고 새 문서·새 object key로 시작해야 한다.
- **FR-033**: 저장소 일시 장애는 기존 1·5·15분 정책으로 최대 3회 재시도하고, 이후 Job을 `DEAD`, 문서를 `FAILED`로 종료해야 한다. 원본 유실 방지나 MinIO 고가용성을 보장하는 것으로 표현해서는 안 된다.
- **FR-034**: 저장소 장애로 `DEAD → FAILED`가 된 문서는 저장소 복구 후 자동으로 재처리해서는 안 된다. 재처리는 reconcile 완료 확인 후 명시적 요청으로만 새 Job을 만들어야 하며, `DEAD`는 Spring 콜백 경계로 노출하지 않고 AI 내부 상태로만 유지해야 한다.
- **FR-035**: Infra가 실행한 reconcile 결과는 Spring이 소유하는 `storage_reconciliation_log`에 `runId + documentId` 기준 멱등으로 적재해야 하며, size·감지 MIME·SHA-256이 모두 일치해 `VERIFIED`로 판정된 객체만 문서의 `storageProvider`를 변경해야 한다. 전달 경로는 Infra 전용 Bearer Service Token으로 인증하고 AI 방향 토큰과 credential·scope를 분리해야 한다.
- **FR-036**: Spring 상태 callback의 `404`는 원인 코드로 구분해야 한다. `JOB_NOT_REGISTERED`는 Spring이 처리 요청 응답의 `jobId`를 먼저 저장한 뒤에도 발생할 수 있는 짧은 경합으로 보고 FastAPI가 1초·3초·10초 간격으로 최대 3회 재시도한다. `DOCUMENT_NOT_FOUND`와 `JOB_DOCUMENT_MISMATCH`는 재시도하지 않고 종료하며, Spring은 `JOB_DOCUMENT_MISMATCH`를 계약 오류로 경고 기록해야 한다. FastAPI는 정상 전달 시각과 재시도 종료 시각·사유를 별도로 영속화해야 한다.
- **FR-037**: PostgreSQL은 환경별로 하나의 인스턴스를 공유하되 Business DB(`festa_{env}_business`)와 AI DB(`festa_{env}_ai`)를 분리해야 한다. `ai_agents`·`ai_documents`·`storage_reconciliation_log`는 Business DB, `document_jobs`·`document_chunks`는 AI DB가 소유한다.
- **FR-038**: Spring runtime/migration role은 AI DB에, FastAPI runtime/migration role은 Business DB에 접속할 수 없어야 한다. DB 간 FK·cross-database query·`ON DELETE CASCADE`에 의존해서는 안 된다.
- **FR-039**: Spring은 소유권·임대·문서 상태를 검증한 뒤 FastAPI 처리 요청에 `documentId`, `boothId`, `agentId`, 파일명·형식·크기·SHA-256, `storageProvider + bucket + objectKey` snapshot을 전달해야 한다. FastAPI는 Business DB를 직접 조회하지 않고 이 snapshot을 Job에 영속화해 처리해야 한다.
- **FR-040**: 기존 Chunk 전량 교체와 Job `SUCCEEDED` 전환은 AI DB의 단일 트랜잭션으로 처리해야 한다. Spring `READY` 전환은 커밋 이후 멱등 callback으로 수행하며 분산 트랜잭션으로 묶지 않는다.
- **FR-041**: Spring은 문서 삭제·비활성화 시 FastAPI에 멱등 cleanup을 발행하고 전달 실패를 재시도해야 한다. FastAPI는 해당 문서의 활성 Job을 취소하고 Chunk를 제거해야 하며, 주기적 reconciliation은 양 DB의 고아·누락 상태를 탐지해 cleanup 재발행 또는 운영 경고로 수렴시켜야 한다.
- **FR-042**: 새 Chunk는 AI DB 트랜잭션에서 검색 불가 상태로 저장해야 한다. Spring이 `READY` callback을 수락한 뒤에만 해당 Chunk를 검색 가능으로 전환하고, callback 미전달·실패·삭제·비활성화 상태의 Chunk는 검색 결과에 포함해서는 안 된다.

### State Model

```yaml
DocumentStatus: QUEUED / PROCESSING / READY / FAILED / DISABLED / EXPIRED
JobStatus: QUEUED / RUNNING / RETRY_WAIT / SUCCEEDED / DEAD / CANCELLED
```

`DocumentStatus`는 사용자에게 보이는 문서의 처리·검색 가능 상태이고, `JobStatus`는 해당 문서를 처리하는 개별 실행 작업의 상태다. RAG 검색에는 `DocumentStatus.READY`인 문서만 포함한다.

| DocumentStatus | 의미 |
|---|---|
| `QUEUED` | 문서 처리 대기 |
| `PROCESSING` | 파싱·청킹·임베딩 처리 중 |
| `READY` | 처리 완료, RAG 검색 가능 |
| `FAILED` | 처리 실패, 실패 사유 확인 가능 |
| `DISABLED` | 원본·메타데이터는 보존하지만 RAG 검색에서 제외 |
| `EXPIRED` | 업로드 미완료로 만료됨. RAG 처리·검색에서 제외되며 보존 기간 안에는 완료 요청으로 복구 가능 |

| JobStatus | 의미 |
|---|---|
| `QUEUED` | 실행 작업 대기 |
| `RUNNING` | Worker가 처리 중 |
| `RETRY_WAIT` | Worker heartbeat 만료 또는 일시 장애로 재시도 대기 |
| `SUCCEEDED` | 실행 작업 성공 |
| `DEAD` | 재시도 상한을 초과한 최종 실패 |
| `CANCELLED` | 부스 임대 만료 등 비즈니스 정책에 의해 실행 작업 취소 |

| JobStatus | DocumentStatus | 전환 의미 |
|---|---|---|
| `QUEUED` | `QUEUED` | 처리 요청 접수 |
| `RUNNING` | `PROCESSING` | Worker가 작업을 소유하고 처리 중 |
| `RETRY_WAIT` | `PROCESSING` | 재시도는 내부 상태로만 유지 |
| `SUCCEEDED` | `READY` | 조각 저장과 처리가 완료됨 |
| `DEAD` | `FAILED` | 재시도 상한 초과, 정제된 실패 사유 제공 |
| `CANCELLED` | `DISABLED` | 부스 임대 만료 등 정책으로 처리 및 검색 비활성화 |

### Key Entities

- **AI Agent**: AI 직원. 부스, 이름, 역할, 지시문, 생성 시각
- **Document**: 업로드된 문서. 부스, 에이전트, 파일명, 크기, SHA-256, object key, 상태, 실패 사유, 업로드 시각
- **Processing Job**: 문서 처리 실행 단위. 문서, 작업 상태, 재시도 횟수, Worker 소유권과 heartbeat 만료 시각, 다음 재시도 시각, 시작·종료 시각, 내부 실패 정보
- **Chunk**: 문서 조각. 문서, boothId, agentId, 텍스트, 임베딩 벡터, `embedding_model_id`
- **Storage Reconciliation Log**: R2 복구 검증 기록. runId, documentId, objectKey, sourceProvider, targetProvider, 기대/실측 size·type·sha256, status, attemptCount, failureReason, checkedAt, resolvedAt

---

## Success Criteria

- **SC-001**: AI 직원 생성부터 문서 업로드·`READY` 전환·해당 `boothId + agentId` 범위 검색까지의 E2E 검증이 성공한다.
- **SC-002**: 문서 처리 중에도 월드·부스·다른 기능이 100% 정상 동작한다.
- **SC-003**: 영원히 "처리중"에 머문 문서가 0건이다.
- **SC-004**: 다른 부스의 문서 조각이 검색된 사례가 **0건** (008의 Critical Test와 연동).
- **SC-005**: 정의된 문서 처리 실패 코드마다 비어 있지 않은 정제된 한국어 대응 메시지를 제공하고, 메시지에서 Secret·Stack Trace·object key·Provider 원문 오류 노출은 0건이다.
- **SC-006**: AI 직원당 최대 허용량(문서 10개·총 100MB)에서 검색 응답 시간은 P95 1초 이하다.
- **SC-007**: 사전에 정답 근거 문서를 지정한 품질 평가 질문에서 해당 문서가 Top-K에 포함되는 비율은 95% 이상이다.
- **SC-008**: 처리 서버 강제 종료 시험에서 중단된 작업의 100%가 자동 회수되어 재시도되거나 최종 실패로 종료된다.
- **SC-009**: AI 처리 서비스 중단 중에도 사용자는 기존 문서 목록과 마지막 처리 상태를 조회할 수 있다.
- **SC-010**: 업로드 미완료 문서는 생성 후 1시간과 다음 5분 판정 주기 안에 100% `EXPIRED`로 전환되고, 보존 기한이 지난 원본은 삭제되거나 재시도 대상으로 남는다.
- **SC-011**: 두 내부 API에서 유효한 방향 토큰만 허용하고 누락·오류·반대 방향 토큰은 100% `401`로 거부하며, `[old] → [old,new] → [new,old] → [new]` 회전 검증 중 정상 호출 실패는 0건이다.
- **SC-012**: R2 차단→운영자 승인→MinIO 전환→문서별 Provider 읽기→R2 reconcile→승인 원복 시험에서 자동 Provider 변경과 이중 쓰기는 0건이고, 검증되지 않은 객체의 Provider 변경은 0건이다.
- **SC-013**: 4개 runtime role × 4개 database CONNECT matrix에서 환경·서비스가 일치하는 대각선 연결만 성공하고, FastAPI가 Business DB를 조회한 호출은 0건이다.
- **SC-014**: 문서 삭제·비활성화 cleanup 재전송과 reconciliation 시험 후 Business DB에 없는 AI Chunk 및 비활성 문서의 검색 가능 Chunk가 0건이다.

---

## Clarifications

| # | 질문 | 담당 | 메모 |
|---|---|---|---|
| C-01 | 허용 형식과 최대 크기는? | AI + 기획 | **확정: MVP PDF·MD·TXT, 파일당 최대 20MB** (2026-08-26 후속 합의로 MD·TXT 추가, 스캔 이미지 PDF 텍스트 없음 처리는 PDF에만 적용) |
| C-02 | 청킹 파라미터(크기·겹침)는? | AI | **확정: 배포 설정으로 조정하는 튜닝 값, spec 미고정** |
| C-03 | 동일 문서 재업로드 정책은? | AI + 기획 | **확정: 동일 Agent의 SHA-256 중복은 재처리하지 않고, 수정본은 기존 `documentId`를 지정해 명시적으로 교체** |
| C-04 | 처리 큐를 도입하는가? | AI + Infra | **확정: P0 인프로세스 Worker + 영속 Job 저장소. heartbeat 30초, Worker lease 90초, sweeper 60초, 최대 3회 재시도(1·5·15분 대기). 부하 실측 후 SQS 검토. 세부 계약은 [Issue #11](https://github.com/kanghyunsoon/ssafesta/issues/11) 참조** |
| C-05 | AI 직원당 문서 수·총량 상한은? | AI + 기획 | **확정: 최대 10개·총 100MB** |
| C-06 | 스캔 PDF(OCR 필요)를 지원하는가? | AI | **확정: MVP 제외, 텍스트 추출 불가 시 구체적 실패 사유와 함께 `FAILED`** |
| C-07 | 문서 원본 저장 위치는? | BE + Infra | **확정: Cloudflare R2(S3-compatible) 우선, 장기 장애 시 운영자 승인 기반 단일 노드 MinIO fallback(S3-compatible fallback 아님, C-10 참조). 메타데이터 Source of Truth는 Spring** ([Issue #51](https://github.com/kanghyunsoon/ssafesta/issues/51), [GitLab Work Item #100](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/100)) |
| C-08 | 업로드 미완료 문서의 만료·정리 정책은? | BE + Infra + FE | **확정: 업로드 URL 15분, 생성 후 1시간에 `EXPIRED`, 전환 후 24시간 보존 뒤 Spring이 R2 원본 삭제·실패 재시도. `EXPIRED`는 업로드 만료로 표시** ([GitLab Work Item #84](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/84)) |
| C-09 | Spring↔FastAPI 내부 API를 어떻게 인증하고 회전하는가? | AI + BE + Infra | **확정: 방향별 Bearer Token 2종, Security Group과 독립적인 애플리케이션 검증, 콤마 목록 최대 2개, 첫 값 송신·전체 값 상수 시간 검증, 단계적 무중단 회전. mTLS는 P2** ([GitLab Work Item #102](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/102)) |
| C-10 | R2 장애 시 fallback·reconcile을 어떻게 운영하는가? | AI + BE + Infra | **부분 확정: 자동 failover·이중 쓰기·자동 원복 금지, 운영자 승인 수동 MinIO 전환, 문서별 Provider 읽기, 유한 Job 재시도(`DEAD`는 AI 내부 상태로만 유지), 저장소 복구 후 자동 재처리 없음(명시적 재처리 요청으로 새 Job), reconcile 결과는 Spring DB `storage_reconciliation_log`에 `runId + documentId` 멱등으로 적재하고 `VERIFIED` 객체만 문서 Provider 반영, 전달 경로는 #102 Service Token 방식을 재사용하되 Infra 전용 credential·scope로 분리, `STORAGE_UNAVAILABLE=503`(재시도 가능)·`STORAGE_QUOTA_EXCEEDED=507`(재시도 불가)로 분리. P0에서는 probe evidence만 수집하고 운영자가 `UPLOAD_BLOCKED`를 수동 적용한다. 미확정 2건(`R2_RECONCILING` 중 신규 업로드 허용 여부, R2 API 장애 자동 판정 수치)은 `docs/26_팀_결정_필요사항.md`에 등록하고 후속 이슈로 분리** ([GitLab Work Item #100](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/100)) |
| C-11 | PostgreSQL의 Business/AI 경계를 어떻게 나누는가? | AI + BE + Infra | **확정: Infra PostgreSQL Isolation and Backup Contract v1 채택. 환경별 단일 PostgreSQL 인스턴스 안에서 `festa_{env}_business`와 `festa_{env}_ai`를 별도 database·login role로 분리한다. Business DB는 `ai_agents`·`ai_documents`, AI DB는 `document_jobs`·`document_chunks`를 소유한다. 교차 DB FK·직접 조회는 금지하고 Spring이 검증한 처리 snapshot 및 멱등 cleanup/reconciliation API로 정합성을 맞춘다.** (S15P21A604-262) |
| C-12 | AI 직원의 `role_code`·`tone_code`·`response_length` 허용값은? | AI + BE | **확정: role 2종(`PROJECT_DOCENT`·`GUIDE`), tone 3종(`FRIENDLY` 기본·`PROFESSIONAL`·`ENTHUSIASTIC`), responseLength 3종(`SHORT` 1~3문장·`MEDIUM` 4~6문장 기본·`LONG` 7~12문장). 저장과 검증은 Spring, 해석은 FastAPI가 한다. **`tone`은 구현 후 튜닝 대상이다** — AI 파트가 프롬프트 템플릿 수정으로 먼저 흡수하되 불가피하면 값 자체가 바뀔 수 있고(#112 AI 회신), 지금은 3개를 증감시킬 근거가 없어 3종으로 간다. 값 **추가**는 가산적이라 언제든 되지만 **삭제·변경**은 저장된 row를 무효로 만드므로 그때는 마이그레이션을 함께 낸다. `responseLength → max_tokens` 매핑은 AI 파트 소유이며 `SHORT` 200·`MEDIUM` 400·`LONG` 800을 제안값으로 두되 모델 확정 후 재검증한다 — Spring은 어휘만 저장하고 토큰 수를 저장하지 않는다** ([GitLab Issue #112](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/112)) |
| C-13 | 부스당 AI 직원 수 상한은? | BE + 기획 | **확정: 1명** (2026-08-27 팀 합의). 부스는 AI 직원을 하나만 두고, **직원의 `role`은 부스 종류에서 갈린다** — 프로젝트 부스면 `PROJECT_DOCENT`, 이벤트 부스면 `GUIDE`. C-12의 role이 정확히 2종인 이유가 이것이다. 다만 이벤트 부스는 아직 구현이 없어(`LayoutTemplate`의 값은 `PROJECT_EXHIBITION` 하나) **당분간 `role`을 서버가 파생하지 않고 소유자가 고른 값을 화이트리스트로 검증한다.** 부스 종류가 코드에 생기면 그때 서버가 종류별 허용 role로 좁힌다. 수치는 서버 설정으로 두고 코드에 하드코딩하지 않는다 |

### Session 2026-08-20

- Q: 처리 중 임대가 만료되면 문서와 실행 작업을 어떻게 처리하는가? → A: 원본·메타데이터는 보존하고 문서는 `DISABLED`, 작업은 `CANCELLED`로 전환하며 재임대 후 `QUEUED`부터 다시 처리한다.
- Q: 문서가 많은 경우 검색 품질과 속도를 어떻게 보장하는가? → A: `boothId + agentId + READY` 선필터, AI 직원당 10개·총 100MB 상한, P95 1초 이하·Top-K 포함률 95% 이상을 적용한다.
- Q: C-01~C-07의 권장안과 C-03 변경안을 확정하는가? → A: C-03은 SHA-256 중복 방지·명시적 교체 변경안을 적용하고, 나머지는 표의 권장안을 확정한다. 서버 재시작 복구 저장 방식은 Issue #11 합의대로 C-04와 상태 모델에 반영한다.
- Q: 처리 서버 재시작과 Worker heartbeat 만료를 어떻게 복구하는가? → A: 사용자 문서 상태와 내부 실행 상태를 분리하고, 영속 Job을 heartbeat 30초·lease 90초·sweeper 60초 기준으로 회수한다. 최대 3회 재시도(1·5·15분 대기) 후 `DEAD → FAILED`로 종료한다.
- Q: Worker lease 만료와 부스 임대 만료는 같은가? → A: 다르다. Worker lease 만료는 `RETRY_WAIT`로 복구하며, 부스 임대 만료는 `CANCELLED → DISABLED`로 처리한다.

### Session 2026-08-25

- Q: 업로드 미완료 문서는 어떻게 정리하는가? → A: 업로드 URL은 15분, 미완료 문서는 생성 후 1시간에 `EXPIRED`, 24시간 보존 후 Spring이 R2 원본을 삭제한다. 보존 기간 내 늦은 완료는 원본이 있으면 `QUEUED`로 복구한다.
- Q: Spring↔FastAPI 내부 API 인증과 토큰 회전은 어떻게 하는가? → A: 방향별 토큰을 콤마 목록으로 주입하고 첫 값만 송신하며 수신자는 최대 2개 값을 상수 시간으로 검증한다. 회전은 `[old] → [old,new] → [new,old] → [new]` 순서로 수행하고 mTLS는 P2로 둔다.
- Q: R2 장애 시 저장소를 어떻게 전환하는가? → A: 자동 failover·이중 쓰기·자동 원복 없이 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE`를 운영자 승인으로 전환한다. 신규 쓰기는 Spring 설정을 따르고 기존 읽기는 문서별 Provider를 따른다. C-10의 잔여 항목은 추가 합의 전 구현자가 정하지 않는다.
- Q: 저장소 장애로 `DEAD`가 된 Job을 저장소 복구 후 자동으로 재처리하는가? → A: 하지 않는다. `DEAD`는 AI 내부 Job 상태로만 유지하고 Spring 콜백 경계(`FAILED` + `failureCode`)는 그대로 둔다. 복구 후에는 reconcile 완료를 확인한 뒤 명시적 재처리 요청으로 새 Job을 만든다.
- Q: reconcile 결과는 어디에 어떻게 기록하는가? → A: 메타데이터 SoT인 Spring의 DB에 `storage_reconciliation_log`(`runId·documentId·objectKey·sourceProvider·targetProvider·expected/actual size·type·sha256·status·attemptCount·failureReason·checkedAt·resolvedAt`)로 적재한다. Infra가 결과를 Infra 전용 Spring 내부 endpoint로 전달하며 #102 Service Token 방식을 재사용하되 AI 방향과 credential·scope를 분리한다. `runId + documentId`로 멱등성을 보장하고 `VERIFIED` 객체만 문서 Provider를 변경한다.
- Q: 저장소 장애·quota 초과 시 오류 HTTP 상태는? → A: `STORAGE_UNAVAILABLE`은 503(재시도 가능), `STORAGE_QUOTA_EXCEEDED`는 507(재시도해도 해소되지 않음)로 분리한다. 두 코드 모두 필드 오류가 아니므로 `errors[]`는 비운다.
- Q: Spring 상태 callback의 404는 모두 같은 방식으로 재시도하는가? → A: 아니다. `JOB_NOT_REGISTERED`만 1초·3초·10초 간격으로 최대 3회 재시도하고, `DOCUMENT_NOT_FOUND`와 `JOB_DOCUMENT_MISMATCH`는 즉시 종료한다. 종료 기록은 정상 전달 기록과 분리하며 Spring은 mismatch를 계약 오류로 경고한다.

### Session 2026-08-27

- Q: pgvector와 AI Job/Chunk는 Business DB의 별도 schema에 두는가? → A: 아니다. Infra 정본에 따라 같은 PostgreSQL 인스턴스 안의 별도 AI DB에 둔다. FastAPI role은 Business DB에 접속하지 않는다.
- Q: DB 간 FK와 직접 조회 없이 문서 처리 입력·삭제 정합성을 어떻게 보장하는가? → A: Spring이 검증한 문서·저장소 snapshot을 처리 요청으로 전달하고, 삭제·비활성화는 멱등 cleanup과 주기적 reconciliation으로 수렴시킨다.
- Q: 저장소에 `PROJECT_DOCENT`(docs/08 §6 예시)와 `GUIDE`(레이아웃 연결 테스트의 INSERT) 두 어휘가 갈려 있는데 어느 쪽이 유효한가? → A: **둘 다 유효값으로 받는다.** 한쪽만 화이트리스트로 만들면 다른 쪽이 깨진다. `role_code`는 이 2종으로 확정하며, 요구한 곳이 없는 후보(`TECH_SUPPORT` 등)는 넣지 않는다 — 값 추가는 가산적이지만 삭제는 저장된 데이터를 무효로 만든다.
- Q: `tone_code`는 무엇을 두는가? → A: `FRIENDLY`(기본)·`PROFESSIONAL`·`ENTHUSIASTIC` 3종. **길이를 뜻하는 값(`CONCISE` 등)은 두지 않는다** — `response_length`와 같은 것을 두 번 말하게 되고, 둘이 어긋났을 때 어느 쪽을 따를지 답이 없어진다.
- Q: `tone_code` 값은 고정인가? → A: **아니다. 구현 후 튜닝 대상이다** (#112 AI 회신). AI 파트가 **프롬프트 템플릿 수정으로 먼저 흡수**하고, 그것으로 안 되면 값 자체를 바꾼다. 지금은 3개를 증감시킬 근거가 없어 3종으로 간다. 값 **추가**는 가산적이라 enum 한 줄이면 되지만 **삭제·변경**은 이미 저장된 `tone_code` row를 무효로 만들므로 마이그레이션을 함께 내야 한다 — 그래서 "튜닝하면 바뀔 수 있다"를 spec에 적어 둔다. `role`도 같은 원칙이라 요구한 곳이 없는 후보(`TECH_SUPPORT` 등)를 미리 넣지 않았다.
- Q: `response_length`의 기준은 무엇인가? → A: `SHORT` 1~3문장, `MEDIUM` 4~6문장(기본, V1 스키마의 `DEFAULT 'MEDIUM'`과 일치), `LONG` 7~12문장. **문단이 아니라 문장 수로 적는다** — 문단은 길이가 정해지지 않아 기준이 되지 못한다.
- Q: `response_length`를 LLM 호출에 어떻게 반영하는가? → A: `max_tokens` 매핑은 **AI 파트가 소유한다.** `SHORT` 200·`MEDIUM` 400·`LONG` 800을 제안값으로 두고 실제 모델 확정 후 재검증한다. Spring은 어휘만 저장하고 토큰 수를 저장하지 않는다 — 모델이 바뀔 때 DB 마이그레이션이 따라오지 않아야 한다.
- Q: 부스당 AI 직원을 몇 명까지 두는가? → A: **1명이다** (팀 합의). 부스는 직원을 하나만 두고, 대신 **그 직원의 `role`이 부스 종류에서 갈린다** — 프로젝트 부스면 `PROJECT_DOCENT`, 이벤트 부스면 `GUIDE`. `role`이 정확히 2종인 이유가 이것이다. 수치는 서버 설정으로 두어 코드에 하드코딩하지 않는다.
- Q: 그러면 `role`을 서버가 부스 종류에서 파생시키는가? → A: **아직 아니다.** 부스 종류가 코드에 없다 — `LayoutTemplate`의 값은 `PROJECT_EXHIBITION` 하나고(spec 005 #19 ④, 셸 프리팹 1종), `booths`에 종류 칼럼이 없으며, 시드된 슬롯 12개가 전부 `USER_RENTAL`이다. 지금 파생시키면 `GUIDE`가 **도달 불가능한 값**이 된다. 그래서 당분간은 소유자가 고른 `role`을 화이트리스트로 검증하고, 부스 종류가 생기면 그때 서버가 종류별 허용 role로 좁힌다 — 값 집합은 그대로이므로 그 전환은 저장된 데이터를 무효로 만들지 않는다.

---

## Out of Scope

- 문서 기반 실제 질문·LLM 답변 생성 및 SSE 전달 (spec 008)
- Agent Test Studio (P2 — AI-07)
- 음성 상담 (P2 — AI-08)
- 문서 자동 요약·태깅
- 스캔 이미지 PDF OCR
- 내부 API mTLS 인증서 발급·갱신·폐기 자동화(P2 보안 강화)

---

## 리뷰 (AI 담당이 채운다 — 3칸 모두 채워야 확정)

| 항목 | 내용 | 완료 |
|---|---|---|
| ① Clarification 답변 (C-01~C-11) | C-01~C-09·C-11 확정. C-10의 P0 범위·reconcile 기록·quota 오류 코드는 확정했고, reconcile 중 신규 업로드와 장애 자동 판정 수치는 후속 이슈로 명시적으로 분리 | ☑ |
| ② 틀렸거나 과한 요구사항 지적 | | ☑ |
| ③ 빠진 요구사항 추가 | | ☑ |

검토자: 김가현 / 검토일: 26/08/20
