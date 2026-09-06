# Implementation Plan: dev/demo 실행 환경

**Branch**: `infra-002-environments` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

**Input**: Confirmed feature specification from `specs/infra-002-environments/spec.md`

## Summary

IAM Role과 AWS Console/API 권한이 없는 단일 EC2에 Docker Compose 기반 dev/demo 실행 환경을 구성한다. Nginx만 80/443 공개 진입점으로 두고 최종 demo는 `Cloudflare DNS/Proxy → EC2 Nginx → 내부 서비스` 경로를 사용한다. 파트별 dev 배포와 `develop` 통합 demo는 network·설정·데이터·release state를 분리하고, 기존 `infra-001`의 release/verification/rollback 계약을 그대로 소비한다.

한 PostgreSQL 인스턴스 안에서 환경별 Spring DB와 AI pgvector DB를 별도 database/role로 분리한다. Redis는 환경·서비스별 ACL과 key prefix를 적용한 단일 임시 저장소로 사용하며 영구 원본을 저장하지 않는다. AI 원본 문서는 비공개 Cloudflare R2 Standard bucket에 저장하고 PostgreSQL dump는 별도 private backup bucket에 보관한다. R2 장애 시 자동 failover 없이 업로드를 차단한 뒤 운영자가 검증한 단일 노드 MinIO로만 수동 전환한다.

## Technical Context

**Language/Version**: Docker Compose v2 YAML, Nginx configuration, Bash, SQL, JSON Schema Draft 2020-12; 애플리케이션 버전은 기존 파트 산출물을 그대로 소비

**Primary Dependencies**: Ubuntu Linux, Docker Engine/Compose v2, Nginx, PostgreSQL 17 + pgvector, Redis 7.2 ACL, Cloudflare DNS/Proxy·R2 Standard·GraphQL Analytics API, S3 SigV4 SDK, 비상 시 단일 노드 MinIO

**Storage**: 단일 PostgreSQL 인스턴스의 `dev_app`/`dev_ai`/`demo_app`/`demo_ai` database와 전용 role, 환경별 named volume; 단일 Redis의 환경·서비스별 ACL/keyspace; R2 private document bucket와 private PostgreSQL backup bucket; R2 원본 문서의 별도 2차 백업은 없음

**Testing**: Compose config/health 검증, JSON Schema validation, `curl`/`openssl`/외부 port scan, `psql` 권한 시험, `redis-cli` ACL·유실 시험, R2 presigned PUT/HEAD·CORS 시험, backup/restore rehearsal, 장애 주입과 demo 사용자 여정 smoke

**Target Platform**: SSH로 관리하는 Ubuntu 단일 EC2; 외부 공개 포트 22/80/443; 브라우저 React·Unity WebGL, 내부 Docker 서비스 Spring/FastAPI/Unity Dedicated Server

**Project Type**: Infrastructure configuration + environment orchestration + operational contracts/runbooks

**Performance Goals**: 고부하 build를 서버 전체 최대 1개로 직렬화하고 build와 demo 동시 시험에서 demo health·핵심 사용자 여정 성공과 demo container restart 0건을 유지한다. 수치형 latency/throughput은 EC2 기준선 측정 결과로 기록하며 계획 단계에서 임의 확정하지 않는다.

**Constraints**: AWS IAM/Console/API와 ALB·NLB·ACM·RDS·ElastiCache·ECS·ECR·S3를 요구하지 않는다. 실제 `EC2_VCPU`, `EC2_RAM_MB`, `EC2_DISK_GB`, `EC2_OS`, `EC2_PUBLIC_IP`, `SG_CHANGE_OWNER`, `SG_80_443_READY`, `ROOT_DOMAIN`과 Secret Reference가 없으면 관련 실환경 단계는 preflight에서 실패해야 한다. R2 무료 한도 기준은 설정값이며 가격 변경 전후에 재검증한다.
**Scale/Scope**: `ai`/`back`/`front`/`game` 네 dev 배포 target, 한 개의 develop 통합 demo, 한 EC2·한 PostgreSQL instance·한 Redis instance, R2 bucket 2개, Nginx ingress 1개. 고가용성·다중 host·관리형 AWS 이관은 제외한다.

운영 입력 C-01·C-02는 설계 미정이 아니다. 값이 없으면 plan 생성은 가능하지만 실제 자원 한도 적용, Security Group 확인, DNS/TLS 적용 단계만 차단한다. C-07은 “시연 중 정상 운영 + 고부하 build 동시 1개 + 실측 cgroup 한도로 demo 우선 보호”로 해소됐다.

## Constitution Check

*GATE: Phase 0 전 평가하고 Phase 1 설계 후 재평가했다.*

| 헌법 게이트 | 설계 적용 | 결과 |
|---|---|:---:|
| 1·17조 Source of Truth·Vector 격리 | Spring 영구 비즈니스 DB와 AI pgvector DB를 환경별 별도 database/role로 분리한다. Redis에는 재생성 가능한 값만 두며 RAG는 `boothId + agentId` 필터를 유지한다. | PASS |
| 2조 실시간/영구 분리 | Unity는 실시간 월드 상태만 담당하고 영구 상태는 PostgreSQL을 대체하지 않는다. | PASS |
| 3조 AI 장애 격리 | AI·R2 장애 시 문서 기능만 제한하고 로그인·부스·월드와 CI/CD는 유지한다. | PASS |
| 6조 WebSocket 경계 | `Cloudflare → Nginx:443 → ws://unity:7777` 경계만 정의하며 7777을 공개하지 않는다. ALB·NLB·ACM은 사용하지 않고 최종 timeout은 infra-003에 위임한다. | PASS |
| 7조 Docker·단일 EC2 | 서버 컴포넌트는 Docker image로 실행하고 관리형 AWS나 IAM을 선행조건으로 두지 않는다. | PASS |
| 8조 endpoint 주입 | `demo`/`api`/`ai`/`world.${ROOT_DOMAIN}`, dev HTTP IP 경로와 `world-dev.${ROOT_DOMAIN}`은 manifest·환경 설정·world-sessions 응답으로 주입한다. | PASS |
| 10조 파트별 CI/CD | dev target은 서비스 단위로 배포하고 전역 `compose down`을 금지한다. demo는 infra-001 release manifest를 소비한다. | PASS |
| 13·15조 인증·Secret | Redis/R2/TLS credential과 presigned URL은 Secret Reference 또는 휘발성 응답으로만 다루며 로그·artifact에 남기지 않는다. | PASS |
| 19·24조 SSE·계약 변경 | API·SSE·인증·upload grant·game 연결은 CDN cache bypass하며 기존 API payload와 infra-001 schema를 변경하지 않는다. | PASS |
| 27조 기준선 동결 | `festa-unity/Docker/`와 동결 runtime을 수정하지 않고 신규 환경 구성은 `infra/environments/`에 둔다. | PASS |
| 29·30조 기록·미정 관리 | C-01·C-02는 preflight 입력으로 남기고 임의 기본값을 만들지 않으며 INFRA 작업 기록을 갱신한다. | PASS |

**Post-design re-check**: [data-model.md](./data-model.md), [contracts/](./contracts/)와 [quickstart.md](./quickstart.md)를 기준으로 재검토한 결과 헌법 위반과 미해결 clarification은 없다. 단일 EC2와 단일 노드 MinIO는 고가용성 또는 백업으로 표현하지 않으며, R2 원본 문서의 2차 백업 부재를 명시한다.

## Architecture and Delivery Design

### Environment topology and isolation

- `festa-dev`와 `festa-demo` Compose project, internal network, named volume, environment file, release state를 분리한다. dev 안에서는 `dev-ai`/`dev-back`/`dev-front`/`dev-game` target이 각자의 service만 `--no-deps`로 갱신한다.
- 공유 data project가 PostgreSQL·Redis를 실행하되 애플리케이션은 환경별 credential과 database/key prefix로만 접근한다. host에는 5432·6379를 publish하지 않는다.
- demo는 `develop` release manifest에 고정된 image ref를 소비한다. 배포·검증·rollback·freshness/target lock은 [infra-001 계약](../infra-001-ci-cd-pipelines/contracts/component-pipeline-contract.md)을 재사용한다.
- 모든 Compose service에 healthcheck와 실측 기반 CPU/memory limit reference를 둔다. 시연 중에도 build/deploy를 허용하되 high-load build 전역 semaphore는 1이고 demo 검증이 실패하면 새 작업을 queue한다.

### Public ingress and static delivery

- Nginx만 host 80/443에 bind한다. dev front·api·ai는 `http://${EC2_PUBLIC_IP}/__dev/{front|api|ai}`의 승인된 제한 경로를 사용한다. URL path를 지원하지 않는 UnityTransport는 `wss://world-dev.${ROOT_DOMAIN}:443` 전용 host를 사용하며 내부 service port는 공개하지 않는다.
- 최종 demo host 계약은 `demo.${ROOT_DOMAIN}`, `api.${ROOT_DOMAIN}`, `ai.${ROOT_DOMAIN}`, `world.${ROOT_DOMAIN}`이다. Cloudflare proxy와 origin은 Full (strict) TLS를 사용한다.
- `demo`의 content-hash 정적 asset만 장기 immutable cache를 허용하고 HTML은 재검증 가능한 짧은 정책을 사용한다. API·인증·SSE·presigned URL·game WebSocket은 항상 bypass/no-store다.
- CDN 장애 점검은 운영자용 `curl --resolve` origin 경로로 수행한다. 인증서 검증을 끄지 않으며 일반 사용자용 별도 우회 host를 만들지 않는다.

### Persistent data, Redis and backup

- PostgreSQL instance 하나에 `dev_app`, `dev_ai`, `demo_app`, `demo_ai` database와 같은 이름 계열의 login role을 만들고 `CONNECT`·schema privilege를 자기 database에만 부여한다. AI database만 pgvector extension과 vector schema를 사용한다.
- Redis default user는 비활성화한다. 환경·서비스별 ACL user는 허용된 command category와 key/channel pattern만 사용하며 `FLUSHALL`, `FLUSHDB`, `CONFIG`, `ACL`, `KEYS` 같은 관리 명령을 받지 않는다.
- Redis key는 `{env}:auth:*`, `{env}:presence:*`, `{env}:lock:*`, `{env}:rag:{boothId}:{agentId}:*`, `{env}:survey:{surveyId}:*` 범위를 사용한다. auth/session을 포함한 모든 값은 유실 가능한 runtime 상태이고, RAG/설문 cache는 TTL과 source 변경 event 기반 무효화를 함께 가진다.
- PostgreSQL은 migration 전 수동 backup과 일일 7개·주간 4개 보존 정책으로 R2 private backup bucket에 dump한다. restore rehearsal이 통과한 객체만 유효 backup으로 기록한다. R2 document bucket의 원본은 이 backup에 복제하지 않는다.

### R2 usage guard and emergency fallback

- browser upload는 서버가 영구 metadata에서 결정한 unique object key에 대한 짧은 PUT presigned URL을 발급하고, 허용 origin/method/header만 CORS에 등록한다. 완료 callback 후 server-side HEAD로 존재·크기·declared content type을 확인하고 본문 magic bytes·SHA-256 검증까지 통과한 뒤 처리 상태를 진행한다.
- R2 Standard의 storage·Class A·Class B 한도는 configuration으로 관리한다. GraphQL Analytics를 15분마다 수집하고 storage current/projected ratio 및 월 누적 operation ratio 중 최댓값으로 판정한다. 80%는 warning, 90%는 신규 upload grant 차단이며 기존 GET은 유지한다.
- 마지막 정상 usage snapshot이 60분을 넘으면 신규 upload는 fail-closed한다. 지표 조회 실패를 0%로 취급하지 않는다.
- storage state는 `R2_ACTIVE → UPLOAD_BLOCKED → FALLBACK_VALIDATING → LOCAL_ACTIVE → R2_RECONCILING → R2_ACTIVE`로만 전이한다. 전환에는 운영자 승인과 S3 contract probe가 필요하며 자동 failover는 금지한다.
- 단일 노드 MinIO는 EC2 장애와 함께 유실될 수 있는 임시 가용성 수단이다. backup 또는 R2 복제본으로 계산하지 않으며 R2 복귀 후 object checksum/metadata를 대조해 명시적으로 reconcile한다.

## Project Structure

### Documentation (this feature)

```text
specs/infra-002-environments/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── environment-manifest.schema.json
│   ├── usage-guard.schema.json
│   ├── public-entry-contract.md
│   ├── object-storage-contract.md
│   ├── postgres-boundary-contract.md
│   └── redis-cache-boundary.md
└── tasks.md                         # 다음 $speckit-tasks 단계에서 생성
```

### Source Code (repository root)

```text
infra/
└── environments/
    ├── compose/
    │   ├── data/compose.yaml
    │   ├── dev/{base,ai,back,front,game}.yaml
    │   ├── demo/compose.yaml
    │   └── emergency/minio.yaml
    ├── config/
    │   ├── manifests/{dev,demo}.json
    │   ├── environments/{dev,demo}.env.example
    │   └── resource-limits.example.yaml
    ├── nginx/
    │   ├── nginx.conf
    │   ├── sites/{dev,demo}.conf
    │   └── snippets/{cache,dynamic-bypass,tls,security}.conf
    ├── postgres/
    │   ├── init/
    │   └── backup/{dump,restore,verify}.sh
    ├── redis/{redis.conf,users.acl.example}
    ├── storage/{r2,usage-guard,fallback}/
    ├── scripts/{preflight,deploy-environment,verify-environment}.sh
    ├── runbooks/{r2-fallback,postgres-restore,redis-recovery,origin-access}.md
    └── tests/{contract,integration,security,failure,resource}/
```

기존 `infra-001`이 소유할 `infra/deploy/`와 Jenkins pipeline은 재구현하지 않는다. 이 feature는 `infra/environments/`의 manifest·Compose overlay·data/ingress/storage 설정과 검증 adapter를 제공하고 infra-001 deploy가 이를 호출하게 한다.

**Structure Decision**: 환경별 차이를 `infra/environments/` 한 경계에 모으고 provider-independent manifest/contract와 Cloudflare/Redis/PostgreSQL adapter 설정을 분리한다. 동결된 Unity runtime과 기존 backend API payload에는 손대지 않는다.

## Phase Outputs and Implementation Order

1. **Preflight/contract**: environment manifest validator, late-bound resource/domain/SG/Secret Reference 검사, infra-001 schema 연결.
2. **Shared data plane**: PostgreSQL database/role/volume 분리, Redis ACL/key namespace/TTL, R2 document/backup bucket contract.
3. **dev/demo runtime**: Compose project/network/volume/release state 분리, dev component-only deployment, demo integration release.
4. **Ingress/static**: Nginx IP dev 경로, demo host/TLS, Cloudflare cache/bypass, origin verification.
5. **Object safety**: presigned PUT/CORS/HEAD/body 검증, usage snapshot/80·90/stale guard, manual MinIO transition/reconcile.
6. **Recovery/acceptance**: PostgreSQL backup/restore, Redis total-loss rebuild, port/secret/cross-env isolation, build/demo contention and failure isolation.
7. **Operational documentation**: `docs/15_Infra_AWS_설계서.md`의 stale schema/prefix/R2 2차 backup/시연 동결 표현을 최신 spec·constitution 결정으로 정합화하고, infra-003 소유 WSS 실측값은 변경하지 않는다.

상세 dependency와 병렬화 가능한 작업은 다음 `$speckit-tasks` 단계에서 생성한다.

## Complexity Tracking

헌법 위반 없음. 단일 EC2의 공유 PostgreSQL·Redis와 emergency MinIO는 비용·권한 제약에 따른 초기 topology이며 고가용성으로 간주하지 않는다.
