# AI(FastAPI) 파트 — SDD 권장 브리프

> **상태**: 권장안 (2026-08-12) — AI 담당자(김가현)가 검토·수정 후 사용한다.
> 확정 전제 (2026-08-12): Embedding = 관리형 API + **1536d 고정**, LLM = 어댑터 뒤 저가 모델 시작,
> timeout TTFT 15s / 전체 60s, Streaming = SSE, **API Key는 GMS 지급 예정** — 지급 전엔 Mock/어댑터로 개발.
> 헌법 13조(Vector 격리)와 3조(AI 장애 격리)는 릴리스 차단 조건이다.

---

## 담당 spec 목록

| Spec | 이름 | 선행 | 비고 |
|---|---|---|---|
| 007 | ai-agent-document | 001, 004 | Document Pipeline + 상태 머신 |
| 008 | ai-conversation-rag | 007 | 격리 Critical Test 포함 |
| (011) | staff-consultation 중 Handoff Summary | 008 | 상담방·Presence는 Spring/Realtime |

착수 전: docs/22에 계획된 **AI 미니 스파이크** (PDF 1개 → 파싱 → 청킹 → 임베딩 → 검색 → 답변, 로컬 e2e)를 먼저 수행하면 spec의 clarify가 실측 기반으로 풀린다. GMS 키 전이므로 임베딩·LLM은 어댑터 인터페이스 + 로컬 Mock(또는 개인 키 임시)으로.

## spec 007 — ai-agent-document

**specify 입력 (초안)**

```text
부스 소유자가 AI 직원(Agent)을 만들고 문서를 업로드하면 AI가 그 문서로 답할 준비가 된다.
파이프라인: 업로드(원본은 Spring/R2 object storage, 메타데이터 Spring 소유) → FastAPI가 파싱 → 청킹 → 임베딩
→ pgvector 저장 (chunk에 boothId, agentId, embedding_model_id 기록).
문서 상태 머신: UPLOADED → PROCESSING → READY / FAILED — 상태는 조회 가능하고 실패 사유를 남긴다.
처리 중에도 다른 기능은 정상 동작한다(비동기).
```

**예상 clarify**: 허용 형식·최대 크기(권장: PDF 우선, 10~20MB), 청킹 파라미터(튜닝 값으로 명시, 결정 아님), 동일 문서 재업로드 정책, 처리 큐(초기 인프로세스 → SQS는 AWS 실측 후).

## spec 008 — ai-conversation-rag

**specify 입력 (초안)**

```text
방문자가 부스의 AI 직원과 텍스트 상담한다. 질문 → 해당 boothId+agentId로 필터된 벡터 검색(Top-K)
→ LLM 어댑터 호출 → SSE로 스트리밍 응답 (이벤트: start/token/source/done/error).
source 이벤트로 답변 근거 문서를 반환한다. Conversation 컨텍스트는 세션 단위로 유지.
실패 시: TTFT 15s 초과 → error 이벤트 + 재시도 UX (프론트), 전체 60s 상한.
Critical Test (릴리스 차단): Booth A의 Agent가 Booth B의 chunk를 1건이라도 반환하면 실패.
Provider raw 응답을 프론트로 직접 흘리지 않는다.
```

**예상 clarify**: Conversation 저장 위치·보존 기간(권장: 서비스 PG + 종료 시 삭제), Rate Limit 수치(부하 확인 후), 동시 스트림 상한, 프롬프트 인젝션 최소 방어(시스템 프롬프트 고정 + 문서 컨텍스트 이스케이프).

## Handoff Summary (spec 011 기여분, P1)

AI 대화를 사람 상담으로 전환할 때 FastAPI가 대화 요약을 생성해 Spring 상담방에 전달한다. 상담방/Presence/WebSocket 서버 자체는 Spring 영역 (역할 분담 문서 §3.2).

## 공통 비기능 요구

- FastAPI 다운 시 Spring·Unity·React의 비AI 기능은 전부 정상 (헌법 3조) — 통합 테스트 항목에 포함
- 모든 LLM/Embedding 호출은 어댑터 인터페이스 뒤 — GMS 키 도착 시 어댑터 구현만 교체
- pgvector 공유/분리는 AWS 실측 후 (docs/26 ③) — spec에서는 스키마 분리만 전제
