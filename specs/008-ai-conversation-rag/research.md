# Phase 0 Research: AI 상담 / RAG 대화

## 1. 휘발성 Conversation 저장소

**Decision**: Redis에 Conversation과 완료 turn만 저장하고 30분 sliding TTL을 적용한다. 정상 종료 시 관련 key를 즉시 삭제한다.

**Rationale**: 다중 FastAPI replica에서 공유 가능하면서 TTL 삭제를 기본 제공하고, PostgreSQL에 원문을 남기지 않는 FR-014를 충족한다.

**Alternatives considered**: 프로세스 메모리는 replica·재시작 간 소실되고 전역 한도를 보장하지 못한다. PostgreSQL은 원문 영구 저장 금지와 충돌한다.

## 2. 동시성·대기열

**Decision**: 사용자·Agent·전역 active counter와 전역 FIFO를 Redis 원자 연산으로 관리한다. 사용자·Agent 초과는 즉시 거부하고 전역 초과만 30개·10초 대기한다.

**Rationale**: 서비스 전체 20개라는 요구를 replica별 semaphore로는 지킬 수 없다. Redis는 Conversation과 같은 휘발성 경계이고 비정상 종료용 lease 만료를 둘 수 있다.

**Alternatives considered**: asyncio semaphore는 단일 프로세스에서만 정확하다. SQS는 10초 대기·브라우저 취소에 비해 운영 복잡도가 크다.

## 3. Spring 접근 검증

**Decision**: Conversation 생성 시 FastAPI→Spring 내부 API 한 번으로 Lease, Agent 소속, `ACTIVE`, `leaseEndsAt`을 검증한다. timeout 1초·재시도 1회 후 Fail Closed다.

**Rationale**: Lease와 Agent의 Source of Truth를 Spring에 유지하며, 매 질문 hot path 호출과 상태 술어 복제를 피한다.

**Alternatives considered**: 매 질문 Spring 호출은 TTFT와 장애 결합을 키운다. FastAPI DB 직접 판정은 Spring 소유 규칙을 복제한다. 서명 snapshot은 키 경계를 늘린다.

## 4. 검색 격리

**Decision**: FastAPI는 질의 Embedding만 생성하고 Spring의 `POST /internal/ai/chunk-search`를 호출한다. Spring은 `boothId + agentId + searchable=true + Document READY`를 SQL에서 강제하며 FastAPI가 결과를 Context 조립 전에 재검증한다. `topK`는 최대 20, timeout은 3초, cosine `distance` 오름차순이고 threshold는 적용하지 않는다.

**Rationale**: DB 소유자인 Spring과 Context 소비자인 FastAPI의 이중 검증이 FR-003의 네 경계 테스트를 가능하게 한다. FastAPI에는 문서 DB 자격증명·ORM·migration을 두지 않는다.

**Alternatives considered**: FastAPI의 직접 DB 검색은 단일 소유권을 깨고, 호출자가 동적 filter를 조립하는 API는 누락 가능성이 있다. LLM prompt만으로 격리하는 방식도 릴리스 차단 요구를 만족하지 못한다.

## 5. LLM·SSE Adapter

**Decision**: Provider별 streaming 응답을 `LlmDelta` adapter로 정규화하고 서비스가 C-07 SSE envelope와 sequence를 생성한다.

**Rationale**: Provider 원문 노출을 막고 Mock/GMS Provider 교체와 timeout 테스트를 동일 계약으로 수행할 수 있다.

**Alternatives considered**: Provider event passthrough는 FE가 공급자에 결합되고 헌법 19조를 위반한다.

## 6. 컨텍스트 절삭

**Decision**: 낮은 우선순위인 이전 요약 → 오래된 대화 → 관련도 낮은 Chunk 순으로 제외한다. 안전 지시문과 현재 질문은 보존한다.

**Rationale**: 2026-08-26 Clarification을 구현 가능한 단일 결정 순서로 고정한다.

**Alternatives considered**: 문자 수 근사는 모델 token 한도를 보장하지 못한다. 무조건 오래된 순서만 자르면 검색 근거와 안전 지시가 손상될 수 있다.
