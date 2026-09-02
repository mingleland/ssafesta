# Research: AI 직원 / 문서 파이프라인

**Date**: 2026-08-20
**Decision source**: [GitHub Issue #11](https://github.com/kanghyunsoon/ssafesta/issues/11), project constitution, docs/07, docs/13, docs/14, docs/15, docs/21

## 1. 상태 소유권

**Decision**: 사용자 노출용 `DocumentStatus`는 Spring이 소유하고, 내부 실행용 `JobStatus`는 FastAPI가 소유한다. FastAPI의 문서 상태 조회 API는 내부 진단용으로 제한하고 사용자 화면은 Spring이 저장한 마지막 상태를 조회한다.

**Rationale**: FastAPI 장애가 문서 목록 조회까지 중단시키면 헌법 3조의 AI 장애 격리를 위반한다. `RETRY_WAIT` 같은 내부 재시도 상태를 사용자에게 노출하지 않아도 된다.

**Alternatives considered**:

- FastAPI 단독 상태 소유: 단순하지만 AI 장애가 사용자 문서 관리 화면으로 전파된다.
- 하나의 공통 상태 집합: 로그와 코드에서 사용자 상태와 실행 상태를 구분하기 어렵다.

## 2. Processing Job과 Chunk 저장 위치

**Decision (C-11)**: Infra의 [PostgreSQL Isolation and Backup Contract v1](../infra-002-environments/contracts/postgres-boundary-contract.md)을 정본으로 채택한다. 하나의 PostgreSQL 17 + pgvector 인스턴스를 공유하되 환경별 Business DB(`festa_{env}_business`)와 AI DB(`festa_{env}_ai`)를 분리한다. Spring은 Business DB의 `ai_agents`·`ai_documents`를, FastAPI는 AI DB의 `document_jobs`·`document_chunks`를 소유한다.

**Rationale**: database·login role 경계로 AI 장애와 권한을 격리하면서 인스턴스 비용은 공유한다. Job과 Chunk를 같은 AI DB에 두면 기존 Chunk 전체 교체와 Job 성공 전환을 하나의 로컬 트랜잭션으로 유지할 수 있다.

**Alternatives considered**:

- 같은 DB의 `public`·`ai` schema 분리: cross-schema FK와 FastAPI의 Business 테이블 직접 조회를 허용해 Infra 권한 불변식을 위반한다.
- 별도 AI PostgreSQL 인스턴스: 격리는 강하지만 P0 비용과 운영 복잡도가 커진다. 별도 database로도 필요한 login role 격리를 달성한다.
- Business DB에 Job 저장: AI 재시도 필드 변경이 BE migration과 배포에 종속된다.
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

DB가 분리되어 있으므로 Spring의 `DocumentStatus.READY` 전환은 같은 트랜잭션에 포함하지 않는다. AI DB에서 Chunk 교체와 Job `SUCCEEDED`를 커밋한 뒤 멱등 callback으로 Business DB 상태를 전환한다.

## 7. 상태 콜백과 장애 복구

**Decision**: FastAPI는 Spring 내부 API로 사용자 문서 상태를 비동기 갱신한다. 터미널 Job에는 콜백 전달 시각과 재시도 정보를 영속화한다. 콜백 실패는 Job 결과를 되돌리지 않고 재시도하며, 주기적 reconciliation이 미전달 터미널 Job을 다시 전송한다. Spring은 처리 요청에서 반환받은 `jobId`를 다른 처리보다 먼저 저장한다. callback `404`는 `JOB_NOT_REGISTERED`·`DOCUMENT_NOT_FOUND`·`JOB_DOCUMENT_MISMATCH`로 구분하며, FastAPI는 `JOB_NOT_REGISTERED`만 1초·3초·10초 간격으로 최대 3회 재시도한다. 나머지 두 코드는 즉시 종료하고, mismatch는 Spring이 계약 오류로 경고 기록한다. 정상 전달 시각과 재시도 종료 시각·사유는 서로 다른 필드에 기록한다. ([GitLab Work Item #106](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/106))

**Rationale**: 청크 저장 후 Spring 콜백 전에 프로세스가 종료되어도 문서가 영원히 `PROCESSING`에 남지 않아야 한다.

**Alternatives considered**:

- 메모리에서만 콜백 재시도: 재시작 시 전달 여부를 잃는다.
- FastAPI가 `ai_documents`를 직접 수정: Spring 소유 행의 쓰기 경계를 위반한다.

## 8. 내부 인증과 DB 권한

**Decision**: P0 내부 API는 Security Group 네트워크 제한과 방향별 Bearer Service Token을 함께 사용한다. `INTERNAL_SPRING_TO_AI_TOKENS`와 `INTERNAL_AI_TO_SPRING_TOKENS`를 콤마 구분 목록으로 주입하며, 값은 비어 있지 않고 중복되지 않은 최대 2개로 제한한다. 송신자는 첫 값을 사용하고 수신자는 모든 값을 상수 시간 비교한다. 토큰 검증은 네트워크 설정과 독립적으로 항상 수행하며 누락·오류·반대 방향 토큰은 `401`로 거부한다. mTLS는 P2 강화 항목이다. ([GitLab Work Item #102](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/102))

토큰은 해당 방향의 송신자와 수신자에 주입한다. Spring→AI 토큰은 Spring이 송신하고 FastAPI가 검증하며, AI→Spring 토큰은 FastAPI/Worker가 송신하고 Spring이 검증한다. Spring은 `MessageDigest.isEqual`, FastAPI는 `secrets.compare_digest`를 사용한다. Secret은 Secrets Manager 또는 CI secret으로 주입하고 저장소·로그에 기록하지 않는다.

무중단 회전은 `[old] → [old,new] → [new,old] → [new]` 순서로 진행한다. 먼저 양쪽 수신자가 신규 토큰도 허용하게 한 뒤 첫 값을 신규 토큰으로 승격하고, 정상 호출을 확인한 후 기존 토큰을 제거한다. 단일 목록도 배포 순서에 따른 `401`을 막기 위해 이 승격 절차가 필요하다.

FastAPI migration/runtime role은 AI DB에만 CONNECT할 수 있고 Business DB에는 연결할 수 없다. migration role은 AI schema DDL과 `vector` 사용 스키마 migration을, runtime role은 `document_jobs`·`document_chunks` 최소 DML/sequence 권한만 가진다. Spring role은 반대로 AI DB에 CONNECT할 수 없다.

**Rationale**: 방향별 토큰은 한 방향의 자격증명이 유출돼도 반대 방향 호출에 재사용되는 것을 막는다. 같은 VPC와 Security Group은 노출 위험을 낮추지만 애플리케이션 인증을 대체하지 않는다. 콤마 목록은 Spring의 기본 목록 바인딩을 활용하고 FastAPI에서 명시적으로 분리·검증할 수 있다.

**Alternatives considered**:

- SG 제한만 사용: 내부 네트워크 침해 시 호출자 인증이 없다.
- 단일 양방향 토큰: 한 방향의 유출이 반대 방향 호출 권한까지 제공한다.
- JSON 배열: Pydantic은 기본 지원하지만 Spring에서 별도 변환이 필요해 공통 주입 형식으로 채택하지 않았다.
- `_CURRENT`/`_PREVIOUS` 개별 변수: 의미는 명확하지만 방향별 변수 수가 늘어난다. 콤마 목록을 사용하되 승격 절차와 최대 2개 제한은 동일하게 유지한다.
- P0 mTLS: 보안은 강하지만 인증서 발급·회전 운영 범위가 커진다.

## 8-1. Business snapshot과 cleanup 정합성

**Decision**: Spring은 문서 소유권·임대·상태를 Business DB에서 검증하고, 처리에 필요한 immutable snapshot(`documentId`, `boothId`, `agentId`, 파일명·형식·크기·SHA-256, `storageProvider`, `bucket`, `objectKey`)을 FastAPI 처리 요청에 전달한다. FastAPI는 snapshot을 Job에 영속화하며 처리 중 Business DB를 조회하지 않는다.

문서 삭제·비활성화 시 Spring은 멱등 cleanup 요청을 발행하고 성공할 때까지 재시도한다. FastAPI는 활성 Job을 `CANCELLED`로 전환하고 해당 문서 Chunk를 제거한다. 주기적 reconciliation은 Spring의 활성 문서 inventory와 AI DB의 Job/Chunk inventory를 비교해 고아 AI 데이터를 cleanup하고 누락·불일치는 운영 경고로 남긴다.

**Rationale**: PostgreSQL database 간 FK·cascade·직접 query 없이도 검증된 입력과 명시적 보상 작업으로 eventual consistency를 달성한다. 삭제 전에 cleanup이 실패해도 재전송과 inventory reconciliation이 고아 데이터를 수렴시킨다.

**Alternatives considered**:

- FastAPI가 Business DB를 읽기 전용 조회: Infra CONNECT matrix와 서비스 데이터 소유권을 위반한다.
- DB 간 FK 또는 외부 데이터 wrapper: 애플리케이션 경계를 DB 결합으로 되돌리고 장애 전파 범위를 넓힌다.
- 삭제 시 best-effort 1회 호출: 일시 장애 뒤 Chunk가 영구 잔존할 수 있다.

## 9. 실패 사유 정책

**Decision**: 사용자에게는 안정적인 오류 코드와 정제된 한국어 메시지만 제공한다. Stack Trace, SQL, object key, Provider 원문은 FastAPI 구조화 로그에만 남긴다.

**Rationale**: 사용자가 대처 가능한 정보를 주면서 내부 구조와 비밀정보 노출을 막는다.

**초기 오류 코드**: `PARSE_FAILED`, `UNSUPPORTED_SCAN_PDF`, `EMBEDDING_TIMEOUT`, `SOURCE_NOT_FOUND`, `SOURCE_HASH_MISMATCH`, `PROCESSING_INTERRUPTED`, `INTERNAL_ERROR`.

`SOURCE_HASH_MISMATCH`는 FastAPI가 저장소에서 내려받은 원본 바이트의 SHA-256을 다시 계산해 처리 요청의 `sourceHash`와 비교했을 때 사용한다. 데이터 무결성 오류이므로 재시도하지 않고 Job을 `DEAD`로 종료하며, Parser·Embedding·Chunk 저장을 실행하지 않은 채 Spring에 `FAILED` callback을 보낸다.

## 10. 문서 개정본 경쟁 방지

**Decision**: Job은 접수 당시 문서의 SHA-256을 snapshot으로 저장한다. 완료 콜백은 `sourceHash`를 포함하며 Spring은 현재 문서 hash와 일치할 때만 `READY`를 적용한다. 불일치하면 오래된 Job 결과를 폐기하고 최신 개정본 Job을 유지한다.

**Rationale**: 같은 `documentId`를 사용하는 명시적 교체 중 오래된 Worker가 늦게 완료되어 최신 문서를 `READY`로 덮는 경쟁 조건을 방지한다.

**Alternatives considered**:

- `documentId`만 비교: 동일 ID 개정본 간 선후관계를 판별할 수 없다.

## 11. 업로드 미완료 문서 만료와 R2 정리

**Decision**: Presigned PUT URL은 15분, 미완료 문서는 생성 후 1시간에 `EXPIRED`, 원본 정리 유예는 `EXPIRED` 전환 후 24시간으로 한다. Spring이 5분 주기로 만료를 판정하고 유예 기간 뒤 R2 원본 삭제와 실패 재시도를 소유한다. 늦은 완료는 원본이 남아 있으면 `QUEUED`로 복구한다.

**Rationale**: URL 만료와 문서 상태 만료를 분리해 진행 중 업로드의 경합을 줄이고, 늦은 완료를 복구할 시간을 주면서 미완료 객체가 영구 보관되는 것을 막는다. `EXPIRED`는 처리 결과가 아니므로 FastAPI callback 상태에 포함하지 않는다.

**Security**: Spring 자격증명에는 문서 버킷 또는 지정 prefix의 `DeleteObject` 최소 권한만 부여한다. 기존 업로드 서명 자격증명에 추가할지 삭제 전용 자격증명으로 분리할지는 Infra 배포 정책으로 선택한다.

**Alternatives considered**:

- R2 Lifecycle rule만 사용: object key만으로 업로드 미완료 문서를 구분할 수 없어 정상 원본까지 삭제할 위험이 있다.
- 만료 즉시 삭제: 지연된 완료 요청을 복구할 수 없다.
- 삭제 전 HEAD 수행: 삭제 자체가 멱등하므로 불필요한 요청과 경합만 늘어난다.

## 12. R2 장애 시 수동 MinIO fallback과 reconcile

**Decision**: P0에서는 자동 failover·이중 쓰기·자동 복제·자동 원복을 구현하지 않는다. 저장소 전환은 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE` 상태 머신을 따르며, `UPLOAD_BLOCKED` 이후 전환과 원복에는 운영자의 검증·승인이 필요하다. ([GitLab Work Item #100](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/100))

Spring은 `upload-enabled`와 `active-write-provider(R2/MINIO_LOCAL)`로 신규 업로드를 결정한다. Provider별 endpoint·bucket·credential은 Secret Reference로 따로 주입한다. FastAPI는 활성 쓰기 Provider를 선택하지 않고 문서의 `storage_provider + storage_bucket + object_key`를 읽어 해당 저장소 adapter를 사용한다. 전환 전 R2 문서는 계속 R2에서 읽고, 전환 후 신규 문서만 MinIO에 쓴다.

업로드 재개 시 Provider가 같으면 같은 문서로 재발급하고, 다르면 기존 미완료 문서를 `EXPIRED`로 전환한 뒤 새 문서와 object key로 시작한다. 저장소 일시 장애는 최대 3회(1·5·15분) 재시도 후 `DEAD → FAILED`로 종료하며 무한 재시도하지 않는다.

Infra가 MinIO와 R2 객체를 실행·검증하고 Spring이 메타데이터 Source of Truth를 갱신한다. reconcile은 동일 object key의 size·감지 MIME·SHA-256이 모두 일치한 객체만 R2 Provider로 바꾸며, 불일치·누락 객체가 있으면 `R2_RECONCILING`을 완료하지 않는다.

**Rationale**: 전역 endpoint 교체만으로는 과거 R2 문서와 신규 MinIO 문서를 동시에 읽을 수 없다. 문서별 Provider는 업로드·처리·삭제·reconcile의 대상 저장소를 결정하고, 운영자 승인 방식은 불완전한 fallback 환경으로 자동 전환되는 것을 막는다.

**Decision (2026-08-25 후속 합의)**: `DEAD`는 AI 내부 Job 상태로만 유지하고 Spring 콜백 경계(`FAILED` + `failureCode`)는 바꾸지 않는다. 저장소 복구 후 terminal Job은 자동 재처리하지 않으며, reconcile 완료 확인 후 명시적 재처리 요청으로 새 Job을 만든다. reconcile 결과는 Spring 소유 `storage_reconciliation_log`(`runId·documentId·objectKey·sourceProvider·targetProvider·expected/actual size·type·sha256·status·attemptCount·failureReason·checkedAt·resolvedAt`)에 적재하며, Infra가 `POST /internal/storage/reconciliation-runs`로 전달한다. 인증은 #102 방식을 재사용하되 `INTERNAL_INFRA_TO_SPRING_TOKENS`로 AI 방향과 credential·scope를 분리하고, `runId + documentId`로 멱등성을 보장하며 `VERIFIED` 객체만 문서 Provider를 변경한다. quota 오류는 `STORAGE_UNAVAILABLE=503`(재시도 가능), `STORAGE_QUOTA_EXCEEDED=507`(재시도 불가)로 분리한다.

**미확정**: `R2_RECONCILING` 중 신규 업로드 허용 여부, timeout·5xx·PUT/HEAD 실패의 장애 자동 판정 수치는 추가 합의가 필요하다. 두 항목은 `docs/26_팀_결정_필요사항.md`에 등록했으며 후속 이슈에서 확정한다. P0은 probe evidence 수집과 운영자 수동 `UPLOAD_BLOCKED` 적용으로 대체하고 구현자는 임의로 확정하지 않는다.

**Alternatives considered**:

- 전역 endpoint 하나만 교체: 기존 R2 객체를 읽을 수 없어 문서별 Provider 계약을 위반한다.
- 런타임 자동 MinIO 전환: 운영자 승인과 fallback 검증을 건너뛸 수 있다.
- R2·MinIO 이중 쓰기: 정합성·비용·실패 조합이 늘어나 P0 범위를 초과한다.
- MinIO를 백업으로 간주: 단일 EC2 장애 시 함께 유실될 수 있어 보장할 수 없다.
