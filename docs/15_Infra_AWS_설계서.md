# SSAFY FESTA Infra / 단일 EC2 운영 설계서

> **목표**: 제공받은 단일 EC2에서 Jenkins, 파트별 dev, 통합 demo, 데이터 저장소를 논리적으로 격리해 운영하고 React/Unity Web, Spring Boot, FastAPI, Unity Dedicated Server를 실제 사용자 경로로 제공한다.
> **상태**: Current MVP Architecture — 현재 사용할 구조를 우선 기술하며, AWS 관리형 서비스는 IAM과 추가 자원이 확보된 뒤 검토할 후속 이관 대상으로만 구분한다.
> **근거 spec**: `specs/infra-001-ci-cd-pipelines/spec.md`, `specs/infra-002-environments/spec.md`, `docs/sdd/parts/INFRA.md`

---

## 1. 인프라 목표

1. 최종 demo를 도메인 기반 HTTPS/WSS로 제공한다.
2. React와 Unity Web 정적 릴리스를 버전별로 배포하고 Cloudflare 캐시로 원본 전송 부하를 줄인다.
3. `ai`/`back`/`front`/`game` dev와 `develop` demo가 서로의 컨테이너·네트워크·설정·배포 상태를 침범하지 않게 한다.
4. Spring Boot, FastAPI, Unity Dedicated Server를 재현 가능한 Docker 이미지로 실행한다.
5. PostgreSQL/pgvector 영구 데이터, Redis 임시 데이터, R2 비공개 원본 문서의 역할을 분리한다.
6. Jenkins 빌드와 서비스 런타임이 같은 EC2의 자원을 경쟁하더라도 demo 사용자 경로를 우선 보호한다.
7. 외부 백업과 복구 검증으로 EC2 또는 디스크 유실에 대비한다.
8. 로그·지표를 서비스별로 확인하고 Secret을 저장소·로그·산출물에서 분리한다.

단일 EC2는 고가용성이나 다중 호스트 수평 확장을 제공하지 않는다. 현재 구현하지 않은 ECS·RDS·ElastiCache·ECR·ALB·CloudFront를 사용 중이라고 설명하지 않는다.

---

## 2. Current Architecture

```text
사용자 브라우저
      │ HTTPS / WSS :443
      ▼
Cloudflare DNS / Proxy / CDN
      │ HTTPS / WSS :443, Full (strict)
      ▼
단일 EC2
├─ Nginx + Let's Encrypt
│  ├─ demo.<domain>  → React + Unity WebGL 정적 릴리스
│  ├─ api.<domain>   → Spring Boot
│  ├─ ai.<domain>    → FastAPI (SSE)
│  └─ world.<domain> → Unity Dedicated Server (내부 ws:7777)
│
├─ Jenkins
│  ├─ Controller — Executor 0
│  ├─ Build Agent
│  ├─ Deploy Agent
│  └─ Unity Agent — 영속 Library/라이선스
│
├─ dev 환경 — ai / back / front / game 논리 분리
├─ demo 환경 — develop 통합 릴리스
│  ├─ Spring Boot
│  ├─ FastAPI
│  └─ Unity Dedicated Server 1개 (11층·단일 채널)
│
├─ PostgreSQL + pgvector — 영구 볼륨
├─ Redis — 유실 가능한 임시 상태
├─ Docker 이미지 — commit SHA / current / known-good
└─ 정적 릴리스 — version / current / known-good

외부 서비스
├─ Cloudflare R2 — 비공개 AI 원본 문서, DB 백업
└─ 외부 LLM API — Embedding / 답변 생성
```

현재 모든 실행 컴포넌트는 한 EC2에 있으므로 물리 장애 영역은 분리되지 않는다. Docker Network·서비스 계정·볼륨·배포 단위를 통한 논리 격리와 외부 백업으로 위험을 줄인다.

---

## 3. Static Web

React와 Unity WebGL 산출물은 EC2의 버전별 정적 릴리스로 보관하고 Nginx가 Origin으로 제공한다.

```text
releases/
├─ <commit-sha>/
│  ├─ React build
│  └─ Unity WebGL build
├─ current     → 현재 릴리스
└─ known-good  → 마지막 정상 릴리스
```

Cloudflare는 `demo.<domain>`의 정적 파일만 CDN 캐시 대상으로 사용한다.

- HTML 진입 파일: 짧은 TTL 또는 `no-cache`로 새 릴리스 확인
- 파일명에 내용 hash가 있는 JS/CSS/WASM/Data: 긴 TTL과 `immutable`
- API·인증·SSE·WSS·Presigned URL: Cache Bypass
- 새 릴리스 검증 실패: `current`를 `known-good`으로 원자적 전환
- Cloudflare를 사용할 수 없는 경우: Nginx Origin 경로로 핵심 정적 콘텐츠 제공

React와 Unity WebGL은 demo 사용자 여정의 같은 릴리스 묶음으로 추적하되, 산출물별 캐시 정책은 분리한다.

---

## 4. Nginx / Public Entry

Nginx는 단일 EC2의 공개 진입점과 리버스 프록시를 담당한다.

```text
demo.<domain>  → versioned static release
api.<domain>   → Spring Boot
ai.<domain>    → FastAPI
world.<domain> → Unity Dedicated Server:7777
```

TLS 경계:

```text
Browser ── TLS ──> Cloudflare ── TLS Full (strict) ──> Nginx ── HTTP/WS ──> Container
```

- Nginx는 Let's Encrypt 인증서를 사용하고 자동 갱신 후 reload한다.
- Cloudflare SSL 모드는 `Full (strict)`로 설정한다.
- `world.<domain>`은 WebSocket Upgrade Header를 전달하고 내부 `ws://unity:7777`로 프록시한다.
- Unity 7777은 외부에 공개하지 않는다.
- WebSocket read timeout은 초기값을 두고 heartbeat·무입력 연결 실측 후 확정한다.
- AI SSE 경로는 `proxy_buffering off`, cache off, 충분한 read timeout을 적용한다.
- API·AI·World는 Cloudflare 정적 캐시 대상에서 제외한다.

최종 성공 여부는 설정 파일 존재가 아니라 외부 브라우저에서 WSS·SSE·Secure Cookie 경로가 실제 동작하는지로 판정한다.

---

## 5. Spring Boot 배포

Spring Boot는 Jenkins가 빌드·테스트한 뒤 commit SHA가 붙은 Docker 이미지로 패키징하고 Docker Compose로 배포한다.

```text
Gradle Build / Test
→ Docker Image:<commit-sha>
→ dev-back 또는 demo 배포
→ Health Check
→ current 승격 또는 known-good 복구
```

주요 런타임 설정:

- 환경별 PostgreSQL Database/Role
- Redis 내부 endpoint
- JWT Secret Reference
- R2 S3-compatible endpoint/bucket
- FastAPI 내부 endpoint
- CORS allowed origin (`demo.<domain>`)
- OAuth callback URL

Coin·Lease·Wallet·Reward 등 영구 상태의 Source of Truth는 PostgreSQL이다. Redis나 Unity 서버 상태만으로 영구 비즈니스 값을 변경하지 않는다.

---

## 6. FastAPI 배포

FastAPI도 Jenkins가 테스트한 Docker 이미지를 단일 EC2의 dev-ai 또는 demo 환경에 배포한다.

```text
Test
→ Docker Image:<commit-sha>
→ dev-ai 또는 demo 배포
→ /ai/health
→ SSE 실측
```

- 원본 문서는 비공개 R2에서 짧은 권한으로 읽는다.
- 문서 메타데이터와 처리 상태는 영구 PostgreSQL이 관리한다.
- Vector 데이터는 같은 PostgreSQL 인스턴스의 별도 pgvector 스키마/Role을 우선 사용한다.
- LLM/Embedding Provider Key는 승인된 Secret 경계에서 런타임에만 주입한다.
- FastAPI 또는 외부 AI 장애가 로그인·부스·월드 등 비AI 기능 전체로 전파되지 않게 한다.
- Document Worker와 Queue는 부하 실측 후 필요한 경우에만 분리한다.

---

## 7. Unity Dedicated Server 배포

현재 범위는 11층·단일 채널이며 Unity Dedicated Server 컨테이너 1개를 사용한다.

```text
Unity Linux Server Build
→ Docker Image:<commit-sha>
→ 단일 EC2 demo 환경
→ 내부 ws://unity:7777
→ Nginx / Cloudflare
→ 외부 wss://world.<domain>:443
```

- ECS Task, 층별 인스턴스, 자동 채널 분배는 현재 요구사항이 아니다.
- Spring `world-sessions` 응답이 `scheme=wss`, `host=world.<domain>`, `port=443`을 내려준다.
- Unity 클라이언트는 서버 주소를 하드코딩하지 않는다.
- 7777은 Docker 내부 Network에서만 접근한다.
- 외부 브라우저 2개 동시 접속, 상호 이동, 무입력 연결 유지, 종료 후 재접속을 검증한다.

단일 컨테이너 수용 목표는 한 채널 30명이며, 수용 가능 여부는 부하 실측으로 판정한다.

---

## 8. Unity Server 이미지

Dockerfile은 다음을 유지한다.

- Linux runtime dependency
- Server binary execute permission
- 비root 실행 사용자
- graceful shutdown signal
- stdout/stderr log
- commit SHA 이미지 식별

현재 구현된 실행 인자:

```text
-port 7777
-maxPlayers 40
```

향후 설정 후보:

```text
INSTANCE_ID
WORLD_ID
CHANNEL_ID
SPRING_INTERNAL_URL
```

향후 후보값은 실제 ENV 파싱이 구현되기 전까지 구현 완료로 기록하지 않는다. Unity Client WebGL 빌드와 Linux Server 이미지는 호환되는 commit으로 함께 추적한다.

---

## 9. PostgreSQL + pgvector

PostgreSQL은 단일 EC2의 Docker 컨테이너와 영구 볼륨으로 운영한다.

저장 대상:

- User
- Booth / Lease
- Layout
- Wallet / Coin Ledger
- Staff
- Survey
- Project
- Agent Config / Document Metadata
- AI Vector(pgvector 별도 스키마)

운영 원칙:

- Docker 영구 볼륨은 애플리케이션 재배포로부터 데이터를 보호한다.
- 볼륨은 EC2·디스크 손실에 대비한 백업이 아니다.
- dev와 demo는 Database/Schema/Role을 구분하고 최소 권한을 적용한다.
- PostgreSQL 5432를 외부에 공개하지 않고 Docker 내부 Network만 사용한다.
- 불가피한 관리 접근은 SSH Tunnel 또는 `127.0.0.1` 바인딩으로 제한한다.
- Migration 도구와 스키마 버전을 릴리스와 함께 추적한다.
- PostgreSQL/pgvector 이미지 버전을 고정한다.
- 정기 `pg_dump`를 EC2 밖의 비공개 R2에 업로드하고 복구를 실측한다.

단일 EC2 구조에는 RDS 자동 백업, Multi-AZ, 자동 장애조치, Point-in-Time Recovery가 없다.

---

## 10. Redis

Redis는 유실 가능한 임시 상태만 저장한다.

용도:

- Presence
- Staff Online
- Cache
- 일시 세션
- 임시 Lock

운영 원칙:

- Coin·Lease·Wallet·Reward·문서 상태 등 영구 기록을 Redis 단독으로 저장하지 않는다.
- Redis 재시작 시 presence·cache·일시 세션·lock이 사라질 수 있음을 정상 복구 시나리오로 문서화한다.
- 캐시는 PostgreSQL 또는 외부 Source of Truth에서 재구성할 수 있어야 한다.
- 보안·토큰 재사용 방지처럼 유실이 보안 사고로 이어지는 기록은 PostgreSQL에 둔다.
- Redis 6379는 외부에 공개하지 않고 필요한 내부 컨테이너만 접근한다.

---

## 11. Cloudflare R2 / S3-compatible Object Storage

R2의 우선 용도는 비공개 AI 원본 문서이며, PostgreSQL 외부 백업은 문서 저장소와 credential·CORS 경계를 분리한 별도 private bucket에 보관한다.

```text
R2 document bucket: documents/booths/{boothId}/agents/{agentId}/...
R2 backup bucket:   postgresql/{env}/{database}/{tier}/...
```

문서 업로드 흐름:

```text
Client → Spring에 업로드 권한 요청
Spring → Usage Admission과 active write provider 확인
Spring → grant에 provider·Object Key를 고정하고 짧은 Presigned URL 발급
Client → grant가 지정한 R2 또는 MinIO에 직접 업로드
Client → 업로드 완료 통지
Spring → grant에 고정된 provider에서 HEAD·본문 크기·감지 형식·SHA-256 검증
Spring → 후속 AI 처리 허용
```

보안·운영 원칙:

- AI 원본 문서는 기본 비공개다.
- Public Bucket을 사용하지 않는다.
- Presigned 권한은 단일 객체·단일 작업·짧은 유효기간으로 제한한다.
- Object Key는 서버가 결정하며 클라이언트 입력을 그대로 신뢰하지 않는다.
- 파일 타입·크기·요청량과 총 사용량에 무과금 안전 한도를 둔다.
- API·로그에 Presigned URL과 R2 Secret 원문을 남기지 않는다.
- R2를 사용할 수 없을 때도 S3-compatible 계약을 유지하며 단일 노드 MinIO로만 수동 fallback한다.
- 한 시점에는 신규 업로드용 active write provider 하나만 허용한다. 기존 객체 읽기는 문서별 `storageProvider`를 따르므로 R2·MinIO reader 설정을 함께 유지한다.
- Usage Guard snapshot은 R2 사용량·freshness 기반 업로드 허용만 판정하고 active provider를 소유하지 않는다.
- active write provider와 upload-enabled는 Spring 배포 설정으로 주입한다. FastAPI는 active provider를 결정하지 않고 문서/Job의 provider를 사용한다.
- R2 원본 문서는 P0에서 별도 2차 외부 백업을 두지 않는다. MinIO는 같은 EC2의 임시 가용성 수단이며 backup·복제본으로 계산하지 않는다.

수동 전환·원복 상태:

```text
R2_ACTIVE
→ UPLOAD_BLOCKED
→ FALLBACK_VALIDATING
→ LOCAL_ACTIVE
→ R2_RECONCILING
→ R2_ACTIVE
```

- 자동 failover·이중 쓰기·자동 복제·자동 원복은 금지한다.
- `FALLBACK_VALIDATING`은 운영자 승인과 disk·credential·PUT·HEAD·CORS·9000/9001 외부 차단 근거를 요구한다.
- `UPLOAD_BLOCKED`, `FALLBACK_VALIDATING`, `R2_RECONCILING`에서는 신규 upload grant를 발급하지 않는다.
- R2 복구 후 MinIO backlog를 고정하고 동일 Object Key의 크기·감지 형식·SHA-256을 대조한다. 성공 객체만 R2 metadata로 전환하고 누락·불일치 객체는 영속적인 미해결 기록으로 남긴다.
- 미해결 객체가 1건이라도 있으면 `R2_RECONCILING`을 완료하거나 R2 쓰기를 재개하지 않는다.

---

## 12. 단일 EC2 네트워크 구조

기존 VPC Public/Private Subnet과 ALB·RDS·ElastiCache 전제 대신 세 계층과 Docker Network로 보호한다.

```text
Internet
   │
AWS Security Group
   │  22(관리자 제한), 80/443(승인된 공개 진입점)
   ▼
EC2 UFW
   │
Nginx
   ├─ demo / api / ai / world 공개 routing
   └─ dev 제한 접근 routing
   │
Docker Networks
   ├─ ci-net       : Jenkins Controller / Agents
   ├─ dev-ai-net   : AI dev
   ├─ dev-back-net : Backend dev
   ├─ dev-front-net: Frontend dev
   ├─ dev-game-net : Game dev
   ├─ demo-net     : Spring / FastAPI / Unity
   └─ data-net     : PostgreSQL / Redis, 필요한 앱만 연결
```

환경별 컨테이너 이름·Network·설정·배포 상태를 분리한다. 한 파트의 dev 배포가 다른 dev와 demo 컨테이너를 재시작해서는 안 된다.

PostgreSQL·Redis·애플리케이션 포트·Jenkins 관리 포트는 인터넷에 직접 publish하지 않는다. 외부 공개는 Nginx를 통한 승인된 HTTP/HTTPS/WSS 경로로 한정한다.

---

## 13. 방화벽·포트 원칙

AWS Security Group, EC2 UFW, Docker port binding을 함께 적용한다. 한 계층만으로 데이터 포트를 보호했다고 간주하지 않는다.

| 포트 | 공개 범위 | 용도 |
|---|---|---|
| 22 | 승인된 관리자 IP | SSH 운영 접근 |
| 80 | HTTP→HTTPS 전환 및 인증서 발급 경로 | Nginx |
| 443 | 승인된 Web HTTPS/WSS | Cloudflare/Nginx 공개 진입점 |
| 5432 | 외부 차단 | PostgreSQL 내부 통신 |
| 6379 | 외부 차단 | Redis 내부 통신 |
| 7777 | 외부 차단 | Nginx→Unity 내부 WebSocket |
| Jenkins 관리/Agent 포트 | 외부 차단 | ci-net 또는 제한된 관리 경로 |

- PostgreSQL과 Redis는 `expose`만 사용하고 host publish를 피한다.
- 관리상 host binding이 필요하면 `127.0.0.1`에만 바인딩한다.
- Nginx의 기본 Host는 승인되지 않은 직접 요청을 거부한다.
- Cloudflare Proxy 안정화 후 443 Origin 접근을 Cloudflare IP 범위로 제한하는 방안을 검토한다.
- Security Group 변경 권한이 없으면 AWS 관리자에게 80/443과 관리자 SSH 규칙을 요청하고 적용 여부를 별도로 검증한다.

---

## 14. DNS / Domain

Route53 대신 Cloudflare DNS를 사용한다.

```text
demo.<domain>   → React + Unity WebGL
api.<domain>    → Spring Boot
ai.<domain>     → FastAPI / SSE
world.<domain>  → Unity Dedicated Server / WSS
```

- 네 레코드는 같은 EC2 공인 IP를 가리키고 Cloudflare Proxy를 활성화한다.
- 정적 캐시는 `demo.<domain>`의 허용된 파일에만 적용한다.
- `api`, `ai`, `world`는 정적 Cache Bypass 대상이다.
- dev는 도메인을 붙이지 않고 EC2 공인 IP의 제한된 진입점으로 검증한다.
- 실제 루트 도메인과 관리 계정 담당자는 구매 전에 확정한다.
- Spring `world-sessions`가 `world.<domain>:443`을 내려주며 Unity Client는 주소를 하드코딩하지 않는다.

---

## 15. Jenkins CI/CD

Jenkins는 단일 EC2에서 Controller와 작업 Agent를 논리적으로 분리한다.

```text
Jenkins Controller — Executor 0, 지휘·이력·자격증명
├─ Build Agent  — Spring / FastAPI / Frontend 이미지·산출물
├─ Deploy Agent — dev/demo 배포·검증·롤백
└─ Unity Agent  — WebGL/Linux Server 빌드, 영속 Library/라이선스
```

파트 브랜치:

```text
ai/back/front/game push
→ 해당 파트 Build/Test
→ Image 또는 Artifact:<commit-sha>
→ 해당 파트 dev만 배포
→ 다른 dev와 demo는 재시작하지 않음
```

`develop`:

```text
검증 완료 변경 Squash Merge
→ 전 컴포넌트 통합 릴리스 생성
→ demo 배포
→ Web → Login → World → AI 사용자 여정 검증
→ 성공 시 current 승격
→ 되돌릴 수 있는 실패만 known-good 자동 복구
```

운영 원칙:

- 오래된 Pipeline Run이 최신 배포를 덮어쓰지 못하게 순서를 통제한다.
- 컨테이너 이미지는 commit SHA로 보관하고 `current`와 `known-good`을 식별한다.
- Controller에서는 빌드를 실행하지 않는다.
- Unity Personal 라이선스는 영속 Unity Agent에서 관리자가 Unity Hub로 1회 활성화한다.
- Unity 계정 인증정보를 Jenkins Credentials나 Pipeline 변수에 저장하지 않는다.
- 소스 저장소가 GitHub에서 GitLab으로 이전되면 Jenkins Pipeline은 유지하고 Webhook만 전환한다.
- 단일 EC2에서는 동시 고부하 빌드를 1개로 제한하고 시연 시간대에는 빌드·배포를 동결한다.
- 빌드 Agent 자원 한도를 설정하고 demo 런타임을 우선 보호한다.

DB·Secret·환경 설정·비가역 데이터 변경 및 외부 AI 장애는 자동 복구하지 않고 상태를 보존한 뒤 수동 판단한다.

---

## 16. 환경 분리

물리 서버는 하나지만 배포 대상은 다음처럼 구분한다.

```text
dev-ai     ← ai 브랜치, Mock 연동 허용
dev-back   ← back 브랜치, Mock 연동 허용
dev-front  ← front 브랜치, Mock 연동 허용
dev-game   ← game 브랜치, Mock 연동 허용
demo       ← develop, 실제 사용자 계약과 외부 연동
```

분리 기준:

- Docker Compose Project/서비스 이름
- Docker Network
- 환경변수와 Secret Reference
- Database/Schema/Role
- Redis namespace 또는 환경별 인스턴스
- 정적 릴리스 경로
- 배포 이력과 current/known-good

dev 배포는 다른 dev와 demo를 재시작하거나 교체하지 않는다. demo는 실제 도메인·HTTPS/WSS·실제 외부 API를 사용하는 통합 사용자 경로다.

---

## 17. Secret 관리

저장소에 커밋하지 않는 값:

- PostgreSQL password
- JWT signing secret/key
- R2 Access Key / Secret
- LLM / Embedding API Key
- OAuth Client Secret
- Mattermost Webhook
- TLS private key

관리 원칙:

- Pipeline Secret은 Jenkins Credentials에 저장하고 필요한 Job에만 주입한다.
- 런타임 Secret은 저장소 밖의 권한 제한 파일 또는 승인된 Secret 경계에서 주입한다.
- Secret 파일은 최소 권한으로 읽고 이미지·정적 산출물·캐시에 포함하지 않는다.
- 로그·테스트 보고서·알림·명령 출력에 원문을 남기지 않는다.
- Secret Scan으로 커밋과 산출물을 검사한다.
- Unity 계정 인증정보는 Jenkins에 저장하지 않는다.

AWS IAM과 관리형 Secret 저장소가 확보되면 공급자별 값을 교체할 수 있지만 현재 필수조건으로 두지 않는다.

---

## 18. Logging / Monitoring

### P0 — 로컬 운영 로그

- Spring, FastAPI, Unity Server는 stdout/stderr로 기록한다.
- Docker `local` 또는 `json-file` 로그 드라이버에 크기·파일 수 제한을 설정한다.
- Nginx access/error 로그와 Jenkins Pipeline 원본 로그를 보존한다.
- 로그에 Secret·토큰·Presigned URL·개인정보 원문을 남기지 않는다.
- EC2 디스크 사용량을 감시해 로그가 영구 데이터 영역을 잠식하지 않게 한다.

CloudWatch는 AWS IAM이 없으므로 현재 수집 경로로 사용하지 않는다.

### P1 — 수집 Agent 기반 관측

- Jenkins와 파트별 컨테이너 로그 수집
- Host/Container CPU·메모리·디스크·네트워크 지표 수집
- Grafana 대시보드
- Infra 담당자가 승인한 규칙 기반 Mattermost 알림
- 중복 이벤트 그룹화와 재알림 억제

일반 CI 성공·실패는 별도 탐지 규칙이 없는 한 Mattermost 알림을 만들지 않는다. 관측 스택 장애가 CI/CD와 애플리케이션 성공 판정을 변경해서도 안 된다.

---

## 19. Health Check

### Spring

```text
/actuator/health
```

DB·Redis·필수 내부 의존성 상태를 구분해 확인한다.

### FastAPI

```text
/ai/health
```

프로세스 상태와 외부 LLM 장애를 구분한다. 외부 LLM 장애만으로 정상인 비AI 서비스를 재시작하지 않는다.

### Static Web / Nginx

- 현재 HTML 진입점 응답
- Unity WebGL 필수 파일과 Content-Encoding
- Host별 routing과 TLS 인증서

### Unity Server

- 컨테이너 process/port 상태
- 외부 `wss://world.<domain>` Upgrade 성공
- 브라우저 2개 상호 이동
- 무입력 연결 유지와 재접속

### develop 통합 릴리스

```text
Web 접속 → Login → World 입장 → AI 응답
```

모든 필수 단계가 통과해야 demo 릴리스를 성공으로 기록한다.

---

## 20. Capacity / Resource Guardrail

현재 단일 EC2는 수평 확장과 고가용성을 제공하지 않는다. 먼저 실제 자원 사용량을 측정하고 demo를 보호한다.

측정 대상:

- Jenkins/Unity 빌드 CPU·메모리·디스크 I/O
- Spring/FastAPI 응답 지연과 메모리
- Unity Server 동시 접속자·CPU·메모리
- PostgreSQL connection·volume 사용량
- Redis memory
- 정적 파일 원본 전송량

운영 규칙:

- 고부하 Build 동시 실행 최대 1개
- 시연 시간대 Build/Deploy 동결
- Build Agent CPU·메모리 제한
- demo 런타임과 PostgreSQL에 우선 자원 확보
- 디스크 여유 공간 임계치 미만이면 새 빌드·업로드 차단
- 한 채널 30명 목표를 실제 부하 테스트로 검증

향후 EC2나 IAM이 추가되면 Agent 또는 특정 서비스를 다른 호스트로 옮길 수 있도록 이미지·설정·배포 단위를 유지한다. 이를 현재 수평 확장 구현 완료로 주장하지 않는다.

---

## 21. 비용·운영 복잡도 관리

- 제공받은 고성능 EC2 한 대를 CI, dev, demo가 함께 사용한다.
- Cloudflare DNS/CDN과 R2는 계정·결제 조건과 무료 사용량을 확인한 뒤 사용한다.
- R2는 프로젝트가 정한 무과금 안전 한도에서 신규 업로드를 차단한다.
- 관리형 AWS 서비스는 IAM·추가 자원·운영 필요성이 확인되기 전 도입하지 않는다.
- 같은 EC2에 구성요소를 추가할 때는 기능 이점보다 자원 사용량과 장애 전파를 먼저 평가한다.
- 구현하지 않은 AWS 서비스를 사용했다고 발표하지 않는다.

현재 구조와 장기 관리형 이관 가능성을 문서와 발표에서 명확히 구분한다.

---

## 22. Backup / Recovery

### PostgreSQL

```text
정기 pg_dump --format=custom
→ 압축·checksum 확인
→ 비공개 R2 backups/postgresql/ 업로드
→ 업로드 결과·크기 기록
```

- 로컬 영구 볼륨과 로컬 dump만으로는 완전한 백업으로 간주하지 않는다.
- 초기 보관안은 일간 7개·주간 4개이며 실제 데이터 크기와 정책 확정 후 조정한다.
- 배포 직전 또는 DB Migration 전 수동 백업을 추가한다.
- pgvector가 설치된 동일 계열 PostgreSQL 이미지에서 `pg_restore`를 실측한다.
- 백업 성공 로그만 보지 않고 복구된 row·vector·문서 메타데이터를 검증한다.

### AI 원본 문서

- R2/MinIO 객체와 PostgreSQL의 storage provider·Object Key·문서 상태 대응 관계를 복구할 수 있어야 한다.
- 정기 Object Inventory/Manifest를 백업 세트와 연결한다.
- R2 원본 문서의 두 번째 외부 보관 위치는 P0에서 도입하지 않는다. R2 자체 장애·계정 상실 시 복구할 수 없는 제한을 운영 근거에 명시한다.

### 배포 산출물

- Docker Image: commit SHA / current / known-good
- Static Release: version / current / known-good
- DB Migration version
- 환경 설정의 Secret 없는 복구본

복구 리허설 결과와 소요시간을 기록한다.

---

## 23. 장애 대응

### FastAPI 또는 외부 LLM Down

- AI 기능만 실패 상태로 표시한다.
- 로그인·부스·월드·비AI 기능은 계속 동작한다.
- 정상 컴포넌트를 자동 복구 대상으로 삼지 않는다.

### Unity Server Container Down

- 해당 사용자는 재접속 안내를 받는다.
- 컨테이너를 known-good 이미지로 재기동하고 WSS 경로를 재검증한다.
- Spring의 영구 비즈니스 데이터에는 영향을 주지 않는다.

### Spring Down

- 신규 로그인·임대·코인·문서 메타데이터 변경을 제한한다.
- known-good 복구 가능 여부를 판단하고 DB Migration 관련 실패는 수동 처리한다.

### Redis Restart

- presence·cache·일시 세션·lock 유실을 허용한다.
- PostgreSQL Source of Truth에서 재구성하고 영구 비즈니스 데이터 손실이 없어야 한다.

### PostgreSQL 또는 EC2 Disk 장애

- 쓰기 작업을 중단하고 추가 손상을 막는다.
- 새 PostgreSQL/EC2에 외부 R2 백업을 복구한다.
- Schema·row·vector·R2 문서 연결을 검증한 뒤 서비스한다.

### EC2 전체 장애

- CI, dev, demo, DB, Redis가 함께 중단되는 단일 장애점임을 인정한다.
- 새 호스트에 Compose·known-good 이미지·정적 릴리스·DB 백업으로 복구한다.

### Cloudflare 또는 R2 장애

- Cloudflare 장애 시 문서화된 Nginx Origin 접근 경로를 사용한다.
- R2 장애는 문서 업로드·AI 문서 처리로 격리하고 비AI 기능 전체 장애로 전파하지 않는다.
- P0에서는 timeout, 연속 5xx 횟수와 관측 시간의 자동 장애 판정 수치를 하드코딩하지 않는다. R2 probe의 timeout·5xx·latency를 관측·알림 evidence로 수집하고 Infra 운영자가 검증한 뒤 `UPLOAD_BLOCKED`를 수동 적용한다.
- 자동 판정 수치는 P0 모니터링 자료가 확보된 뒤 Infra·BE 별도 이슈에서 확정하고 장애 주입으로 검증한다. 후속 자동 판정을 도입해도 자동 상태 변경은 신규 upload grant를 막는 `UPLOAD_BLOCKED`까지만 허용한다.
- `FALLBACK_VALIDATING`, `LOCAL_ACTIVE`, `R2_RECONCILING`, `R2_ACTIVE` 전환은 자동화하지 않으며 MinIO 전환·R2 원복은 Infra 운영자가 검증·승인한다.
- MinIO 전환 배포가 실패하면 R2가 복구되지 않은 상태에서 임의 원복하지 않고 `UPLOAD_BLOCKED`로 rollback한다.
- R2 복구 후 `R2_RECONCILING`에서 신규 업로드를 차단하고 객체별 크기·감지 형식·SHA-256과 metadata를 검증한다.
- reconcile 실행 결과는 run/item 단위로 남기며 검증 성공 객체만 Spring metadata 변경 대상으로 전달한다.

---

## 24. 구현·실측 순서

1. EC2 vCPU·RAM·Disk·OS와 Security Group/UFW 권한 확인
2. Docker/Compose 설치와 ci/dev/demo/data Network·볼륨 분리
3. Jenkins Controller Executor 0과 Build/Deploy/Unity Agent 구성
4. 파트별 dev 독립 배포와 다른 환경 무재시작 검증
5. PostgreSQL/pgvector·Redis 영속/임시 데이터 경계 검증
6. Spring·FastAPI·Unity Server demo 통합 배포
7. Nginx HTTP Origin과 버전별 정적 릴리스·known-good 롤백 검증
8. 도메인 구매 후 Cloudflare DNS/Proxy·Let's Encrypt·Full (strict) 적용
9. HTTPS 페이지에서 WSS 2브라우저·idle/heartbeat·재접속 실측
10. SSE가 중간 버퍼링 없이 순차 도착하는지 실측
11. Refresh Cookie·CORS·OAuth callback을 실제 demo 도메인에서 검증
12. R2 Presigned 직접 업로드·HEAD 검증·사용량 제한 시험
13. `pg_dump→R2→pg_restore` 복구 리허설
14. Unity Build와 demo 동시 부하 측정 후 자원 제한·시연 동결 정책 확정

ECR/ECS/ALB/RDS부터 구성하지 않는다. 현재 성공 기준은 단일 EC2의 실제 외부 사용자 경로와 복구 가능성이다.

---

## 25. 확정 필요 사항

| ID | 항목 | 결정 주체 | 결정 시점 |
|---|---|---|---|
| C-01 | EC2 vCPU·RAM·Disk와 80/443 Security Group 변경 담당자 | Infra + AWS 관리자 | 실환경 구성 전 |
| C-02 | 신규 demo 루트 도메인과 구매·관리 계정 담당자 | Infra + 팀 | DNS/TLS 적용 전 |
| C-03 | Cloudflare DNS/CDN·R2 사용 계정과 결제·초과 과금 책임 | Infra + 팀 리드 | Cloudflare/R2 적용 전 |
| C-04 | R2 무과금 안전 한도와 신규 업로드 차단 기준 | Infra + BE + 기획 | 문서 업로드 적용 전 |
<<<<<<< HEAD
| C-05 | R2 장애 시 fallback·복구 | Infra + BE + AI | **확정: 운영자 승인 기반 단일 노드 MinIO fallback(S3-compatible fallback 아님), 자동 failover·이중 쓰기·자동 원복 금지, 문서별 Provider 읽기.** 원본 문서의 두 번째 외부 백업 위치는 미확정 ([spec 007 C-10](../specs/007-ai-agent-document/spec.md), [GitLab Work Item #100](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/100)) |
=======
| C-05 | ✅ 운영자 승인 수동 MinIO fallback, active write provider 단일화, 객체별 provider 읽기, upload-blocked reconcile로 확정. 자동 전환·원복·이중 쓰기·복제 금지. 후속 자동 판정을 도입해도 자동 동작은 `UPLOAD_BLOCKED`까지만 허용. 원본 문서 2차 외부 백업은 P0 미도입 | Infra + BE + AI | 2026-08-25 확정 — Jira `S15P21A604-215`, GitLab #100 |
>>>>>>> c10b3286 (docs(infra): R2 수동 MinIO fallback 및 reconcile 계약 정합화 (S15P21A604-215))
| C-06 | PostgreSQL/pgvector Database·Schema·Role 분리와 최종 백업 보관 정책 | Infra + BE + AI | 데이터 환경 구성 전 |
| C-07 | 시연 시간대·빌드/배포 동결 시간과 긴급 배포 승인 절차 | Infra + 팀 | 서버 부하 실측 후 |
| C-08 | Docker 로그 보존량과 P1 지표·탐지 규칙·Mattermost 재알림 기준 | Infra | 관측 설계 전 |
| C-09 | WSS heartbeat/timeout과 SSE keepalive의 최종값 | Infra + Unity + AI | 외부 실측 후 |
| C-10 | R2 가용성 자동 판정 수치(timeout·연속 5xx 횟수·관측 시간) | Infra + BE | P0 모니터링 자료 확보 후 별도 이슈에서 확정·장애 주입 검증 |

이미 확정된 사항:

- CI/CD는 Jenkins를 사용한다.
- 초기 자원은 고성능 단일 EC2 한 대다.
- dev는 EC2 IP, 최종 demo만 신규 도메인·TLS를 사용한다.
- DNS/Proxy/CDN은 Cloudflare, Origin TLS는 Nginx Let's Encrypt를 사용한다.
- demo 서브도메인은 `demo`·`api`·`ai`·`world`로 분리한다.
- Unity Server는 11층·단일 채널 컨테이너 1개이며 외부 `wss:443`을 내부 `ws:7777`로 전달한다.
- PostgreSQL/pgvector는 영구 데이터, Redis는 유실 가능한 임시 데이터만 담당한다.
