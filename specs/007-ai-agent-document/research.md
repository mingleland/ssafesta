# Research: AI 직원 / 문서 파이프라인

**Date**: 2026-08-20
**Decision source**: [GitHub Issue #11](https://github.com/kanghyunsoon/ssafesta/issues/11), project constitution, docs/07, docs/13, docs/14, docs/15, docs/21

## 1. 상태 소유권

**Decision**: 사용자 노출용 `DocumentStatus`는 Spring이 소유하고, 내부 실행용 `JobStatus`는 FastAPI가 소유한다. FastAPI의 문서 상태 조회 API는 내부 진단용으로 제한하고 사용자 화면은 Spring이 저장한 마지막 상태를 조회한다.

**Rationale**: FastAPI 장애가 문서 목록 조회까지 중단시키면 헌법 3조의 AI 장애 격리를 위반한다. `RETRY_WAIT` 같은 내부 재시도 상태를 사용자에게 노출하지 않아도 된다.

**Alternatives considered**:

- FastAPI 단독 상태 소유: 단순하지만 AI 장애가 사용자 문서 관리 화면으로 전파된다.
- 하나의 공통 상태 집합: 로그와 코드에서 사용자 상태와 실행 상태를 구분하기 어렵다.

## 2. Processing Job 저장 위치

**Decision**: 별도 AI DB 인스턴스를 만들지 않고 기존 PostgreSQL RDS의 FastAPI 전용 `ai` 스키마에 `document_jobs`를 둔다. Spring은 `public` 스키마, FastAPI는 `ai` 스키마의 migration을 각각 소유한다.

**Rationale**: `public.ai_document_chunks`의 전체 교체와 Job 성공 전환을 하나의 DB 트랜잭션으로 묶을 수 있다. 별도 인스턴스 비용과 분산 트랜잭션을 피하면서 migration 소유권은 분리한다.

**Alternatives considered**:

- 별도 AI RDS: 격리는 강하지만 P0 비용과 운영 복잡도가 크고 원자적 청크 교체가 어렵다.
- Spring 스키마에 Job 저장: AI 재시도 필드 변경이 BE migration과 배포에 종속된다.
- SQS만 사용: 메시지 수신 여부와 사용자 문서 상태를 연결할 영속 Job이 여전히 필요하다.

## 3. Queue와 Worker

**Decision**: P0는 FastAPI 프로세스 내부 Worker를 사용하되 Job을 먼저 DB에 영속화한다. 다중 Worker는 `FOR UPDATE SKIP LOCKED`와 Worker lease로 작업을 경쟁 없이 획득한다. 부하 실측 후 SQS로 교체할 수 있도록 Job 저장소와 Worker 실행기를 분리한다.

**Rationale**: 현재 규모에서 외부 Queue 도입을 미루면서도 프로세스 재시작 시 작업을 잃지 않는다.

**Alternatives considered**:

- 메모리 Queue만 사용: 재시작 시 대기 작업을 잃고 `PROCESSING` 고착을 만든다.
- P0부터 SQS: 신뢰성은 높지만 현재 범위보다 운영 요소가 늘어난다.

## 4. Job 상태와 문서 상태 매핑

**Decision**:

| JobStatus | DocumentStatus | 의미 |
|---|---|---|
| `QUEUED` | `QUEUED` | 접수 완료 |
| `RUNNING` | `PROCESSING` | Worker 처리 중 |
| `RETRY_WAIT` | `PROCESSING` | 내부 재시도 대기 |
| `SUCCEEDED` | `READY` | 처리 및 청크 저장 완료 |
| `DEAD` | `FAILED` | 재시도 상한 초과 |
| `CANCELLED` | `DISABLED` | 부스 임대 만료 등 비즈니스 취소 |

**Rationale**: Worker lease 만료와 부스 임대 만료는 원인과 후속 동작이 다르다. Worker lease 만료는 복구 대상이고, 부스 임대 만료는 검색 비활성화 대상이다.

**Alternatives considered**:

- `FAILED`를 JobStatus에도 사용: `DocumentStatus.FAILED`와 의미가 겹친다.
- 모든 lease 만료를 `CANCELLED` 처리: 일시적인 Worker 중단을 복구하지 못한다.

## 5. 재시작 복구 수치

**Decision**: heartbeat 30초, Worker lease TTL 90초, sweeper 주기 60초를 초기값으로 사용하고 환경 설정으로 조정 가능하게 한다. 실패 후 최대 3회 재시도하며 대기 시간은 1분, 5분, 15분이다. 최초 실행을 포함한 최대 실행 횟수는 4회다.

**Rationale**: heartbeat 3회 누락을 Worker 상실로 판단하며, 긴 PDF의 정상 처리 시간과 무관하게 생존 여부를 판별한다. 세 번의 명시적 backoff와 “최대 3회 재시도”의 의미를 일치시킨다.

**Alternatives considered**:

- `started_at + N분`: 큰 PDF의 정상 장기 작업을 잘못 중단할 수 있다.
- 무한 재시도: Provider 비용과 장애 부하를 통제할 수 없다.

## 6. 멱등 처리와 청크 교체

**Decision**: 동일 문서의 활성 Job은 하나만 허용한다. 중복 처리 요청은 기존 활성 Job을 반환한다. 재처리 성공 시 `document_id`의 기존 청크를 삭제하고 새 청크 전량을 삽입한 뒤 Job을 `SUCCEEDED`로 전환하는 작업을 하나의 트랜잭션으로 처리한다.

**Rationale**: UPSERT만 사용하면 새 청크 수가 줄었을 때 이전 청크의 꼬리가 남아 검색 결과를 오염시킨다.

**Alternatives considered**:

- `(document_id, chunk_no)` UPSERT만 사용: 줄어든 청크 뒤의 오래된 행이 남는다.
- 처리 중 청크를 점진적으로 공개: 부분 결과가 RAG 검색에 노출될 수 있다.

## 7. 상태 콜백과 장애 복구

**Decision**: FastAPI는 Spring 내부 API로 사용자 문서 상태를 비동기 갱신한다. 터미널 Job에는 콜백 전달 시각과 재시도 정보를 영속화한다. 콜백 실패는 Job 결과를 되돌리지 않고 재시도하며, 주기적 reconciliation이 미전달 터미널 Job을 다시 전송한다.

**Rationale**: 청크 저장 후 Spring 콜백 전에 프로세스가 종료되어도 문서가 영원히 `PROCESSING`에 남지 않아야 한다.

**Alternatives considered**:

- 메모리에서만 콜백 재시도: 재시작 시 전달 여부를 잃는다.
- FastAPI가 `ai_documents`를 직접 수정: Spring 소유 행의 쓰기 경계를 위반한다.

## 8. 내부 인증과 DB 권한

**Decision**: P0 내부 API는 Security Group 네트워크 제한과 Service Token을 함께 사용한다. 토큰은 Secrets Manager에서 주입하고 저장소·로그에 기록하지 않는다. mTLS는 후속 강화 항목이다. FastAPI runtime role은 `ai.document_jobs` DML, `public.ai_documents`·`public.ai_agents` SELECT, `public.ai_document_chunks` DELETE/INSERT 권한만 가진다.

**Rationale**: 8주 프로젝트에서 구현 가능한 방어를 적용하면서 최소 권한과 비밀정보 분리를 지킨다.

**Alternatives considered**:

- SG 제한만 사용: 내부 네트워크 침해 시 호출자 인증이 없다.
- P0 mTLS: 보안은 강하지만 인증서 발급·회전 운영 범위가 커진다.

## 9. 실패 사유 정책

**Decision**: 사용자에게는 안정적인 오류 코드와 정제된 한국어 메시지만 제공한다. Stack Trace, SQL, object key, Provider 원문은 FastAPI 구조화 로그에만 남긴다.

**Rationale**: 사용자가 대처 가능한 정보를 주면서 내부 구조와 비밀정보 노출을 막는다.

**초기 오류 코드**: `PARSE_FAILED`, `UNSUPPORTED_SCAN_PDF`, `EMBEDDING_TIMEOUT`, `SOURCE_NOT_FOUND`, `PROCESSING_INTERRUPTED`, `INTERNAL_ERROR`.

## 10. 문서 개정본 경쟁 방지

**Decision**: Job은 접수 당시 문서의 SHA-256을 snapshot으로 저장한다. 완료 콜백은 `sourceHash`를 포함하며 Spring은 현재 문서 hash와 일치할 때만 `READY`를 적용한다. 불일치하면 오래된 Job 결과를 폐기하고 최신 개정본 Job을 유지한다.

**Rationale**: 같은 `documentId`를 사용하는 명시적 교체 중 오래된 Worker가 늦게 완료되어 최신 문서를 `READY`로 덮는 경쟁 조건을 방지한다.

**Alternatives considered**:

- `documentId`만 비교: 동일 ID 개정본 간 선후관계를 판별할 수 없다.
