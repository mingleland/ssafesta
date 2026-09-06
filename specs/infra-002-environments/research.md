# Phase 0 Research: dev/demo 실행 환경

**Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

모든 기술 미정은 아래 결정으로 해소했다. C-01·C-02는 설계를 바꾸는 질문이 아니라 실제 배포 전에 입력할 자원·소유자 값이며 [quickstart](./quickstart.md)의 preflight가 누락을 차단한다.

## R-01. 단일 EC2 환경 격리

**Decision**: shared data project와 `festa-dev`·`festa-demo` runtime project를 분리한다. dev 내부에는 `ai`/`back`/`front`/`game` service target을 두고 대상 service만 `docker compose up -d --no-deps`로 갱신한다. network, volume, env file, container name, release state는 dev/demo별로 고유하게 둔다.

**Rationale**: PostgreSQL·Redis의 생명주기를 파트 배포에서 분리하면서 한 파트의 배포가 다른 파트와 demo를 재시작하지 않게 한다. 기존 infra-001 target lock과 component pipeline 계약을 그대로 사용할 수 있다.

**Alternatives considered**:

- 파트마다 완전한 stack 복제: 격리는 강하지만 단일 EC2 자원 소비와 운영 복잡도가 과도하다.
- dev/demo 한 Compose project: 전역 조작 실수가 demo로 전파되며 FR-002·FR-021을 약화한다.
- Kubernetes/ECS: 현재 IAM·추가 host·관리 권한 범위 밖이다.

## R-02. 빌드와 demo 자원 경합

**Decision**: 시연 중에도 build/deploy는 허용한다. high-load build는 서버 전체에서 최대 1개만 실행하고, demo service에는 실측 기반 cgroup CPU/memory limit·reservation을 적용한다. 새 high-load build는 demo health가 깨졌거나 semaphore를 얻지 못하면 queue한다. 같은 target 배포는 infra-001 lock/freshness 정책으로 직렬화한다.

**Rationale**: 사용자가 선택한 정상 운영 정책을 유지하면서 FR-023의 demo 우선 보호를 직접 검증할 수 있다. 고정 CPU/RAM 숫자를 추측하지 않고 실제 EC2 기준선 이후 한도만 채운다.

**Alternatives considered**:

- 시연 시간대 build/deploy 전면 동결: 단순하지만 팀의 정상 운영 선택과 맞지 않는다.
- 무제한 병렬 build: 단일 EC2에서 demo 가용성을 증명할 수 없다.
- 임의의 고정 resource 수치: FR-022와 헌법 30조를 위반한다.

## R-03. 공개 ingress·TLS·CDN

**Decision**: Nginx만 80/443을 공개한다. dev front·api·ai는 EC2 IP의 제한된 Nginx 경로를 사용하고, URL path를 지원하지 않는 UnityTransport는 `world-dev.${ROOT_DOMAIN}` 전용 WSS host를 사용한다. demo는 `demo`/`api`/`ai`/`world.${ROOT_DOMAIN}` host를 사용한다. 최종 경로는 Cloudflare DNS/Proxy → Nginx이며 origin mode는 Full (strict)다. content-hash 정적 asset만 장기 cache하고 HTML은 재검증, API·auth·SSE·upload grant·WebSocket은 bypass/no-store한다.

**Rationale**: 헌법 6·7조의 단일 EC2 경계를 구현하고 ALB/NLB 없이 TLS와 static offload를 제공한다. host별 cache 정책이 동적 응답 혼입을 막는다.

**Alternatives considered**:

- service port 직접 공개: FR-008과 내부 경계를 위반한다.
- ALB/NLB/ACM: IAM과 AWS API 권한이 없고 헌법에서 현재 경로 제외가 확정됐다.
- 모든 응답 cache: 인증·SSE·game 연결과 사용자별 응답이 유출될 수 있다.

## R-04. R2 bucket·credential 경계

**Decision**: R2 Standard private bucket 두 개를 사용한다. 하나는 AI 원본 문서, 다른 하나는 PostgreSQL backup이다. public access와 `r2.dev`/custom public domain을 끄고 bucket별 credential을 signer/read/backup/restore 역할로 분리한다. backup bucket에는 browser CORS를 두지 않는다.

**Rationale**: R2 token은 bucket 범위로 제한할 수 있어 문서와 backup을 prefix만으로 나누는 것보다 피해 범위가 작다. Standard에만 무료 tier가 적용된다. [R2 API token](https://developers.cloudflare.com/r2/api/tokens/), [R2 pricing](https://developers.cloudflare.com/r2/pricing/)

**Alternatives considered**:

- 한 bucket의 prefix 분리: credential 최소 권한 경계가 약하다.
- R2 Infrequent Access: 무료 tier 대상이 아니어서 무과금 목표와 맞지 않는다.
- AWS S3: 현재 IAM/AWS API 권한 범위 밖이다.

## R-05. Presigned browser upload와 실제 파일 검증

**Decision**: Spring이 권한을 검증하고 server-generated document ID와 unique object key를 정한 뒤 S3 API domain의 단일 객체 PUT presigned URL을 짧게 발급한다. `Content-Type`을 서명하고 허용된 browser origin·PUT·필요 header만 CORS에 등록한다. 완료 후 server-side HEAD로 존재·크기·declared type을 확인하고, 제한된 GET/stream으로 magic bytes와 SHA-256까지 검증한 뒤에만 processing을 시작한다. 완료 처리는 idempotent하다.

**Rationale**: R2 presigned URL은 GET/PUT/HEAD/DELETE와 1초~7일 expiry를 지원하지만 bearer token이며 만료 전 재사용될 수 있다. custom domain에서는 사용할 수 없고 browser 사용에는 CORS가 필요하다. HEAD의 Content-Type과 ETag만으로 실제 파일 형식·SHA-256을 증명할 수 없으므로 body 검사가 필요하다. [R2 presigned URLs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/), [R2 CORS](https://developers.cloudflare.com/r2/buckets/cors/)

**Alternatives considered**:

- application server를 통한 upload proxy: EC2 network/CPU 부하 절감 목적을 훼손한다.
- client가 object key 결정: tenant/booth/agent 경계 우회 가능성이 있다.
- HEAD metadata만 검증: client가 주장한 MIME을 실제 형식으로 오인할 수 있다.
- ETag를 SHA-256으로 사용: multipart 등에서 파일 SHA-256과 같다고 보장할 수 없다.

## R-06. R2 무료 한도 guardrail

**Decision**: Cloudflare Analytics를 15분마다 수집해 account 전체 Standard storage와 월 누적 Class A/B operation을 계산한다. 한도는 확인일과 함께 configuration으로 관리한다. 2026-08-24 공식 기본값은 storage 10 GB-month, Class A 1,000,000/month, Class B 10,000,000/month다. 현재 storage bytes 비율과 일별 peak 기반 월말 예상 GB-month 비율 중 큰 값, 그리고 Class A/B 비율 중 최댓값으로 판정한다. 80%에서 warning, 90%에서 신규 upload grant 차단, snapshot이 60분 넘게 stale이면 fail-closed하며 기존 read는 유지한다.

**Rationale**: `r2OperationsAdaptiveGroups`와 `r2StorageAdaptiveGroups`가 operation/storage 지표를 제공하고 31일 보존한다. storage 과금은 일별 peak의 월평균 GB-month이므로 현재 byte만 청구 사용량으로 부를 수 없다. stale 값을 0으로 취급하면 과금을 예방할 수 없다. [R2 metrics and analytics](https://developers.cloudflare.com/r2/platform/metrics-analytics/), [R2 pricing](https://developers.cloudflare.com/r2/pricing/)

**Alternatives considered**:

- application 자체 request counter만 사용: console/API 외 업로드와 계정 공유 사용량을 누락한다.
- 90%에서 read까지 차단: FR-025의 기존 문서 조회 유지에 어긋난다.
- 가격/한도를 code constant로 고정: 공급자 정책 변경을 흡수하지 못한다.

## R-07. S3-compatible 수동 emergency fallback

**Decision**: 단일 node/single drive MinIO를 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE` 상태로 수동 운영한다. validation은 disk·credential·PUT·HEAD·CORS·외부 port 차단을 확인한다. local object에는 provider를 기록하고 R2 복귀 시 같은 key의 size/type/SHA-256을 검증한 뒤 metadata를 전환한다. 9000/9001은 host public port로 publish하지 않는다.

**Rationale**: S3-compatible adapter를 유지하면서 R2 장애 중 제한적 신규 업로드만 복구할 수 있다. MinIO 공식 single-node/single-drive 안내도 이를 개발·평가 또는 availability 요구가 낮은 용도로 설명하므로 같은 EC2의 backup이나 durability 수단으로 간주할 수 없다. [MinIO container deployment](https://min.io/docs/minio/container/index.html)

**Alternatives considered**:

- 자동 failover: split-brain·누락 객체 위험이 있고 FR-017을 위반한다.
- local directory 직접 저장: S3-compatible 계약을 깨뜨린다.
- MinIO를 상시 backup으로 간주: EC2와 장애 영역이 같아 FR-018을 충족하지 못한다.

## R-08. PostgreSQL database/role 격리와 backup

**Decision**: 한 PostgreSQL 17 instance에 dev/demo별 Spring business DB와 AI pgvector DB, 총 4개 database와 전용 login role을 둔다. `PUBLIC CONNECT`를 회수하고 각 role은 자기 DB에만 CONNECT·schema 권한을 가진다. pgvector extension은 관리자 초기화 단계에서 AI DB에만 설치한다. PostgreSQL dump는 별도 R2 private backup bucket에 daily 7·weekly 4·migration 전 manual tier로 보존하고 restore rehearsal 후 유효 backup으로 기록한다.

**Rationale**: 헌법 17조의 DB+service account 분리와 FR-021의 환경 격리를 단일 instance 제약 안에서 만족한다. 같은 EC2의 local copy만으로는 host/disk 유실을 복구하지 못한다.

**Alternatives considered**:

- schema만 분리: database CONNECT 경계와 credential 피해 범위가 약하다.
- DB instance 4개: 단일 EC2에서 불필요한 memory/운영 비용이 든다.
- local backup만 보관: FR-018의 외부 장애 영역 요구를 충족하지 못한다.
- 원본 문서까지 PostgreSQL backup에 복제: spec에서 2차 문서 backup 제외가 확정됐다.

## R-09. Redis 임시 데이터 경계

**Decision**: Redis 7.2 instance 하나의 default user를 끄고 dev/demo·back/ai별 ACL user와 key pattern을 둔다. runtime user에는 관리·전체 삭제·전체 탐색 명령을 허용하지 않는다. key는 environment prefix와 tenant scope를 포함하며 모두 TTL 또는 명시적 invalidation 규칙을 가진다. RAG cache key에는 `boothId + agentId + sourceRevision + queryHash`, survey cache에는 `surveyId + sourceRevision`을 포함한다. eviction은 `noeviction`을 기본으로 하고 namespace budget/usage warning을 두며 cache write 실패는 source 조회로 저하한다. AOF는 재시작 편의를 위한 선택일 뿐 정확성 근거가 아니다.

**Rationale**: Redis ACL은 command와 key pattern을 제한할 수 있고 Redis 7은 read/write key pattern을 구분한다. 영구 원본을 PostgreSQL/R2에 두면 Redis 전체 유실이 정확성 손실이 아니라 재로그인·cache miss로만 나타난다. [Redis ACL](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/)

**Alternatives considered**:

- RAG 문서/chunk/vector 또는 survey response를 Redis에 저장: FR-033과 Source of Truth를 위반한다.
- Redis logical DB 번호로 환경 격리: ACL key 경계와 잘못된 FLUSH 방지가 약하다.
- `allkeys-lru`: cache가 session과 경쟁해 인증 상태를 예측 불가능하게 축출할 수 있다.
- survey cache를 즉시 필수화: spec 010은 MVP 원본 실시간 집계를 우선하므로 실측 후 선택 기능이 적합하다.

## R-10. 기존 feature와의 계약 경계

**Decision**: infra-001의 [component pipeline contract](../infra-001-ci-cd-pipelines/contracts/component-pipeline-contract.md), [release manifest](../infra-001-ci-cd-pipelines/contracts/release-manifest.schema.json), [verification result](../infra-001-ci-cd-pipelines/contracts/verification-result.schema.json)을 복제하지 않고 참조한다. `EnvironmentManifest.deploymentTargetId`는 verification `targetId`와 같아야 한다. WSS heartbeat/idle timeout과 browser 실측은 infra-003가 소유하며 이 feature는 hostname/TLS/ingress upstream contract만 제공한다.

**Rationale**: Jenkins·rollback·release state를 재구현하지 않고 feature 소유권과 헌법 24·27조를 지킨다.

**Alternatives considered**:

- infra-002 전용 release schema: 같은 개념의 두 schema가 drift한다.
- WSS timeout을 여기서 확정: infra-003 실측 책임과 충돌한다.

## R-11. 기존 운영 문서의 stale 결정

**Decision**: 이 feature의 canonical source는 `spec.md`, Constitution v2.0.0과 본 설계 산출물이다. `docs/15_Infra_AWS_설계서.md`에 남은 pgvector schema-only 분리, 한 bucket prefix 분리, R2 원본 2차 backup TBD, 시연 시간대 build/deploy 동결 표현은 구현 작업에서 최신 결정으로 정합화한다.

**Rationale**: 오래된 운영 설명이 plan과 다른 topology·backup 보장을 약속하면 acceptance와 장애 대응이 서로 달라진다. `docs/26_팀_결정_필요사항.md`의 ALB/NLB와 pgvector 항목은 이번 계획에서 최신 결정으로 갱신했다.

**Alternatives considered**:

- stale 문서를 그대로 두고 plan만 사용: 운영자가 잘못된 backup/배포 절차를 선택할 수 있다.
- Unity 인수인계 문서의 WSS 수치까지 함께 확정: infra-003 실측 소유권을 침범한다.

## Resolved Inputs and Deferred Values

| 항목 | 처리 |
|---|---|
| C-01 EC2 사양·SG 담당자 | `EC2_VCPU`, `EC2_RAM_MB`, `EC2_DISK_GB`, `EC2_OS`, `SG_CHANGE_OWNER`, `SG_80_443_READY` late-bound input. 누락 시 resource/외부 network 단계 fail. |
| C-02 실제 domain | `ROOT_DOMAIN`, domain owner late-bound input. host template은 고정하고 누락 시 DNS/TLS 단계 fail. |
| C-07 시연 정책 | 해소: 시연 중 build/deploy 허용, high-load build 최대 1, demo cgroup 우선 보호. 숫자만 C-01 실측 후 입력. |
| R2 무료 한도 | 공급자 변경 가능 설정. 현재 공식 값과 확인일을 기록하고 월별/가격 변경 시 재확인. |
| WSS timeout | infra-003 외부 실측으로 이관. |

미해결 clarification은 남아 있지 않다.
