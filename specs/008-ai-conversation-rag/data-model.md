# Data Model: AI 상담 / RAG 대화

## 소유권

| 저장소 | 소유 데이터 | 원문 허용 |
|---|---|---|
| Spring/PostgreSQL+pgvector | Lease, AI Agent, 문서 Chunk·Embedding·검색 Scope | 대화 원문 금지 |
| FastAPI/Redis | Conversation·완료 turn·요약·용량 제어 | 30분 TTL 동안만 허용 |

## Conversation — Redis

| 필드 | 형식 | 규칙 |
|---|---|---|
| `conversationId` | opaque string | 서버 발급, 전역 유일 |
| `userId` | int64 | 로그인 사용자, 소유권 검사 키 |
| `boothId` | int64 | Spring 검증 snapshot, 변경 불가 |
| `agentId` | int64 | 해당 Booth 소속·`ACTIVE` 검증, 변경 불가 |
| `leaseEndsAt` | UTC datetime | 매 질문 전 FastAPI가 현재 UTC와 비교 |
| `status` | `ACTIVE/STREAMING/CLOSED` | 동시 stream은 하나만 허용 |
| `lastActivityAt` | UTC datetime | 성공 요청마다 갱신 |
| `expiresAt` | UTC datetime | 마지막 활동 + 30분 |

상태 전이: `ACTIVE → STREAMING → ACTIVE`, 정상 종료 `ACTIVE → CLOSED → 즉시 삭제`. Stream 실패도 `ACTIVE`로 복귀하되 실패 turn은 저장하지 않는다.

## ConversationTurn — Redis

| 필드 | 형식 | 규칙 |
|---|---|---|
| `requestId` | opaque string | 시도마다 새 값 |
| `userMessageId` / `assistantMessageId` | opaque string | 재시도마다 새 값 |
| `question` / `answer` | string | `done` 뒤에만 함께 확정 |
| `sources` | list | `documentId + chunkId` 중복 금지 |
| `createdAt` | UTC datetime | 순서 보조 |

최신 사용자-AI 왕복 최대 6회를 원문으로 유지한다. 그보다 이전 이력은 휘발성 요약 후보이며 예산 초과 시 요약부터 제외한다.

## ConversationScope — 값 객체

`boothId`, `agentId`, `documentStatus=READY`, `searchable=true`를 한 묶음으로 전달한다. 클라이언트 질문·Agent 지시문에서 재구성할 수 없고 Conversation에서만 생성한다. FastAPI가 질의 Embedding과 함께 Spring 검색 API에 전달한다.

## RetrievedChunk — Spring 검색 API 결과

`documentId`, `chunkNo`, `pageNumber`, `section`, `originalFilename`, `content`, cosine `distance`를 가진다. Spring query와 FastAPI Context Builder가 Scope 일치를 각각 확인한다. `content`는 로그·SSE source에 기록하지 않는다.

## StreamAttempt — 요청 수명 객체

`requestId`, `conversationId`, `messageId`, `sequence`, `startedAt`, `firstTokenAt`, `terminalType`, `timeoutPhase`를 가진다. Redis에 실패 원문을 저장하지 않으며 관측에는 식별자·지연·오류 코드만 남긴다.

## CapacityLease — Redis

사용자 active, Agent active, 전역 active, FIFO queue position과 만료 시각을 가진다. 획득·반환은 원자적이며 process 상실 시 lease TTL로 회수한다.

## 검증 불변식

1. Conversation Scope와 다른 Chunk는 0건이어야 한다.
2. `done` 없는 turn은 Conversation 이력에 없어야 한다.
3. Conversation 관련 모든 원문 key는 정상 종료 즉시 또는 마지막 활동 30분 안에 없어야 한다.
4. 사용자·Agent 제한 요청은 전역 queue에 들어가지 않는다.
5. `CLOSED` 또는 만료 Lease에서는 검색·LLM 호출이 0건이어야 한다.
