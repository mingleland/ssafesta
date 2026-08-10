# SSAFY FESTA Infra / AWS 설계서

> **목표**: React/Unity Web, Spring Boot, FastAPI, Unity Dedicated Server를 AWS에 배포하고, World Instance를 수평 확장 가능한 형태로 운영한다.  
> **상태**: Target Architecture Draft — 비용과 팀 역량에 따라 개발 초기 배치는 단순화할 수 있다.

---

## 1. 인프라 목표

1. HTTPS 기반 Web 서비스 제공
2. Unity Web 정적 파일 안정적 배포
3. Spring / FastAPI 독립 배포
4. Unity Dedicated Server Container 실행
5. PostgreSQL / Redis / S3 역할 분리
6. World Instance를 Task 단위로 추가 가능한 구조
7. 로그·지표를 중앙에서 확인
8. Secret을 코드 저장소와 분리

---

## 2. Target Architecture

```text
                            Internet
                               │
                         Route53 / DNS
                               │
                      CloudFront / HTTPS
                        │              │
                        │              └─ S3 Static
                        │                 React + Unity Web Build
                        │
                              ALB
                               │
              ┌────────────────┼────────────────┐
              │                │                │
        Spring Boot         FastAPI        Session Service
        ECS/EC2 후보       ECS/EC2 후보      후보
              │                │                │
              └───────┬────────┴───────┬────────┘
                      │                │
                     RDS          ElastiCache/Redis
                      │
                     S3

Unity Dedicated Server
Linux Build → Docker → ECR → ECS Task
                         ├─ World-01
                         ├─ World-02
                         └─ Booth Instance(P2)
```

Spring/FastAPI를 ECS에 둘지 EC2에 둘지는 팀 운영 부담과 비용에 따라 최종 확정한다. Unity Dedicated Server는 ECR+ECS가 대표 목표다.

---

## 3. Static Web

### S3

저장:

- React build
- Unity Web build
- 공개 정적 asset

### CloudFront

- HTTPS
- CDN cache
- 정적 파일 전송
- Unity Build 파일 Cache 정책 분리 검토

React와 Unity Build를 동일 배포 단위로 둘지 별도 Origin으로 둘지는 CI/CD 편의에 따라 결정한다.

---

## 4. ALB

용도:

- Spring API routing
- FastAPI routing
- Health Check
- HTTPS termination 후보

예시:

```text
/api/*     → Spring
/ai/*      → FastAPI
```

AI SSE를 사용할 경우 ALB/Proxy timeout과 buffering 설정을 실제 검증한다.

---

## 5. Spring Boot 배포

### Target

Docker Container 기반 배포를 권장한다.

```text
Spring Build
→ Docker Image
→ ECR
→ ECS Service 또는 EC2 Docker
```

### 환경변수

- DB connection
- Redis endpoint
- JWT secret/reference
- S3 bucket
- AI internal endpoint
- CORS allowed origin

---

## 6. FastAPI 배포

```text
FastAPI
→ Docker
→ ECR
→ ECS Service 또는 EC2 Docker
```

AI Provider Key는 Secrets Manager / Parameter Store 같은 Secret 저장소 사용을 권장한다.

문서 처리 부하가 커지면:

```text
API Service
+
Document Worker
+
Queue(SQS 후보)
```

로 분리할 수 있다. MVP에는 동작 안정성이 우선이다.

---

## 7. Unity Dedicated Server 배포

```text
Unity 6 Project
→ Linux Dedicated Server Build
→ Docker Image
→ ECR
→ ECS Task
```

### ECS Task 단위

```text
Task #1 → 11F-01
Task #2 → 11F-02
Task #3 → Booth-7-01(P2)
```

한 Task 안에 여러 World를 무리하게 몰아넣기보다 Instance 단위를 명확히 유지한다.

---

## 8. Unity Server 이미지

Dockerfile 고려:

- Linux runtime dependency
- Server binary execute permission
- 환경변수로 instance/channel 설정
- Port expose
- graceful shutdown signal
- stdout/stderr log

예상 환경변수:

```text
INSTANCE_ID
WORLD_ID
CHANNEL_ID
MAX_PLAYERS
SPRING_INTERNAL_URL
REDIS_ENDPOINT(optional)
```

---

## 9. RDS PostgreSQL

저장:

- User
- Booth / Lease
- Layout
- Wallet / Coin Ledger
- Staff
- Survey
- Project
- Agent Config
- Event / Vote(P2)
- pgvector 또는 별도 AI DB

### 운영 원칙

- Public access 비활성 권장
- Private subnet
- App Security Group에서만 접근
- 자동 백업 설정
- Migration 도구 사용

---

## 10. Redis / ElastiCache

용도:

- Presence
- Channel Assignment
- Staff Online
- Session
- Instance 상태
- Lock

영구 비즈니스 기록을 Redis 단독으로 저장하지 않는다.

---

## 11. S3

Bucket 또는 Prefix 분리:

```text
static-web/
booths/{boothId}/agents/{agentId}/documents/
project-media/
images/
```

### 보안

- AI 문서는 기본 비공개
- Presigned URL 사용 후보
- Public Bucket 금지
- 파일 타입/크기 검증

---

## 12. 네트워크 구조

권장 VPC:

```text
VPC
├─ Public Subnet
│  └─ ALB / 필요 시 NAT
└─ Private Subnet
   ├─ Spring
   ├─ FastAPI
   ├─ Unity Server
   ├─ RDS
   └─ Redis
```

초기 프로젝트 비용/복잡도 때문에 단순화할 수 있으나 RDS/Redis를 인터넷에 직접 노출하는 구조는 피한다.

---

## 13. Security Group 원칙

### ALB

- 443 from Internet

### Spring/FastAPI

- App port only from ALB 또는 내부 서비스

### RDS

- 5432 only from Application SG

### Redis

- Redis port only from 필요한 Application SG

### Unity Server

실제 NGO Transport가 요구하는 Port/Protocol을 POC 후 제한적으로 Open한다.

---

## 14. DNS / Domain

권장 분리 예:

```text
festa.example.com       → Web
api.festa.example.com   → Spring
ai.festa.example.com    → FastAPI
```

실제 SSAFY 배포 도메인 규칙에 맞게 조정한다.

---

## 15. CI/CD

### Web

```text
Git Push/MR Merge
→ npm test/build
→ S3 Upload
→ CloudFront Invalidation
```

### Spring

```text
Build/Test
→ Docker Build
→ ECR Push
→ ECS Deploy 또는 EC2 Restart
→ Health Check
```

### FastAPI

```text
Test
→ Docker Build
→ ECR Push
→ Deploy
→ /ai/health
```

### Unity Client

```text
Unity Web Build
→ Artifact
→ S3
→ CloudFront
```

### Unity Server

```text
Linux Server Build
→ Docker
→ ECR
→ ECS Task Definition Revision
```

---

## 16. 환경 분리

가능하면:

```text
dev
staging
prod/demo
```

SSAFY 프로젝트 규모가 작다면 최소 `dev / demo`는 분리하는 것이 좋다.

같은 DB를 로컬 테스트와 시연이 공유해 데이터가 망가지는 상황을 피한다.

---

## 17. Secret 관리

저장소 금지:

- DB password
- JWT secret
- AWS key
- LLM key
- OAuth secret

권장:

- AWS Secrets Manager
- SSM Parameter Store
- GitLab protected CI variables

중 하나를 팀 환경에 맞게 사용한다.

---

## 18. Logging / Monitoring

### CloudWatch 후보

Spring:

- request count
- 4xx/5xx
- latency
- JVM memory

FastAPI:

- AI latency
- LLM errors
- token usage
- document processing errors

Unity Server:

- instanceId
- current players
- disconnects
- memory / CPU

Infra:

- ECS task restart
- ALB 5xx
- RDS connections
- Redis memory

---

## 19. Health Check

### Spring

```text
/actuator/health 후보
```

### FastAPI

```text
/ai/health
```

### Unity Server

ECS가 확인할 수 있는 process/heartbeat 방식이 필요하다. 실제 HTTP Health Endpoint 추가 여부는 구현 난이도에 따라 결정한다.

---

## 20. Scaling

### Web

CloudFront/S3 자체 확장.

### Spring/FastAPI

필요 시 ECS Service Task count 확장.

### Unity Server

```text
수용량 부족
→ 새 World Task 실행
→ Session Service에 READY 등록
→ 새 사용자 배정
```

자동화는 P2다. MVP는 수동 Task 실행으로도 아키텍처 검증 가능하다.

---

## 21. 비용 절감 전략

- 개발 초기 Spring/FastAPI를 한 EC2에 둘 수 있음
- Unity Server도 POC 단계는 한 EC2 Docker로 검증 가능
- RDS/ElastiCache 비용이 부담되면 개발 환경은 단순화 가능
- 최종 발표에서는 Target Architecture와 실제 MVP 배치를 구분해 설명

구현하지 않은 AWS 서비스를 사용했다고 발표하지 않는다.

---

## 22. Backup / Recovery

최소:

- RDS 자동 백업
- S3 파일 보존
- DB Migration version 관리
- 배포 이미지 ECR tag/revision 관리

시연 직전:

- DB snapshot 또는 export
- 안정 버전 Docker/Image tag
- Web 안정 build 보관

---

## 23. 장애 대응

### FastAPI Down

- AI 기능 오류
- World/Project/Survey는 계속 동작

### Unity World Task Down

- 해당 Channel 사용자 재접속 안내
- Spring 영구 데이터 영향 없음

### Spring Down

- 신규 비즈니스 작업 제한
- 알람 발생
- 시연 시 backup 영상 준비

---

## 24. POC 순서

1. Unity Linux Dedicated Server local build
2. Docker local 실행
3. 외부 Client 연결
4. AWS 단일 Instance에서 실행
5. ECR push
6. ECS Task 실행
7. Web Client → ECS Server 연결

ECS부터 만들고 Unity 연결 자체가 안 되는 상황을 피한다.

---

## 25. 확정 필요 사항

- Spring/FastAPI 최종 ECS vs EC2
- Unity Transport Port/Protocol
- ALB를 Unity Server에도 사용할지 여부
- RDS/pgvector 분리 여부
- Redis Managed 여부
- SQS 도입 시점
- CI/CD Platform 구체 설정
- Autoscaling 기준
- 예산 상한
