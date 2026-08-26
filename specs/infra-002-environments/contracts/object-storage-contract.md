# S3-compatible Object Storage Contract v1.1

Cloudflare R2가 primary이고 단일 node MinIO는 수동 emergency provider다. 신규 쓰기는 한 시점에 하나의 provider만 사용하지만, 기존 객체 읽기는 문서별 `storageProvider`를 따른다. R2·MinIO endpoint·bucket·credential reference는 전환 후에도 기존 객체를 읽을 수 있도록 provider registry에 함께 유지한다.

## Provider boundaries

| Provider | Bucket purpose | Public | Browser CORS | Durability role |
|---|---|:---:|:---:|---|
| R2 Standard | private AI original documents | No | approved upload origins + PUT only | primary original store |
| R2 Standard | private PostgreSQL backups | No | No | off-host DB backup |
| MinIO single-node/single-drive | R2 outage 중 새 documents | No | approved upload origins + PUT only | temporary availability, not backup |

document signer, AI reader, backup writer, restore reader credential을 분리한다. 각 credential은 필요한 bucket/operation만 허용한다.

문서 저장소 식별자는 `R2`와 `MINIO_LOCAL`로 고정한다. `R2_BACKUPS`는 PostgreSQL backup binding이며 문서의 `storageProvider` 값으로 사용하지 않는다.

## Runtime control and reader registry

[storage-failover-state.schema.json](./storage-failover-state.schema.json)이 운영자 승인 기반 쓰기 control plane의 기계 판독 계약이다. Spring 배포 설정은 다음 두 값을 소비한다.

```yaml
app.ai.storage:
  upload-enabled: true
  active-write-provider: R2 # R2 | MINIO_LOCAL
```

- `upload-enabled=false`이면 active provider 값과 무관하게 신규 upload grant를 발급하지 않는다.
- `active-write-provider`는 신규 upload grant의 provider만 정한다. 기존 객체의 HEAD·GET 대상에 사용하지 않는다.
- FastAPI에는 `active-write-provider`를 주입하지 않는다. FastAPI는 Job/문서 metadata의 `storageProvider`와 읽기 전용 provider registry를 사용한다.
- Spring과 FastAPI 모두 기존 객체를 읽는 데 필요한 R2·MinIO provider config reference를 가진다. Secret 원문은 schema·manifest·로그에 넣지 않는다.
- 배포 전 검증은 활성 쓰기 provider가 Spring registry에 존재하고, 두 서비스의 동일 provider ID가 같은 endpoint/bucket 계열 reference를 가리키는지 확인한다.

## Upload flow

1. Spring은 authenticated user의 booth/agent/document 권한을 검증한다.
2. Spring이 `documentId`와 unpredictable unique object key를 생성한다. client가 보낸 key/path/userId를 신뢰하지 않는다.
3. Usage Admission이 `NORMAL` 또는 `WARNING`이고 Storage Failover Control이 `uploadEnabled=true`이며 active write provider가 writable일 때만 단일 객체 PUT grant를 발급한다.
4. grant와 문서 metadata에는 발급 당시 `storageProvider`, bucket reference와 object key를 고정한다. 이후 active write provider가 바뀌어도 완료 HEAD·GET은 이 값을 따른다.
5. 응답에만 presigned URL과 required headers를 포함한다. URL을 log, DB, cache, release artifact에 저장하지 않는다.
6. Browser는 정확한 `Content-Type` header와 body로 provider에 직접 PUT한다.
7. 완료 요청을 받으면 grant에 고정된 provider에서 server-side HEAD로 존재·length·declared type을 확인한다.
8. 제한된 GET/stream으로 magic bytes와 SHA-256을 검사한다. 검증 전 processing을 시작하지 않는다.
9. 완료 요청은 idempotent하며 같은 grant로 AI job을 두 번 만들지 않는다.

Presigned URL은 “단일 object·operation·expiry 범위”이지 one-time token은 아니다. 만료 전 재사용 가능성을 object key와 idempotent completion으로 통제한다.

## Grant response fields

`grantId`, `documentId`, `operation`, `expiresAt`, `objectKey`의 opaque reference, `requiredHeaders`, `uploadUrl`을 반환한다. `storageProvider`는 grant/문서 내부 metadata에 고정하며 이 Infra 계약은 사용자-facing API payload 추가를 요구하지 않는다. `uploadUrl`은 민감정보로 취급한다.

## CORS

- `AllowedOrigins`: `https://demo.${ROOT_DOMAIN}`와 명시적으로 승인한 dev origin만; wildcard 금지.
- `AllowedMethods`: `PUT`.
- `AllowedHeaders`: 실제 signature/request에 쓰는 `Content-Type`, checksum/metadata header만.
- `ExposeHeaders`: 필요 시 `ETag`; ETag를 SHA-256으로 간주하지 않는다.
- backup bucket에는 CORS가 없다.

## Verification outcomes

| Failure | Result |
|---|---|
| missing object | reject; processing not started |
| length mismatch/limit exceeded | reject and quarantine/delete per document policy |
| signed vs sent Content-Type mismatch | provider 403; completion reject |
| declared vs detected content mismatch | reject |
| SHA-256 mismatch | reject |
| expired or wrong-object URL | provider reject; no new object/job |
| repeated completion | return prior result; no duplicate job |

## Usage admission state

[usage-guard.schema.json](./usage-guard.schema.json)의 snapshot을 사용한다. 80% warning에서는 upload를 허용하고 경고하며 90% 또는 stale에서는 새 grant를 발급하지 않는다. 기존 GET과 비AI 경로는 유지한다.

이 snapshot의 `state`는 `UsageAdmissionState`이며 active provider를 포함하거나 변경하지 않는다. `UsageAdmissionState.UPLOAD_BLOCKED`와 아래 `StorageFailoverState.UPLOAD_BLOCKED`는 이름만 같고 소유 목적이 다른 상태다.

## Manual fallback state

허용 상태 전이는 다음뿐이다.

```text
R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE
LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE
```

- 자동 provider 변경·이중 쓰기·자동 복제·자동 원복 금지.
- `FALLBACK_VALIDATING`에서 disk free space, credential, PUT, HEAD, CORS, public port denial 증거가 모두 필요하다.
- `LOCAL_ACTIVE`에서만 `uploadEnabled=true`, `activeWriteProvider=MINIO_LOCAL`이다. 이때 생성한 metadata는 `MINIO_LOCAL`을 명시하고 기존 R2 object는 R2에서 읽는다.
- `UPLOAD_BLOCKED`, `FALLBACK_VALIDATING`, `R2_RECONCILING`에서는 `uploadEnabled=false`, active write provider는 null이다.
- `R2_RECONCILING` 진입 전에 신규 upload grant를 차단해 검사 대상을 고정한다.
- reconcile은 동일 key의 size, detected type, SHA-256을 비교하고 성공한 object만 metadata를 R2로 전환한다.
- 불일치/누락 object가 있으면 `R2_RECONCILING`을 완료하지 않고 자동 원복하지 않는다.
- EC2/MinIO 유실은 복구할 수 없으며 MinIO object를 backup 수량에 포함하지 않는다.

| StorageFailoverState | uploadEnabled | activeWriteProvider | 허용 동작 |
|---|:---:|---|---|
| `R2_ACTIVE` | true | `R2` | R2 신규 쓰기, 객체별 provider 읽기 |
| `UPLOAD_BLOCKED` | false | null | 기존 객체 읽기, 장애 조사 |
| `FALLBACK_VALIDATING` | false | null | MinIO probe와 승인 근거 수집 |
| `LOCAL_ACTIVE` | true | `MINIO_LOCAL` | MinIO 신규 쓰기, 객체별 provider 읽기 |
| `R2_RECONCILING` | false | null | 고정 backlog 복사·검증, 기존 객체 읽기 |

### Availability detection boundary

- P0에서는 timeout, 연속 5xx 횟수와 관측 시간의 자동 장애 판정 수치를 고정하거나 코드 기본값으로 두지 않는다.
- P0는 R2 probe의 timeout·5xx·latency를 관측 자료와 알림 evidence로 수집하고, Infra 운영자가 이를 검증한 뒤 `UPLOAD_BLOCKED`를 수동 적용한다.
- 자동 판정 수치는 P0 모니터링 자료가 확보된 뒤 Infra·BE의 별도 이슈에서 결정하고 장애 주입 시험으로 검증한다.
- 후속 자동 판정을 도입해도 자동으로 허용되는 상태 변경은 `UPLOAD_BLOCKED`뿐이다. `FALLBACK_VALIDATING`, `LOCAL_ACTIVE`, `R2_RECONCILING`, `R2_ACTIVE` 전환은 운영자 승인 없이 수행하지 않는다.
- 이 가용성 판정은 quota·metric freshness만 소유하는 `usage-guard.schema.json`에 포함하지 않는다.

### Deploy and rollback

1. P0에서는 R2 probe evidence를 검토한 운영자 판단으로 `UPLOAD_BLOCKED` control state를 배포하고 Spring의 신규 grant 차단을 확인한다. 후속 자동 판정을 도입하더라도 자동 동작의 최대 범위는 쓰기 차단까지다.
2. 운영자가 MinIO probe evidence를 승인한 뒤 `FALLBACK_VALIDATING → LOCAL_ACTIVE` 설정을 배포한다.
3. Spring 신규 grant가 `MINIO_LOCAL`로 서명되고 기존 R2 문서가 계속 R2에서 읽히는지 smoke 검증한다.
4. 설정·배포·smoke 중 실패하면 `LOCAL_ACTIVE`를 유지하지 않는다. R2가 아직 불가하면 `UPLOAD_BLOCKED`로 rollback하고, R2가 검증된 경우에만 `R2_ACTIVE`로 복귀한다.
5. endpoint·bucket·credential 원문은 전환 상태에 저장하지 않고 provider config/Secret reference만 변경한다.

### Reconciliation result

Infra는 MinIO backlog를 고정한 뒤 동일 object key로 R2에 복사하고 객체마다 다음 결과를 생성한다.

`runId`, `documentId`, `objectKey`, `sourceProvider`, `targetProvider`, expected/actual size, expected/actual detected type, expected/actual SHA-256, `status`, `attemptCount`, `failureReason`, `checkedAt`, `resolvedAt`, evidence reference.

- 상태는 최소 `PENDING`, `VERIFIED`, `UNRESOLVED`를 구분한다.
- Infra evidence는 Secret·presigned URL을 제거하고, application metadata Source of Truth가 결과를 영속 반영할 수 있는 handoff를 제공한다.
- metadata provider 변경은 `VERIFIED` 객체만 허용한다. `UNRESOLVED`가 1건이라도 있으면 원복 승인을 막는다.
- reconciliation 기록 자체가 object body의 backup을 의미하지 않는다.

## PostgreSQL backup object

권장 key:

```text
postgresql/{env}/{database}/daily/{yyyy}/{mm}/{dd}/{backupSetId}.dump
postgresql/{env}/{database}/weekly/{yyyy}-W{ww}/{backupSetId}.dump
postgresql/{env}/{database}/pre-migration/{releaseId}/{backupSetId}.dump
```

dump와 manifest/checksum을 같이 보관한다. R2 document object는 backup bucket에 복제하지 않고 inventory reference만 연결한다.

