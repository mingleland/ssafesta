# Quickstart Validation: AI 직원 / 문서 파이프라인

이 문서는 구현 완료 후 spec 007의 핵심 계약을 로컬·CI에서 검증하는 실행 가이드다. 구현 코드는 [plan.md](./plan.md), 상태와 DB 규칙은 [data-model.md](./data-model.md), HTTP payload는 [contracts/](./contracts/)를 따른다.

## Prerequisites

- Docker 및 Docker Compose
- Python 3.12 이상
- PostgreSQL + pgvector 테스트 인스턴스
- AI migration role과 runtime role
- 테스트용 S3 adapter 또는 격리된 bucket
- deterministic Embedding fake: 입력별 고정 1536차원 vector 반환
- Spring callback fake 또는 Backend 로컬 인스턴스

실제 Service Token, DB 비밀번호, Provider Key를 저장소에 기록하지 않는다.

## 1. 환경과 migration

```bash
cd festa-ai
python -m venv .venv
python -m pip install -e ".[test]"
alembic upgrade head
```

확인 결과:

- `ai.document_jobs`와 세 인덱스(pickup, lease, callback)가 존재한다.
- `document_id` FK가 `ON DELETE CASCADE`다.
- runtime role은 `public.ai_documents`를 SELECT할 수 있지만 UPDATE할 수 없다.

## 2. 기본 처리 성공

1. Spring Fixture에 `QUEUED` Document와 S3 PDF를 준비한다.
2. `POST /ai/v1/documents/process`를 호출한다.
3. Worker가 처리할 때까지 기다린다.

기대 결과:

- 응답은 202, `jobId`, `status=QUEUED`, `existing=false`다.
- Job은 `QUEUED → RUNNING → SUCCEEDED`로 전이한다.
- Document는 `QUEUED → PROCESSING → READY`로 전이한다.
- Chunk는 모두 같은 `document_id`, `booth_id`, `agent_id`, `embedding_model_id`를 가진다.
- `chunk_count`와 `processed_at`이 Spring 상태에 반영된다.

## 3. 중복 요청 멱등성

같은 `documentId`와 `sourceHash`로 처리 요청을 동시에 두 번 보낸다.

기대 결과:

- 활성 Job은 정확히 한 개다.
- 두 응답의 `jobId`가 같다.
- 한 응답 이상은 `existing=true`다.
- Embedding Provider 호출과 Chunk 교체는 한 번만 수행된다.

## 4. Worker 강제 종료 복구

1. Job이 `RUNNING`이고 heartbeat가 기록된 것을 확인한다.
2. Worker 프로세스를 강제 종료한다.
3. 90초 lease와 다음 60초 sweeper 주기까지 기다리거나 테스트 clock을 전진시킨다.
4. Worker를 다시 시작한다.

기대 결과:

- 만료 Job은 `RETRY_WAIT`로 회수된다.
- 1분 backoff 후 다시 `RUNNING`이 된다.
- 재시도 성공 시 `SUCCEEDED → READY`다.
- 실패가 계속되면 1·5·15분의 세 번 재시도 후 `DEAD → FAILED`다.
- 영구 `PROCESSING` Document는 0건이다.

## 5. callback 중간 종료 복구

1. Chunk commit과 Job `SUCCEEDED` 직후 Spring callback을 실패시키거나 FastAPI를 종료한다.
2. FastAPI를 다시 시작한다.

기대 결과:

- Chunk와 `SUCCEEDED` Job은 유지된다.
- `callback_delivered_at`이 비어 있는 Job을 reconciliation이 찾는다.
- callback을 재전송해 Document가 `READY`가 된다.
- 같은 callback을 두 번 전송해도 Spring 결과는 한 번 적용한 것과 같다.

## 6. 청크 수가 줄어드는 재처리

1. 첫 처리에서 40개 Chunk를 만든다.
2. 같은 Document 개정본을 25개 Chunk가 되도록 재처리한다.

기대 결과:

- 최종 Chunk는 25개뿐이다.
- 이전 `chunk_no=25..39`는 남지 않는다.
- 실패를 주입하면 40개 기존 Chunk가 그대로 유지되고 부분적인 새 Chunk는 없다.

## 7. 부스 임대 만료

1. Job을 `RUNNING`으로 만든다.
2. Spring Document를 부스 임대 만료 정책에 따라 `DISABLED`로 전환한다.
3. Worker가 성공 commit 직전 검증을 수행하게 한다.

기대 결과:

- Job은 `CANCELLED`다.
- Document는 `DISABLED`를 유지한다.
- 새 Chunk는 공개되지 않으며 `READY` callback이 발생하지 않는다.
- 이 동작은 Worker heartbeat lease 만료의 `RETRY_WAIT`와 구분된다.

## 8. 오래된 개정본 완료 경쟁

1. hash A Job을 `RUNNING`으로 둔다.
2. 같은 Document를 hash B 개정본으로 교체한다.
3. hash A Job의 완료 callback을 늦게 보낸다.

기대 결과:

- Spring은 409 stale revision을 반환한다.
- hash B의 상태를 hash A 결과가 덮지 않는다.
- FastAPI는 해당 stale callback을 재시도 무한 루프에 넣지 않는다.

## 9. 격리 Critical Test

Booth A/Agent A와 Booth B/Agent B에 서로 다른 표식 문서를 등록하고 혼합 검색을 수행한다.

기대 결과:

- 모든 검색 쿼리에 `booth_id + agent_id + READY` 필터가 적용된다.
- 다른 Booth 또는 Agent Chunk 반환은 0건이다.
- 1건이라도 유출되면 CI와 배포를 차단한다.

## 10. 자동 테스트

```bash
cd festa-ai
pytest tests/unit
pytest tests/contract
pytest tests/integration
pytest -m failure_injection
pytest -m isolation
```

전체 통과 조건:

- OpenAPI 계약 검증 성공
- 상태 전이와 DB 제약 검증 성공
- 강제 종료·callback 실패 복구 성공
- 다른 Booth/Agent Chunk 유출 0건
- 사용자 오류 응답과 로그 검사에서 Secret·Stack Trace 노출 0건
