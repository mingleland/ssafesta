# Quickstart: dev/demo 환경 검증

이 문서는 `infra/environments/` 구현 후 실행하는 acceptance guide다. production secret이나 presigned URL을 command history/report에 남기지 않는다. 실제 값은 승인된 secret injection 경계에서 제공한다.

## 1. Prerequisites

- Ubuntu 단일 EC2 SSH access, Docker Engine, Compose v2, `curl`, `openssl`, `jq`, `psql`, `redis-cli`.
- 외부 검증 host의 port scanner.
- Cloudflare team account의 DNS/R2 설정 권한과 scoped API token reference.
- infra-001 형식의 release manifest와 verification validator.
- 폐기 가능한 test document/DB와 demo smoke account.

필수 late-bound 변수:

```text
EC2_VCPU
EC2_RAM_MB
EC2_DISK_GB
EC2_OS
EC2_PUBLIC_IP
SG_CHANGE_OWNER
SG_80_443_READY
ROOT_DOMAIN
CLOUDFLARE_ACCOUNT_ID_REF
CLOUDFLARE_ANALYTICS_TOKEN_REF
R2_DOCUMENT_SIGNER_CREDENTIAL_REF
R2_DOCUMENT_READER_CREDENTIAL_REF
MINIO_DOCUMENT_SIGNER_CREDENTIAL_REF
MINIO_DOCUMENT_READER_CREDENTIAL_REF
STORAGE_FAILOVER_STATE_REF
R2_BACKUP_WRITER_CREDENTIAL_REF
R2_RESTORE_READER_CREDENTIAL_REF
POSTGRES_ADMIN_CREDENTIAL_REF
REDIS_ADMIN_CREDENTIAL_REF
TLS_PRIVATE_KEY_REF
```

## 2. Static contract validation

```bash
./infra/environments/scripts/preflight.sh \
  --manifest infra/environments/config/manifests/dev.json \
  --check-only

./infra/environments/tests/contract/run.sh

docker compose \
  -f infra/environments/compose/data/compose.yaml \
  -f infra/environments/compose/demo/compose.yaml \
  config --quiet
```

Expected:

- 두 JSON schema와 dev/demo manifest가 유효하다.
- infra-001 release/verification reference가 존재하고 target ID가 일치한다.
- rendered Compose/log-safe report에 Secret 원문이 없다.
- PostgreSQL/Redis/Unity/Jenkins/MinIO internal port에 public host binding이 없다.

필수 변수 하나씩 제거해 preflight를 다시 실행한다. 누락된 변수명이 표시되고 관련 실환경 단계가 시작되지 않아야 한다. `ROOT_DOMAIN` 누락은 local/dev contract test까지 막지 않지만 DNS/TLS deployment는 막아야 한다.

## 3. Shared data plane

```bash
docker compose \
  -f infra/environments/compose/data/compose.yaml \
  --project-name festa-data up -d

./infra/environments/tests/integration/postgres-isolation.sh
./infra/environments/tests/integration/redis-acl.sh
```

Expected:

- `dev/demo × business/ai` 네 database-role 조합에서 자기 DB CONNECT만 성공한다.
- AI DB에만 `vector` extension이 있고 runtime role은 extension/admin 권한이 없다.
- Redis anonymous/default access, cross-env key access와 `FLUSHALL`/`FLUSHDB`/`CONFIG`/`ACL`/`KEYS`가 거부된다.
- 5432/6379는 EC2 외부에서 접근되지 않는다.

## 4. Dev component isolation

infra-001 component adapter로 한 component의 새 full SHA만 dev에 배포한다.

```bash
./infra/environments/scripts/deploy-environment.sh \
  --environment dev \
  --component back \
  --release-manifest "$RELEASE_MANIFEST_PATH"

./infra/environments/scripts/verify-environment.sh \
  --environment dev \
  --component back
```

Expected:

- back image/release ID만 변경된다.
- dev의 ai/front/game과 모든 demo container restart count는 0이다.
- data project의 PostgreSQL/Redis가 재생성되지 않는다.
- 승인된 Mock dependency를 쓸 경우 manifest/verification evidence에 Mock임이 표시된다.
- 어느 adapter도 전체 environment에 `docker compose down`을 실행하지 않는다.

## 5. Demo integration and provenance

```bash
./infra/environments/scripts/deploy-environment.sh \
  --environment demo \
  --release-manifest "$RELEASE_MANIFEST_PATH"

./infra/environments/scripts/verify-environment.sh \
  --environment demo \
  --journey web,login,world,ai,document
```

Expected:

- web/login/AI health/document path가 동일 infra-001 release ID와 verification result에 연결된다.
- 비AI 검증 실패는 infra-001 rollback/manual 규칙을 따르고 AI-only 장애는 정상 비AI 경로를 불필요하게 중단하지 않는다.
- current/known-good pointer는 infra-001이 소유하고 infra-002가 별도 release state를 만들지 않는다.

## 6. Resource contention

```bash
./infra/environments/tests/resource/demo-with-heavy-build.sh \
  --heavy-build-requests 2 \
  --sample-interval 5
```

Expected:

- high-load build 실행 수는 모든 시점에 1 이하이고 두 번째 요청은 queue된다.
- build 중 demo health와 핵심 user journey가 성공하고 demo restart count는 0이다.
- `docker inspect`에서 demo와 build agent의 실측 기반 CPU/memory limit가 적용됐다.
- latency/resource sample은 기준선 증거로 기록하되 임의 숫자를 pass/fail 기준으로 만들지 않는다.

시연 중 build/deploy는 허용한다. 새 build admission 시 demo health가 실패하면 작업을 시작하지 않고 queue한다.

## 7. Network, DNS, TLS and cache

외부 검증 host에서 다음을 실행한다.

```bash
nmap -Pn -p 22,80,443,5432,6379,7777,8080,9000,9001 "$EC2_PUBLIC_IP"

curl -fsS "https://demo.${ROOT_DOMAIN}/health"
curl -fsS "https://api.${ROOT_DOMAIN}/health"
curl -fsS -N "https://ai.${ROOT_DOMAIN}/health"
openssl s_client -connect "demo.${ROOT_DOMAIN}:443" -servername "demo.${ROOT_DOMAIN}"
```

Expected:

- 승인된 source의 22와 public 80/443 외 internal/data/management port 연결 성공 0건.
- Security Group과 UFW 결과를 각각 기록해 차단 계층을 구분할 수 있다.
- demo hosts의 DNS와 certificate chain/expiry가 유효하고 Cloudflare-origin Full (strict)이 동작한다.
- content-hash static asset 두 번째 요청은 origin 도달이 감소한다.
- HTML은 새 release를 재검증하고 API/auth/SSE/upload grant/WebSocket 응답은 MISS가 아니라 명시적 BYPASS/no-store다.

Origin 검증:

```bash
curl --resolve "demo.${ROOT_DOMAIN}:443:${EC2_PUBLIC_IP}" \
  "https://demo.${ROOT_DOMAIN}/health"
```

인증서 검증을 끄는 `-k`를 사용하지 않는다. 이 결과는 운영자 검증 evidence이며 일반 사용자 fallback URL이 아니다.

## 8. Presigned upload and verification

```bash
./infra/environments/tests/integration/object-upload.sh --case valid
./infra/environments/tests/integration/object-upload.sh --case wrong-content-type
./infra/environments/tests/integration/object-upload.sh --case expired
./infra/environments/tests/integration/object-upload.sh --case wrong-object
./infra/environments/tests/integration/object-upload.sh --case size-mismatch
./infra/environments/tests/integration/object-upload.sh --case spoofed-mime
./infra/environments/tests/integration/object-upload.sh --case duplicate-completion
```

Expected:

- authorized user와 approved origin의 정확한 PUT만 성공한다.
- CORS wildcard가 없고 unapproved origin preflight/PUT이 실패한다.
- signed Content-Type 변조, expiry, 다른 object key, size mismatch, magic-byte/SHA mismatch는 processing을 시작하지 않는다.
- 성공 case는 HEAD evidence가 body type/SHA evidence보다 먼저 있고 그 뒤에만 AI processing이 한 번 시작된다.
- duplicate completion은 같은 결과를 반환하고 job을 추가하지 않는다.
- presigned URL 원문이 app/proxy/CI log와 artifact에서 검출되지 않는다.

## 9. R2 usage guard

```bash
./infra/environments/tests/failure/r2-usage-guard.sh \
  --ratios 0.79,0.80,0.90 \
  --include-storage-class-a-class-b

./infra/environments/tests/failure/r2-usage-guard.sh --stale-minutes 61
```

Expected:

| Input | State | New upload | Existing read |
|---|---|---|---|
| max 79% | `NORMAL` | allow | allow |
| max 80% | `WARNING` | allow + alert | allow |
| max 90% | `UPLOAD_BLOCKED` | deny | allow |
| 61m stale | `STALE_BLOCKED` | deny | allow |

각 storage/current+projected, Class A, Class B 지표가 단독으로 임계값을 넘는 case와 다른 project bucket을 포함한 account 합계를 검증한다. snapshot은 [usage schema](./contracts/usage-guard.schema.json)에 맞아야 하며 active provider 필드를 포함해서는 안 된다.

## 10. R2 outage, MinIO fallback and reconciliation

```bash
./infra/environments/tests/failure/storage-fallback.sh --inject-r2-outage
```

Expected sequence:

1. `R2_ACTIVE → UPLOAD_BLOCKED`; 자동 provider 전환 없음.
2. 운영자 승인 없이 `FALLBACK_VALIDATING` 또는 `LOCAL_ACTIVE` 진입 거부.
3. disk/credential/PUT/HEAD/CORS/public-port probe 통과 후에만 `LOCAL_ACTIVE`.
4. `LOCAL_ACTIVE`의 Spring 신규 grant만 `MINIO_LOCAL`을 사용하고, 기존 R2 문서와 FastAPI Job은 문서별 provider를 사용한다.
5. MinIO 9000/9001 외부 접근 실패, approved HTTPS upload만 성공.
6. R2 복귀 후 `R2_RECONCILING`에서 신규 grant가 차단되고 고정 backlog만 검사된다.
7. object size/detected type/SHA 불일치가 있으면 item은 `UNRESOLVED`, state는 `R2_RECONCILING`을 유지한다.
8. 전량 검증과 운영자 승인 뒤 `VERIFIED` 객체의 metadata provider만 R2로 바뀌고 `R2_ACTIVE`.

control state는 [storage failover schema](./contracts/storage-failover-state.schema.json)에 맞아야 한다. `LOCAL_ACTIVE` object가 EC2 유실 시 복구되지 않는다는 warning, backlog count, run/item별 검증 결과와 미해결 사유를 Secret 없는 evidence에 남긴다. MinIO를 backup으로 보고하지 않는다.

## 11. PostgreSQL backup and restore

```bash
./infra/environments/postgres/backup/dump.sh --environment demo --tier manual-test
./infra/environments/postgres/backup/restore.sh --target disposable-demo-restore
./infra/environments/postgres/backup/verify.sh --target disposable-demo-restore
```

Expected:

- dump/manifest/checksum은 document bucket이 아닌 private backup bucket에 있다.
- empty/disposable PostgreSQL에 R2 backup만으로 schema·row·vector metadata가 복구된다.
- document metadata의 object key가 R2 document inventory와 100% 대응한다.
- 원본 document body는 backup set에 복제되지 않았고 R2 account/data loss 시 복구 불가 제한이 report에 있다.
- daily 7, weekly 4와 pre-migration retention 분류가 검증된다.

## 12. Redis total-loss recovery

test environment와 운영자 전용 credential에서만 실행한다.

```bash
./infra/environments/tests/failure/redis-total-loss.sh --environment dev
```

Expected:

- session/presence/lock/cache는 사라질 수 있고 사용자는 재로그인한다.
- RAG original/metadata/chunk/vector와 survey/question/response 영구 손실은 0건이다.
- cache miss 후 재생성된 RAG 결과와 survey aggregate가 PostgreSQL/R2 현재 source와 100% 일치한다.
- document revision/survey response 변경 뒤 이전 revision cache가 선택되지 않는다.
- Redis 장애가 로그인 이외의 영구 회원/ledger data와 비AI world service를 손상하지 않는다.

## 13. Failure isolation and secret scan

```bash
./infra/environments/tests/failure/isolation.sh --dependency ai
./infra/environments/tests/failure/isolation.sh --dependency r2
./infra/environments/tests/security/secret-scan.sh
```

Expected:

- AI/R2 장애 중 로그인·부스·월드와 CI/CD는 정상이고 전체 environment를 정상으로 오판하지 않는다.
- repository, rendered Compose, app/Nginx/CI logs, release/verification evidence, metrics labels에서 credential, token, presigned URL, TLS private key 원문 검출 0건.
- cross-env network/DB/Redis/Secret 접근이 거부된다.

## 14. Completion evidence

각 scenario는 실행 시각, environment/release ID, sanitized command, result, evidence path를 infra-001 verification result 또는 연결된 report에 남긴다. 다음은 완료가 아니다.

- C-01/C-02 없이 DNS/TLS/resource limit를 임의값으로 통과 처리.
- local PostgreSQL copy만 만들어 backup 성공 처리.
- HEAD Content-Type만 보고 실제 file format 검증 처리.
- Redis AOF가 있다는 이유로 Redis를 Source of Truth로 처리.
- MinIO가 실행 중이라는 이유로 R2 backup이 생겼다고 처리.
- 내부 port를 일시 공개한 상태로 acceptance 완료.
