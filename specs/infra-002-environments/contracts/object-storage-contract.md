# S3-compatible Object Storage Contract v1

Cloudflare R2가 정상 운영의 문서 저장소다. R2 장애 또는 R2 사용량 입장 제어 차단 시 신규 업로드를 먼저 fail-closed한다. R2 장기 장애에서만 운영자 승인을 거쳐 MinIO fallback으로 전환할 수 있으며, 로컬 디스크·자동 provider 전환은 허용하지 않는다. Backend/FastAPI는 provider endpoint·region·credential을 adapter 뒤에서 사용하고 사용자-facing 문서 payload는 바꾸지 않는다.

## Provider boundaries

| Provider | Bucket purpose | Public | Browser CORS | Durability role |
|---|---|:---:|:---:|---|
| R2 Standard | private AI original documents | No | approved upload origins + PUT only | primary original store |
| MinIO | private AI original documents | No | approved upload origins + PUT only | operator-approved R2 long-outage fallback |
| R2 Standard | private PostgreSQL backups | No | No | off-host DB backup |

document signer, AI reader, backup writer, restore reader credential을 분리한다. 각 credential은 필요한 bucket/operation만 허용한다.

### Credential operation matrix

| Credential / grant | 대상 | 허용 작업 | 금지 사항 |
|---|---|---|---|
| Spring document manager (`document signer`) | AI 원본 document bucket의 서버 생성 document prefix | 단일 객체 `PutObject` presign, 완료 검증용 `HeadObject`/제한된 `GetObject`, 만료 객체 `DeleteObject` | client가 지정한 key 삭제, backup bucket 접근, bucket 전체 삭제 |
| Browser presigned grant | Spring이 생성한 단일 `objectKey` | `PutObject`만, 15분 | `GetObject`, `ListBucket`, `DeleteObject`, 다른 key 접근 |
| AI reader | Job에 기록된 provider와 `objectKey` | `GetObject`만 | `PutObject`, `DeleteObject`, backup bucket 접근 |
| Backup writer | PostgreSQL backup bucket/prefix | backup 객체 쓰기 | AI 원본 bucket 접근, `DeleteObject` |
| Restore reader | PostgreSQL backup bucket/prefix | 복구에 필요한 읽기 | AI 원본 bucket 접근, `PutObject`, `DeleteObject` |

P0에서는 삭제 전용 credential을 추가하지 않는다. Spring document manager의 `DeleteObject`는 AI 원본 document bucket과 서버가 생성한 document prefix에만 부여하며 PostgreSQL backup bucket에는 부여하지 않는다. Secret 원문은 provider별 런타임 Secret 경계에서 주입하고 저장소·로그·API payload에 남기지 않는다.

## Upload flow

1. Spring은 authenticated user의 booth/agent/document 권한을 검증한다.
2. Spring이 `documentId`와 unpredictable unique object key를 생성한다. client가 보낸 key/path/userId를 신뢰하지 않는다.
3. Usage Guard가 `NORMAL` 또는 `WARNING`이고 R2 admission state가 writable일 때만 단일 객체 PUT grant를 발급한다.
4. 응답에만 presigned URL과 required headers를 포함한다. URL을 log, DB, cache, release artifact에 저장하지 않는다.
5. Browser는 정확한 `Content-Type` header와 body로 provider에 직접 PUT한다.
6. 완료 요청을 받으면 server-side HEAD로 존재·length·declared type을 확인한다.
7. 제한된 GET/stream으로 magic bytes와 SHA-256을 검사한다. 검증 전 processing을 시작하지 않는다.
8. 완료 요청은 idempotent하며 같은 grant로 AI job을 두 번 만들지 않는다.

Presigned URL은 “단일 object·operation·expiry 범위”이지 one-time token은 아니다. 만료 전 재사용 가능성을 object key와 idempotent completion으로 통제한다.

## Grant response fields

`grantId`, `documentId`, `operation`, `expiresAt`, `objectKey`의 opaque reference, `requiredHeaders`, `uploadUrl`을 반환한다. `uploadUrl`은 민감정보로 취급한다.

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

## Expired upload deletion

업로드 미완료 문서 정리 상태와 TTL은 [spec 007 C-08](../../007-ai-agent-document/spec.md)의 Spring 계약을 따른다. Infra는 다음 권한 경계를 보장하고 삭제 대상 상태를 자체적으로 추론하지 않는다.

1. Spring은 삭제 직전에 DB에서 문서가 `EXPIRED`이고 전환 후 24시간이 지났는지 다시 확인한다.
2. 삭제 key는 client 입력이 아니라 문서 metadata의 `storageProvider`와 `objectKey`로 결정한다.
3. 정상 운영에서는 R2 adapter로 삭제한다. fallback 전환 뒤에는 문서 metadata의 `storageProvider`가 가리키는 adapter로 삭제한다.
4. 존재하지 않는 key의 삭제는 멱등 성공으로 처리한다. provider 오류는 문서의 `EXPIRED` 상태와 `objectKey`를 유지한 채 기록하고 다음 정리 주기에 재시도한다.
5. 정상 문서와 미완료 문서가 같은 document prefix를 사용하므로 R2 lifecycle rule로 이 삭제를 대신하지 않는다.

정상 운영의 신규 문서는 R2 reader/manager를 사용한다. fallback 중에도 기존 문서는 각 문서의 `storageProvider`를 따라 읽고 삭제한다.

## Usage admission

[usage-guard.schema.json](./usage-guard.schema.json)의 snapshot을 사용한다. 80% warning에서는 upload를 허용하고 경고하며 90% 또는 stale에서는 새 grant를 발급하지 않는다. 기존 GET과 비AI 경로는 유지한다.

## R2 outage handling

- R2 probe 실패 또는 Usage Guard 차단 상태에서는 신규 PUT grant를 발급하지 않는다.
- 자동 provider 전환과 로컬 디스크 fallback은 허용하지 않는다. R2 장기 장애의 MinIO 전환·reconciliation은 운영자 승인 후에만 `storage-failover.sh`로 수행한다.
- `storage-failover.sh`는 상태 전환과 reconcile 실행 전체를 단일 lock으로 직렬화하며, 진행 중 run이 있으면 다음 전환·reconcile 시작을 거부한다. reconcile 요청은 target provider와 `targetBucket`을 명시한다.
- ⚠️ **`targetBucket`은 아직 보낼 수 없다 — 계약 개정이 선행이다.** spec 007 계약은 현재 `0.2.0`이고 reconcile 요청 스키마가 `additionalProperties: false`라, 이 필드를 실으면 무시가 아니라 `422 RECONCILIATION_INVALID`로 거절된다(`StorageReconciliationControllerIntegrationTest#anUndefinedFieldIsRejected`가 `errors[0].field = targetBucket`으로 고정). 계약 `0.3.0`(`targetBucket` required)과 Spring 수신단 구현이 `storage-failover.sh` 착수(spec 007 T076)보다 먼저다. 방향은 GitLab #100에서 합의됐고 남은 것은 시점뿐이다 — Jira `S15P21A604-770`.
- 기존 문서 조회와 정상 비AI 경로는 R2 의존 범위 밖에서 계속 제공한다.
- active provider가 R2인 차단 상태는 R2 contract probe와 최신 usage snapshot이 모두 성공해야 업로드를 재개한다. MinIO fallback 중 R2로의 복귀는 자동 전환하지 않고, 운영자 승인과 같은 단일 lock 절차를 다시 거친다.
- R2 원본 문서는 별도 2차 백업이 없으며 PostgreSQL dump만 private R2 backup bucket에 보관한다.

## PostgreSQL backup object

권장 key:

```text
postgresql/{env}/{database}/daily/{yyyy}/{mm}/{dd}/{backupSetId}.dump
postgresql/{env}/{database}/weekly/{yyyy}-W{ww}/{backupSetId}.dump
postgresql/{env}/{database}/pre-migration/{releaseId}/{backupSetId}.dump
```

dump와 manifest/checksum을 같이 보관한다. R2 document object는 backup bucket에 복제하지 않고 inventory reference만 연결한다.

