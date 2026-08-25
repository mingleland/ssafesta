# AI 문서 처리 Job — BE(DB) 관점 검토

> 황덕 백엔드 파트. spec 007 SC-003(영원히 "처리중"에 머문 문서 0건) 관련 AI 파트 확인 요청에 대한 답변 근거.
> 작성일: 2026-08-20 | 관련: spec 007 · 헌법 1·3·10·17·18조 · docs/07 §5 · docs/13 · docs/14 · docs/15
> **모든 권고는 제안이며 확정이 아니다** (헌법 30조). 확정 필요 항목은 §7에 모았다.

---

## 0. 사실 확인

요청서의 현황 인식은 **전부 맞다.** 코드로 확인했다 (`13c26c4`).

| 주장 | 확인 |
|---|---|
| `ai_documents.processing_status` 존재 | ✅ `V1__initial_schema.sql:71` |
| `ai_document_chunks` + `UNIQUE(document_id, chunk_no)` 존재 | ✅ `V1__initial_schema.sql:77-82` |
| Job / heartbeat / retry 저장 구조 없음 | ✅ V1~V5 어디에도 없음 |
| 원본·메타데이터 Spring / 처리·검색 FastAPI | ✅ spec 007 FR-010, docs/07 §5 |

### 다만 세 가지를 덧붙인다

**(1) 이미 결정된 것이 있다.** 새로 정하기 전에 확인이 필요하다.

- `docs/07 §5 데이터 소유권` 표는 이미 확정돼 있다.
  `AI Document file → Spring metadata / AI processing`, `AI Chunk / Embedding → FastAPI AI`
- `docs/13 §6`이 Document 상태 5종을 확정했다: `QUEUED / PROCESSING / READY / FAILED / DISABLED`
- `docs/14 §7`의 `POST /ai/v1/documents/process` 응답에 **`jobId`가 이미 계약으로 존재**한다 (`"job_doc_152"`)
- `docs/13 §5` Document Pipeline에 **"Processing Job 생성" 단계가 이미 그려져 있다**

즉 Job 개념 자체는 신설이 아니라 **문서에는 있는데 저장 구조가 없는 상태**다.

**(2) `실패 사유` 컬럼이 없다 — 이건 이 논의와 무관하게 이미 결손이다.**

spec 007이 두 곳에서 요구한다.

- FR-007: "실패한 문서는 **사유**를 제공해야 한다"
- Key Entities: "Document: 부스, 에이전트, 파일명, 크기, 상태, **실패 사유**, 업로드 시각"

그런데 `ai_documents`에는 `processing_status`뿐이다. 재시도 상한에 도달한 문서를 `FAILED`로 떨굴 때 **사유를 적을 곳이 없다.**
`coin_reconciliation_runs.failure_reason TEXT`라는 기존 선례가 있으니 같은 패턴으로 채운다. → §5 BE 작업 1번.

**(3) `docs/14 §8`이 헌법 3조와 충돌한다.** → §1에서 다룬다.

---

## 1. DocumentStatus vs JobStatus — 저장·소유 주체

### 권고 — 분리한다. Document는 Spring, Job은 FastAPI

| | 소유 | 저장 | 내용 |
|---|---|---|---|
| **DocumentStatus** | **Spring** | `ai_documents.processing_status` | `QUEUED/PROCESSING/READY/FAILED/DISABLED` (docs/13 §6 그대로) + `failure_reason` |
| **JobStatus** | **FastAPI** | FastAPI 소유 테이블 (§2) | `attempt_no`, `lease_expires_at`, `worker_id`, `last_error`, `next_retry_at` |

**핵심 논거는 헌법 3조(AI 장애 격리)다.**

> 3. FastAPI 장애가 월드 접속·비AI 기능을 중단시키지 않는다.

사용자에게 보이는 문서 상태가 FastAPI에만 있으면, **AI 서버가 죽는 순간 문서 관리 화면도 같이 죽는다.**
"AI가 지금 안 된다"와 "내가 올린 문서 목록을 볼 수 없다"는 전혀 다른 심각도다. 후자는 3조 위반이다.
반대로 내부 실행 상태(몇 번째 재시도인지, 어느 워커가 잡았는지)는 FastAPI가 죽으면 조회할 이유도 없다.

부수 효과로 UX도 정리된다. 2차 재시도 중에도 사용자 화면은 계속 "처리 중"이어야 하는데,
한 컬럼에 섞으면 재시도마다 화면이 깜빡이거나 사용자에게 `RETRYING`이라는 내부 개념이 노출된다.

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| **A. 분리 (권고)** | Document=Spring, Job=FastAPI | 헌법 3조 충족 · 관심사 분리 · UX 안정 · 각자 마이그레이션 독립 | 상태 전이 시 서비스 간 호출 1회 필요 (§3) |
| B. 단일 상태 통합 | `processing_status` 하나에 재시도까지 표현 | 테이블 추가 없음 · 가장 단순 | 사용자에게 내부 상태 노출 · FastAPI가 Spring 테이블 직접 쓰기 · 재시도 이력 소실 |
| C. 둘 다 Spring | Spring이 워커 실행 상태까지 관리 | 단일 DB 트랜잭션 · 조회 단순 | **Spring이 AI 파이프라인에 결합** — 헌법 3조가 막으려는 방향 · 워커 스케일 시 Spring이 병목 |
| D. 둘 다 FastAPI | 문서 상태까지 FastAPI 소유 | AI 파트 자율성 최대 | **헌법 3조 위반** · docs/07 §5("Spring metadata")와 정면 충돌 |

### ⚠️ 함께 정정이 필요한 기존 계약

`docs/14 §8`에 `GET /ai/v1/documents/{documentId}/status`가 **FastAPI 엔드포인트**로 정의돼 있다.
이걸 FE/사용자 경로로 쓰면 위의 3조 문제가 그대로 발생한다.

**권고**: 이 엔드포인트는 **내부 진단용으로 격하**하고, 사용자 노출 상태 조회는 Spring이 자기 DB에서 서빙한다.
`chunkCount`·`processedAt`처럼 FastAPI만 아는 값은 상태 전이 콜백(§3)에 실어 Spring에 넘긴다.

---

## 2. Processing Job 영속화 위치

### 권고 — 같은 RDS 인스턴스 · FastAPI 전용 스키마 (`ai`)

```text
RDS (PostgreSQL + pgvector)
├── public   ← Spring Flyway 관리 (users, booths, ai_documents, ai_document_chunks …)
└── ai       ← FastAPI 자체 마이그레이션 관리 (document_jobs …)
```

논거 셋:

**(1) 원자성.** 재처리 멱등성(§4)의 핵심은 *"이 문서의 chunk를 지우고 → 다시 넣고 → job을 완료로"* 가
**한 트랜잭션**이어야 한다는 것이다. 중간에 죽으면 조각이 반만 남는다.
`ai_document_chunks`가 이 DB에 있으므로 job도 같은 DB여야 이게 공짜로 성립한다. SQS나 별도 DB면 성립하지 않는다.

**(2) 배포 독립성.** 헌법 10조가 `ai`/`back` 파트 브랜치의 **개별 CI/CD**를 규정한다.
job 테이블을 Spring Flyway에 두면 AI 파트가 재시도 필드 하나 추가할 때마다 BE 마이그레이션 → BE 배포를 기다려야 한다.
스키마를 나누면 각자 배포한다.

**(3) SQS는 MVP 범위 밖이고, 애초에 이 문제를 혼자 못 푼다.**
`docs/15`는 Queue(SQS)를 *"문서 처리 부하가 커지면"* 분리하는 후보로 두고 **"MVP에는 동작 안정성이 우선"**이라 명시했고,
"SQS 도입 시점"은 미결 항목으로 남아 있다(docs/15:544).
게다가 SQS의 visibility timeout은 **재배달**을 해줄 뿐, *"3번째 시도 중이고 지난번엔 파싱에서 실패했다"* 를
사용자와 운영자에게 보여주려면 **어차피 DB 기록이 필요**하다. SQS는 job 테이블의 대체재가 아니라 추가재다.

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| A. Spring PG · `public` | Flyway가 job 테이블까지 관리 | 단일 스키마 · BE가 전체 조망 · FK로 정합성 강제 | **AI 배포가 BE 마이그레이션에 종속** (헌법 10조와 마찰) · 소유권 혼선 |
| **B. 같은 RDS · `ai` 스키마 (권고)** | 스키마로 소유권 분리 | 원자성 유지 · 배포 독립 · 인프라 추가 0 | 스키마/롤 권한 설계 필요 · 크로스 스키마 FK는 쓸 수 있으나 규율 필요 |
| C. SQS | 큐를 AWS에 위임 | 재배달·DLQ 무료 · 워커 수평 확장 | **원자성 상실** · 새 인프라+IAM+DLQ 운영 · docs/15상 MVP 범위 밖 · 상태 조회용 DB가 결국 또 필요 |
| D. Redis | ElastiCache에 job 보관 | 이미 도입 예정 · 빠름 | **docs/15가 금지** — "영구 비즈니스 기록을 Redis 단독으로 저장하지 않는다" · 재시도 이력이 휘발 |

> **연계 미결**: `docs/15 §9`가 *"pgvector 또는 별도 AI DB"* 를 아직 열어뒀다.
> 별도 AI DB로 가면 chunks와 job이 함께 이동하므로 (1)의 원자성 논거는 유지되지만 Flyway 경계가 다시 바뀐다.
> **이 결정이 먼저다.** → §7

### `ai_document_chunks`는 어디에 두나

**현행 유지(Spring Flyway 관리)를 권고한다.** 데이터 소유는 FastAPI지만 DDL은 Spring이 갖는 현재 구조를 바꾸지 않는다.

- `booths`/`ai_agents`/`ai_documents`로의 **FK가 헌법 17조(Vector 격리)의 하드 보장**이다. 앱 로직이 아니라 DB가 막는다
- FR-012("삭제 후 답변에 사용되지 않아야 한다")를 Spring 삭제 트랜잭션 안에서 보장할 수 있다
  (`AccountDeletionService`가 layout을 정리하는 것과 같은 패턴)
- 이미 `ix_ai_document_chunks_scope (booth_id, agent_id)`가 있다

---

## 3. FastAPI의 접근 방식

### 권고 — 하이브리드. 읽기·chunk 쓰기는 DB 직접, `ai_documents` 상태 전이는 Spring 내부 API

| 대상 | 방식 | 이유 |
|---|---|---|
| `ai_document_chunks` INSERT/DELETE | **DB 직접** | 조각 수십~수백 건. HTTP 중계는 비현실적. docs/07 §5가 이미 FastAPI 소유로 확정 |
| `ai_documents`, `ai_agents` 조회 | **DB 직접 (읽기 전용 롤)** | 처리에 필요한 `s3_key`·`agent_id` 등. 읽기는 소유권을 흔들지 않음 |
| `ai_documents.processing_status` 갱신 | **Spring 내부 API 콜백** | Spring 소유 테이블. 두 서비스가 같은 행을 쓰면 소유권이 무너지고 Flyway 변경 시 FastAPI가 깨진다 |
| `ai` 스키마 job 테이블 | **DB 직접** | FastAPI 소유 |

제안 엔드포인트 (BE 제공):

```text
PATCH /internal/ai/documents/{documentId}/status
{ "status": "READY", "chunkCount": 42, "failureReason": null, "processedAt": "..." }
```

**헌법 3조와의 방향성**: 3조가 금지하는 건 *"Spring이 AI 응답을 동기 중계하는 구조"* 다.
여기서 호출 방향은 **FastAPI → Spring 콜백**이고, Spring은 `process` 요청을 던지고 기다리지 않는다(fire-and-forget).
따라서 3조에 저촉되지 않는다. 콜백이 실패해도 FastAPI가 재시도하면 되고, 그동안 문서는 `PROCESSING`으로 남을 뿐이다.

### 후보안

| | 방식 | 장점 | 단점 |
|---|---|---|---|
| A. 전부 DB 직접 | FastAPI가 `ai_documents`까지 직접 UPDATE | 가장 빠름 · 서비스 간 호출 0 | **소유권 붕괴** · Spring 스키마 변경이 FastAPI를 조용히 깨뜨림 · 상태 전이 규칙이 두 곳에 중복 |
| B. 전부 Spring API 경유 | chunk 삽입까지 HTTP | 경계가 가장 깨끗 · DB 자격증명 1곳 | **벡터 대량 삽입 비현실적** · Spring이 AI 파이프라인 경로에 들어옴(3조 역행) · 지연·페이로드 폭증 |
| **C. 하이브리드 (권고)** | 쓰기 소유권 기준으로 분리 | 소유권 유지 + 성능 · 3조 정합 | 규칙을 지켜야 함(문서화 필요) · DB 롤 권한 2종 설계 |

---

## 4. 재시작 복구 정책

### 4-1. stale 판정 — 고정 타임아웃이 아니라 **Lease(임대)**

```text
job 행:  status=RUNNING, worker_id, lease_expires_at
워커:    처리 중 주기적으로 lease_expires_at 갱신 (heartbeat)
스위퍼:  now() > lease_expires_at AND status='RUNNING'  →  죽은 것으로 간주, 회수
```

**"`started_at`으로부터 N분"은 쓰지 않기를 권고한다.** 큰 PDF는 오래 걸리는 게 정상이고,
고정 타임아웃은 *정상 작업을 죽이는* 쪽으로 틀린다. 그러면 같은 문서를 두 번 임베딩해 비용이 새고
`UNIQUE(document_id, chunk_no)` 충돌이 정상 경로에서 터진다.
Lease는 **살아있는 동안 계속 연장되므로 처리 시간과 무관하게 정확**하다.

우리 저장소의 기존 동시성 패턴과도 결이 같다 — 임대는 `ux_booth_leases_active_slot` 부분 유니크 인덱스로
"활성 임대는 하나"를 DB가 강제한다. job도 `UNIQUE(document_id) WHERE status IN ('QUEUED','RUNNING')` 같은
부분 유니크로 **같은 문서에 job이 둘 생기는 것**을 DB가 막을 수 있다.

**권고 수치** (팀 확정 필요): heartbeat **30초** / lease TTL **90초**(=3회 연속 누락) / 스위퍼 주기 **60초**

### 4-2. 재시도 상한 — **3회**, 이후 `FAILED` + 사유

근거: 일시 장애(네트워크, LLM 5xx, S3 순단)는 통상 1~2회 내 통과한다.
3회를 넘기면 문서 자체 문제일 확률이 높고 — 대표적으로 스캔 PDF(spec 007 C-06은 MVP 제외 + 사유 안내 권장) —
무한 재시도는 **임베딩 API 비용을 태운다**. 재시도 간격은 지수 백오프(예: 1분 → 5분 → 15분).

상한 도달 시 `ai_documents`를 `FAILED` + `failure_reason`으로 전이한다 → **§0-(2) 컬럼이 필요한 이유가 여기다.**

회수·재시도 주체는 **FastAPI 자체 스위퍼**를 권고한다. Spring이 FastAPI 내부 job을 청소하러 들어가면 3조가 다시 무너진다.
다만 안전망으로 **Spring 쪽에 "N시간 이상 `PROCESSING`인 문서" 운영 지표**를 두는 건 별개로 유용하다
(`coin_reconciliation_runs`가 "탐지만 하고 고치지 않는" 것과 같은 성격).

### 4-3. Chunk 멱등 처리 — 기반은 이미 있다. 다만 함정이 하나 있다

`UNIQUE(document_id, chunk_no)`가 이미 존재하므로 `INSERT ... ON CONFLICT (document_id, chunk_no) DO UPDATE`로
재시도가 안전해진다. **여기까지는 스키마 변경 없이 된다.**

**함정**: `ON CONFLICT`만으로는 부족하다. 재시도 때 chunk 개수가 **줄어들면** 이전 시도의 잔여 조각이 남는다.

```text
1차 시도: 40조각 삽입 → chunk_no 0..39 → 죽음
파서 개선/설정 변경 후
2차 시도: 25조각 생성 → chunk_no 0..24 UPSERT
결과:     chunk_no 25..39 가 그대로 남는다  ← 유령 조각이 검색에 잡힌다
```

이건 헌법 17조(Vector 격리)까지는 아니지만 **오래된 내용이 답변에 인용되는** 실질적 오염이다.

**권고**: 재처리 시작 시 `DELETE FROM ai_document_chunks WHERE document_id = ?` 후 전량 재삽입을,
**chunk 삽입 및 job 완료 표시와 같은 트랜잭션**으로 묶는다.
조각 수가 최대 수백 건 수준이라 전량 재삽입 비용이 부분 갱신 로직의 복잡도보다 싸다.
→ **이것이 §2에서 job을 같은 DB에 두자고 한 이유다.** 다른 저장소면 이 트랜잭션이 성립하지 않는다.

### 후보안 요약

| 항목 | 권고 | 대안 | 대안의 단점 |
|---|---|---|---|
| stale 판정 | Lease + heartbeat | `started_at + N분` 고정 타임아웃 | 정상 장시간 작업을 죽임 · 중복 임베딩 |
| | | SQS visibility timeout | 원자성 없음 · 이력 조회 불가 |
| 재시도 상한 | 3회 + 지수 백오프 | 무제한 | 비용 폭주 · 영구 `PROCESSING`과 사실상 동일 |
| | | 1회(재시도 없음) | 일시 장애에 사용자가 수동 재업로드 |
| 멱등성 | 전량 DELETE 후 재삽입 (한 트랜잭션) | `ON CONFLICT` 단독 | **유령 조각** |
| | | chunk에 `attempt_no` 추가 후 이전 attempt 정리 | 스키마 변경 필요 · 정리 누락 시 같은 문제 |

---

## 5. 담당 범위

### BE (Spring / Flyway) — 내가 한다

| # | 항목 | 비고 |
|---|---|---|
| 1 | `V6` — `ai_documents.failure_reason TEXT` 추가 | FR-007이 이미 요구. **이 논의와 무관하게 필요** |
| 2 | `V6` — `ai_documents.processing_started_at`, `last_processed_at` (선택) | 사용자 노출 수준만. 내부 attempt/lease는 넣지 않음 |
| 3 | `PATCH /internal/ai/documents/{documentId}/status` 내부 API | 인증은 내부 전용(서비스 토큰 또는 SG 제한). 헌법 15조상 키는 Secret 저장소 |
| 4 | 사용자용 문서 상태·목록 조회를 Spring에서 서빙 | docs/14 §8 정정과 연동 |
| 5 | `ai` 스키마 생성 + FastAPI용 DB 롤/권한 | 인프라 담당과 협의. Flyway로 할지 인프라 프로비저닝으로 할지 결정 필요 |
| 6 | (선택) "N시간 이상 `PROCESSING`" 운영 지표 | 탐지 전용. 자동 교정 안 함 |

### AI (FastAPI)

| # | 항목 |
|---|---|
| 1 | `ai` 스키마 job 테이블 + 자체 마이그레이션(Alembic 등) |
| 2 | 워커 lease 갱신(heartbeat) · 기동 시/주기 스위퍼 |
| 3 | 재시도·백오프·상한 처리, 상한 도달 시 Spring 콜백으로 `FAILED` + 사유 전달 |
| 4 | 재처리 시 chunk 전량 재삽입을 한 트랜잭션으로 |
| 5 | `POST /ai/v1/documents/process` 멱등 처리 (같은 documentId 중복 요청 시 기존 job 반환) |

### 스키마 변경이 **필요 없는** 것

- Chunk 중복 방지의 기반(`UNIQUE(document_id, chunk_no)`) — **이미 있다**
- Document 상태 5종 — **이미 정의돼 있다** (docs/13 §6)
- `jobId` 계약 — **이미 있다** (docs/14 §7)

---

## 6. 요약

| # | 질문 | 권고 |
|---|---|---|
| 1 | 상태 소유 | **분리.** DocumentStatus=Spring / JobStatus=FastAPI (헌법 3조) |
| 2 | Job 저장 위치 | **같은 RDS, `ai` 전용 스키마.** SQS는 MVP 범위 밖 + 원자성 상실 |
| 3 | 접근 방식 | **하이브리드.** chunk·읽기는 DB 직접, `ai_documents` 갱신은 Spring 내부 API |
| 4 | 복구 정책 | **Lease+heartbeat**(고정 타임아웃 아님) / **3회** 상한 / **전량 재삽입** 멱등 |
| 5 | 담당 | BE: `failure_reason` 마이그레이션 + 내부 상태 API + `ai` 스키마 권한 / AI: job 테이블·워커·스위퍼 |

---

## 7. 팀 확정이 필요한 항목 (헌법 30조 — 임의 확정하지 않음)

| # | 항목 | 결정권 | 비고 |
|---|---|---|---|
| 1 | **pgvector 같은 DB vs 별도 AI DB** | BE + AI + 인프라 | `docs/15 §9` 미결. **§2보다 먼저 정해야 한다** |
| 2 | `GET /ai/v1/documents/{id}/status`의 사용자 노출 여부 | BE + AI + FE | 현재 계약이 헌법 3조와 충돌 (§1) |
| 3 | heartbeat 30s / lease TTL 90s / 스위퍼 60s | BE + AI | 권고 수치. 실측 후 조정 |
| 4 | 재시도 상한 3회 · 백오프 1/5/15분 | BE + AI | 임베딩 비용과 직결 |
| 5 | 내부 API 인증 방식 (서비스 토큰 vs SG 제한 vs mTLS) | BE + 인프라 | 헌법 15조 |
| 6 | SQS 도입 시점 | 인프라 | `docs/15:544` 기존 미결 |
| 7 | `failure_reason` 사용자 노출 문구 정책 | AI + FE | 원문 스택트레이스 노출 금지 |
