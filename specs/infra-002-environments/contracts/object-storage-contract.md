# S3-compatible Object Storage Contract v1

Cloudflare R2가 primary이고 단일 node MinIO는 수동 emergency provider다. Backend/FastAPI가 공급자별 endpoint·region·credential을 adapter 뒤에서 교체하며 사용자-facing 문서 payload는 바꾸지 않는다.

## Provider boundaries

| Provider | Bucket purpose | Public | Browser CORS | Durability role |
|---|---|:---:|:---:|---|
| R2 Standard | private AI original documents | No | approved upload origins + PUT only | primary original store |
| R2 Standard | private PostgreSQL backups | No | No | off-host DB backup |
| MinIO SNSD | R2 outage 중 새 documents | No | approved upload origins + PUT only | temporary availability, not backup |

document signer, AI reader, backup writer, restore reader credential을 분리한다. 각 credential은 필요한 bucket/operation만 허용한다.

## Upload flow

1. Spring은 authenticated user의 booth/agent/document 권한을 검증한다.
2. Spring이 `documentId`와 unpredictable unique object key를 생성한다. client가 보낸 key/path/userId를 신뢰하지 않는다.
3. Usage Guard가 `NORMAL` 또는 `WARNING`이고 active provider가 writable일 때만 단일 객체 PUT grant를 발급한다.
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

## Usage admission

[usage-guard.schema.json](./usage-guard.schema.json)의 snapshot을 사용한다. 80% warning에서는 upload를 허용하고 경고하며 90% 또는 stale에서는 새 grant를 발급하지 않는다. 기존 GET과 비AI 경로는 유지한다.

## Manual fallback

허용 상태 전이는 다음뿐이다.

```text
R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE
LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE
```

- 자동 provider 변경 금지.
- `FALLBACK_VALIDATING`에서 disk free space, credential, PUT, HEAD, CORS, public port denial 증거가 모두 필요하다.
- `LOCAL_ACTIVE`에서 생성한 metadata는 provider `MINIO_LOCAL`을 명시한다. 기존 R2 object는 R2에서 읽는다.
- reconcile은 동일 key의 size, detected type, SHA-256을 비교하고 성공한 object만 metadata를 R2로 전환한다.
- 불일치/누락 object가 있으면 `R2_RECONCILING`을 완료하지 않는다.
- EC2/MinIO 유실은 복구할 수 없으며 MinIO object를 backup 수량에 포함하지 않는다.

## PostgreSQL backup object

권장 key:

```text
postgresql/{env}/{database}/daily/{yyyy}/{mm}/{dd}/{backupSetId}.dump
postgresql/{env}/{database}/weekly/{yyyy}-W{ww}/{backupSetId}.dump
postgresql/{env}/{database}/pre-migration/{releaseId}/{backupSetId}.dump
```

dump와 manifest/checksum을 같이 보관한다. R2 document object는 backup bucket에 복제하지 않고 inventory reference만 연결한다.

