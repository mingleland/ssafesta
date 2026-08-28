# Unity Dedicated Server — Docker 가이드


## ⚡ 먼저: 손으로 하지 마라 — `Festa/배포/` 메뉴가 정본이다 (S15P21A604-311)

에디터 메뉴 **`Festa/배포/배포 빌드 (Linux 서버 + WebGL + Docker 이미지)`** 하나가
서버 빌드 → 웹 빌드 → 이미지 빌드를 한 흐름으로 처리한다. 아래 수동 절차는
**메뉴가 막혔을 때의 폴백**이자 무슨 일이 일어나는지 읽는 용도다.

수동으로 할 때 빠뜨리기 쉬운 것 두 가지 — 메뉴는 둘 다 처리한다:

- **웹만 뽑고 서버를 잊는다(또는 반대).** 스폰 위치처럼 **서버 권위**인 값은 WebGL 을
  다시 뽑아도 반영되지 않는다. 증상이 "고쳤는데 그대로"로 나와 추적에 시간을 다 쓴다.
- **측정용 빌드를 배포한다.** `Festa/부하테스트/WebGL 빌드` 는 Development ON 이라
  F3/F6 계측 도구가 살아 있다 — 실사용자가 아바타 40기를 소환할 수 있다.

이미지에는 태그가 둘 붙는다: `festa-world:dev` 와 `festa-world:<git-sha>`.
커밋되지 않은 변경이 섞였으면 `-dirty` 가 붙는다 — 지금 도는 컨테이너가 어느 코드인지
답할 수 있어야 하고, 재현 불가를 숨기지 않기 위해서다.

코드는 그대로고 이미지만 다시 말면 되면 **`Festa/배포/Docker 이미지만 갱신`** 을 쓴다.

---

## (참고) 수동 절차

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
