# Deployment Handoff — Unity Dedicated Server

> Infra 담당자에게 넘기는 인수인계 문서. Unity 쪽에서 확정된 것과 AWS 단계에서 결정해야 할 것을 구분한다.
> 로컬 Docker 검증 완료 상태 기준 (poc-status.md 참조).

## 1. 확정된 것 (로컬 검증 완료)

### 이미지 / 실행

| 항목 | 값 |
|---|---|
| Base image | ubuntu:22.04 + ca-certificates, libatomic1 |
| Dockerfile | `festa-unity/Docker/Dockerfile` |
| 빌드 컨텍스트 | `Builds/linux-server/` (Unity Linux Server 빌드 출력) |
| 이미지 빌드 | `docker build -t festa-world:dev -f Docker/Dockerfile Builds/linux-server` |
| 실행 | `docker run -d -p 7777:7777 festa-world:dev` |
| ENTRYPOINT | `/app/festa-unity.x86_64 -batchmode -nographics` |
| CMD (기본 인자) | `-port 7777 -maxPlayers 40` |
| 실행 유저 | 비루트 (`unity`) |

### 네트워크 / Transport

- **프로토콜: WebSocket over TCP, 포트 7777** (NGO UnityTransport, 코드에서 `UseWebSockets=true` 강제)
- 서버는 `0.0.0.0:7777` 바인딩 (컨테이너 친화)
- 브라우저 클라이언트는 UDP 불가 → WebSocket 전용. 배포 시 `wss://` 필수 (HTTPS 페이지에서 `ws://`는 mixed content로 차단됨)
- **목표 구조: 브라우저 → wss(443) → LB(ACM TLS 종료) → ws(7777) → 컨테이너**
  - LB는 ALB / NLB 둘 다 성립 (아래 §4 비교) — AWS 단계에서 확정
- idle timeout: 기본 60초는 짧을 가능성이 높음 → **초기 권장값 180초** (필수값 아님 — WebSocket/Transport heartbeat 실측 후 최종 확정)

### 기동 성공 판정 로그 (stdout)

```
[NetworkBootstrap] Dedicated server start=True port=7777 maxPlayers=40 (WebSocket)
[ConnectionManager] Approved client=<id> nickname=<name>   ← 클라이언트 접속 시
```

### 로그 방식

- 전부 **stdout/stderr** (Unity Debug.Log → 컨테이너 로그)
- ECS에서는 awslogs 드라이버로 CloudWatch 수집하면 됨. 파일 로그 없음
- 셰이더 관련 경고("Dedicated Server Optimizations") 다수 출력 — 무해, 필터 대상

## 2. 서버 파라미터 (현재 CLI 인자, 향후 ENV 연동 예정)

| 현재 CLI | 향후 ECS ENV 후보 (doc 15 §8) | 기본값 |
|---|---|---|
| `-port <n>` | — | 7777 |
| `-maxPlayers <n>` | `MAX_PLAYERS` | 40 |
| (미구현) | `INSTANCE_ID` / `WORLD_ID` / `CHANNEL_ID` | — |
| (미구현) | `SPRING_INTERNAL_URL` | — |

ENV → CLI 매핑은 ECS Task Definition에서 command 인자로 주입하거나, 추후 NetworkBootstrap에 ENV 파싱 추가 (작은 작업, 필요 시 요청).

## 3. 클라이언트가 서버 주소를 받는 방식

- **하드코딩 금지 원칙.** 정식 흐름: Spring `POST /api/v1/world-sessions` 응답 → Unity가 그 값으로 접속
- Unity 쪽 계약은 이미 구현됨: `IUserApiClient.CreateWorldSessionAsync()` (현재 Mock이 `127.0.0.1:7777` 반환)
- 현재 POC는 DevConnectionHud 수동 입력 사용

### Endpoint 계약 (제안 — full URI가 아닌 구조화 필드)

UnityTransport API가 URI가 아니라 host/port를 받는 구조이므로, 계약도 그에 맞춘다:

```json
{
  "sessionId": "ws_...",
  "worldId": "11F",
  "channelId": "11F-01",
  "endpoint": {
    "scheme": "wss",        // "ws"(로컬/개발) | "wss"(배포)
    "host": "world.festa.example.com",
    "port": 443
  },
  "connectionToken": "...",
  "expiresAt": "..."
}
```

UnityTransport 매핑:

| 계약 필드 | UnityTransport 적용 |
|---|---|
| host, port | `SetConnectionData(host, port)` |
| scheme == wss | `NetworkSettings.WithSecureClientParameters(serverName: host)` + `UseWebSockets=true` |
| scheme == ws | `UseWebSockets=true`만 (로컬 개발) |
| ~~path~~ | **계약에서 제외** — UnityTransport WebSocket은 경로를 사실상 지원하지 않음(기본 `/`). LB 라우팅은 host 기반으로 |

- ⚠️ **AWS 착수 전 Unity 코드 수정 1건 필요**: ConnectionManager에 위 scheme 분기(wss 시 secure client parameters) 추가 + `WorldSessionDto`를 위 구조로 갱신. AWS 작업 시작 시점에 진행

## 4. Load Balancer 선택 + Health Check — AWS 단계에서 결정

두 구성 모두 성립한다. Health Check 방식이 핵심 차이다.

| | **ALB** (HTTPS Listener) | **NLB** (TLS Listener) |
|---|---|---|
| TLS 종료 | ACM에서 종료 → 백엔드 평문 ws | **ACM에서 종료 가능 (TLS Listener + TCP Target Group)** → 백엔드 평문 ws. 서버가 인증서를 직접 관리할 필요 없음 |
| Health Check | **HTTP(S)만 지원** → Unity 서버에 HTTP 엔드포인트 없음 → 경량 `/healthz` HTTP listener 추가 필요 (C# HttpListener, 별도 포트) | **TCP 체크 지원** → 7777 포트 열림만 확인, Unity 코드 수정 불필요 |
| WebSocket | Upgrade 네이티브 지원, L7 라우팅 가능 | L4 통과 — WebSocket을 그냥 TCP로 흘림, 문제 없음 |
| idle timeout | 기본 60초 (조정 가능) | TCP idle timeout 조정 가능 (2024+부터 configurable) |
| 기타 | 향후 path/host 기반 라우팅·Spring API 공유 용이 | 구성 단순, 채널별 포트 분리 시 자연스러움 |

→ **둘 다 열어두고 AWS 단계에서 실측으로 확정.** NLB를 쓰면 Health Check 문제가 코드 수정 없이 풀리고, ALB를 쓰면 `/healthz` HTTP listener 추가(반나절)가 필요하다는 트레이드오프.

## 5. ECR / ECS 배포 시 필요한 것 (Infra 작업 목록)

- [ ] ECR 리포지토리 생성 → `docker tag` + `push`
- [ ] ECS Task Definition: 포트 매핑 7777, awslogs, (필요 시 CPU/MEM — 로컬 관측치: 유휴 시 경량, 부하 테스트 후 확정)
- [ ] Task당 World Instance 1개 원칙 (doc 15 §7): `11F-01` = Task 1개
- [ ] LB(ALB 또는 NLB, §4에서 확정): 443 Listener + ACM 인증서, Target → 7777, idle timeout 초기 180s(권장값, 실측 후 확정)
- [ ] Route53: `world.festa.example.com` → ALB (도메인 규칙은 팀 확정)
- [ ] Security Group: ALB만 7777 접근 허용
- [ ] Web 정적 배포: S3 + CloudFront(HTTPS). Unity Web 빌드 압축 설정과 CloudFront `Content-Encoding` 헤더 정합 확인 (현재 로컬은 Compression Disabled 상태)

## 6. 최종 검증 시나리오 (AWS)

1. ECS(또는 EC2 Docker)에서 Task 실행 → CloudWatch에 기동 로그 확인
2. CloudFront의 HTTPS 페이지에서 Web 클라이언트 로드
3. **`wss://world.<domain>`으로 Connect → Approved 로그 + 캡슐 스폰**
4. 브라우저 2개에서 상호 이동 확인
5. 60초 이상 방치 후 연결 유지 확인 (idle timeout 검증)

이 5개가 통과하면 ADR 결정 1(Transport)이 최종 확정된다.
