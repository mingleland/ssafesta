# SSAFY FESTA AI Server API 명세서

> **서버**: FastAPI  
> **기본 통신**: REST(JSON)  
> **실시간 응답**: SSE 우선 검토  
> **문서 저장**: S3  
> **Vector DB**: PostgreSQL + pgvector  
> **인증**: Spring 발급 JWT 기반, 검증 방식 세부 TBD

---

## 1. API 영역

| 영역 | 용도 |
|---|---|
| Conversation | AI 대화 세션 |
| Chat | 일반/Streaming 답변 |
| Document | 문서 처리 |
| Handoff | 사람 상담 전환 요약 |
| Agent Test | 운영자 테스트(P2) |
| Health | 상태 확인 |
| Voice | STT/TTS(P2) |

---

## 2. 공통 Header

```http
Authorization: Bearer <access-token>
Content-Type: application/json
```

Streaming:

```http
Accept: text/event-stream
```

---

## 3. 공통 식별자

```text
userId
boothId
agentId
conversationId
documentId
requestId
```

클라이언트가 전달한 ID를 그대로 신뢰하지 않고 권한·Scope를 검증한다.

---

## 4. Conversation 생성

### POST `/ai/v1/conversations`

#### Request

```json
{
  "boothId": 7,
  "agentId": 78
}
```

#### Response

```json
{
  "conversationId": "conv_01JABCXYZ",
  "boothId": 7,
  "agentId": 78,
  "status": "ACTIVE",
  "createdAt": "2026-08-08T16:00:00+09:00"
}
```

#### 처리

1. 인증 확인
2. FastAPI가 Spring을 서버 간 호출해 Booth / Agent / Lease 접근 가능 여부 확인
3. Spring 검증 응답 본문의 `leaseEndsAt` 저장
4. Agent 설정 로드
5. Conversation 생성

#### Lease 검증 계약 (spec 008 C-09, Issue #14)

- Spring 검증은 **Conversation 생성 시 한 번만** 수행한다. 이후 질문마다 Spring을 호출하지 않는다.
- Spring은 서버 간 검증 응답 본문에 UTC 기준 `leaseEndsAt`을 전달한다. 서명된 Snapshot은 사용하지 않는다.
- 검증 호출 timeout은 1초이며 1회 재시도해 총 2초를 넘기지 않는다. 최종 실패 시 Conversation을 생성하지 않는 Fail Closed를 적용한다.
- FastAPI는 이후 질문마다 자체 UTC 시각과 저장된 `leaseEndsAt`을 비교한다. 만료됐으면 검색·LLM 호출 전에 HTTP `409`와 `BOOTH_LEASE_EXPIRED`를 반환한다.

### DELETE `/ai/v1/conversations/{conversationId}`

React AI Chat Overlay가 정상적으로 닫힐 때 호출한다.

- 성공 응답은 `204 No Content`다.
- FastAPI는 Conversation과 휘발성 Message·요약 원문을 즉시 삭제한다.
- 연결 종료 등으로 호출이 유실되면 마지막 활동 시각 기준 30분 TTL이 삭제를 보장한다.
- 이미 삭제됐거나 만료된 Conversation에 대한 반복 호출은 멱등하게 처리한다.

---

## 5. 일반 Chat

### POST `/ai/v1/conversations/{conversationId}/messages`

Streaming을 사용할 수 없는 환경의 기본 REST 방식.

#### Request

```json
{
  "message": "이 프로젝트에서 사용한 기술 스택을 설명해 주세요."
}
```

#### Response

```json
{
  "messageId": "msg_123",
  "conversationId": "conv_01JABCXYZ",
  "role": "assistant",
  "content": "이 프로젝트에서는 React, Spring Boot, Unity 등을 사용했습니다.",
  "sources": [
    {
      "documentId": 152,
      "title": "프로젝트_기획서.pdf",
      "chunkId": "chunk_152_08"
    }
  ],
  "handoffRecommended": false,
  "createdAt": "2026-08-08T16:01:15+09:00"
}
```

---

## 6. Streaming Chat

### POST `/ai/v1/conversations/{conversationId}/stream`

#### Request

```json
{
  "message": "이 프로젝트의 핵심 기능을 알려주세요."
}
```

### Event: start

```text
event: start
data: {"type":"start","requestId":"req_01JABC","conversationId":"conv_01JABCXYZ","messageId":"msg_124","sequence":0}
```

### Event: token

```text
event: token
data: {"type":"token","requestId":"req_01JABC","conversationId":"conv_01JABCXYZ","messageId":"msg_124","sequence":1,"delta":"이 프로젝트의 "}
```

### Event: source

```text
event: source
data: {"type":"source","requestId":"req_01JABC","conversationId":"conv_01JABCXYZ","messageId":"msg_124","sequence":8,"documentId":152,"title":"프로젝트_기획서.pdf","chunkId":"chunk_152_03"}
```

`sourceUrl`은 선택 필드로 예약한다. P0에서는 원문 접근 계약이 없으므로 생략하고 문서명만 표시한다. 향후 제공할 때는 인증·권한 검증이 적용된 URL만 허용하며 S3 Key나 무제한 공개 URL을 전달하지 않는다.

### Event: done

```text
event: done
data: {"type":"done","requestId":"req_01JABC","conversationId":"conv_01JABCXYZ","messageId":"msg_124","sequence":10,"handoffRecommended":false}
```

P0에서 `handoffRecommended`는 타입에 유지하지만 별도 FE 동작은 하지 않는다. 사람 상담 전환 UI는 spec 011(P1)에서 구현한다.

### Event: error

```text
event: error
data: {"type":"error","requestId":"req_01JABC","conversationId":"conv_01JABCXYZ","messageId":"msg_124","sequence":4,"code":"LLM_TIMEOUT","message":"AI 응답이 지연되고 있습니다.","retryable":true,"timeoutPhase":"FIRST_TOKEN"}
```

`LLM_TIMEOUT`은 코드 하나를 유지하고 `timeoutPhase`로 `FIRST_TOKEN`(첫 token 15초 초과)과 `TOTAL_RESPONSE`(전체 60초 초과)를 구분한다. `retryAfterSeconds`는 재시도 시점을 계산할 수 있을 때만 포함한다.

#### 이벤트 순서·재시도

- 모든 `data`는 `type`, `requestId`, `conversationId`, `messageId`, `sequence`를 포함하며 `type`은 SSE `event`와 같아야 한다.
- 정상 순서는 `start → token 0..N → source 0..N → done`, 실패 순서는 `start → token/source 0..N → error`다.
- `sequence`는 `start=0`부터 1씩 증가하는 무결성 검증 값이며 재개 offset이 아니다.
- 자동 재연결은 지원하지 않는다. 재시도는 같은 `conversationId`와 새 `requestId`·`messageId`를 사용한다.
- `done`에 도달하지 못한 사용자 질문과 부분 AI 응답은 대화 이력에 확정 저장하지 않는다.

#### Stream 중 Lease 만료

- Stream 시작 전에 Lease가 만료됐으면 SSE를 열지 않고 HTTP `409`와 `BOOTH_LEASE_EXPIRED`를 반환한다.
- Stream 도중 만료되면 이미 진행 중인 응답 1건만 기존 전체 응답 timeout인 최대 60초 안에서 완료한다.
- 진행 중 Stream에는 별도의 만료 Push나 `LEASE_EXPIRED` 이벤트를 보내지 않는다. 해당 응답 종료 후의 새 질문부터 `BOOTH_LEASE_EXPIRED`로 차단한다.

---

## 7. Document Processing

파일 업로드 자체는 Spring/S3가 담당하고 FastAPI는 업로드된 문서를 처리한다.

### POST `/ai/v1/documents/process`

#### Request

```json
{
  "documentId": 152,
  "boothId": 7,
  "agentId": 78,
  "s3Key": "booths/7/agents/78/documents/152/project.pdf"
}
```

#### Response

```json
{
  "jobId": "job_doc_152",
  "documentId": 152,
  "status": "QUEUED"
}
```

### 처리

```text
S3 Download
→ Parsing
→ Normalization
→ Chunking
→ Embedding
→ pgvector 저장
→ READY
```

---

## 8. Document Status

### GET `/ai/v1/documents/{documentId}/status`

#### Response

```json
{
  "documentId": 152,
  "status": "READY",
  "chunkCount": 42,
  "processedAt": "2026-08-08T16:10:00+09:00"
}
```

### Status

| Status | 설명 |
|---|---|
| `QUEUED` | 대기 |
| `PROCESSING` | 처리 중 |
| `READY` | 검색 가능 |
| `FAILED` | 처리 실패 |
| `DISABLED` | 검색 제외 |

---

## 9. Handoff Summary

### POST `/ai/v1/conversations/{conversationId}/handoff-summary`

#### Response

```json
{
  "conversationId": "conv_01JABCXYZ",
  "summary": "사용자는 프로젝트의 기술 스택과 참여 방법을 질문했습니다.",
  "topics": ["기술 스택", "프로젝트 참여"],
  "lastUserIntent": "프로젝트 담당자에게 참여 방법을 문의하고 싶음"
}
```

FastAPI는 Summary만 생성하며 Staff 선택·상담방 생성은 Spring이 담당한다.

---

## 10. Agent Test — P2

### POST `/ai/v1/agents/{agentId}/test`

#### Request

```json
{
  "message": "이 프로젝트는 어떤 문제를 해결하나요?"
}
```

#### Response

```json
{
  "answer": "프로젝트 설명...",
  "retrievedChunks": [
    {
      "documentId": 152,
      "chunkId": "chunk_152_03",
      "score": 0.88,
      "preview": "..."
    }
  ],
  "latencyMs": 820
}
```

---

## 11. Health Check

### GET `/ai/health`

```json
{
  "status": "UP"
}
```

내부 상세 Health 후보:

```json
{
  "status": "UP",
  "llm": "UP",
  "database": "UP",
  "vectorStore": "UP",
  "s3": "UP"
}
```

외부 Endpoint에 인프라 상세를 과도하게 노출하지 않는다.

---

## 12. Voice — P2

후보:

```text
POST /ai/v1/stt
POST /ai/v1/tts
```

Codec, Streaming, Provider는 TBD다.

---

## 13. 오류 형식

```json
{
  "type": "ERROR",
  "code": "AGENT_NOT_FOUND",
  "message": "AI Agent를 찾을 수 없습니다.",
  "requestId": "req_123"
}
```

### Error Code

| Code | 설명 | 기본 `retryable` |
|---|---|---|
| `INVALID_REQUEST` | 요청 형식 오류 | `false` |
| `UNAUTHORIZED` | 인증 실패 | `false` |
| `FORBIDDEN` | 권한 없음 | `false` |
| `BOOTH_NOT_FOUND` | Booth 없음 | `false` |
| `AGENT_NOT_FOUND` | Agent 없음 | `false` |
| `AGENT_DISABLED` | Agent 비활성 | `false` |
| `BOOTH_LEASE_EXPIRED` | Booth 임대 만료 — 신규 Conversation·질문 차단 | `false` |
| `CONVERSATION_NOT_FOUND` | Conversation 없음 | `false` |
| `DOCUMENT_NOT_FOUND` | 문서 없음 | `false` |
| `DOCUMENT_NOT_READY` | 문서 미처리 | `true` |
| `DOCUMENT_PROCESSING_FAILED` | 처리 실패 | `false` |
| `RAG_SEARCH_FAILED` | 검색 실패 | `true` |
| `LLM_TIMEOUT` | LLM Timeout — `timeoutPhase`로 첫 token/전체 응답 구분 | `true` |
| `LLM_PROVIDER_ERROR` | Provider 오류 | `true` |
| `STREAM_CLOSED` | Streaming 비정상 종료 | `true` |
| `RATE_LIMITED` | 요청 제한 — `Retry-After` 또는 `retryAfterSeconds` 제공 | `true` |
| `INTERNAL_ERROR` | 내부 오류 | `true` |

---

## 14. Rate Limit

초기값은 환경 설정으로 조정할 수 있으며 다음 계약을 적용한다.

| 범위 | 한도 | 초과 처리 |
|---|---|---|
| 사용자 | 활성 Stream 1개, 슬라이딩 60초간 유효 질문 5회 | 대기열 없이 즉시 `429 + Retry-After` |
| AI Agent | 활성 Stream 5개 | 대기열 없이 즉시 `429 + Retry-After` |
| 서비스 전체 | 활성 Stream 20개 | FIFO 대기열 진입 |
| 전체 대기열 | 최대 30개, 최대 10초 | 가득 찼거나 10초 초과 시 `429 + Retry-After` |

입력 검증에서 거부되어 LLM을 호출하지 않은 요청은 사용자 질문 횟수에 포함하지 않는다.

---

## 15. Logging

최소:

```text
requestId
conversationId
boothId
agentId
latency
ragLatency
llmLatency
tokenUsage
errorCode
```

Access Token, API Key, Password는 로그에 기록하지 않는다.

---

## 16. 호출 Sequence

### 기본 상담

```text
Client              Spring             FastAPI            Vector/LLM
  | AI 이용 요청 ---->|                   |                    |
  |<-- 인증/접근 승인 --|                   |                    |
  |---------------- Conversation ------->|                    |
  |<--------------- conversationId ------|                    |
  |---------------- Stream ------------->|                    |
  |                                      |-- Vector Search -->|
  |                                      |<-- Chunks ---------|
  |                                      |-- LLM ----------->|
  |<--------------- Tokens --------------|<-- Stream --------|
```

### Handoff

```text
Visitor → Spring Consultation Request
Spring → FastAPI Summary
FastAPI → Spring Summary
Spring → Staff Notify
Staff → Accept
Spring → Visitor Connected
```

---

## 17. API 목록

| Method | Endpoint | Priority |
|---|---|---|
| POST | `/ai/v1/conversations` | P0 |
| DELETE | `/ai/v1/conversations/{id}` | P0 |
| POST | `/ai/v1/conversations/{id}/messages` | P0 |
| POST | `/ai/v1/conversations/{id}/stream` | P0 |
| POST | `/ai/v1/documents/process` | P0 |
| GET | `/ai/v1/documents/{id}/status` | P0 |
| POST | `/ai/v1/conversations/{id}/handoff-summary` | P1 |
| POST | `/ai/v1/agents/{id}/test` | P2 |
| GET | `/ai/health` | P0 |
| POST | `/ai/v1/stt` | P2 |
| POST | `/ai/v1/tts` | P2 |

---

## 18. 확정 필요 사항

- API Domain
- JWT 검증 방식
- Spring ↔ FastAPI 내부 인증
- LLM Provider
- Embedding Model
- Chunk 크기
- Top-K
- 최대 문서 크기
- 지원 파일 형식
- SSE / WebSocket 최종 선택
- STT/TTS Provider
