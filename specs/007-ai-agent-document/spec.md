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

### User Story 2 — 문서를 올리면 AI가 그 내용으로 답한다 (Priority: P0)

소유자가 문서를 업로드하면 처리 과정을 거쳐 AI가 그 내용을 근거로 답할 수 있게 된다.

**Why this priority**: RAG의 입력. 이게 없으면 AI가 일반 지식만 말한다.

**Independent Test**: 문서 업로드 → 상태가 처리중→준비완료로 변함 → 해당 문서 내용을 물으면 답변에 반영됨.

**Acceptance Scenarios**:

1. **Given** AI 직원이 있는 소유자, **When** 문서를 업로드하면, **Then** 업로드가 접수되고 처리 상태를 볼 수 있다.
2. **Given** 처리 중인 문서, **When** 상태를 조회하면, **Then** 진행 상황(대기/처리중/준비완료/실패)을 알 수 있다.
3. **Given** 처리에 실패한 문서, **When** 상태를 보면, **Then** **실패 사유**를 알 수 있다.
4. **Given** 문서가 처리 중, **When** 사용자가 다른 기능을 쓰면, **Then** 정상 동작한다 (비동기).
5. **Given** 준비완료된 문서, **When** 그 내용을 AI에게 물으면, **Then** 문서 내용을 근거로 답한다.
6. **Given** 문서 처리 중 실행 서버가 중단됨, **When** 서비스가 복구되면, **Then** 중단된 작업은 자동으로 재시도되거나 재시도 상한 초과 사유와 함께 실패로 종료된다.
7. **Given** AI 처리 서비스가 일시적으로 응답하지 않음, **When** 소유자가 문서 목록과 상태를 조회하면, **Then** 기존 문서 상태를 계속 확인할 수 있다.

### User Story 3 — 문서를 관리한다 (Priority: P1)

**Acceptance Scenarios**:

1. **Given** 업로드한 문서 목록, **When** 조회하면, **Then** 파일명·상태·업로드 시각이 보인다.
2. **Given** 등록된 문서, **When** 삭제하면, **Then** 이후 답변에 그 문서 내용이 사용되지 않는다.

### Edge Cases

- MVP는 PDF만 허용하며 파일당 20MB를 초과하면 명확한 사유와 함께 거부한다.
- 텍스트를 추출할 수 없는 스캔 이미지 PDF는 OCR 지원 범위 밖이므로 `FAILED`와 구체적인 실패 사유를 제공한다.
- 같은 문서를 두 번 올린 경우 동일 AI 직원에 등록된 활성 문서와 파일 SHA-256이 같으면 중복으로 판정하고 재처리하지 않는다. 수정본 교체는 기존 문서를 지정하는 별도 교체 요청으로 처리한다.
- 처리 중 임대가 만료되면 문서 원본·메타데이터는 보존하고 `DocumentStatus`를 `DISABLED`, 실행 중인 `JobStatus`를 `CANCELLED`로 전환한다. 완료 직전에도 Lease를 다시 확인해 만료된 문서가 `READY`가 되지 않도록 하며, 재임대 후 활성화할 때는 `QUEUED`부터 다시 처리한다.
- 처리 도중 서버가 재시작되거나 Worker heartbeat가 끊기면 만료된 실행 작업을 회수해 재시도한다. 최대 3회의 재시도 후에도 완료하지 못하면 실행 작업은 `DEAD`, 문서는 정제된 실패 사유와 함께 `FAILED`가 되며 영원히 `PROCESSING`에 머물지 않는다.
- 문서가 많은 경우에도 검색 전에 `boothId + agentId + READY`로 범위를 제한한다. AI 직원당 문서는 최대 10개·총 100MB로 제한하고, 최대 허용량에서 검색 응답 P95 1초 이하 및 정답 근거 문서의 Top-K 포함률 95% 이상을 검증한다.

---

## Requirements

### Functional Requirements

- **FR-001**: 부스 소유자는 AI 직원을 생성·수정할 수 있어야 한다.
- **FR-002**: AI 직원은 이름·역할·말투·지시문을 가져야 한다.
- **FR-003**: 다른 부스의 AI 직원에 접근할 수 없어야 한다.
- **FR-004**: 소유자는 AI 직원에 문서를 업로드할 수 있어야 한다.
- **FR-005**: 문서 처리는 **비동기**여야 하며 다른 기능을 막아서는 안 된다.
- **FR-006**: 문서는 **`DocumentStatus`(`QUEUED`/`PROCESSING`/`READY`/`FAILED`/`DISABLED`)** 를 가져야 하고 조회할 수 있어야 한다.
- **FR-007**: 실패한 문서는 사용자가 이해하고 대응할 수 있도록 정제된 **실패 사유**를 제공해야 하며, 내부 예외·Stack Trace·외부 Provider 원문 오류를 노출해서는 안 된다.
- **FR-008**: 처리된 문서 조각에는 **boothId, agentId, 임베딩 모델 식별자**가 기록되어야 한다 (헌법 18조).
- **FR-009**: 임베딩 차원은 **1536으로 고정**한다 (헌법 18조).
- **FR-010**: 문서 원본은 **S3**, 문서 메타데이터의 Source of Truth는 **Spring**이 소유하고, 처리·검색은 FastAPI가 담당한다 (헌법 1조).
- **FR-011**: MVP는 PDF만 허용하며 파일당 최대 크기는 20MB다. 허용 형식과 크기를 초과하면 명확한 사유와 함께 거부해야 한다.
- **FR-012**: 소유자는 문서를 삭제할 수 있어야 하며, 삭제 후 답변에 사용되지 않아야 한다.
- **FR-013**: 처리 실패나 중단이 **월드·부스·비AI 기능을 중단시켜서는 안 된다** (헌법 3조).
- **FR-014**: LLM/Embedding 호출은 **어댑터 뒤에** 두어 제공자 교체가 구현 교체로 끝나야 한다 (헌법 15조).
- **FR-015**: 임대 만료 시 문서 원본·메타데이터는 보존하되 문서를 `DISABLED`, 실행 중인 처리 작업을 `CANCELLED`로 전환해야 하며, 만료된 문서가 `READY`로 전환되어서는 안 된다.
- **FR-016**: 문서 처리 작업은 **`JobStatus`(`QUEUED`/`RUNNING`/`RETRY_WAIT`/`SUCCEEDED`/`DEAD`/`CANCELLED`)** 를 가져야 한다.
- **FR-017**: RAG 검색은 `boothId + agentId + DocumentStatus.READY`로 범위를 제한한 후 수행해야 한다.
- **FR-018**: AI 직원 한 명이 등록할 수 있는 문서는 최대 10개, 총 원본 크기는 100MB로 제한해야 한다.
- **FR-019**: 동일 AI 직원의 활성 문서 중 파일 SHA-256이 같은 문서가 있으면 중복으로 판정하고 재처리하지 않아야 한다. 수정본 교체는 기존 `documentId`를 지정하는 명시적 요청으로 처리해야 한다.
- **FR-020**: 청킹 크기와 overlap은 배포 설정으로 조정 가능한 튜닝 값이어야 하며 spec에 고정하거나 코드에 하드코딩하지 않아야 한다.
- **FR-021**: 처리 요청은 실행 전에 영속화해야 한다. 처리 서버가 재시작되거나 실행 작업의 heartbeat가 만료되면 작업을 회수해 최대 3회 재시도하고, 재시도 상한을 초과하면 실행 작업을 `DEAD`, 문서를 `FAILED`로 전환해야 한다.
- **FR-022**: MVP에서는 스캔 이미지 PDF의 OCR을 지원하지 않으며, 텍스트를 추출할 수 없으면 구체적인 실패 사유와 함께 `FAILED`로 전환해야 한다.
- **FR-023**: 사용자에게 노출되는 문서 상태와 내부 실행 작업 상태는 분리해야 하며, AI 처리 서비스가 중단되어도 사용자는 문서 목록과 마지막 상태를 조회할 수 있어야 한다.
- **FR-024**: 실행 작업을 재처리할 때 기존 문서 조각이 남거나 중복되지 않아야 하며, 완료된 새 조각 집합만 검색에 사용해야 한다.
- **FR-025**: 동일 문서에 활성 실행 작업은 하나만 존재해야 하며, 중복 처리 요청에는 기존 활성 작업을 반환해야 한다.

### State Model

```yaml
DocumentStatus: QUEUED / PROCESSING / READY / FAILED / DISABLED
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
- **Document**: 업로드된 문서. 부스, 에이전트, 파일명, 크기, SHA-256, S3 Key, 상태, 실패 사유, 업로드 시각
- **Processing Job**: 문서 처리 실행 단위. 문서, 작업 상태, 재시도 횟수, Worker 소유권과 heartbeat 만료 시각, 다음 재시도 시각, 시작·종료 시각, 내부 실패 정보
- **Chunk**: 문서 조각. 문서, boothId, agentId, 텍스트, 임베딩 벡터, `embedding_model_id`

---

## Success Criteria

- **SC-001**: 소유자가 안내 없이 AI 직원을 만들고 문서를 올려 준비완료까지 도달한다.
- **SC-002**: 문서 처리 중에도 월드·부스·다른 기능이 100% 정상 동작한다.
- **SC-003**: 영원히 "처리중"에 머문 문서가 0건이다.
- **SC-004**: 다른 부스의 문서 조각이 검색된 사례가 **0건** (008의 Critical Test와 연동).
- **SC-005**: 실패한 문서의 사유만 보고 사용자가 대처할 수 있다.
- **SC-006**: AI 직원당 최대 허용량(문서 10개·총 100MB)에서 검색 응답 시간은 P95 1초 이하다.
- **SC-007**: 사전에 정답 근거 문서를 지정한 품질 평가 질문에서 해당 문서가 Top-K에 포함되는 비율은 95% 이상이다.
- **SC-008**: 처리 서버 강제 종료 시험에서 중단된 작업의 100%가 자동 회수되어 재시도되거나 최종 실패로 종료된다.
- **SC-009**: AI 처리 서비스 중단 중에도 사용자는 기존 문서 목록과 마지막 처리 상태를 조회할 수 있다.

---

## Clarifications

| # | 질문 | 담당 | 메모 |
|---|---|---|---|
| C-01 | 허용 형식과 최대 크기는? | AI + 기획 | **확정: MVP PDF만, 파일당 최대 20MB** |
| C-02 | 청킹 파라미터(크기·겹침)는? | AI | **확정: 배포 설정으로 조정하는 튜닝 값, spec 미고정** |
| C-03 | 동일 문서 재업로드 정책은? | AI + 기획 | **확정: 동일 Agent의 SHA-256 중복은 재처리하지 않고, 수정본은 기존 `documentId`를 지정해 명시적으로 교체** |
| C-04 | 처리 큐를 도입하는가? | AI + Infra | **확정: P0 인프로세스 Worker + 영속 Job 저장소. heartbeat 30초, Worker lease 90초, sweeper 60초, 최대 3회 재시도(1·5·15분 대기). 부하 실측 후 SQS 검토. 세부 계약은 [Issue #11](https://github.com/kanghyunsoon/ssafesta/issues/11) 참조** |
| C-05 | AI 직원당 문서 수·총량 상한은? | AI + 기획 | **확정: 최대 10개·총 100MB** |
| C-06 | 스캔 PDF(OCR 필요)를 지원하는가? | AI | **확정: MVP 제외, 텍스트 추출 불가 시 구체적 실패 사유와 함께 `FAILED`** |
| C-07 | 문서 원본 저장 위치는? (S3 등) | BE + Infra | **확정: 원본 S3, 메타데이터 Source of Truth는 Spring** |

### Session 2026-08-20

- Q: 처리 중 임대가 만료되면 문서와 실행 작업을 어떻게 처리하는가? → A: 원본·메타데이터는 보존하고 문서는 `DISABLED`, 작업은 `CANCELLED`로 전환하며 재임대 후 `QUEUED`부터 다시 처리한다.
- Q: 문서가 많은 경우 검색 품질과 속도를 어떻게 보장하는가? → A: `boothId + agentId + READY` 선필터, AI 직원당 10개·총 100MB 상한, P95 1초 이하·Top-K 포함률 95% 이상을 적용한다.
- Q: C-01~C-07의 권장안과 C-03 변경안을 확정하는가? → A: C-03은 SHA-256 중복 방지·명시적 교체 변경안을 적용하고, 나머지는 표의 권장안을 확정한다. 서버 재시작 복구 저장 방식은 Issue #11 합의대로 C-04와 상태 모델에 반영한다.
- Q: 처리 서버 재시작과 Worker heartbeat 만료를 어떻게 복구하는가? → A: 사용자 문서 상태와 내부 실행 상태를 분리하고, 영속 Job을 heartbeat 30초·lease 90초·sweeper 60초 기준으로 회수한다. 최대 3회 재시도(1·5·15분 대기) 후 `DEAD → FAILED`로 종료한다.
- Q: Worker lease 만료와 부스 임대 만료는 같은가? → A: 다르다. Worker lease 만료는 `RETRY_WAIT`로 복구하며, 부스 임대 만료는 `CANCELLED → DISABLED`로 처리한다.

---

## Out of Scope

- Agent Test Studio (P2 — AI-07)
- 음성 상담 (P2 — AI-08)
- 문서 자동 요약·태깅
- 스캔 이미지 PDF OCR

---

## 리뷰 (AI 담당이 채운다 — 3칸 모두 채워야 확정)

| 항목 | 내용 | 완료 |
|---|---|---|
| ① Clarification 답변 (C-01~C-07) | 전 항목 확정. C-04 서버 재시작 복구 계약까지 Issue #11 합의로 반영 | ☑ |
| ② 틀렸거나 과한 요구사항 지적 | | ☑ |
| ③ 빠진 요구사항 추가 | | ☑ |

검토자: 김가현 / 검토일: 26/08/20
