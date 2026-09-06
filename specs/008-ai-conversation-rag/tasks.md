# Tasks: AI 상담 / RAG 대화

**Input**: `specs/008-ai-conversation-rag/`의 spec·plan·research·data-model·contracts·quickstart
**Tests**: FR-003 격리와 계약·timeout·삭제 검증은 필수다.

## S15P21A604-449 Search Ownership Migration — 최우선 선행 작업

> 아래 기존 항목 중 FastAPI `ChunkRepository` 또는 FastAPI의 pgvector 직접 조회를 요구하는 내용은 이 절로 대체되며 실행하지 않는다. 완료 이력은 삭제하지 않는다.

- [X] T052 [DOCS] 문서 세트를 Spring Business DB/pgvector 단독 소유로 정합화한다 (S15P21A604-449)
- [X] T053 [DOCS] `contracts/spring-chunk-search-api.yaml`에 검색 요청·응답과 격리 조건을 정의한다 (S15P21A604-449)
- [ ] T048 [BE] 검색 repository/service/controller에 `boothId + agentId + searchable=true + Document READY` 강제 조건을 구현한다 (S15P21A604-398)
- [ ] T049 [AI] 직접 pgvector repository를 제거하고 질의 Embedding 후 Spring 검색 client를 호출하도록 변경한다
- [ ] T050 [BE/AI] 실제 Business DB fixture로 topK, 3초 timeout, distance 정렬, threshold 없음과 scope 누출 0건을 검증한다
- [ ] T051 [AI] 검색 응답을 Context에 넣기 전 Conversation scope를 재검증하고 위반 시 Fail Closed 처리한다

**Dependency**: T048과 S15P21A604-399 Agent 설정 합의 → T049/T051 → T050 Release Gate.

## Phase 1: Setup

- [ ] T001 Add Redis, tokenizer, SSE test dependencies and pytest markers in festa-ai/pyproject.toml
- [ ] T002 [P] Add Conversation, timeout, rate-limit, Redis, Spring access, and LLM adapter settings without secrets in festa-ai/app/core/config.py and festa-ai/.env.example
- [ ] T003 [P] Register conversation router in festa-ai/app/api/v1/router.py
- [ ] T004 [P] Create AI conversation frontend module directories under festa-frontend/src/entities/ai-conversation/ and festa-frontend/src/features/ai-chat/

## Phase 2: Foundational

- [ ] T005 Create ConversationScope, Conversation, ConversationTurn, StreamAttempt, and SSE event models in festa-ai/app/models/conversation.py
- [ ] T006 [P] Define normalized streaming LLM adapter interfaces and test fake in festa-ai/app/providers/llm.py and festa-ai/tests/fakes/llm.py
- [ ] T007 [P] Implement Redis connection lifecycle and health degradation handling in festa-ai/app/core/redis.py and festa-ai/app/main.py
- [ ] T008 Implement TTL conversation storage, atomic completed-turn commit, and immediate deletion in festa-ai/app/repositories/conversation_repository.py
- [ ] T009 Implement Redis atomic user/Agent/global capacity leases and FIFO queue in festa-ai/app/services/capacity_service.py
- [ ] T010 [P] Implement Spring Booth Access contract DTO/client with 1-second timeout and one retry in festa-ai/app/clients/spring_booth_access.py
- [X] T011 [P] [BE] Implement Spring internal Lease·Agent ownership·ACTIVE validation endpoint in backend/src/main/java/com/example/ssafesta/internal/ai/AiBoothAccessController.java and backend/src/main/java/com/example/ssafesta/internal/ai/AiBoothAccessService.java. FR-024 순서로 단락 평가하고 거부는 `200 + allowed:false + denialCode`다(404 없음). `leaseEndsAt == null` ⟺ `BOOTH_LEASE_EXPIRED`, `agentStatus` 존재 ⟺ 소속 확인이며 `ACTIVE` 외 저장값은 `INACTIVE`로 정규화한다. 한 요청은 시각을 한 번만 읽는다. `INTERNAL_AI_TO_SPRING_TOKENS` 상수 시간 검증과 `/internal/**` fail-closed 체인을 함께 넣는다 (`S15P21A604-327`, 2026-08-30 경로·범위 교정)
- [X] T012 [P] [BE] Add Spring access validation integration tests in backend/src/test/java/com/example/ssafesta/internal/ai/AiBoothAccessApiIntegrationTest.java — 판정 매트릭스(없는 부스·없는 직원·타 부스 직원·`DISABLED`·단일 시각)와 토큰 4종(정상·누락·오류·반대 방향)·사용자 토큰 401·미정의 내부 경로 403. 설정 부팅 검증은 `InternalTokenPropertiesTest` (`S15P21A604-327`)
- [ ] T013 Add common sanitized error mapping and no-prompt logging policy in festa-ai/app/api/errors.py and festa-ai/app/core/logging.py
- [ ] T014 Implement Access Token signature/claims validation and explicit guest rejection without accepting Refresh Tokens in festa-ai/app/core/auth.py and festa-ai/tests/unit/test_auth.py

## Phase 3: User Story 1 — 부스 AI 대화 (P0) 🎯 MVP

**Goal**: 로그인 사용자가 질문하고 streaming 답변과 근거를 확인하며 후속 질문을 이어간다.
**Independent Test**: 정상 Conversation에서 `start→token*→source*→done`과 완료 turn 맥락·React 표시를 검증한다.

- [ ] T015 [P] [US1] Write Conversation API and C-07 SSE contract tests in festa-ai/tests/contract/test_conversation_api.py and festa-ai/tests/contract/test_sse_contract.py
- [ ] T016 [P] [US1] Write context budget and completed-turn-only unit tests in festa-ai/tests/unit/test_context_service.py and festa-ai/tests/unit/test_conversation_repository.py
- [ ] T017 [US1] Implement authenticated Conversation create/get/close lifecycle with 30-minute TTL in festa-ai/app/services/conversation_service.py
- [X] T018 [US1] Implement token counting and low-priority truncation order in festa-ai/app/services/context_service.py (`S15P21A604-129`)
- [ ] T019 [US1] Implement question validation, READY-empty fixed response, retrieval, context assembly, and LLM orchestration in festa-ai/app/services/rag_service.py
- [ ] T020 [US1] Implement C-07 sequence, source deduplication, terminal exclusivity, and completed-turn commit in festa-ai/app/services/stream_service.py
- [ ] T021 [US1] Implement create, message SSE, and idempotent close endpoints in festa-ai/app/api/v1/conversations.py
- [ ] T022 [P] [US1] Define frontend API types and strict SSE parser in festa-frontend/src/entities/ai-conversation/types.ts and festa-frontend/src/entities/ai-conversation/events.ts
- [ ] T023 [US1] Implement Conversation create/stream/close client in festa-frontend/src/entities/ai-conversation/api.ts
- [ ] T024 [US1] Implement token rendering, source titles, sensitive-data warning, retry, and close behavior in festa-frontend/src/features/ai-chat/AiChatOverlay.tsx and festa-frontend/src/features/ai-chat/useAiConversation.ts
- [ ] T025 [US1] Wire AI interaction and guest login guidance into festa-frontend/src/features/overlay/OverlayHost.tsx
- [ ] T026 [P] [US1] Add frontend SSE sequence, rendering, retry ID, and close tests in festa-frontend/src/features/ai-chat/__tests__/AiChatOverlay.test.tsx and festa-frontend/src/entities/ai-conversation/__tests__/events.test.ts

## Phase 4: User Story 2 — Booth·Agent 데이터 격리 (P0 Release Gate)

**Goal**: 검색·LLM 입력·SSE·답변 어디에도 다른 Scope 데이터가 한 건도 섞이지 않는다.
**Independent Test**: 실제 PostgreSQL+pgvector 고정 Fixture의 여섯 격리 케이스를 `pytest -m isolation`으로 모두 통과한다.

- [ ] T027 [P] [US2] Create A/A1·A/A2·B/B1·DISABLED pgvector fixture in festa-ai/tests/isolation/conftest.py
- [ ] T028 [P] [US2] Write repository Scope and READY-state isolation tests in festa-ai/tests/isolation/test_chunk_repository_scope.py
- [ ] T029 [P] [US2] Write forged Scope and prompt-injection zero-call tests in festa-ai/tests/isolation/test_scope_forgery.py
- [ ] T030 [P] [US2] Write LLM-input, SSE-source, answer-leak, and 50-request mixed concurrency tests in festa-ai/tests/isolation/test_rag_boundaries.py
- [X] T031 [US2] Implement the only public scoped READY search entry point in festa-ai/app/repositories/chunk_repository.py (`S15P21A604-128`)
- [ ] T032 [US2] Add RetrievedChunk Scope revalidation and fail-closed security metric in festa-ai/app/services/rag_service.py
- [ ] T033 [US2] Add pytest -m isolation as a release-blocking stage in infra/jenkins/pipelines/component.groovy and infra/jenkins/pipelines/develop.groovy while preserving disabled .gitlab-ci.yml job definitions

## Phase 5: User Story 3 — 실패·timeout·Lease 만료 복구 (P0)

**Goal**: timeout, 연결 끊김, Lease 만료, 과부하가 검색·LLM 오호출이나 월드 장애로 번지지 않는다.
**Independent Test**: timeout phase, capacity limits, Lease/Agent 검증 실패, Redis TTL, AI service down을 장애 주입으로 검증한다.

- [ ] T034 [P] [US3] Write first-token 15-second and total-response 60-second tests in festa-ai/tests/integration/test_stream_timeouts.py
- [ ] T035 [P] [US3] Add a 20-concurrent-stream TTFT load test that asserts at least 95 percent start within 15 seconds in festa-ai/tests/performance/test_ttft_load.py
- [ ] T036 [P] [US3] Write user·Agent immediate rejection and global FIFO 20/30/10-second tests in festa-ai/tests/integration/test_capacity_limits.py
- [ ] T037 [P] [US3] Write Spring retry/fail-closed, Lease expiry, Agent mismatch/inactive, and mid-stream expiry tests in festa-ai/tests/integration/test_booth_access.py
- [ ] T038 [P] [US3] Write explicit-close and idle-TTL no-raw-data tests in festa-ai/tests/integration/test_conversation_expiry.py
- [ ] T039 [US3] Enforce capacity acquisition/release, Retry-After, and queue cancellation in festa-ai/app/services/capacity_service.py and festa-ai/app/api/v1/conversations.py
- [ ] T040 [US3] Enforce FIRST_TOKEN/TOTAL_RESPONSE timeout errors and single terminal event in festa-ai/app/services/stream_service.py
- [ ] T041 [US3] Enforce pre-question UTC Lease check and allow only the already-started stream to finish in festa-ai/app/services/conversation_service.py
- [ ] T042 [US3] Add disconnected/truncated response guidance without automatic SSE resume in festa-frontend/src/features/ai-chat/useAiConversation.ts and festa-frontend/src/features/ai-chat/AiChatOverlay.tsx
- [ ] T043 [P] [US3] Verify AI outage leaves non-AI overlay and world shell functional in festa-frontend/src/features/ai-chat/__tests__/AiServiceIsolation.test.tsx

## Phase 6: Polish & Cross-Cutting

- [ ] T044 [P] Add request latency, queue depth, timeout phase, and Scope mismatch metrics without raw text in festa-ai/app/core/metrics.py
- [ ] T045 [P] Add executable local validation commands and expected outputs to festa-ai/README.md
- [ ] T046 Run all commands in specs/008-ai-conversation-rag/quickstart.md and record results in specs/008-ai-conversation-rag/quickstart.md
- [ ] T047 Verify no secrets or Conversation raw text are committed or emitted by tests using festa-ai/.env.example and festa-ai/tests/

## Dependencies & Execution Order

- Phase 1 → Phase 2 → user stories.
- US1 establishes Conversation/SSE; US2 adds mandatory release-gate protection around US1; US3 adds failure behavior. Recommended order is US1 → US2 → US3.
- Backend T011/T012 can proceed in parallel with FastAPI foundation after the contract is accepted.
- Frontend T022/T026 can proceed in parallel with FastAPI US1 after `conversation-api.yaml` is fixed.

## Parallel Examples

- US1: T015, T016, T022, T026 target independent test/type files.
- US2: T027–T030 can be authored in parallel before T031/T032 implementation.
- US3: T034–T038 and T043 are independent failing tests before service changes.

## Implementation Strategy

1. Complete setup/foundation and validate Conversation lifecycle.
2. Deliver US1 as the visible MVP slice.
3. Complete US2 before any release; isolation failure blocks shipping.
4. Complete US3 and full quickstart validation.
