# Quickstart Validation: AI 상담 / RAG 대화

## 전제

- Python 3.12, `festa-ai` test 의존성
- Spring Business PostgreSQL 17 + pgvector와 Redis 7.2 테스트 인스턴스
- Spring Booth Access·Chunk Search 계약 구현
- 원문을 기록하지 않는 LLM Test Adapter

## 실행

```bash
cd festa-ai
pytest tests/unit
pytest tests/contract
pytest tests/integration
pytest -m isolation
```

Frontend는 `festa-frontend`에서 `npm test`와 `npm run build`를 실행한다.

## 필수 시나리오

### 1. 정상 대화와 SSE

Conversation 생성 후 질문을 보낸다. `start(sequence=0) → token* → source* → done` 순서, 공통 envelope, 근거 문서명, 동일 Conversation의 후속 질문 맥락을 확인한다.

### 2. Release Gate 격리

spec의 A/A1·A/A2·B/B1·DISABLED fixture를 Spring Business DB pgvector에 적재하고 `POST /internal/ai/chunk-search` 결과, LLM 입력, SSE source, 누적 답변을 검사한다. `searchable=false` fixture도 포함하며 금지 Scope가 한 건이라도 보이면 실패한다. FastAPI에 문서 DB credential이 없어야 하고 Scope 위조 요청은 검색·LLM 호출 0건이어야 한다.

### 2-1. 검색 파라미터

`topK=1/20` 성공, `topK=21` 거부, 1536차원 검증, 3초 timeout을 확인한다. 결과는 cosine `distance` 오름차순이고 threshold로 임의 제외되지 않아야 한다.

### 3. 50개 혼합 동시성

세 Scope의 질문 50개를 동시에 실행한다. 전역 active≤20, FIFO≤30, Agent active≤5를 확인하고 모든 결과 Scope가 일치해야 한다.

### 4. Timeout과 재시도

Test Adapter로 첫 token 15초와 전체 60초 timeout을 각각 주입한다. `error.code=LLM_TIMEOUT`, 올바른 `timeoutPhase`, 단일 종료 이벤트를 확인한다. 재시도는 같은 Conversation과 새 request/message ID를 사용하며 실패 turn은 이력에 없어야 한다.

### 5. Lease·Agent 검증

정상, 1차 timeout 후 성공, 2회 실패, Lease 만료, Agent 소속 불일치, Agent 비활성을 검사한다. 실패 케이스는 Conversation·검색·LLM 호출이 모두 0건이어야 한다.

### 6. 원문 삭제

DELETE 후 Redis 원문 key가 즉시 없어지는지 확인한다. 종료 호출을 생략한 fixture는 마지막 활동 30분 후 없어져야 하며 PostgreSQL·로그·metric에는 질문·답변 문자열이 없어야 한다.

### 7. React Overlay

게스트 로그인 안내, 민감정보 경고, token 누적 표시, source 문서명, 잘린 sequence 안내, retryable 오류의 재시도, 닫기 시 DELETE 호출을 검증한다. AI 서비스 중단 중에도 기존 월드 overlay와 비AI 화면은 정상이어야 한다.
