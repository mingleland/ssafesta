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
- **확정 구조 (2026-08-21, Issue #30): 브라우저 → wss(443) → Cloudflare Edge → EC2 Nginx → ws(7777) → 컨테이너**
  - **TLS 는 두 번 종료된다** — 사용자↔Cloudflare Edge, Cloudflare↔EC2 Nginx. **ALB 는 사용하지 않는다** (IAM 없음 → ACM 불가). 도메인은 `world.<domain>` (Cloudflare DNS)
- idle timeout: **Nginx `proxy_read_timeout` 을 180초 이상**으로 올려야 한다. 기본 60초는 NGO 무트래픽 연결을 끊는다 — ALB 시절 지적된 문제가 Nginx 기본값에도 동일하게 있다. Cloudflare 는 WebSocket 을 프록시하며 `Upgrade`/`Connection` 헤더 통과 설정이 필요하다

### 기동 성공 판정 로그 (stdout)

```
[NetworkBootstrap] Dedicated server start=True port=7777 maxPlayers=40 (WebSocket)
[ConnectionManager] Approved client=<id> nickname=<name>   ← 클라이언트 접속 시
```

### 로그 방식

- 전부 **stdout/stderr** (Unity Debug.Log → 컨테이너 로그)
- 수집 방식 **미정** — ECS 를 쓰지 않으므로 awslogs 드라이버가 전제가 아니다. EC2 Docker 에서 `docker logs` / awslogs(IAM 필요) / 별도 스택 중 선택 대기 (#30 ④). 파일 로그는 없다
- 셰이더 관련 경고("Dedicated Server Optimizations") 다수 출력 — 무해, 필터 대상

## 2. 서버 파라미터 (현재 CLI 인자, 향후 ENV 연동 예정)

| 현재 CLI | 향후 ENV 후보 (컨테이너 환경변수) | 기본값 |
|---|---|---|
| `-port <n>` | — | 7777 |
| `-maxPlayers <n>` | `MAX_PLAYERS` | 40 |
| (미구현) | `INSTANCE_ID` / `WORLD_ID` / `CHANNEL_ID` | — |
| (미구현) | `SPRING_INTERNAL_URL` | — |

ENV → CLI 매핑은 `docker run`/Compose 의 command 인자로 주입하거나

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

- ✅ **완료 (문서가 낡아 있었다).** `ConnectionManager` 의 scheme 분기와 `WorldSessionDto` 구조 갱신은 **이미 구현돼 있다** — `ConnectionManager.cs:57-73` 이 `scheme == "wss"` 일 때 `UseEncryption` + `SetClientSecrets(host)` 를 설정하고, `WorldSessionDto.endpoint` 도 위 구조다. **wss 실측을 위한 Unity 사전 작업은 0건이며 `world.<domain>` 이 서면 바로 붙는다** (2026-08-21 확인, #30)

## 4. ~~Load Balancer 선택 + Health Check~~ — ⛔ 무효 (2026-08-21, #30)

**ALB vs NLB 비교는 무효다.** AWS IAM 이 없어 ACM 을 쓸 수 없고, LB 대신 **Cloudflare + EC2 Nginx** 로 확정됐다.

대신 확인해야 하는 것:

| 항목 | 요구 |
|---|---|
| Nginx WebSocket 프록시 | `proxy_set_header Upgrade $http_upgrade` · `Connection "upgrade"` · HTTP/1.1 |
| Nginx idle timeout | `proxy_read_timeout` **180초 이상** (기본 60초 → NGO 연결 끊김) |
| Cloudflare | WebSocket 프록시 허용, `world` 서브도메인 |
| Health Check | **LB 가 없으므로 요구가 사라졌다.** `/healthz` 추가도 불필요 — 컨테이너 재시작 정책으로 대체 |

~~ALB `/healthz` 추가 반나절 vs NLB TCP 체크 코드수정 0~~ 트레이드오프는 성립하지 않는다.

## 5. 배포 시 필요한 것 (Infra 작업 목록) — 2026-08-21 갱신 (#30)

ECR/ECS 를 쓰지 않는다. 단일 EC2 + Jenkins + 자체 이미지 저장소 구조다.

- [ ] 이미지 빌드 → Jenkins 이미지 저장소에 **커밋 SHA 태그**로 push
- [ ] 컨테이너 실행: 포트 매핑 7777, 재시작 정책, (CPU/MEM — 로컬 관측치: 유휴 시 경량, 부하 테스트 후 확정)
- [ ] **Nginx**: `world.<domain>` 443 → `ws://127.0.0.1:7777`, Upgrade 헤더 통과, `proxy_read_timeout` 180s+
- [ ] **Cloudflare DNS**: `world.<domain>` → EC2, WebSocket 프록시 허용
- [ ] Security Group: 외부에 **443 만** 열고 7777 은 노출하지 않는다 (Nginx 가 loopback 으로 접근)
- [ ] 롤백: `current` / `known-good` 태그 전환 (Jenkins 파이프라인)

## 6. 최종 검증 시나리오 (AWS)

1. EC2 Docker 에서 컨테이너 실행 → 컨테이너 로그에서 기동 로그 확인
2. CloudFront의 HTTPS 페이지에서 Web 클라이언트 로드
3. **`wss://world.<domain>`으로 Connect → Approved 로그 + 캡슐 스폰**
4. 브라우저 2개에서 상호 이동 확인
5. 60초 이상 방치 후 연결 유지 확인 (idle timeout 검증)

이 5개가 통과하면 ADR 결정 1(Transport)이 최종 확정된다.
