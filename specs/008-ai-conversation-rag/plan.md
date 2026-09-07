# Implementation Plan: AI 상담 / RAG 대화

**Branch**: `docs/S15P21A604-97-spec008-plan-tasks` | **Date**: 2026-08-26 | **Spec**: [spec.md](./spec.md)
**Input**: `specs/008-ai-conversation-rag/spec.md` | **Predecessor**: [spec 007 plan](../007-ai-agent-document/plan.md)

## Summary

로그인 사용자가 부스 AI 직원과 대화하면 FastAPI가 Spring에서 Lease와 Agent 소속·`ACTIVE` 상태를 검증하고, Redis에 30분 TTL의 Conversation을 만든다. FastAPI는 질의 Embedding을 생성한 뒤 Spring의 `POST /internal/ai/chunk-search`로 `boothId + agentId` Scope 검색을 요청하고 LLM Adapter를 호출하며, `start/token/source/done/error` SSE 계약으로 React에 전달한다. 원문은 Redis에만 두고 정상 종료 즉시 또는 유휴 30분 후 삭제한다. Spring Business DB+pgvector를 통과하는 격리 테스트는 릴리스 차단 조건이다.

## Technical Context

**Language/Version**: Python 3.12+, TypeScript/React 19
**Primary Dependencies**: FastAPI, Uvicorn, redis-py asyncio, HTTPX, Pydantic Settings, Embedding/LLM Provider adapter; Spring Data JPA, pgvector; React, TanStack Query, Vitest
**Storage**: Spring Business PostgreSQL+pgvector가 Chunk 검색을 단독 소유한다. FastAPI는 문서 DB에 연결하지 않고 Redis에 Conversation 원문·TTL·rate limit·전역 스트림/대기열 상태만 둔다. Conversation 원문의 PostgreSQL·로그 저장 금지
**Testing**: pytest, pytest-asyncio, HTTPX ASGI client, 실제 PostgreSQL+pgvector·Redis 통합 Fixture/Testcontainers, LLM adapter fake; Vitest/Testing Library
**Target Platform**: Linux Docker container와 브라우저 React overlay
**Project Type**: FastAPI SSE web service + React overlay
**Performance Goals**: 전체 활성 20개에서 첫 token 15초 이내 95% 이상, 전체 응답 60초 이내, 검색 P95 1초 이하
**Constraints**: 질문 2,000자, 모델 입력 8,000토큰, 사용자 활성 1·60초당 5회, Agent 활성 5, 전역 활성 20·FIFO 30·대기 10초, Scope 유출 0건
**Scale/Scope**: P0 공유 LLM worker pool, 다중 FastAPI replica에서도 Redis 원자 연산으로 전역 한도 유지, UI는 텍스트 상담·근거명·재시도까지

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 설계 후 재확인.*

| 조항 | 검증 | 결과 |
|---|---|---|
| 1. Source of Truth | Lease·Agent·Document·Chunk/pgvector는 Spring, 휘발성 Conversation은 Redis가 소유 | PASS |
| 3. AI 장애 격리 | AI API와 overlay 실패가 월드·부스·비AI API를 차단하지 않음 | PASS |
| 14. 접속 토큰 검증 | 사용자 Access Token만 받고 Refresh Token을 FastAPI에 전달하지 않음 | PASS |
| 15. Adapter·Secret | LLM은 adapter 뒤, Provider·Service Token은 Secret 주입 | PASS |
| 17. RAG 격리 | Spring 검색 API와 SQL에 `booth_id + agent_id + searchable + READY`를 필수화하고 실제 pgvector 테스트로 차단 | PASS |
| 19. SSE 정규화 | Provider 원문을 노출하지 않고 C-07의 다섯 이벤트와 envelope만 전송 | PASS |
| 24. 계약 변경 | FastAPI↔Spring·FastAPI↔React 계약을 `contracts/`에 명시 | PASS |
| 25. 텍스트 UI | 입력·오류·근거·로그인 안내는 React overlay가 담당 | PASS |

### 설계 후 재검증

- Conversation 원문은 Redis TTL과 명시적 DELETE 두 경로로 삭제하며 DB·로그·오류 추적에 남기지 않는다.
- 검색 Scope는 클라이언트 질문이나 Agent prompt에서 받지 않고 Conversation snapshot에서만 읽는다.
- LLM 입력 조립·SSE source·최종 답변까지 동일 Scope를 검증하는 `isolation` 테스트 그룹을 Jenkins component/develop pipeline의 릴리스 게이트로 둔다. 비활성 `.gitlab-ci.yml`은 실행 근거로 사용하지 않는다.
- Constitution 위반과 정당화가 필요한 복잡성은 없다.

## Project Structure

### Documentation

```text
specs/008-ai-conversation-rag/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/{conversation-api.yaml,spring-booth-access-api.yaml}
└── tasks.md
```

### Source Code

```text
festa-ai/
├── app/
│   ├── api/v1/conversations.py
│   ├── models/conversation.py
│   ├── repositories/conversation_repository.py
│   ├── services/{conversation,context,rag,stream,capacity}_service.py
│   ├── clients/{spring_booth_access,spring_chunk_search}.py
│   └── providers/llm.py
└── tests/{unit,contract,integration,isolation}/

festa-frontend/src/
├── features/ai-chat/{AiChatOverlay.tsx,AiChatOverlay.css,useAiConversation.ts}
├── entities/ai-conversation/{api.ts,events.ts,types.ts}
└── features/ai-chat/__tests__/
```

**Structure Decision**: FastAPI는 Conversation orchestration과 질의 Embedding/LLM adapter만 담당한다. React에는 기존 `OverlayHost`가 여는 AI Chat feature를 추가하고, Spring이 Chunk repository와 pgvector 검색 endpoint를 소유한다.

## Detailed Design

### 1. Conversation 생성과 Scope

1. React가 로그인 Access Token과 `boothId`, `agentId`로 Conversation 생성을 요청한다.
2. FastAPI가 사용자 인증 후 Spring 내부 API를 최대 1초·1회 재시도로 호출한다.
3. Spring 응답의 `allowed=true`, Lease 유효, Agent 소속 일치·`ACTIVE`를 확인한다.
4. 검증 실패나 2회 호출 실패는 Fail Closed로 종료하며 검색·LLM은 호출하지 않는다.
5. 성공 시 Redis에 `userId`, `boothId`, `agentId`, `leaseEndsAt`, 상태와 30분 TTL을 저장한다. 이후 질문 Scope는 이 snapshot만 사용한다.

### 2. 질문 접수와 용량 제어

- 2,000자·Conversation 소유자·Lease 만료를 LLM 호출 전에 검사한다.
- 사용자 활성 1개와 60초당 5회, Agent 활성 5개는 초과 즉시 `429 + Retry-After`다.
- 전역 활성 20개만 FIFO 대기열 30개·10초를 사용한다. Redis 원자 연산으로 replica 간 정확성을 유지하고 취소·timeout 때 슬롯을 반환한다.
- `READY` 문서가 0개면 고정 안내를 반환하고 검색·LLM을 호출하지 않는다.

### 3. 격리 검색

- FastAPI는 질의 Embedding을 만든 뒤 `POST /internal/ai/chunk-search`만 검색 진입점으로 사용한다.
- Spring SQL은 `booth_id=:booth_id AND agent_id=:agent_id AND searchable=true`와 부모 Document `READY`를 강제한다.
- `topK`는 최대 20, 내부 timeout은 3초다. cosine `distance` 오름차순이며 threshold는 적용하지 않는다.
- FastAPI는 반환된 scope를 Context 조립 전에 전건 재검증한다. 불일치 1건이면 응답을 중단하고 원문 없는 보안 지표만 기록한다.

### 4. 컨텍스트 예산

- 8,000토큰 예산을 안전 지시문 → 현재 질문 → 관련도순 Chunk → 최신 왕복 최대 6회 → 이전 이력 요약 순으로 배정한다.
- 초과 시 이전 이력 요약 → 오래된 대화 → 관련도 낮은 Chunk 순으로 제외한다. 안전 지시문과 현재 질문은 절삭하지 않는다.
- 사용자 입력과 Chunk는 비신뢰 구획으로 렌더링하고 Scope·시스템 지시를 바꿀 권한을 주지 않는다.

### 5. SSE와 turn 확정

- 연결 전 오류는 HTTP 오류, `start` 이후 오류는 `error` 이벤트로 보낸다.
- `sequence`는 `start=0`부터 1씩 증가하며 `done`과 `error`는 상호 배타적인 단일 종료다.
- LLM delta는 adapter에서 정규화해 `token`으로 보내고 근거는 중복 제거한 `source`로 보낸다.
- `done`에 도달한 turn만 Redis 이력에 원자적으로 확정한다. 실패한 질문·부분 응답은 저장하지 않고 재시도는 같은 Conversation의 새 request/message ID를 쓴다.
- 첫 token 15초와 전체 60초 timeout을 독립 측정하고 `timeoutPhase`를 구분한다.

### 6. 종료·만료·관측

- overlay 정상 종료는 DELETE API로 원문을 즉시 삭제한다. 비정상 종료는 Redis 30분 TTL이 삭제한다.
- 응답 도중 Lease가 만료되면 시작된 1건만 기존 60초 한도 내 완료하며 다음 질문부터 `BOOTH_LEASE_EXPIRED`다.
- 로그·metric은 `requestId`, 지연, event 수, error code, 기대/실제 식별자만 기록하고 질문·답변·Chunk 원문은 기록하지 않는다.

## Complexity Tracking

Constitution 위반 없음.
