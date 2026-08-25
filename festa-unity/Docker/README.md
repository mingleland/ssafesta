# Unity Dedicated Server — Docker 가이드

## 사전 조건

- **Docker Desktop 설치** (Windows): https://www.docker.com/products/docker-desktop/
  - 설치 후 재부팅 → Docker Desktop 실행 → 좌하단 고래 아이콘이 초록색인지 확인
  - WSL2 설치를 요구하면 안내에 따라 설치
- Unity **Linux Server** 빌드가 `Builds/linux-server/`에 있어야 함

## 빌드 출력 확인

`Builds/linux-server/` 안에 대략 이런 파일이 있어야 정상:

```text
festa-unity.x86_64        ← 실행 파일 (이름이 다르면 Dockerfile 수정)
UnityPlayer.so
festa-unity_Data/
```

## 명령어 (PowerShell, festa-unity 폴더에서)

```powershell
# 1. 이미지 빌드
docker build -t festa-world:dev -f Docker/Dockerfile Builds/linux-server

# 2. 컨테이너 실행 (11F-01 채널 가정)
docker run -d --name festa-world-01 -p 7777:7777 festa-world:dev

# 3. 서버 기동 로그 확인 — 아래 로그가 보여야 성공
#    [NetworkBootstrap] Dedicated server start=True port=7777 maxPlayers=40 (WebSocket)
docker logs -f festa-world-01

# 중지/삭제 (재시작할 때)
docker rm -f festa-world-01
```

## 접속 테스트

1. 에디터 **Play 하지 않는다** (에디터 서버와 포트 충돌 방지)
2. Web 빌드 페이지(브라우저) 열기 → HUD에서 Addr `127.0.0.1`, Port `7777` → **Connect as Client**
3. 성공 판정:
   - 브라우저에 자기 캡슐(노랑) 스폰
   - `docker logs`에 `[ConnectionManager] Approved client=...` 출력
   - 탭 2개 접속 시 서로 보임

→ 이게 되면 "브라우저 ↔ Docker 컨테이너 서버" 검증 완료.
다음 단계는 이 이미지를 ECR에 푸시하고 AWS에서 실행 + ALB wss (docs/22 §2).

## 트러블슈팅

| 증상 | 원인/해결 |
|---|---|
| `docker: command not found` | Docker Desktop 미설치 또는 미실행 |
| chmod 단계에서 "no such file" | 실행 파일 이름이 다름 — 빌드 폴더 확인 후 Dockerfile의 두 곳(RUN chmod, ENTRYPOINT) 수정 |
| 컨테이너가 바로 종료됨 | `docker logs festa-world-01`로 원인 확인 후 공유 |
| 브라우저 접속 실패 | 에디터 Play 중인지(포트 충돌), `-p 7777:7777` 매핑 확인 |
| 로그에 start=True인데 접속 안 됨 | Windows 방화벽 팝업에서 거부했는지 확인 |
