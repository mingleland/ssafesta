# Data Model: dev/demo 실행 환경

이 문서는 application domain table을 새로 정의하지 않는다. 환경 구성, 저장 경계, 운영 상태와 검증 증거를 모델링한다. 영구 비즈니스/RAG/survey entity는 기존 Spring·AI schema가 소유한다.

## 1. Environment Manifest

배포 target이 소비하는 공급자 중립 환경 선언이다. JSON 형식은 [environment-manifest.schema.json](./contracts/environment-manifest.schema.json)을 따른다.

| Field | Type | Rule |
|---|---|---|
| `schemaVersion` | string | 현재 `1.0.0` |
| `environmentId` | enum | `dev` 또는 `demo` |
| `kind` | enum | `part-dev` 또는 `integrated-demo` |
| `composeProject` | string | 환경마다 고유; dev/demo 동일값 금지 |
| `deploymentTargetIds` | map | dev는 component별 `dev-ai/back/front/game`, demo는 `integration` |
| `releaseManifestRef` | path/ref | infra-001 release manifest; Secret 또는 mutable tag 금지 |
| `runtimeServices` | Runtime Service[] | service 이름·component·internal endpoint·health·resource ref |
| `publicEntryRefs` | string[] | Public Entry ID 참조 |
| `dataBindings` | Data Binding[] | PostgreSQL/Redis/object/volume binding 참조 |
| `secretRefs` | string[] | 실제 값이 아닌 runtime secret identifier |
| `resourcePolicy` | Resource Policy | demo 우선 보호와 build concurrency |

**Validation**

- `environmentId`, `composeProject`, network/volume/release state는 dev와 demo 간 중복될 수 없다.
- `kind=integrated-demo`이면 `integration` target과 모든 필수 component가 있어야 한다.
- image와 release provenance는 infra-001 manifest에서 가져오며 manifest에 credential 원문을 넣지 않는다.
- service endpoint는 Docker alias 또는 template 변수이며 실제 도메인/IP를 client artifact에 고정하지 않는다.

## 2. Runtime Service

| Field | Type | Rule |
|---|---|---|
| `serviceId` | string | 환경 내 고유 |
| `component` | enum | `ai`, `back`, `front`, `game`, `ingress`, `postgres`, `redis`, `minio` |
| `imageRef` | string/ref | immutable tag/digest; data service는 pinned version |
| `networkRefs` | string[] | 필요한 internal network만 |
| `internalEndpoint` | URI | host port 공개 주소 금지 |
| `healthCheckRef` | string | readiness 판정 규칙 |
| `cpuLimitRef`, `memoryLimitRef` | string | 실측 후 채워지는 resource config key |
| `persistentVolumeRefs` | string[] | stateless service는 비어 있음 |
| `releaseId` | string/null | app service는 infra-001 release와 연결 |

**Relationship**: Environment 1:N Runtime Service. Shared data service는 별도 data project가 소유하며 app component deployment에 의해 재생성되지 않는다.

## 3. Public Entry

| Field | Type | Rule |
|---|---|---|
| `entryId` | string | manifest에서 참조 |
| `environmentId` | enum | `dev`, `demo` |
| `scheme` | enum | dev 임시 `http`; demo `https`/`wss` |
| `hostTemplate` | string | demo는 `${ROOT_DOMAIN}` template, dev는 `${EC2_PUBLIC_IP}` |
| `pathPrefix` | string | dev 제한 경로 또는 `/` |
| `upstreamServiceId` | ref | Runtime Service |
| `cacheMode` | enum | `static-immutable`, `html-revalidate`, `bypass` |
| `tlsMode` | enum | `none-dev-only`, `full-strict` |
| `validationOwner` | string | infra-002 또는 infra-003 |

상세 routing과 cache 불변식은 [public-entry-contract.md](./contracts/public-entry-contract.md)를 따른다.

## 4. PostgreSQL Binding

| Field | Type | Rule |
|---|---|---|
| `bindingId` | string | 환경·용도 조합 |
| `environmentId` | enum | `dev`, `demo` |
| `purpose` | enum | `business`, `ai-vector` |
| `databaseName` | string | 다른 binding과 고유 |
| `serviceRole` | string | 다른 binding과 고유 |
| `credentialRef` | secret ref | password 원문 금지 |
| `migrationOwner` | enum | `spring`, `ai` |
| `backupPolicyRef` | ref | Backup Policy |
| `persistentVolumeRef` | ref | shared PostgreSQL volume |

**Required instances**

| Environment | Purpose | Database | Role |
|---|---|---|---|
| dev | business | `festa_dev_business` | `festa_dev_back_app` |
| dev | ai-vector | `festa_dev_ai` | `festa_dev_ai_app` |
| demo | business | `festa_demo_business` | `festa_demo_back_app` |
| demo | ai-vector | `festa_demo_ai` | `festa_demo_ai_app` |

`PUBLIC CONNECT`를 회수하고 각 role은 자기 database에만 접근한다. role은 `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`이며 AI DB의 `vector` extension은 관리자만 설치한다.

## 5. Object Storage Provider

| Field | Type | Rule |
|---|---|---|
| `providerId` | enum | 문서용 `R2`, `MINIO_LOCAL`; 백업용 `R2_BACKUPS` |
| `providerType` | enum | `R2`, `MINIO` |
| `endpointRef`, `region` | config | S3 adapter input |
| `bucket` | string | document와 backup 별도 |
| `storageClass` | enum | R2는 `STANDARD` |
| `publicAccessEnabled` | boolean | 항상 false |
| `credentialRef`, `readCredentialRef` | secret refs | bucket/operation 최소 권한 |
| `keyTemplate` | string | client 입력을 직접 삽입하지 않음 |
| `presignTtlSeconds` | integer | 짧은 설정값, 공급자 최대보다 작음 |
| `allowedContentTypes`, `maxObjectBytes` | policy | application 문서 정책 참조 |

문서 bucket과 backup bucket의 credential은 교차 접근하지 못한다.

Spring과 FastAPI의 provider registry에는 문서용 `R2`와 `MINIO_LOCAL` 연결 reference를 동시에 유지한다. 활성 쓰기 provider는 Spring만 소비하며 FastAPI는 문서/Job의 `providerId`로 읽기 provider를 선택한다.

## 6. Temporary Object Grant and Upload Verification

### Temporary Object Grant

| Field | Type | Rule |
|---|---|---|
| `grantId` | UUID/string | 서버 생성 |
| `environmentId`, `documentId`, `providerId` | refs | grant 발급 당시 권위 있는 metadata에서 결정하고 이후 active write provider 변경으로 바꾸지 않음 |
| `bucket`, `objectKey` | string | 단일 객체에 고정 |
| `operation` | enum | `PUT`, `GET`, `HEAD` |
| `expectedSizeBytes`, `expectedContentType`, `expectedSha256` | values | 허용 정책과 일치 |
| `requiredHeaders` | map | signature에 포함 |
| `issuedAt`, `expiresAt` | timestamp | 짧은 TTL |
| `status` | enum | 아래 상태 전이 |

Presigned URL 원문은 entity에 저장하지 않고 발급 응답에서만 전달한다.

### Grant state

```text
ISSUED → UPLOADED_REPORTED → HEAD_VERIFIED → BODY_VERIFIED → CONSUMED
   ├──────────────→ EXPIRED
   └──────────────→ REJECTED
```

- HEAD 검증은 존재·size·declared Content-Type만 판정한다.
- BODY 검증은 magic bytes와 SHA-256을 판정한다.
- `CONSUMED` 전이만 후속 AI 처리를 한 번 시작할 수 있고 같은 완료 요청은 같은 결과를 반환한다.

### Upload Verification

`grantId`, `verifiedAt`, HEAD status, length, declared type, detected type, ETag, verified SHA-256, result, rejection reason, `processingAllowed`, evidence reference를 가진다. ETag는 SHA-256의 대체값이 아니다.

## 7. Usage Admission Snapshot

JSON 형식은 [usage-guard.schema.json](./contracts/usage-guard.schema.json)을 따른다.

| Field | Type | Rule |
|---|---|---|
| `billingMonthUtc` | `YYYY-MM` | Cloudflare billing 기준 month |
| `collectedAt`, `dataFreshThrough` | timestamp | freshness 판정 |
| `source` | enum | GraphQL/API/mock-test |
| `currentStorageBytes` | integer | account Standard 합계 |
| `projectedGbMonth` | number | 일별 peak 기반 보수적 예상 |
| `classARequests`, `classBRequests` | integer | month-to-date account 합계 |
| `limits` | object | 값·source URL·verified date 포함 |
| `ratios` | object | storage current/projected, A, B, max |
| `state` | enum | `UsageAdmissionState`: `NORMAL`, `WARNING`, `UPLOAD_BLOCKED`, `STALE_BLOCKED` |
| `existingReadsAllowed` | boolean | 항상 true |
| `evidenceRef` | string | Secret 없는 snapshot/report |

**State rules**

```text
maxRatio < 0.80                  → NORMAL
0.80 ≤ maxRatio < 0.90          → WARNING
maxRatio ≥ 0.90                 → UPLOAD_BLOCKED
now - dataFreshThrough > 60 min → STALE_BLOCKED (ratio보다 우선)
```

이 snapshot은 R2 사용량과 지표 freshness만 판정한다. active write provider, MinIO 상태 또는 운영자 승인 정보는 포함하지 않으며 [storage-failover-state.schema.json](./contracts/storage-failover-state.schema.json)의 control state를 변경하지 않는다.

## 8. Storage Failover State

JSON 형식은 [storage-failover-state.schema.json](./contracts/storage-failover-state.schema.json)을 따른다.

| Field | Type | Rule |
|---|---|---|
| `state` | enum | 아래 전이 중 하나 |
| `uploadEnabled` | boolean | `R2_ACTIVE`, `LOCAL_ACTIVE`에서만 true |
| `activeWriteProvider` | provider ref/null | true이면 각각 `R2`, `MINIO_LOCAL`; blocked/검증/reconcile 상태는 null |
| `providerConfigRefs` | map | R2·MinIO 설정/Secret 묶음의 reference; 원문 금지 |
| `changedBy` | operator id | 자동 전환 금지 |
| `changedAt`, `reason`, `approvalRef` | audit | 필수 |
| `validationEvidenceRefs` | string[] | provider probe 결과 |
| `backlogObjectCount`, `reconciliationRunId`, `reconciliationCursor` | recovery | MinIO → R2 진행 상태 |
| `lastVerifiedAt` | timestamp | 상태 검증 시각 |

```text
R2_ACTIVE
  → UPLOAD_BLOCKED
  → FALLBACK_VALIDATING
  → LOCAL_ACTIVE
  → R2_RECONCILING
  → R2_ACTIVE
```

모든 전이는 운영자 승인과 evidence를 요구한다. `LOCAL_ACTIVE` 객체는 provider metadata가 `MINIO_LOCAL`이고 reconcile 검증 전에는 R2 객체로 표시하지 않는다. P0에서는 `R2_RECONCILING` 동안 `uploadEnabled=false`로 backlog를 고정한다.

### Reconciliation Run

| Field | Type | Rule |
|---|---|---|
| `runId`, `environmentId` | identity | 운영자가 시작한 reconcile 실행 |
| `sourceProvider`, `targetProvider` | enum | `MINIO_LOCAL` → `R2` 고정 |
| `startedBy`, `approvedBy` | operator refs | 자동 실행 금지 |
| `startedAt`, `finishedAt` | timestamp/null | UTC |
| `status` | enum | `RUNNING`, `VERIFIED`, `UNRESOLVED` |
| `backlogObjectCount`, `verifiedCount`, `unresolvedCount` | integer | 합계가 inventory와 일치 |
| `evidenceRef` | ref | 민감정보 제거 결과 |

### Reconciliation Item

| Field | Type | Rule |
|---|---|---|
| `runId`, `documentId`, `objectKey` | identity/refs | 문서 metadata와 연결 |
| `sourceProvider`, `targetProvider` | enum | `MINIO_LOCAL` → `R2` |
| `expectedSize`, `actualSize` | integer/null | byte 대조 |
| `expectedDetectedType`, `actualDetectedType` | string/null | client declared type이 아닌 감지 형식 대조 |
| `expectedSha256`, `actualSha256` | digest/null | 전체 본문 SHA-256 |
| `status` | enum | `PENDING`, `VERIFIED`, `UNRESOLVED` |
| `attemptCount`, `failureReason` | integer/string/null | 미해결 사유 필수 |
| `checkedAt`, `resolvedAt` | timestamp/null | 감사 시각 |
| `evidenceRef` | ref | Secret·presigned URL 원문 금지 |

`VERIFIED` item만 application metadata의 provider를 R2로 변경할 수 있다. `UNRESOLVED` item이 1건이라도 있으면 run은 `VERIFIED`가 될 수 없고 `R2_ACTIVE` 전이를 승인하지 않는다.

## 9. Redis Cache Class

| Field | Type | Rule |
|---|---|---|
| `classId`, `environmentId`, `ownerService` | identity | ACL user와 연결 |
| `keyPattern` | string | environment prefix 필수 |
| `sourceOfTruth` | enum | `redis-ephemeral`, `postgres-business`, `postgres-ai-r2` |
| `ttlPolicy` | duration/rule | 무기한이면 명시적 근거 필요; 현재 범위는 모두 TTL/lease |
| `invalidationTriggers` | event[] | source commit 이후 실행 |
| `fallbackBehavior` | enum | `reauthenticate`, `source-read`, `recompute`, `lease-expired` |
| `sensitivePayload` | boolean | log/metric value 금지 |
| `maxEntryBytes`, `namespaceBudget` | config refs | 큰 원문 저장 방지 |
| `aclUserRef` | secret/config ref | environment/service 전용 |

**Key classes**

| Key pattern | Source/meaning | Invalidation/fallback |
|---|---|---|
| `<env>:auth:refresh:<hash>` | 유실 허용 session | TTL; 유실 시 재로그인 |
| `<env>:auth:session:<userId>` | active session pointer | TTL/logout; 유실 시 재로그인 |
| `<env>:auth:oauth-handoff:<opaqueId>` | 일회 handoff | 짧은 TTL/consume |
| `<env>:presence:<scope>:<memberId>` | 실시간 presence | heartbeat TTL |
| `<env>:lock:<resource>:<id>` | 임시 lease/lock | lease TTL |
| `<env>:wallet:daily:<userId>:<date>` | PostgreSQL rule cache | KST day TTL; miss는 DB transaction |
| `<env>:rag:<boothId>:<agentId>:<sourceRevision>:<queryHash>` | PG/R2에서 재생성한 검색 결과 | document revision 변경/TTL/source-read |
| `<env>:survey:<surveyId>:<sourceRevision>:aggregate` | PostgreSQL 응답 집계 | response commit 후 revision 변경/TTL/recompute |

RAG document, metadata, chunk, vector와 survey/question/response는 이 모델에 포함되지 않는다.

## 10. Backup Set

| Field | Type | Rule |
|---|---|---|
| `backupSetId`, `environmentId`, `databaseName` | identity | database별 dump |
| `tier` | enum | `daily`, `weekly`, `pre-migration` |
| `sourceReleaseId`, `schemaVersion` | provenance | restore 호환성 판정 |
| `postgresVersion`, `pgvectorVersion` | version | AI DB는 pgvector 필수 |
| `dumpKey`, `dumpSizeBytes`, `sha256` | R2 object evidence | backup bucket에만 저장 |
| `createdAt` | timestamp | UTC |
| `documentInventoryRef` | reference | 당시 R2 문서 목록 참조; 복제본 아님 |
| `restoreVerificationRef` | reference/null | rehearsal 성공 증거 |

보존은 daily 7, weekly 4이며 migration 직전 backup은 release와 연결한다. local dump는 R2 upload와 checksum 확인 뒤 임시 파일로만 취급한다.

## 11. Resource Policy

| Field | Type | Rule |
|---|---|---|
| `heavyBuildMaxConcurrency` | integer | 항상 1 |
| `demoPriority` | boolean | true |
| `ec2CapacityRefs` | string[] | vCPU/RAM/disk/OS late-bound 입력 |
| `demoServiceLimitRefs` | map | 실측 후 구성값 |
| `buildAgentLimitRefs` | map | demo reserve 침범 금지 |
| `admissionHealthRefs` | string[] | 새 build 전 demo health 확인 |

실제 숫자 없이도 contract와 test를 만들 수 있지만, 숫자 input이 없으면 preflight는 실제 deployment를 허용하지 않는다.

## 12. Cross-artifact Relationships

- Environment Manifest `releaseManifestRef` → infra-001 `release-manifest.schema.json`.
- Environment Manifest `deploymentTargetIds[*]` → infra-001 verification `targetId`.
- Runtime Service `releaseId` → infra-001 release provenance.
- PostgreSQL Binding → Backup Set N:1/1:N.
- Temporary Object Grant → Object Storage Provider N:1, Upload Verification 1:0..1. grant의 provider는 active write provider가 바뀌어도 고정된다.
- Redis Cache Class → PostgreSQL/R2 Source of Truth reference, Redis value 자체는 영구 entity가 아님.
- Usage Admission Snapshot과 Storage Failover State는 서로 변경하지 않는 별도 상태다.
- Storage Failover State는 active write provider만 바꾸며 기존 object provider metadata를 일괄 추정하지 않는다.
- Storage Failover State → Reconciliation Run 1:0..N, Reconciliation Run → Reconciliation Item 1:N.
