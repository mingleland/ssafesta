# Dedicated Server와 실행 환경

## 1. KHS가 해결하려던 문제

에디터 Host 한 대에 다른 사용자가 붙는 방식은 개발 확인에는 편하지만 운영 구조가 아니다. Host 사용자가 종료하면 월드가 사라지고, 브라우저 Client가 임의로 서버 역할을 맡게 되며, 채널별 정원과 접속 승인을 일관되게 관리하기 어렵다.

KHS 작업에서는 **그래픽이 없는 Unity Linux Dedicated Server를 별도 프로세스로 실행**하고 WebGL Client가 WebSocket으로 접속하는 POC를 구성했다.

## 2. 적용한 개념

### Dedicated Server

Dedicated Server는 플레이 화면을 렌더링하지 않고 네트워크 권한과 공유 상태만 처리한다. SSAFESTA에서는 다음 책임을 가진다.

- Client 접속 승인과 정원 제한
- Player NetworkObject Spawn·Despawn
- 위치·상태·아바타 외형 전파
- 현재 Booth/Zone 상태의 서버 권한 관리

정적 부스 가구나 AI 응답을 직접 보관·렌더링하는 서버는 아니다.

Client끼리는 서로 직접 Socket을 연결하지 않는다. 모든 WebGL Client는 Dedicated Server에만 접속하며, 한 Client의 이동·부스 상태·아바타 변경은 NGO가 서버를 경유해 다른 Client에 전달한다. 단, 현재 이동 Transform은 Owner Client 권위이고 서버는 중계 역할을 하며, 닉네임·부스 ID·아바타 외형값은 서버가 최종 기록한다.

### Headless Linux Build와 Container

Unity Linux Server 빌드를 Ubuntu 22.04 기반 Docker Image에 넣었다. 실행 시 `-batchmode -nographics`를 사용하고, 컨테이너 내부에서는 비루트 `unity` 사용자로 프로세스를 실행한다.

컨테이너화한 이유는 다음과 같다.

- 개발 PC와 운영 서버의 실행 환경 차이를 줄인다.
- 동일 Image를 여러 Channel 인스턴스로 반복 실행할 수 있다.
- 포트, 정원 같은 값을 실행 인자로 바꿀 수 있다.
- 이후 ECR/ECS 같은 환경으로 옮길 수 있는 배포 단위를 만든다.

## 3. 브라우저 때문에 WebSocket을 선택한 이유

일반 Native Unity Client는 UDP를 쓸 수 있지만 브라우저 WebGL은 임의 UDP Socket을 열 수 없다. 그래서 `NetworkBootstrap`이 Client와 Server 모두 `UnityTransport.UseWebSockets = true`로 통일한다.

로컬 개발 경로는 다음과 같다.

```text
WebGL Browser
  └─ ws://127.0.0.1:7777
       └─ Docker Port Mapping
            └─ Unity Linux Dedicated Server
```

운영 후보 경로는 다음과 같으며 아직 AWS E2E 검증 전이다.

```text
HTTPS Web Page
  └─ wss://world.example.com
       └─ Load Balancer에서 TLS 종료
            └─ ws://Unity Dedicated Server:7777
```

HTTPS 페이지는 보안 정책상 평문 `ws://`로 접속할 수 없으므로 운영에서는 `wss://`가 필요하다.

## 4. 실행 설정을 코드와 분리한 방법

`NetworkBootstrap`은 다음 CLI 인자를 읽는다.

| 인자 | 기본값 | 역할 |
|---|---:|---|
| `-port` | 7777 | 서버 수신 포트 |
| `-maxPlayers` | 40 | 채널 정원 |

서버는 컨테이너에서 모든 인터페이스를 받아야 하므로 `0.0.0.0`에 Bind한다. Client는 World Session API가 준 실제 Host와 Port로 연결한다.

## 5. 채널 확장에 적용한 생각

한 서버에 모든 사용자를 넣는 대신 동일 월드를 실행하는 여러 Dedicated Server를 Channel로 본다.

```text
11F World Data
├─ Channel 01: Dedicated Server, 목표 30~40명
├─ Channel 02: Dedicated Server, 목표 30~40명
└─ Channel 03: Dedicated Server, 목표 30~40명
```

각 채널은 플레이어 상태만 따로 가지며 Booth Published 데이터는 Spring에서 함께 읽는다. 따라서 채널 수가 늘어도 Booth Layout을 채널마다 복제 저장하지 않는다.

Session/Channel Manager와 자동 증설은 KHS가 구현한 범위가 아니다. KHS는 Unity가 고정 주소가 아니라 `scheme/host/port` 응답을 받아 연결할 수 있는 Client 경계를 준비했다.

## 6. 현재 구현 상태

### KHS 구현 완료

- Unity Server/Batch Mode 자동 `StartServer()`
- WebSocket Transport 강제
- 포트·정원 CLI 파싱
- Linux Server용 Dockerfile
- 비루트 사용자 실행
- 로컬 Browser ↔ Docker Server 테스트 절차 문서화

### 아직 검증·구현하지 않은 범위

- 실제 AWS ECR/ECS 배포
- ALB `wss` TLS 종료 E2E
- Session/Channel Manager
- 자동 Health Check·Drain·Scale-out
- 운영 동시접속 30~40명 부하 시험

## 7. 관련 파일

- `festa-unity/Assets/_Project/Scripts/Network/Bootstrap/NetworkBootstrap.cs`
- `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs`
- `festa-unity/Docker/Dockerfile`
- `festa-unity/Docker/README.md`
- [월드 세션 Spec](../../specs/002-world-session/spec.md)
