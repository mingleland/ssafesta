# Implementation Plan: AI 직원 / 문서 파이프라인

**Branch**: `docs/S15P21A604-647-spec007-revision` | **Date**: 2026-09-11 (개정) · 2026-09-06 (최초) | **Spec**: [spec.md](./spec.md)
**Input**: GitLab Work Item #119의 게시 합의와 Spring Flyway V21, 2026-09-11 결정 4건(`S15P21A604-647`)
**Decision record**: [research.md](./research.md), [GitLab #119](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/119), `docs/26_팀_결정_필요사항.md` 결정 기록 로그 2026-09-11

## Summary

부스 편집자가 등록한 문서를 비동기로 파싱·청킹·임베딩해 `boothId + agentId`로 격리된 검색 자료로 만든다. AI 문서의 Document·Job·Chunk·staging과 pgvector는 Spring Business DB가 단일 소유하고, FastAPI는 DB credential 없이 문서 처리와 질의 임베딩 계산만 수행한다. Spring은 Job을 먼저 만든 뒤 FastAPI에 처리 snapshot을 push하고, FastAPI는 결과를 batch·heartbeat·finalize·failed API로 돌려준다. finalize는 Chunk 교체와 Job·Document 상태를 하나의 Spring 로컬 트랜잭션으로 확정한다.

spec 007의 완료 경계는 문서가 `READY`가 되고 Spring 내부 검색 API에서 해당 scope의 Chunk를 조회할 수 있는 상태까지다. 실제 질문·답변과 SSE는 spec 008이 담당한다.

## Technical Context

**Language/Version**: Java 21/Spring Boot 3.5, Python 3.12/FastAPI
**Primary Dependencies**: Spring Data JPA/JDBC, Flyway, PostgreSQL pgvector, FastAPI, HTTPX, Pydantic, boto3, PDF parser, Embedding Provider adapter
**Storage**: AI 문서 영속 상태는 `festa_{env}_business`의 PostgreSQL 17 + pgvector. FastAPI는 문서 DB를 사용하지 않는다. 원본은 Cloudflare R2가 기본이며 운영자 승인 시 MinIO를 사용한다.
**Testing**: Spring 통합 테스트/Testcontainers, pytest 계약·Worker 테스트, 실제 PostgreSQL+pgvector 격리 fixture, storage·Embedding fake
**Target Platform**: Linux Docker container
**Project Type**: Spring 영속/API 서비스 + FastAPI 처리 Worker
**Performance Goals**: Agent당 문서 10개·100MB(= `N_total` 145,646 chunk)에서 검색 P95 1초 이하. Top-K 포함률은 층별로 다르다 — Spring 정확 스캔 100%(SC-007a), AI 의미 회수율 95% 이상(SC-007b)
**Constraints**: PDF·MD·TXT 20MB, vector 1536차원, scope 유출 0건, FastAPI 문서 DB credential 0개, 영구 `PROCESSING` 0건
**Scale/Scope**: P0 push Worker, batch 최대 200 Chunk/8MB, topK 최대 20

## Constitution Check

| 헌법 조항 | 검증 | 결과 |
|---|---|---|
| 1. Source of Truth | AI 문서 영속 상태는 Spring DB 하나만 갱신 | PASS |
| 3. AI 장애 격리 | 문서 상태 조회와 DB 정합성은 FastAPI 가용성에 의존하지 않음 | PASS |
| 10. 개별 CI/CD | Spring과 FastAPI는 계약 fake로 독립 검증 후 develop에서 통합 | PASS |
| 15. Secret 분리 | 방향별 Service Token·Provider credential은 Secret 주입, FastAPI DB credential 제거 | PASS |
| 17. Vector 격리 | Spring 검색 API가 `boothId + agentId + searchable + READY`를 강제하고 AI가 재검증 | PASS |
| 18. Embedding 차원 | `vector(1536)`과 `embedding_model_id` 검증 | PASS |
| 24. 계약 변경 | #119 게시 합의와 OpenAPI를 AI·BE가 공동 검토 | PASS |
| 27. 기준선 동결 | 기존 Unity 기준선과 무관한 서버 문서 파이프라인 변경 | PASS |
| 30. 미정 항목 | Agent 설정 조회 형태는 S15P21A604-399에서 확정. 2026-09-11 결정 4건(교체 원본 복구 제외·SC-007 분해·FAILED 7일·FR-040a)은 `docs/26` 결정 기록 로그에 등록 후 반영 | PASS |

### 설계 후 재검증

- FastAPI가 Document·Job·Chunk를 직접 읽거나 쓰는 경로를 제거한다.
- DB 커밋과 외부 callback 사이 정합성 문제가 Spring finalize 로컬 트랜잭션으로 사라진다.
- cancel 전달 실패와 늦은 Worker 결과는 `attemptNo` fencing으로 격리한다.
- Chunk 검색의 scope 조건은 클라이언트가 추가·삭제할 수 없는 Spring 서버 조건이다.

### 2026-09-11 개정 재검증 (`S15P21A604-647`)

- 상태 값을 늘리지 않고 `replaced_at` 하나로 복구 가능 여부를 가른다 → V24 CHECK·스윕 predicate·OpenAPI·FE 상태표가 그대로다.
- `FAILED` 원본 삭제가 `EXPIRED`와 같은 스윕 경로를 재사용한다 → 새 워커·새 큐가 생기지 않는다.
- finalize의 마지막 0행이 예외로 바뀌어 FR-040의 "하나의 트랜잭션"이 실제로 성립한다 (지금 구현은 이를 어긴다).
- SC-007이 층별로 갈려 각 기준이 측정 가능한 주체를 갖는다 — BE는 `S15P21A604-521`, AI는 T067.

## Project Structure

### Documentation

```text
specs/007-ai-agent-document/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── tasks.md
├── checklists/requirements.md
└── contracts/
    ├── document-processing-api.yaml
    ├── document-result-contract.md
    └── spring-storage-reconciliation-api.yaml

specs/008-ai-conversation-rag/contracts/
└── spring-chunk-search-api.yaml
```

Agent 설정 조회 계약은 S15P21A604-399에서 경로·응답·호출 빈도를 합의한 뒤 추가한다.

### Source Code

```text
backend/src/main/
├── java/com/example/ssafesta/ai/               # Document·Job·Chunk·staging 도메인
├── java/com/example/ssafesta/internal/ai/      # 처리 결과·검색·설정 내부 API
└── resources/db/migration/                     # V21 이후 forward migration

festa-ai/app/
├── api/v1/documents.py                         # Spring 처리 요청 접수
├── clients/                                    # Spring 결과·검색·설정 client
├── services/document_processing_service.py
├── workers/document_worker.py
└── providers/{storage,document_parser,embedding}.py
```

FastAPI의 `app/db`, Alembic migration, DB repository 기반 pickup/recovery 코드는 cutover 후 제거한다.

## Detailed Design

### 1. 상태와 소유권

- Spring `DocumentStatus`: `QUEUED / PROCESSING / READY / FAILED / DISABLED / EXPIRED`
- Spring `JobStatus`: `QUEUED / RUNNING / RETRY_WAIT / SUCCEEDED / DEAD / CANCELLED`
- Spring은 Document·Job·Chunk·staging의 유일한 영속 writer다.
- FastAPI는 전달받은 snapshot과 메모리의 in-flight 상태만 사용한다.
- **`EXPIRED`는 복구 가능 여부까지 말하지 않는다.** 그것은 `replaced_at`(V30 예정)이 가른다 — 값이 없으면 업로드 미완료 만료라 보존 기간 안에 복구되고, 값이 있으면 수정본 교체로 밀려난 원본이라 복구 대상이 아니다(FR-027a). 상태 값을 늘리지 않는 이유는 갈라야 할 것이 "복구 가능한가" 하나뿐이기 때문이다 — `REPLACED`를 새로 만들면 V24 CHECK 개정·스윕 predicate·OpenAPI·FE 상태표·기존 테스트가 전부 따라온다.

### 1-1. 원본 삭제 스윕

한 경로가 세 종류의 원본을 같은 방식으로 지운다. 대상 판정은 상태와 경과 시간뿐이고 `replaced_at`은 보지 않는다.

| 대상 | 유예 기준 | 유예 | 근거 |
|---|---|---|---|
| `EXPIRED` (업로드 미완료) | `expired_at` | 24시간 | FR-027·FR-028 |
| `EXPIRED` + `replaced_at` (교체된 원본) | `expired_at` | 24시간 | FR-028 — 복구 경로가 없는 행이 삭제 경로에서도 빠지면 원본만 무기한 남는다 |
| `FAILED` | `updated_at` | **7일** | FR-028a — 조사·재처리는 유예 안에서 한다 |

물리 삭제는 트랜잭션 밖이라 사후 조건부 `UPDATE`로 되돌릴 수 없다. 조회와 삭제 사이에 복구·재처리가 끼는 경쟁은 **허용하고 `ERROR` 경보로 드러낸다** — 스윕 인스턴스가 하나이고 경쟁 창이 한 행의 저장소 왕복 한 번이라, 공유 claim/lock이나 영속 삭제 큐를 들이는 비용이 그 창이 막는 손해보다 크다. 경보가 실제로 울리면 그때 근거가 생긴다.

### 2. 처리 요청과 Worker 소유권

1. Spring이 업로드 원본과 소유권·임대·문서 상태를 검증한다.
2. Spring이 영속 Job을 먼저 만들고 `jobId + attemptNo`와 문서·저장소·처리 버전 snapshot을 FastAPI에 보낸다.
3. FastAPI는 요청을 검증해 202로 접수하고 Worker가 원본을 처리한다.
4. FastAPI는 30초마다 `jobId + attemptNo + workerId` heartbeat를 보내고 Spring은 일치할 때만 lease를 90초 연장한다.
5. lease가 만료되면 Spring이 Job을 회수해 attempt를 증가시키고 1·5·15분 backoff로 최대 세 번 재요청한다.

처리 접수·cancel 계약: [document-processing-api.yaml](./contracts/document-processing-api.yaml)

### 3. 파싱·청킹·임베딩

- FastAPI는 snapshot의 Provider·bucket·object key로 원본을 읽고 실제 SHA-256을 다시 계산한다.
- 해시 불일치는 `SOURCE_HASH_MISMATCH`로 failed를 전송하며 Chunk를 만들지 않는다.
- PDF는 페이지 정보를, MD·TXT는 UTF-8 텍스트를 보존한다.
- chunk size·overlap은 배포 설정이며 embedding은 1536차원을 검증한다.

#### 최대 부하 fixture 규모 `N_total` = 145,646 chunk

SC-007a와 `S15P21A604-521`의 Testcontainers fixture가 쓸 상수다. **보수적 상한**이며, 실제 문서는 이보다 훨씬 적은 청크를 만든다.

| 단계 | 근거 | 값 |
|---|---|---|
| ① 에이전트당 원본 총량 | FR-018 / `config.py` `agent_document_max_total_bytes` | 104,857,600 B (100 MiB) |
| ② 바이트 → 토큰 상한 | cl100k_base BPE 어휘에 256개 단일 바이트가 모두 있어 토큰 하나는 최소 1바이트다 → **토큰 수 ≤ 바이트 수** | ≤ 104,857,600 토큰 |
| ③ 가장 조밀한 contentType | `text/markdown`·`text/plain`은 파일 바이트 = UTF-8 텍스트 바이트(비율 1.0). `application/pdf`는 컨테이너·폰트·xref 오버헤드가 있어 같은 바이트에서 추출 텍스트가 더 적다 → **TXT·MD가 최악** | 비율 1.0 채택 |
| ④ chunk stride | `config.py` `chunk_size` 900 − `chunk_overlap` 180 (`text_chunker.chunk_pages`의 `step`) | 720 토큰 |
| ⑤ 페이지 경계 보정 | MD·TXT는 문서 전체가 1 페이지(`document_parser._parse_plain_text`)이고, 페이지마다 마지막 부분 창이 하나 더 생긴다. 문서 수 상한은 10(FR-018) | +10 |

**`N_total` = ⌈104,857,600 / 720⌉ + 10 = 145,636 + 10 = 145,646**

- ②는 증명 가능한 상한이다. 한국어는 cl100k_base에서 대략 2~3 B/token, 영어는 약 4 B/token이라 실측은 이 값의 1/2~1/4 수준이 된다.
- 한 페이지의 창 개수는 `⌈(t − 900) / 720⌉ + 1`(t ≤ 900이면 1)이다. 문서 수 상한 10을 그대로 더하면 어떤 분할에서도 이 값을 넘지 않는다 — 가장 나쁜 분할의 실측 합은 145,640이다.
- 상한 밖으로 두는 것: **텍스트가 거의 없는 페이지를 극단적으로 많이 담은 PDF.** 페이지마다 1 청크가 나므로 산술적으로는 이 값을 넘을 수 있지만, 그 구성은 정상 문서가 아니고 부하 fixture의 기준이 되지 않는다. 실제로 문제가 되면 그때 페이지 수 상한을 별도로 정한다.

### 4. Batch와 finalize

- batch 상한은 200 Chunk 또는 8MB 중 먼저 도달하는 값이다.
- Spring staging PK는 `(job_id, batch_seq, chunk_no)`이며 같은 payload 재전송은 멱등 성공한다.
- out-of-order batch 자체는 허용한다. 같은 키의 내용 충돌과 finalize 시 누락된 `chunkNo`는 거부한다.
- finalize는 staging 개수, 0부터 이어지는 chunk 번호, source hash, embedding model, 1536차원을 검증한다.
- 검증 후 기존 Chunk 삭제 → 신규 Chunk 반영 → `searchable=true` → Job `SUCCEEDED` → Document `READY`를 한 트랜잭션으로 처리한다.
- 중간 실패는 전부 rollback되어 기존 검색 가능 Chunk를 보존한다.
- **마지막 `READY` 전환이 0행이어도 예외로 롤백한다** (FR-040a). 문서가 그 사이 `DISABLED`·`EXPIRED`가 된 경우이며, 여기서 로그만 남기고 커밋하면 검색되지 않는 문서에 새 Chunk가 붙고 Job은 `SUCCEEDED`가 되어 FR-040의 원자성이 깨진다.
- 롤백은 `SUCCEEDED` 표시도 되돌리므로 재전송이 finalize 멱등 분기(Job `SUCCEEDED`일 때만 진입)에 닿지 못한다. 그래서 이 경로의 응답은 **`410 JOB_GONE`**이고 반복 요청도 무변화 410이다 — 워커가 attempt를 올려 다시 시도해도 결과가 같다는 뜻이라 `409`(stale attempt)와 구분된다.
- `DISABLED`·`EXPIRED` 문서를 `PROCESSING`으로 되돌리는 복구는 이 spec 범위 밖이다.

결과 수신 의미 계약: [document-result-contract.md](./contracts/document-result-contract.md). Endpoint와 DTO 명칭은 Jira S15P21A604-400에서 합의한 뒤 OpenAPI로 고정한다.

### 5. 오류·취소·늦은 결과

- 모든 결과 요청은 `jobId + attemptNo`를 사용한다.
- stale attempt는 409, 삭제·취소된 Job 또는 비활성 문서는 410이다.
- failed는 안정적인 failure code, 정제된 message, retryable 여부를 전달한다.
- 문서 삭제·비활성화·임대 만료는 Spring DB에서 Job `CANCELLED`와 Chunk·staging 정리를 먼저 확정한다.
- FastAPI cancel은 멱등이며 전달 실패가 Spring DB 정합성을 바꾸지 않는다.

### 6. RAG 검색

- FastAPI는 Conversation이 보관한 `boothId + agentId`, 1536차원 query embedding, topK를 Spring에 보낸다.
- Spring은 `booth_id + agent_id + searchable=true + ai_documents.status=READY`를 강제한다.
- 요청은 임의 필터를 받지 않고 topK를 20 이하로 제한한다.
- 응답은 출처 필드와 cosine `distance`를 제공하며 최소 임계값은 적용하지 않는다.
- FastAPI는 응답 scope를 전건 재검증한 뒤 Context에 넣는다.
- **검색은 정확 스캔이다.** `SET LOCAL enable_indexscan = off`로 HNSW ordered index scan을 그 문장에서만 막는다 — scope 필터와 join이 붙은 상태에서 HNSW는 후필터라 매칭 청크가 있어도 `topK`보다 적게 돌려줄 수 있고, 오류 없이 짧아진 결과는 답변이 인용을 조용히 잃는 형태로만 드러난다. SC-007a의 100%가 성립하는 근거가 이것이고, 그래서 그 층에서 95%라는 비율은 측정 대상이 아니다.

계약: [spec 008 spring-chunk-search-api.yaml](../008-ai-conversation-rag/contracts/spring-chunk-search-api.yaml)

### 7. Agent 추론 설정

설정의 Source of Truth는 Spring `ai_agents`다. FastAPI가 Business DB를 직접 읽지 않는다는 경계는 확정됐지만, 별도 endpoint 여부·경로·응답·호출 빈도·캐시 무효화는 S15P21A604-399에서 합의한다. 확정 전에는 경로나 캐시 정책을 문서 또는 코드로 고정하지 않는다.

### 8. 인증·관측

- Spring→FastAPI 처리·cancel은 `INTERNAL_SPRING_TO_AI_TOKENS`를 사용한다.
- FastAPI→Spring 결과·검색은 `INTERNAL_AI_TO_SPRING_TOKENS`를 사용한다.
- 수신자는 최대 두 토큰을 상수 시간으로 검증하고 반대 방향 토큰을 401로 거부한다.
- Spring은 영속 Job 상태를 기준으로 DEAD 비율·callback 미전달을 집계·경고한다. 양쪽 로그에는 `jobId`, `documentId`, `correlationId`, `attemptNo`, `workerId`를 남기되 원문·Secret·object key를 남기지 않으며, FastAPI는 이 상태를 중복 저장·집계하지 않는다.

### 9. Cutover

1. V21 배포와 스키마 검증
2. Spring 처리 결과·검색 API 배포
3. FastAPI consumer를 Spring API로 전환
4. 처리 중 Job 0건과 결과 API 통합 검증
5. FastAPI DB credential·ORM·Alembic·DB pickup/recovery 코드 제거
6. 배포 설정에서 FastAPI PostgreSQL 연결이 0건임을 검증

실데이터가 있으면 전환 전에 환경별 Job·Chunk 수와 상태를 확인하고 export/import·해시·chunk count 대조를 추가한다.

## Complexity Tracking

헌법 위반을 정당화하는 예외 없음.
