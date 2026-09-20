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

부스 **편집자(소유자·스태프)** 가 AI 직원의 이름·역할·말투·지시문을 설정해 저장하고, 필요하면
지운다.

**Why this priority**: 008(AI 상담)의 전제. 이게 없으면 대화할 대상이 없다.

**Independent Test**: AI 직원 생성 → 설정 저장 → 다시 열었을 때 설정이 유지됨 → 삭제 후 재등록됨.
배치에서 쓰고 있는 직원은 삭제가 거부됨.

**Acceptance Scenarios**:

1. **Given** 부스 편집자, **When** AI 직원을 만들면, **Then** 이름·역할·지시문이 저장된다.
2. **Given** 저장된 AI 직원, **When** 설정을 수정하면, **Then** 변경이 반영된다.
3. **Given** 다른 부스의 편집자, **When** 남의 AI 직원에 접근하면, **Then** 거부된다.
4. **Given** 이미 AI 직원이 있는 부스, **When** 또 만들면, **Then** 거부된다 (C-13 — 부스당 1명).
5. **Given** 아무것도 참조하지 않는 AI 직원, **When** 삭제하면, **Then** 지워지고 다시 만들 수 있다.
6. **Given** 문서·상담이 딸렸거나 **공개된 배치가 가리키는** AI 직원, **When** 삭제하면,
   **Then** 무엇이 막는지와 함께 거부된다 (C-14).

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
10. **Given** 수정본 교체로 밀려나 `replaced_at`이 찍힌 `EXPIRED` 원본, **When** 보존 기간 안에 늦은 완료를 요청하면, **Then** 복구되지 않고 이미 교체됐음을 알린다.

### User Story 3 — 문서를 관리한다 (Priority: P1)

**Acceptance Scenarios**:

1. **Given** 업로드한 문서 목록, **When** 조회하면, **Then** 파일명·상태·업로드 시각이 보인다.
2. **Given** 등록된 문서, **When** 삭제하면, **Then** 이후 답변에 그 문서 내용이 사용되지 않는다.

### Edge Cases

- 이미 AI 직원이 있는 부스에 또 만들면 거부한다 (C-13 — 부스당 1명). 덮어쓰지 않는다 — 수정은 별도 경로다.
- 문서·상담이 딸렸거나 **Draft·현재 공개된 배치가 가리키는** AI 직원은 삭제를 거부한다 (C-14).
  참조가 없어진 뒤에는 삭제되고, 재등록도 된다.
- MVP는 PDF·MD·TXT를 허용하며 파일당 20MB를 초과하면 명확한 사유와 함께 거부한다.
- 텍스트를 추출할 수 없는 스캔 이미지 PDF는 OCR 지원 범위 밖이므로 `FAILED`와 구체적인 실패 사유를 제공한다.
- 같은 문서를 두 번 올린 경우 동일 AI 직원에 등록된 활성 문서와 파일 SHA-256이 같으면 중복으로 판정하고 재처리하지 않는다. 수정본 교체는 기존 문서를 지정하는 별도 교체 요청으로 처리한다.
- 업로드 URL은 발급 후 15분간 유효하다. 문서 생성 후 1시간 동안 업로드가 완료되지 않으면 `EXPIRED`로 전환하고, 전환 후 24시간 동안 늦은 완료 요청을 복구할 수 있도록 원본을 보존한다. 이후에도 미완료면 Spring이 원본 삭제를 재시도한다.
- `EXPIRED` 문서의 늦은 완료 요청에서 원본이 남아 있으면 `QUEUED`로 복구하고, 이미 삭제되었으면 새 업로드 권한을 받도록 안내한다.
- 수정본 교체로 밀려난 원본은 `EXPIRED`가 되며 `replaced_at`이 함께 찍힌다. 이 행은 **복구 대상이 아니다** — 같은 `EXPIRED`라도 "아직 안 올라온 것"과 "이미 다른 원본으로 대체된 것"은 되돌릴 자리가 다르다. 원본 삭제는 기존 `EXPIRED` 스윕이 그대로 처리한다.
- `FAILED` 문서의 원본은 `updated_at` 기준 7일 유예 후 `EXPIRED`와 같은 스윕 경로에서 삭제한다. 조사·재처리는 유예 기간 안에 한다.
- 처리 중 임대가 만료되면 문서 원본·메타데이터는 보존하고 Spring은 같은 DB 트랜잭션에서 `DocumentStatus`를 `DISABLED`, 활성 Job을 `CANCELLED`로 전환하고 Chunk·staging을 정리한다. 실행 중 FastAPI 작업에는 멱등 cancel을 전달하며, 전달에 실패해도 늦은 결과는 `attemptNo` fencing으로 거부한다. 재임대 후 활성화할 때는 새 Job을 `QUEUED`부터 다시 처리한다.
- 처리 도중 서버가 재시작되거나 Worker heartbeat가 끊기면 만료된 실행 작업을 회수해 재시도한다. 최대 3회의 재시도 후에도 완료하지 못하면 실행 작업은 `DEAD`, 문서는 정제된 실패 사유와 함께 `FAILED`가 되며 영원히 `PROCESSING`에 머물지 않는다.
- 문서가 많은 경우에도 검색 전에 `boothId + agentId + READY`로 범위를 제한한다. AI 직원당 문서는 최대 10개·총 100MB로 제한하고, 최대 허용량에서 검색 응답 P95 1초 이하를 검증한다. Top-K 포함률은 두 층이 다르다 — Spring 검색은 범위 안 모든 청크를 채점하므로 정답 청크가 **100%** 들어와야 하고, 자연어 질의의 **의미** 회수율 95% 이상은 AI 층에서 따로 잰다 (SC-007a·SC-007b).
- 내부 Service Token 교체 중에는 수신자가 기존·신규 토큰을 먼저 함께 허용한 뒤 송신 토큰을 전환하고, 안정화 확인 후 기존 토큰을 제거해 배포 순서 차이로 `401`이 발생하지 않게 한다.
- R2 장애 시 자동으로 MinIO로 전환하거나 양쪽에 동시에 쓰지 않는다. 운영자가 검증·승인한 뒤 활성 **쓰기** Provider만 바꾸며, 기존 문서는 문서별 `storageProvider + bucket + objectKey`가 가리키는 저장소에서 계속 읽는다.
- 업로드를 재개하려는 동안 활성 쓰기 Provider가 바뀌었으면 기존 미완료 문서를 `EXPIRED`로 전환하고 새 문서·새 object key로 업로드를 시작한다.
- 저장소 장애로 `FAILED`가 된 문서는 저장소가 복구돼도 자동으로 다시 처리되지 않는다. reconcile이 끝난 뒤 명시적 재처리 요청을 받아야 새 Job이 시작된다.
- 문서가 삭제되거나 비활성화될 때 Spring은 영속 Job·Chunk·staging을 먼저 로컬 트랜잭션으로 정리한다. FastAPI cancel 전달은 멱등하게 재시도할 수 있지만 DB 정합성은 전달 성공 여부에 의존하지 않는다.
- Chunk batch는 순서와 관계없이 받을 수 있어야 한다. finalize는 staging의 전체 개수와 `chunkNo` 연속성, source hash, embedding model, 1536차원을 검증하며 누락·충돌이 있으면 기존 Chunk와 Document 상태를 바꾸지 않는다.

---

## Requirements

### Functional Requirements

- **FR-001**: 부스 **편집자(소유자·스태프)** 는 AI 직원을 생성·조회·수정·**삭제**할 수 있어야 한다.
  삭제는 그 직원을 참조하는 것이 하나도 없을 때만 허용하고, 있으면 **무엇이 막는지와 함께 거부**한다
  (C-14).
- **FR-002**: AI 직원은 이름·역할·말투·지시문을 가져야 한다.
- **FR-003**: 다른 부스의 AI 직원에 접근할 수 없어야 한다.
- **FR-004**: 소유자는 AI 직원에 문서를 업로드할 수 있어야 한다.
- **FR-005**: 문서 처리는 **비동기**여야 하며 다른 기능을 막아서는 안 된다.
- **FR-006**: 문서는 **`DocumentStatus`(`QUEUED`/`PROCESSING`/`READY`/`FAILED`/`DISABLED`/`EXPIRED`)** 를 가져야 하고 조회할 수 있어야 한다. `EXPIRED`는 사용자에게 **업로드 만료**로 표시한다.
  다만 **`replaced_at`이 찍힌 `EXPIRED` 행은 목록에서 "교체됨"으로 표시하고 복구 안내를 제공해서는 안 된다** — 같은 상태 값이 화면에서 두 가지 뜻으로 읽히지 않아야 하며, 복구할 수 없는 원본에 복구 버튼을 띄우면 T-24와 같은 "말은 되는데 동작하지 않는" 조합이 된다.
  **상태 값 자체는 늘리지 않는다.** `replaced_at`은 구분자이지 상태가 아니다 (`DocumentStatus` 6종 유지).
- **FR-007**: 실패한 문서는 사용자가 이해하고 대응할 수 있도록 정제된 **실패 사유**를 제공해야 하며, 내부 예외·Stack Trace·외부 Provider 원문 오류를 노출해서는 안 된다.
- **FR-008**: 처리된 문서 조각에는 **boothId, agentId, 임베딩 모델 식별자**가 기록되어야 한다 (헌법 18조).
- **FR-009**: 임베딩 차원은 **1536으로 고정**한다 (헌법 18조).
- **FR-010**: 문서 원본은 **Cloudflare R2(S3-compatible)** 를 기본으로 사용하고 장기 장애 시 운영자 승인 기반의 단일 노드 MinIO fallback을 적용한다. 자동 failover·이중 쓰기·자동 원복은 금지하며, 문서 메타데이터의 Source of Truth는 **Spring**이 소유하고 처리·검색은 FastAPI가 담당한다 (헌법 1조).
- **FR-011**: MVP는 PDF·MD·TXT를 허용하며 파일당 최대 크기는 20MB다. 허용 형식과 크기를 초과하면 명확한 사유와 함께 거부해야 한다.
- **FR-012**: 소유자는 문서를 삭제할 수 있어야 하며, 삭제 후 답변에 사용되지 않아야 한다.
- **FR-013**: 처리 실패나 중단이 **월드·부스·비AI 기능을 중단시켜서는 안 된다** (헌법 3조).
- **FR-014**: LLM/Embedding 호출은 **어댑터 뒤에** 두어 제공자 교체가 구현 교체로 끝나야 한다 (헌법 15조).
- **FR-015**: **임대가 유효하지 않게 될 때**(만료 또는 임차인의 조기 반납 — spec 004 D12) 문서 원본·메타데이터는 보존하되 문서를 `DISABLED`, 실행 중인 처리 작업을 `CANCELLED`로 전환해야 하며, 그 문서가 `READY`로 전환되어서는 안 된다. **두 경우의 처리는 같다** — Job 취소 사유 코드 `BOOTH_LEASE_EXPIRED` 는 "유효한 임대가 없음" 을 뜻하며 반납에도 그대로 쓴다 (2026-09-14 확정).
- **FR-016**: 문서 처리 작업은 **`JobStatus`(`QUEUED`/`RUNNING`/`RETRY_WAIT`/`SUCCEEDED`/`DEAD`/`CANCELLED`)** 를 가져야 한다.
- **FR-017**: RAG 검색은 FastAPI의 직접 DB 조회가 아니라 Spring 내부 검색 경로만 사용해야 한다. Spring은 `boothId + agentId + searchable=true + DocumentStatus.READY`를 서버에서 강제하고, FastAPI는 반환된 모든 Chunk의 scope를 Context 조립 전에 다시 검증해야 한다.
- **FR-018**: AI 직원 한 명이 등록할 수 있는 문서는 최대 10개, 총 원본 크기는 100MB로 제한해야 한다.
- **FR-019**: 동일 AI 직원의 활성 문서 중 파일 SHA-256이 같은 문서가 있으면 중복으로 판정하고 재처리하지 않아야 한다. 수정본 교체는 기존 `documentId`를 지정하는 명시적 요청으로 처리해야 한다. 교체로 밀려난 원본의 상태와 복구 가능 여부는 FR-027a가 정한다.
- **FR-019a**: 업로드 URL 발급 요청은 파일 SHA-256(`contentSha256`, `^[a-f0-9]{64}$`)을 **필수로** 받아야 한다.
  이 값은 **사전 중복 확인에만** 쓰고 최종 검증이 아니다 — 원본의 실제 해시는 FastAPI가 R2에서 다시 계산해
  대조하며, 불일치하면 Chunk를 저장하지 않고 `failureCode=SOURCE_HASH_MISMATCH`인 `FAILED` callback을 보낸다
  (#84 3파트 합의, 2026-08-25).
- **FR-019b**: 중복 판정 대상은 같은 AI 직원의 **`QUEUED`·`PROCESSING`·`READY`** 문서뿐이다. `FAILED`·`DISABLED`는
  제외하므로 **실패한 문서와 같은 파일의 재업로드는 허용된다** — 막으면 사용자가 빠져나갈 길이 없다. 따라서
  같은 (직원, 해시) 행이 복수 존재할 수 있고 유일성은 활성 상태 안에서만 성립한다.
- **FR-019c**: 중복은 **오류가 아니다.** 업로드 URL을 발급하지 않고 기존 `documentId`를 **200으로** 반환하며,
  응답의 `duplicate` 판별자로 갈린다. 전용 오류 코드를 만들지 않는다 — 요청 목적이 이미 달성된 상태이고,
  FR-025(중복 처리 요청은 기존 활성 작업을 반환)와 같은 결이다.
- **FR-020**: 청킹 크기와 overlap은 배포 설정으로 조정 가능한 튜닝 값이어야 하며 spec에 고정하거나 코드에 하드코딩하지 않아야 한다.
- **FR-021**: 처리 요청은 실행 전에 영속화해야 한다. 처리 서버가 재시작되거나 실행 작업의 heartbeat가 만료되면 작업을 회수해 최대 3회 재시도하고, 재시도 상한을 초과하면 실행 작업을 `DEAD`, 문서를 `FAILED`로 전환해야 한다.
- **FR-022**: MVP에서는 스캔 이미지 PDF의 OCR을 지원하지 않으며, 텍스트를 추출할 수 없으면 구체적인 실패 사유와 함께 `FAILED`로 전환해야 한다.
- **FR-023**: 사용자에게 노출되는 문서 상태와 내부 실행 작업 상태는 분리해야 하며, AI 처리 서비스가 중단되어도 사용자는 문서 목록과 마지막 상태를 조회할 수 있어야 한다.
- **FR-024**: 실행 작업을 재처리할 때 기존 문서 조각이 남거나 중복되지 않아야 하며, 완료된 새 조각 집합만 검색에 사용해야 한다.
- **FR-025**: 동일 문서에 활성 실행 작업은 하나만 존재해야 하며, 중복 처리 요청에는 기존 활성 작업을 반환해야 한다.
- **FR-026**: 업로드 URL의 기본 유효시간은 15분이며, 생성 후 1시간 동안 완료되지 않은 문서는 `EXPIRED`로 전환해야 한다. 만료 판정 주기는 5분을 기본값으로 하되 운영 설정으로 조정할 수 있어야 한다.
- **FR-027**: `EXPIRED` 전환 후 24시간 동안 원본을 보존해야 한다. 이 기간의 늦은 완료 요청은 원본이 있으면 `QUEUED`로 복구하고, 원본이 없으면 새 업로드 권한이 필요함을 알려야 한다.
- **FR-027a**: **수정본 교체(FR-019)로 밀려난 원본은 복구 대상에서 제외해야 한다.** 교체된 원본은 `EXPIRED`로 전환하면서 `replaced_at`(신규 컬럼, V30 예정)을 함께 기록하고, 이 값이 있는 행에는 FR-027의 24시간 복구 경로를 적용하지 않는다. 보존 기간 안의 늦은 완료 요청이라도 `QUEUED`로 되돌리지 않고 이미 교체됐음을 알려야 한다. 판정 근거는 **`replaced_at`의 존재 여부 하나**이며 새 `DocumentStatus` 값을 만들지 않는다.
- **FR-028**: Spring은 `EXPIRED` 전환 후 24시간이 지난 미완료 원본을 R2에서 삭제해야 한다. 삭제 실패 시 `EXPIRED` 상태와 object key를 유지하고 성공할 때까지 재시도해야 한다.
  **교체된 원본(FR-027a)도 같은 스윕이 같은 조건으로 처리한다** — 스윕의 대상 판정은 `EXPIRED` + 경과 시간뿐이며 `replaced_at`을 보지 않는다. 복구 경로가 없는 행이 삭제 경로에서 빠지면 원본만 무기한 남는다.
- **FR-028a**: Spring은 `FAILED` 문서의 원본을 `updated_at` 기준 **7일** 유예 후 FR-028과 **같은 스윕 경로**에서 삭제해야 한다. 조사·재처리는 유예 기간 안에 수행한다.
  물리 삭제는 트랜잭션 밖에서 일어나므로 사후 조건부 `UPDATE`로 되돌릴 수 없다. 따라서 **조회와 삭제 사이에 재처리가 끼어드는 경쟁은 허용하고 `ERROR` 경보로 드러낸다** — 이미 `EXPIRED` 경로가 내린 것과 같은 판단이며, 유예 중 재처리로 `FAILED`를 벗어난 행은 스냅샷 전체 일치 조건에서 자동으로 빠진다.
  **공유 claim/lock과 영속 삭제 큐는 범위 밖이다** — 스윕 인스턴스가 하나이고 경쟁 창은 한 행의 저장소 왕복 한 번뿐이라, 새 영속 구조를 들이는 비용이 그 창이 막는 손해보다 크다. 경보가 실제로 울리면 그때 도입 근거가 생긴다.
- **FR-029**: Spring↔FastAPI 내부 호출은 방향별로 분리된 Bearer Service Token을 사용해야 한다. 수신자는 네트워크 차단 설정과 독립적으로 토큰을 항상 검증하고, 누락·오류·반대 방향 토큰은 `401`로 거부해야 한다. Secret은 해당 방향의 송신자와 수신자에만 주입하고 저장소·로그에 기록해서는 안 된다.
- **FR-030**: 각 문서는 `storageProvider(R2/MINIO_LOCAL) + bucket + objectKey`를 가져야 한다. 신규 업로드는 Spring의 활성 쓰기 Provider를 사용하고, FastAPI는 전역 활성 Provider가 아니라 문서 메타데이터의 저장소 식별자로 원본을 읽어야 한다.
- **FR-031**: P0의 R2 장애 probe는 운영 판단을 위한 evidence만 수집하며 `UPLOAD_BLOCKED` 전환은 운영자가 수동으로 수행해야 한다. 자동 장애 판정과 자동 상태 전환은 후속 이슈에서 기준이 확정될 때까지 구현하지 않는다. MinIO 전환과 R2 원복은 운영자의 검증·승인이 있어야 하며, 검증된 객체만 reconcile 후 Provider를 R2로 변경해야 한다.
- **FR-032**: 업로드 미완료 문서를 재개할 때 활성 쓰기 Provider가 기존 문서의 Provider와 다르면 기존 문서를 `EXPIRED`로 전환하고 새 문서·새 object key로 시작해야 한다.
- **FR-033**: 저장소 일시 장애는 기존 1·5·15분 정책으로 최대 3회 재시도하고, 이후 Job을 `DEAD`, 문서를 `FAILED`로 종료해야 한다. 원본 유실 방지나 MinIO 고가용성을 보장하는 것으로 표현해서는 안 된다.
- **FR-034**: 저장소 장애로 Job `DEAD`·Document `FAILED`가 된 문서는 저장소 복구 후 자동으로 재처리해서는 안 된다. 재처리는 reconcile 완료 확인 후 명시적 요청으로만 Spring이 새 Job을 만들어야 한다.
- **FR-035**: Infra가 실행한 reconcile 결과는 Spring이 소유하는 `storage_reconciliation_log`에 `runId + documentId` 기준 멱등으로 적재해야 하며, size·감지 MIME·SHA-256이 모두 일치해 `VERIFIED`로 판정된 객체만 문서의 `storageProvider`를 변경해야 한다. Infra는 진행 중인 reconcile이 있는 동안 다음 Provider 전환·reconcile 시작을 거부하여 문서당 진행 run을 최대 하나로 직렬화해야 한다. 전달 경로는 Infra 전용 Bearer Service Token으로 인증하고 AI 방향 토큰과 credential·scope를 분리해야 한다.
- **FR-036**: 처리 결과 API의 모든 batch·heartbeat·finalize·failed 요청은 `jobId + attemptNo`를 포함해야 한다. 현재 Job의 attempt와 다르면 `409`, 삭제·취소된 Job 또는 반영할 수 없는 문서 상태이면 `410`으로 거부하며, 같은 batch와 finalize 재전송은 멱등해야 한다.
- **FR-037**: AI 문서의 Document·Job·Chunk·staging 영속 상태는 Spring Business DB가 단일 Source of Truth로 소유해야 한다. Spring Flyway가 `ai_document_jobs`, `ai_document_chunks`, `ai_document_chunk_staging`과 pgvector 스키마·제약을 관리하고 FastAPI는 문서 DB credential·ORM·migration을 가져서는 안 된다.
- **FR-038**: Spring은 소유권·임대·문서 상태를 검증하고 Job을 먼저 생성한 뒤 FastAPI에 `jobId`, `attemptNo`, `documentId`, `boothId`, `agentId`, 파일명·형식·크기·SHA-256, `storageProvider + bucket + objectKey`, embedding model·chunker version snapshot을 전달해야 한다. FastAPI는 장시간 연결 없이 `202 Accepted`로 비동기 접수하고 이 snapshot으로만 원본을 처리해야 한다.
- **FR-039**: FastAPI는 처리 결과를 최대 200 Chunk 또는 HTTP body 8MB 중 먼저 도달하는 batch로 Spring에 전송해야 한다. Spring은 `(jobId, batchSeq, chunkNo)` 기준으로 같은 batch를 멱등 수신하고, heartbeat는 현재 `attemptNo + workerId`가 일치할 때만 lease를 연장해야 한다.
- **FR-040**: Spring은 finalize에서 staging 개수·`chunkNo` 연속성·source hash·embedding model·1536차원을 검증한 뒤 기존 Chunk 교체, 신규 Chunk의 검색 활성화, Job `SUCCEEDED`, Document `READY`를 하나의 로컬 DB 트랜잭션으로 확정해야 한다. 실패하면 기존 검색 가능 Chunk와 Document 상태가 유지되어야 한다.
- **FR-040a**: **`READY` 전환이 0행이면 finalize 전체를 롤백해야 한다.** 문서가 그 사이 `DISABLED`·`EXPIRED`가 되어 `PROCESSING`이 아니면 `READY`로 올릴 수 없고, 이때 Chunk 교체와 Job `SUCCEEDED`만 커밋되면 **FR-040이 요구한 "하나의 트랜잭션"이 깨진다** — 검색되지 않는 문서에 새 Chunk가 붙고 Job은 성공했다고 말한다. 경고 로그만 남기고 나머지를 커밋해서는 안 된다.
  롤백하면 `SUCCEEDED` 표시도 취소되므로 재전송이 finalize 멱등 분기(Job이 `SUCCEEDED`일 때만 진입)로 들어가지 못한다. 따라서 이 실패의 응답은 **`410 JOB_GONE`** 이고, **같은 요청을 반복해도 아무것도 바뀌지 않은 채 같은 410** 이어야 한다. 워커가 재시도로 해결할 수 있는 상황이 아니라는 뜻을 그대로 전달하는 코드이며, `409`(stale attempt)와 달리 attempt를 올려 다시 시도해도 결과가 같다.
  **`DISABLED`·`EXPIRED`가 된 문서를 `PROCESSING`으로 되돌리는 복구 정책은 이 spec의 범위 밖이다.** 임대 만료·업로드 만료는 각각 FR-015·FR-026이 정한 비즈니스 판정이며, finalize가 그것을 뒤집을 자리가 아니다.
- **FR-041**: 문서 삭제·비활성화·**임대가 유효하지 않게 될 때**(만료 또는 조기 반납, spec 004 D12) Spring은 로컬 DB 트랜잭션에서 활성 Job을 `CANCELLED`로 전환하고 Chunk·staging을 정리해야 한다. 실행 중 FastAPI 작업에는 멱등 cancel을 전송하되 전달 실패가 DB 정합성을 막아서는 안 되며, 늦은 결과는 fencing으로 거부해야 한다.
- **FR-042**: FastAPI는 query embedding과 서버가 보관한 `boothId + agentId`, `topK`를 Spring 내부 검색 API에 전달해야 한다. Spring은 `boothId + agentId + searchable=true + DocumentStatus.READY`를 강제하고 `topK`를 최대 20으로 제한하며, cosine distance(`distance`, 낮을수록 유사)를 반환해야 한다. 최소 유사도 임계값은 적용하지 않는다.

### State Model

```yaml
DocumentStatus: QUEUED / PROCESSING / READY / FAILED / DISABLED / EXPIRED
JobStatus: QUEUED / RUNNING / RETRY_WAIT / SUCCEEDED / DEAD / CANCELLED
```

`DocumentStatus`는 사용자에게 보이는 문서의 처리·검색 가능 상태이고, `JobStatus`는 Spring이 영속화하는 개별 실행 작업의 상태다. RAG 검색에는 `DocumentStatus.READY`이면서 `searchable=true`인 Chunk만 포함한다.

| DocumentStatus | 의미 |
|---|---|
| `QUEUED` | 문서 처리 대기 |
| `PROCESSING` | 파싱·청킹·임베딩 처리 중 |
| `READY` | 처리 완료, RAG 검색 가능 |
| `FAILED` | 처리 실패, 실패 사유 확인 가능 |
| `DISABLED` | 원본·메타데이터는 보존하지만 RAG 검색에서 제외 |
| `EXPIRED` | 원본이 활성 집합에서 빠짐. RAG 처리·검색에서 제외된다. **복구 가능 여부는 `replaced_at`이 가른다** — 값이 없으면 업로드 미완료 만료이며 보존 기간 안에 완료 요청으로 복구할 수 있고(FR-027), 값이 있으면 수정본 교체로 밀려난 원본이라 복구 대상이 아니다(FR-027a). 두 경우 모두 원본 삭제 스윕은 동일하다(FR-028) |

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
- **Document**: 업로드된 문서. 부스, 에이전트, 파일명, 크기, SHA-256, object key, 상태, 실패 사유, 업로드 시각, 만료 시각, **교체 시각(`replaced_at` — 수정본 교체로 밀려난 원본에만 기록, 신규 컬럼 V30 예정)**
- **Processing Job**: Spring이 생성·영속화하는 문서 처리 실행 단위. 문서 snapshot, 작업 상태, attempt fencing, 재시도 횟수, Worker 소유권과 heartbeat 만료 시각, 다음 재시도 시각, 시작·종료 시각, 내부 실패 정보
- **Chunk Staging**: FastAPI가 보낸 미확정 batch를 Spring이 finalize 전까지 보관하는 영역. jobId, batchSeq, chunkNo, 텍스트, 임베딩, 모델, 페이지·섹션
- **Chunk**: finalize가 성공한 문서 조각. 문서, 원본 Job 값, boothId, agentId, 텍스트, 임베딩 벡터, `embedding_model_id`, 검색 가능 여부
- **Storage Reconciliation Log**: R2 복구 검증 기록. runId, documentId, objectKey, sourceProvider, targetProvider, 기대/실측 size·type·sha256, status, attemptCount, failureReason, checkedAt, resolvedAt

---

## Success Criteria

- **SC-001**: AI 직원 생성부터 문서 업로드·`READY` 전환·해당 `boothId + agentId` 범위 검색까지의 E2E 검증이 성공한다.
- **SC-002**: 문서 처리 중에도 월드·부스·다른 기능이 100% 정상 동작한다.
- **SC-003**: 영원히 "처리중"에 머문 문서가 0건이다.
- **SC-004**: 다른 부스의 문서 조각이 검색된 사례가 **0건** (008의 Critical Test와 연동).
- **SC-005**: 정의된 문서 처리 실패 코드마다 비어 있지 않은 정제된 한국어 대응 메시지를 제공하고, 메시지에서 Secret·Stack Trace·object key·Provider 원문 오류 노출은 0건이다.
- **SC-006**: AI 직원당 최대 허용량(문서 10개·총 100MB)에서 검색 응답 시간은 P95 1초 이하다.
- **SC-007a** (BE): AI 직원당 최대 부하(`N_total` = **145,646** chunk)에서 Spring 내부 검색이 **정확 스캔을 유지**해, 사전에 지정한 정답 청크가 Top-K에 포함되는 비율이 **100%** 다. 벡터 인덱스의 후필터로 Top-K가 조용히 짧아진 사례는 0건이다. `N_total` 산출 근거는 [plan.md §3](./plan.md)에 있다.
- **SC-007b** (AI): 사전에 정답 근거 문서를 지정한 자연어 품질 평가 질문에서 해당 문서가 Top-K에 포함되는 **의미 회수율**은 95% 이상이다.
  SC-007이 하나였을 때 이 둘이 섞여 있었다. Spring 검색은 범위 안 모든 청크를 채점하므로 BE 쪽 포함률은 구조상 100%거나 버그이며, 95%라는 비율을 잴 대상이 아니다. 95%는 질의 문장이 정답 청크와 의미적으로 이어지는가의 문제이고 그것은 임베딩·청킹 품질, 즉 AI 층의 책임이다.
- **SC-008**: 처리 서버 강제 종료 시험에서 중단된 작업의 100%가 자동 회수되어 재시도되거나 최종 실패로 종료된다.
- **SC-009**: AI 처리 서비스 중단 중에도 사용자는 기존 문서 목록과 마지막 처리 상태를 조회할 수 있다.
- **SC-010**: 업로드 미완료 문서는 생성 후 1시간과 다음 5분 판정 주기 안에 100% `EXPIRED`로 전환되고, 보존 기한(`EXPIRED` 24시간 · `replaced_at`이 찍힌 교체 원본 동일 · `FAILED` `updated_at` 기준 7일)이 지난 원본은 삭제되거나 재시도 대상으로 남는다. `replaced_at`이 찍힌 행이 늦은 완료 요청으로 복구된 사례는 **0건**이며, 조회와 삭제 사이 재처리가 끼어든 경쟁은 삭제를 막지 않되 100% `ERROR` 경보로 남는다.
- **SC-011**: 두 내부 API에서 유효한 방향 토큰만 허용하고 누락·오류·반대 방향 토큰은 100% `401`로 거부하며, `[old] → [old,new] → [new,old] → [new]` 회전 검증 중 정상 호출 실패는 0건이다.
- **SC-012**: R2 차단→운영자 승인→MinIO 전환→문서별 Provider 읽기→R2 reconcile→승인 원복 시험에서 자동 Provider 변경과 이중 쓰기는 0건이고, 검증되지 않은 객체의 Provider 변경은 0건이다.
- **SC-013**: 배포 환경의 FastAPI에 문서 PostgreSQL credential이 주입된 사례와 문서 DB 연결 시도가 모두 0건이며, AI 문서 영속 상태를 변경하는 경로는 Spring 내부 API뿐이다.
- **SC-014**: 중복·순서 변경·누락 batch, finalize 직전 장애, cancel 경합, 오래된 attempt 결과 시험에서 Chunk 중복과 부분 교체가 0건이고 삭제·비활성 문서가 검색된 사례가 0건이다. `READY`로 전환할 수 없는 문서의 finalize는 100% 전부 롤백되어 Job이 `SUCCEEDED`로 남은 사례가 0건이며, 같은 요청을 반복해도 상태 변화 없이 같은 `410`을 받는다.

---

## Clarifications

| # | 질문 | 담당 | 메모 |
|---|---|---|---|
| C-01 | 허용 형식과 최대 크기는? | AI + 기획 | **확정: MVP PDF·MD·TXT, 파일당 최대 20MB** (2026-08-26 후속 합의로 MD·TXT 추가, 스캔 이미지 PDF 텍스트 없음 처리는 PDF에만 적용) |
| C-02 | 청킹 파라미터(크기·겹침)는? | AI | **확정: 배포 설정으로 조정하는 튜닝 값, spec 미고정** |
| C-03 | 동일 문서 재업로드 정책은? | AI + 기획 | **확정: 동일 Agent의 SHA-256 중복은 재처리하지 않고, 수정본은 기존 `documentId`를 지정해 명시적으로 교체** |
| C-04 | 처리 큐를 도입하는가? | AI + BE + Infra | **확정: P0 FastAPI 인프로세스 Worker + Spring 영속 Job 저장소의 push 처리. heartbeat 30초, Worker lease 90초, sweeper 60초, 최대 3회 재시도(1·5·15분 대기). in-flight 유실은 Spring의 lease 만료 회수와 attempt 증가로 복구한다. 부하 실측 후 SQS 검토.** ([GitLab #119](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/119)) |
| C-05 | AI 직원당 문서 수·총량 상한은? | AI + 기획 | **확정: 최대 10개·총 100MB** |
| C-06 | 스캔 PDF(OCR 필요)를 지원하는가? | AI | **확정: MVP 제외, 텍스트 추출 불가 시 구체적 실패 사유와 함께 `FAILED`** |
| C-07 | 문서 원본 저장 위치는? | BE + Infra | **확정: Cloudflare R2(S3-compatible) 우선, 장기 장애 시 운영자 승인 기반 단일 노드 MinIO fallback(S3-compatible fallback 아님, C-10 참조). 메타데이터 Source of Truth는 Spring** ([Issue #51](https://github.com/kanghyunsoon/ssafesta/issues/51), [GitLab Work Item #100](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/100)) |
| C-08 | 업로드 미완료 문서의 만료·정리 정책은? | BE + Infra + FE | **확정: 업로드 URL 15분, 생성 후 1시간에 `EXPIRED`, 전환 후 24시간 보존 뒤 Spring이 R2 원본 삭제·실패 재시도. `EXPIRED`는 업로드 만료로 표시** ([GitLab Work Item #84](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/84)) |
| C-09 | Spring↔FastAPI 내부 API를 어떻게 인증하고 회전하는가? | AI + BE + Infra | **확정: 방향별 Bearer Token 2종, Security Group과 독립적인 애플리케이션 검증, 콤마 목록 최대 2개, 첫 값 송신·전체 값 상수 시간 검증, 단계적 무중단 회전. mTLS는 P2** ([GitLab Work Item #102](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/102)) |
| C-10 | R2 장애 시 fallback·reconcile을 어떻게 운영하는가? | AI + BE + Infra | **부분 확정: 자동 failover·이중 쓰기·자동 원복 금지, 운영자 승인 수동 MinIO 전환, 문서별 Provider 읽기, 유한 Job 재시도, 저장소 복구 후 자동 재처리 없음(명시적 재처리 요청으로 새 Job), reconcile 결과는 Spring DB `storage_reconciliation_log`에 `runId + documentId` 멱등으로 적재하고 `VERIFIED` 객체만 문서 Provider 반영. Infra는 진행 run이 있는 동안 다음 Provider 전환·reconcile 시작을 거부하여 문서당 진행 run을 하나로 직렬화한다. 전달 경로는 #102 Service Token 방식을 재사용하되 Infra 전용 credential·scope로 분리. Spring 배포 주입은 `AI_STORAGE_UPLOAD_GATE=OPEN|QUOTA_BLOCKED|UNAVAILABLE`와 `AI_STORAGE_ACTIVE_WRITE_PROVIDER=R2|MINIO_LOCAL`이며 기본값 없이 누락·미구성 Provider·부분 Provider 설정 시 기동 실패한다. Usage Guard는 용량·관측 상태만 다루고 active provider를 결정하지 않으며, 운영자가 두 상태를 읽어 upload gate에 반영한다. `QUOTA_BLOCKED`는 `507 STORAGE_QUOTA_EXCEEDED`(재시도 불가), `UNAVAILABLE`은 `503 STORAGE_UNAVAILABLE`(재시도 가능)이다. P0에서는 probe evidence만 수집하고 운영자가 `UPLOAD_BLOCKED`를 수동 적용한다. 미확정 2건(`R2_RECONCILING` 중 신규 업로드 허용 여부, R2 API 장애 자동 판정 수치)은 `docs/26_팀_결정_필요사항.md`에 등록하고 후속 이슈로 분리** ([GitLab Work Item #100](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/100)) |
| C-11 | PostgreSQL의 Business/AI 경계를 어떻게 나누는가? | AI + BE + Infra | **확정: AI 문서의 Document·Job·Chunk·staging 영속 상태는 Spring Business DB가 단일 Source of Truth로 소유한다. Spring Flyway가 `ai_document_jobs`, `ai_document_chunks`, `ai_document_chunk_staging`과 pgvector를 관리한다. FastAPI는 문서 DB credential·ORM·migration 없이 파싱·청킹·임베딩·질의 임베딩만 담당하고 Spring 내부 API로 처리·검색한다. S15P21A604-262의 별도 AI DB 계약은 이 결정으로 대체한다.** ([GitLab #119](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/119), S15P21A604-397) |
| C-12 | AI 직원의 `role_code`·`tone_code`·`response_length` 허용값은? | AI + BE | **확정: role 2종(`PROJECT_DOCENT`·`GUIDE`), tone 3종(`FRIENDLY` 기본·`PROFESSIONAL`·`ENTHUSIASTIC`), responseLength 3종(`SHORT` 1~3문장·`MEDIUM` 4~6문장 기본·`LONG` 7~12문장). 저장과 검증은 Spring, 해석은 FastAPI가 한다. **`tone`은 구현 후 튜닝 대상이다** — AI 파트가 프롬프트 템플릿 수정으로 먼저 흡수하되 불가피하면 값 자체가 바뀔 수 있고(#112 AI 회신), 지금은 3개를 증감시킬 근거가 없어 3종으로 간다. 값 **추가**는 가산적이라 언제든 되지만 **삭제·변경**은 저장된 row를 무효로 만드므로 그때는 마이그레이션을 함께 낸다. `responseLength → max_tokens` 매핑은 AI 파트 소유이며 `SHORT` 200·`MEDIUM` 400·`LONG` 800을 제안값으로 두되 모델 확정 후 재검증한다 — Spring은 어휘만 저장하고 토큰 수를 저장하지 않는다** ([GitLab Issue #112](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/112)) |
| C-13 | 부스당 AI 직원 수 상한은? | BE + 기획 | **확정: 1명** (2026-08-27 팀 합의). 부스는 AI 직원을 하나만 두고, **직원의 `role`은 부스 종류에서 갈린다** — 프로젝트 부스면 `PROJECT_DOCENT`, 이벤트 부스면 `GUIDE`. C-12의 role이 정확히 2종인 이유가 이것이다. 다만 이벤트 부스는 아직 구현이 없어(`LayoutTemplate`의 값은 `PROJECT_EXHIBITION` 하나) **당분간 `role`을 서버가 파생하지 않고 소유자가 고른 값을 화이트리스트로 검증한다.** 부스 종류가 코드에 생기면 그때 서버가 종류별 허용 role로 좁힌다. 수치는 서버 설정으로 두고 코드에 하드코딩하지 않는다 |
| C-14 | AI 직원 삭제를 지원하는가, 참조가 있으면 어떻게 하는가? | BE | **확정: 지원한다. 참조가 하나라도 있으면 `409 AGENT_DELETE_CONFLICT`로 거부하고 무엇이 막는지 message 에 담는다** (2026-08-30). **검사 대상 3종** — ⑴ `ai_documents.agent_id` ⑵ `consultations.agent_id` ⑶ **Draft·현재 Published Layout 의 `AI_AGENT.configId`**. `ai_document_chunks`는 독립 blocker로 검사하지 않는다 — Spring이 접근할 수 없어서가 아니라, Chunk의 정본 부모인 `ai_documents` 참조가 있으면 이미 삭제를 거부하고 문서 삭제 시 Chunk는 cascade 정리되므로 중복 검사할 필요가 없기 때문이다. **"현재 Published"는 `booths.published_layout_version` 포인터로 판정**한다. Draft 검사는 기존 데이터 보호이며 강한 불변식은 Published 무결성이다. Agent 삭제와 Layout Publish는 `booths` 행 잠금을 공유하고, 알려진 문서·상담 FK 위반만 같은 409로 번역한다. |
| C-15 | AI 직원 편집 권한 범위는? | BE | **확정: 소유자 + 스태프**(`BoothAccessGuard`, 2026-08-30). 005·016·009 와 **정확히 같은 편집자 범위**다 — 같은 "편집자"가 기능마다 다른 뜻이 되지 않게 한다. 직원 역할별 제한(011 C-09 `ADMIN`·`CONTENT_EDITOR`)은 **011 구현 때 가드 한 곳에서 일괄**로 닫는다([GitLab #116](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/116)) — 그때까지 `CONSULTANT`도 편집할 수 있고, 이는 알고 여는 창이다 |
| C-16 | FastAPI가 Agent 추론 설정을 어떤 API 형태와 빈도로 조회하는가? | AI + BE | **확정: FastAPI가 질문마다 검색 전에 별도 `GET /internal/ai/agent-config?boothId=&agentId=`를 1회 호출하고 캐시하지 않는다. 인증은 `INTERNAL_AI_TO_SPRING_TOKENS`의 첫 토큰을 쓰며, `found:true`면 `role`·`tone`·`responseLength`·`systemPrompt`·`forbiddenTopics`를 받는다. `found:false`의 `AGENT_NOT_IN_BOOTH`·`AGENT_INACTIVE`와 401·timeout·5xx·계약 위반은 Fail Closed로 처리해 Embedding·검색·LLM을 호출하지 않는다.** ([계약](../008-ai-conversation-rag/contracts/spring-agent-config-api.yaml), S15P21A604-399·507) |
| C-17 | FastAPI 결과 수신 API의 endpoint·DTO는 무엇인가? | AI + BE | **부분 확정: heartbeat·batch·finalize·failed 의미, `jobId+attemptNo` fencing, batch 상한, 409/410은 확정. 실제 경로·DTO 이름·공통 오류 봉투는 S15P21A604-400에서 합의한 뒤 OpenAPI로 고정한다.** |

### Session 2026-08-20

- Q: 처리 중 임대가 만료되면 문서와 실행 작업을 어떻게 처리하는가? → A: 원본·메타데이터는 보존하고 문서는 `DISABLED`, 작업은 `CANCELLED`로 전환하며 재임대 후 `QUEUED`부터 다시 처리한다.
- Q: 문서가 많은 경우 검색 품질과 속도를 어떻게 보장하는가? → A: `boothId + agentId + READY` 선필터, AI 직원당 10개·총 100MB 상한, P95 1초 이하·Top-K 포함률 95% 이상을 적용한다. → **포함률 부분은 2026-09-11 결정으로 SC-007a(BE 100%)·SC-007b(AI 95%)로 갈렸다.**
- Q: C-01~C-07의 권장안과 C-03 변경안을 확정하는가? → A: C-03은 SHA-256 중복 방지·명시적 교체 변경안을 적용하고, 나머지는 표의 권장안을 확정한다. 서버 재시작 복구 저장 방식은 Issue #11 합의대로 C-04와 상태 모델에 반영한다.
- Q: 처리 서버 재시작과 Worker heartbeat 만료를 어떻게 복구하는가? → A: 사용자 문서 상태와 내부 실행 상태를 분리하고, 영속 Job을 heartbeat 30초·lease 90초·sweeper 60초 기준으로 회수한다. 최대 3회 재시도(1·5·15분 대기) 후 `DEAD → FAILED`로 종료한다.
- Q: Worker lease 만료와 부스 임대 만료는 같은가? → A: 다르다. Worker lease 만료는 `RETRY_WAIT`로 복구하며, 부스 임대 만료는 `CANCELLED → DISABLED`로 처리한다.

### Session 2026-08-25

- Q: 업로드 미완료 문서는 어떻게 정리하는가? → A: 업로드 URL은 15분, 미완료 문서는 생성 후 1시간에 `EXPIRED`, 24시간 보존 후 Spring이 R2 원본을 삭제한다. 보존 기간 내 늦은 완료는 원본이 있으면 `QUEUED`로 복구한다. → **복구 조건은 2026-09-11 결정으로 좁혀졌다 — `replaced_at`이 찍힌 교체 원본은 제외한다(FR-027a).**
- Q: Spring↔FastAPI 내부 API 인증과 토큰 회전은 어떻게 하는가? → A: 방향별 토큰을 콤마 목록으로 주입하고 첫 값만 송신하며 수신자는 최대 2개 값을 상수 시간으로 검증한다. 회전은 `[old] → [old,new] → [new,old] → [new]` 순서로 수행하고 mTLS는 P2로 둔다.
- Q: R2 장애 시 저장소를 어떻게 전환하는가? → A: 자동 failover·이중 쓰기·자동 원복 없이 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE`를 운영자 승인으로 전환한다. 신규 쓰기는 Spring 설정을 따르고 기존 읽기는 문서별 Provider를 따른다. C-10의 잔여 항목은 추가 합의 전 구현자가 정하지 않는다.
- Q: 저장소 장애로 `DEAD`가 된 Job을 저장소 복구 후 자동으로 재처리하는가? → A: 하지 않는다. Spring Job은 `DEAD`, Document는 `FAILED`로 유지한다. 복구 후에는 reconcile 완료를 확인한 뒤 명시적 재처리 요청으로 새 Spring Job을 만든다.
- Q: reconcile 결과는 어디에 어떻게 기록하는가? → A: 메타데이터 SoT인 Spring의 DB에 `storage_reconciliation_log`(`runId·documentId·objectKey·sourceProvider·targetProvider·expected/actual size·type·sha256·status·attemptCount·failureReason·checkedAt·resolvedAt`)로 적재한다. Infra가 결과를 Infra 전용 Spring 내부 endpoint로 전달하며 #102 Service Token 방식을 재사용하되 AI 방향과 credential·scope를 분리한다. `runId + documentId`로 멱등성을 보장하고 `VERIFIED` 객체만 문서 Provider를 변경한다. Infra는 진행 중인 reconcile이 있으면 다음 Provider 전환과 reconcile 시작을 거부해 문서당 진행 run을 하나로 직렬화한다.
- Q: 저장소 장애·quota 초과 시 오류 HTTP 상태는? → A: `STORAGE_UNAVAILABLE`은 503(재시도 가능), `STORAGE_QUOTA_EXCEEDED`는 507(재시도해도 해소되지 않음)로 분리한다. 두 코드 모두 필드 오류가 아니므로 `errors[]`는 비운다.
- Q: Spring 상태 callback의 404 계약은 유지되는가? → A: 아니다. 2026-09-03 단일 DB 소유권 합의로 상태 callback은 폐기됐다. 모든 결과 요청은 `jobId+attemptNo`를 사용하며 stale attempt는 409, 삭제·취소된 Job은 410으로 처리한다.

### Session 2026-08-27

- Q: pgvector와 AI Job/Chunk는 Business DB의 별도 schema에 두는가? → A: **2026-09-03 결정으로 대체됐다.** Spring Business DB가 Document·Job·Staging·Chunk와 pgvector를 모두 소유한다.
- Q: 삭제 정합성을 어떻게 보장하는가? → A: **2026-09-03 결정으로 대체됐다.** Spring 로컬 트랜잭션과 V21 FK cascade로 Job·Staging·Chunk를 정리하고 늦은 FastAPI 결과는 fencing으로 거부한다.
- Q: 저장소에 `PROJECT_DOCENT`(docs/08 §6 예시)와 `GUIDE`(레이아웃 연결 테스트의 INSERT) 두 어휘가 갈려 있는데 어느 쪽이 유효한가? → A: **둘 다 유효값으로 받는다.** 한쪽만 화이트리스트로 만들면 다른 쪽이 깨진다. `role_code`는 이 2종으로 확정하며, 요구한 곳이 없는 후보(`TECH_SUPPORT` 등)는 넣지 않는다 — 값 추가는 가산적이지만 삭제는 저장된 데이터를 무효로 만든다.
- Q: `tone_code`는 무엇을 두는가? → A: `FRIENDLY`(기본)·`PROFESSIONAL`·`ENTHUSIASTIC` 3종. **길이를 뜻하는 값(`CONCISE` 등)은 두지 않는다** — `response_length`와 같은 것을 두 번 말하게 되고, 둘이 어긋났을 때 어느 쪽을 따를지 답이 없어진다.
- Q: `tone_code` 값은 고정인가? → A: **아니다. 구현 후 튜닝 대상이다** (#112 AI 회신). AI 파트가 **프롬프트 템플릿 수정으로 먼저 흡수**하고, 그것으로 안 되면 값 자체를 바꾼다. 지금은 3개를 증감시킬 근거가 없어 3종으로 간다. 값 **추가**는 가산적이라 enum 한 줄이면 되지만 **삭제·변경**은 이미 저장된 `tone_code` row를 무효로 만들므로 마이그레이션을 함께 내야 한다 — 그래서 "튜닝하면 바뀔 수 있다"를 spec에 적어 둔다. `role`도 같은 원칙이라 요구한 곳이 없는 후보(`TECH_SUPPORT` 등)를 미리 넣지 않았다.
- Q: `response_length`의 기준은 무엇인가? → A: `SHORT` 1~3문장, `MEDIUM` 4~6문장(기본, V1 스키마의 `DEFAULT 'MEDIUM'`과 일치), `LONG` 7~12문장. **문단이 아니라 문장 수로 적는다** — 문단은 길이가 정해지지 않아 기준이 되지 못한다.
- Q: `response_length`를 LLM 호출에 어떻게 반영하는가? → A: `max_tokens` 매핑은 **AI 파트가 소유한다.** `SHORT` 200·`MEDIUM` 400·`LONG` 800을 제안값으로 두고 실제 모델 확정 후 재검증한다. Spring은 어휘만 저장하고 토큰 수를 저장하지 않는다 — 모델이 바뀔 때 DB 마이그레이션이 따라오지 않아야 한다.
- Q: 부스당 AI 직원을 몇 명까지 두는가? → A: **1명이다** (팀 합의). 부스는 직원을 하나만 두고, 대신 **그 직원의 `role`이 부스 종류에서 갈린다** — 프로젝트 부스면 `PROJECT_DOCENT`, 이벤트 부스면 `GUIDE`. `role`이 정확히 2종인 이유가 이것이다. 수치는 서버 설정으로 두어 코드에 하드코딩하지 않는다.
- Q: 그러면 `role`을 서버가 부스 종류에서 파생시키는가? → A: **아직 아니다.** 부스 종류가 코드에 없다 — `LayoutTemplate`의 값은 `PROJECT_EXHIBITION` 하나고(spec 005 #19 ④, 셸 프리팹 1종), `booths`에 종류 칼럼이 없으며, 시드된 슬롯 12개가 전부 `USER_RENTAL`이다. 지금 파생시키면 `GUIDE`가 **도달 불가능한 값**이 된다. 그래서 당분간은 소유자가 고른 `role`을 화이트리스트로 검증하고, 부스 종류가 생기면 그때 서버가 종류별 허용 role로 좁힌다 — 값 집합은 그대로이므로 그 전환은 저장된 데이터를 무효로 만들지 않는다.

### Session 2026-09-03

- Q: AI 문서 Job·Chunk의 DB 소유권은 어디인가? → A: Spring Business DB 하나다. Spring Flyway가 Job·Chunk·staging·pgvector를 관리하고 FastAPI 문서 DB credential과 Alembic을 제거한다. 기존 S15P21A604-262의 별도 AI DB 계약은 폐기한다.
- Q: Worker가 DB를 직접 poll하지 않으면 작업을 어떻게 받는가? → A: Spring이 Job을 먼저 만든 뒤 FastAPI에 push하고 FastAPI는 202로 접수한다. in-flight 유실은 heartbeat 30초·lease 90초와 Spring의 attempt 증가 회수로 복구한다.
- Q: 결과를 어떻게 원자적으로 공개하는가? → A: FastAPI가 batch로 staging에 보내고 Spring finalize가 검증·Chunk 교체·`searchable=true`·Job `SUCCEEDED`·Document `READY`를 하나의 로컬 트랜잭션으로 확정한다.
- Q: 검색 경계는 어디에서 강제하는가? → A: Spring 내부 검색 API가 `boothId + agentId + searchable=true + READY`를 강제하고 FastAPI가 반환 scope를 다시 검증한다. cosine `distance`를 그대로 반환하며 최소 임계값은 두지 않는다.

### Session 2026-09-11

- Q: 수정본 교체로 밀려난 원본은 어떤 상태이며 복구할 수 있는가? → A: `EXPIRED` + `replaced_at`이고 **복구 대상이 아니다**(FR-027a). 기존 `EXPIRED` 정의가 "보존 기간 안에는 복구 가능"이라 교체 원본과 충돌했는데, 상태를 늘리는 대신 구분자 컬럼 하나로 가른다. 목록 표시(FR-006)·복구(FR-027)·삭제 스윕(FR-028)·State Model·SC-010을 함께 맞췄다.
- Q: `REPLACED` 상태를 새로 만들지 않는 이유는? → A: **상태 하나를 늘리면 V24 CHECK 개정·스윕 predicate·OpenAPI·FE 상태표·기존 테스트가 전부 따라오는데, 갈라야 하는 것은 "복구 가능한가" 한 가지뿐이라 컬럼 하나로 충분하다.**
- Q: 최대 허용량에서 Top-K 포함률 95%는 누구의 기준인가? → A: 둘로 나눈다. Spring 검색은 `SET LOCAL enable_indexscan = off`로 정확 스캔을 강제해 범위 안 모든 청크를 채점하므로 BE 쪽은 **100%**(SC-007a, `S15P21A604-521`, Testcontainers)이고, 95%는 자연어 질의의 의미 회수율(SC-007b, T067, AI)이다. 구조상 100%인 값에 95%를 걸면 버그를 통과시킨다.
- Q: 최대 부하 픽스처의 `N_total`은? → A: **145,646 chunk.** 산출은 plan.md §3.
- Q: `FAILED` 문서의 원본은 언제 지우는가? → A: `updated_at` 기준 7일 유예 후 `EXPIRED`와 같은 스윕 경로(FR-028a). 조회와 삭제 사이 재처리 경쟁은 허용하고 `ERROR` 경보로 드러내며, 공유 claim/lock·영속 삭제 큐는 범위 밖이다.
- Q: finalize의 `READY` 전환이 0행이면? → A: 예외를 던져 finalize 전체를 롤백하고 `410 JOB_GONE`을 돌려준다(FR-040a). 반복 요청도 무변화 410이다. 지금 구현은 ERROR 로그만 남기고 나머지를 커밋해 FR-040을 어기고 있다. `DISABLED`·`EXPIRED` 문서를 `PROCESSING`으로 되돌리는 복구는 범위 밖이다.

---

## Out of Scope

- 문서 기반 실제 질문·LLM 답변 생성 및 SSE 전달 (spec 008)
- Agent Test Studio (P2 — AI-07)
- 음성 상담 (P2 — AI-08)
- 문서 자동 요약·태깅
- 스캔 이미지 PDF OCR
- 내부 API mTLS 인증서 발급·갱신·폐기 자동화(P2 보안 강화)
- `DISABLED`·`EXPIRED`가 된 문서를 `PROCESSING`으로 되돌리는 복구 정책 (FR-040a)
- 원본 삭제 스윕의 공유 claim/lock과 영속 삭제 큐 (FR-028a — 경쟁은 허용하고 `ERROR` 경보로 드러낸다)

---

## 리뷰 (AI 담당이 채운다 — 3칸 모두 채워야 확정)

| 항목 | 내용 | 완료 |
|---|---|---|
| ① Clarification 답변 (C-01~C-17) | C-01~C-09·C-11~C-16 확정. C-10 잔여 운영 수치와 C-17 결과 API 경로·DTO는 후속 이슈로 분리 | ☑ |
| ② 틀렸거나 과한 요구사항 지적 | | ☑ |
| ③ 빠진 요구사항 추가 | | ☑ |

검토자: 김가현 / 검토일: 26/08/20
