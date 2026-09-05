# 월요일(2026-09-08) 게임 서버 수동 배포 런북 — 게임 파트

> 인프라 CI(Unity Agent)는 아직 오프라인(#124). 이 문서는 **수동 경로**로 `world.ssafesta.world` 에 게임 서버를 올리고 WebGL 을 서빙하기까지의 순서다. 각 단계에 "누가 / 무엇을 미리 받아야 하나" 를 적었다. 시크릿 값은 어디에도 쓰지 않는다.

## ⚠ 2026-09-05 오후 통합 실측에서 나온 **차단 항목** (이게 먼저다)

| # | 항목 | 상태 | 어디에 |
|---|---|---|---|
| — | ~~릴리스 WebGL 클라이언트가 게임 서버에 승인되지 않는다~~ → **빌드 결함 아님.** 자동화 브라우저 탭이 hidden 상태라 Chrome 이 rAF 를 멈춰 Unity 루프가 정지했던 것. 보이는 탭에서는 Docker 서버(`festa-world:dev`=60b8cd42)에 승인·스폰·이동 정상 | ✅ 해결(환경 요인) — 단, **실사용자도 탭을 30초 뒤로 보내면 끊긴다**(T-120, 재접속 UX 미결) | docs/25 **T-115**·**T-120**, Jira -420 |
| ☆ | 스태프 NPC 2기에 벤더 데모 컨트롤러 잔존(매 프레임 경고·입력 가로채기) | ✅ 씬에서 제거 (Jira -425, MR) | T-119 |
| ☆ | 로비 → main 씬 전환 50~84초(WebGL) — 로딩 표시 없음 | 🟡 FE 안내(-429, !273)는 Unity 신호 `onWorldLoadStart`(!280) 로 켜짐 — develop head 빌드에서 확인. 단계별 소요는 `[WorldLoadTimeline] 요약` 콘솔 한 줄(!291)로 읽는다 | #129, -431 |
| ★ | 씬 배치 상호작용(관리 데스크·오락기) F 무반응 | ✅ !281 (T-122) — develop head 빌드로 F 확인 필요 | -435 |
| ☆ | 회전 감도·앉기 이름표·벽 조명 팝인 | ✅ !282 / 걷기 끊김은 계측 대기(F3 최대 프레임) | -436 |
| ☆ | Overlay 열림 중 월드 입력·React 입력창 타이핑 | ✅ Unity 측 !279(`InputBridge`, `captureAllKeyboardInput=false`) — FE `OverlayHost` 배선 대기 | #132, -434 |
| ☆ | 백그라운드 복귀 재접속 | ✅ Unity `WorldReconnector`(!284·!288, 5회 ~67초) — 서버 재기동 실측으로 루프 확인. FE 안내 UI·수신부는 FE(#131) | #131, -432 |
| ☆ | 실테스트 2차(23:00): 오락기 중 조작 잠금·게스트 로비 생략·외곽선 하이라이트(부위 한정)·조명 히스테리시스 | ✅ !286 — 게스트 즉시 입장·NPC 외곽선·F→관리 화면 패널 실측 확인 | -437 |
| ☆ | 걷기 끊김 (최대 프레임 14~52 ms, GC/1s 1) | ✅ 원인 확정 — **Development 빌드의 IMGUI 개발 도구**(접속 패널·PerfHud)가 ≈1.3 MB/s 할당 → 8 MB 힙에서 초당 GC 1회. 릴리스에는 없는 비용. !290 으로 접속 후 패널 숨김(F2)·PerfHud 기본 숨김(F3). 재빌드에서 GC/1s 0 확인 | -437 ⑤ |
| ★ | 캔버스 초기 포커스 — `captureAllKeyboardInput=false` 이후 첫 키 입력이 무시됨(클릭 후 정상) | 🟡 FE 가 인스턴스 준비 시 `canvas.focus()` (#132 요청) | #132 |
| ☆ | 게스트 데스크 F → "부스 정보를 불러오지 못했습니다"(로그인 안내여야 함) | 🟡 FE 결함 보고 | #128 |
| ☆ | 설문 키오스크 E2E(-415) | 🟡 슬롯 1 게시본에 SURVEY_KIOSK 없음 — 스튜디오에서 배치·게시 후 확인 | -415 |
| ★ | FE `UnityHost.tsx` canvas 에 `id` 없음 → Unity `_main` 크래시 | 🟡 MR !259 (FE 리뷰 머지 필요) | #128 §1, T-114 |
| ★ | FE 가 게스트에게 AT 를 안 넘김 → 배포에서 게스트 Unity 는 카탈로그·world-sessions 401 | 🟡 FE 판단 필요 (권장: guest 도 전달) | #128 §2, T-116, docs/26 ③ |
| ☆ | FE 60초 게이트 타임아웃이 로비 체류 중 만료 | 🟡 FE 판단 | #128 §3, T-117 |
| ☆ | manifest 상대 URL 을 FE 가 해석하지 않음 | 🟡 FE 판단 (권장: `new URL(v, BUILD_BASE+'/')`) | #127 코멘트, #128 §4, T-118 |
| ☆ | 배포 빌드 ApiConfig 복원 누락 | ✅ !257 머지 — 다음 빌드에서 `[Release] ApiConfig 복원` 로그 확인 | T-113 |

**★ 셋이 닫히지 않으면 월요일 게스트 사용자 테스트는 불가능하다.** 첫 항목은 게임 파트가 계속 본다.

### 로컬 통합 스택 재현 레시피 (2026-09-05 검증)

```bash
# 1) DB/캐시
docker compose -f backend/compose.yaml --env-file backend/.env up -d
# 2) Spring (컨테이너 maven, JDK21). Git Bash 는 MSYS_NO_PATHCONV=1 필수 — 아니면 -w /app 이 C:/Program Files/Git/app 으로 바뀐다
cd backend && MSYS_NO_PATHCONV=1 docker run -d --name festa-spring -v "$(pwd -W):/app" -w /app -v ssafesta-m2:/root/.m2 \
  --env-file .env -e POSTGRES_HOST=host.docker.internal -e REDIS_HOST=host.docker.internal \
  -e WORLD_HOST=127.0.0.1 -e WORLD_PORT=7777 -e WORLD_SCHEME=ws -e FRONTEND_BASE_URL=http://localhost:5173 \
  -p 8080:8080 maven:3.9-eclipse-temurin-21 mvn -B -q spring-boot:run && cd ..
# 3) AI + MinIO
docker build -t festa-ai:local festa-ai && docker run -d --name festa-ai-local --env-file festa-ai/.env -p 8001:8000 festa-ai:local && docker start festa-minio
# 4) 게임 서버 — BE 와 같은 시크릿, 원장 경로는 /app (기본 /var/lib/festa-world 는 쓰기 불가 → exit 78)
docker run -d --name festa-world-01 -p 7777:7777 -e CONNECTION_TOKEN_SECRET="$(grep ^CONNECTION_TOKEN_SECRET= backend/.env | cut -d= -f2-)" -e WORLD_REPLAY_LEDGER=/app/used-grants.log festa-world:dev
# 5) WebGL (Local env 빌드) — Spring 프록시 + FE 오리진 CORS
cd festa-unity/Tools/mock-api && node serve.mjs --root ../../Builds/web-release --port 8000 --spring http://localhost:8080 --allow-origin http://localhost:5173
# 6) FE
cd festa-frontend && npm run dev -- --port 5173 --strictPort     # .env: VITE_API_BASE_URL=http://localhost:8080, VITE_UNITY_BUILD_BASE=http://localhost:8000
```

브라우저는 **`http://localhost:5173`** 으로(Spring CORS 허용 오리진). 확인: `docker logs festa-world-01 | grep Approved`.

## 0. 주말 중 받아야 하는 것 (없으면 월요일 진행 불가)

| # | 항목 | 누가 | 확인 방법 |
|---|---|---|---|
| A | Cloudflare A 레코드 `world`·`demo`·`api.ssafesta.world` → EC2 | 도메인 소유자 | `nslookup world.ssafesta.world 1.1.1.1` 이 EC2 IP 를 돌려준다 (사내 리졸버는 캐치올이라 믿지 않는다 — T-110) |
| B | EC2 접근 (ssh 또는 배포 계정) | 정승욱 | `ssh` 로그인 |
| C | `CONNECTION_TOKEN_SECRET` 파일 위치 — BE 와 **같은 값** | 정승욱·BE | `/opt/festa/secrets/<이름>` 존재, 32바이트 이상 |
| D | 게임 이미지 전달 방법 (tar / 레지스트리) | 정승욱 | GitLab #52 회신 |
| E | WebGL 정적 경로·URL (`VITE_UNITY_BUILD_BASE`) | 정승욱·FE | GitLab #127 회신 |
| F | OAuth 앱 리디렉트 URI·시크릿 (회원 로그인) | BE | 이 런북 범위 밖 — 없으면 게스트만 |

## 1. 빌드 (로컬, develop head)

```bash
cd festa-unity && git fetch origin develop && git checkout origin/develop --detach
```

에디터 메뉴 **`Festa/배포/배포 빌드 (Linux 서버 + WebGL + Docker 이미지)`** — 다이얼로그에서 "빌드만" 선택. 산출물:

- `Builds/linux-server/festa-unity.x86_64` (+ `.dockerignore`)
- `Builds/web-release/` — `index.html`, `Build/`, `TemplateData/`, **`manifest.json`** (Brotli + Decompression Fallback ON)
- Docker 이미지 `festa-world:<sha>` (작업 트리가 깨끗해야 `-dirty` 가 안 붙는다)

확인:

```bash
ls festa-unity/Builds/web-release/manifest.json && cat festa-unity/Builds/web-release/manifest.json
docker images festa-world --format '{{.Tag}} {{.Size}}' | head -2
```

`manifest.json` 의 4개 URL 이 `Build/` 의 실제 파일명과 일치해야 한다(파일명은 해시).

## 2. 이미지 전달 (D 에 따라 택 1)

```bash
# tar 전달
docker save festa-world:<sha> | gzip > festa-world-<sha>.tar.gz     # ≈ 430 MB → 압축 후 더 작다
scp festa-world-<sha>.tar.gz <ec2>:/tmp/
ssh <ec2> 'gunzip -c /tmp/festa-world-<sha>.tar.gz | docker load'
```

## 3. 게임 서버 기동 (EC2)

`infra/unity-server/compose.yaml` 을 쓴다 — **dev 쪽 `infra/deploy/compose/dev/game.compose.yaml` 은 시크릿·볼륨이 없어 exit 78 로 죽는다(#126).**

```bash
cd ~/festa/S15P21A604 && git pull
cp infra/unity-server/.env.example infra/unity-server/.env      # 값 채우기 (아래)
bash infra/unity-server/scripts/preflight.sh                    # image ref·Secret 파일·network·volume·7777 비공개 확인
docker compose --env-file infra/unity-server/.env -f infra/unity-server/compose.yaml up -d
docker logs -f festa-world-01 | head -40                        # "Approved" 전까지 exit 78 없이 대기 상태여야 한다
```

`infra/unity-server/.env` 에 채울 것 (값은 서버에만):

```ini
GAME_IMAGE_REF=festa-world:<sha>
ROOT_DOMAIN=ssafesta.world
CONNECTION_TOKEN_SECRET_FILE=/opt/festa/secrets/<C 에서 정한 이름>
DEMO_NETWORK_NAME=festa-demo
WORLD_REPLAY_LEDGER_VOLUME=festa-demo-world-replay
```

## 4. Nginx — `world.ssafesta.world` 443 → 7777

```bash
envsubst '${ROOT_DOMAIN} ${GAME_UPSTREAM_HOST} ${GAME_UPSTREAM_PORT}' \
  < infra/unity-server/nginx/world.conf.template > /etc/nginx/sites-available/world.conf
ln -sf /etc/nginx/sites-available/world.conf /etc/nginx/sites-enabled/world.conf
nginx -t && systemctl reload nginx
```

TLS 인증서는 `certbot --nginx -d world.ssafesta.world` (ci 와 같은 방식, JSW/26 §7 참조).

## 5. 종단 실측 (S15P21A604-83 완료 조건)

```bash
bash infra/unity-server/scripts/verify-public-wss.sh --approval-evidence <PASS file> --output infra/unity-server/evidence/2026-09-08-wss.md
```

- 브라우저 2개(회원 1 + 게스트 1)로 접속 → 서로 이동 보임
- 10분 무입력 후 재이동
- 한쪽 탭 강제 종료 → 서버 로그에 정리 → 재접속

## 6. WebGL 서빙 + FE

E 가 정해지면 (#127):

```bash
rsync -av --delete festa-unity/Builds/web-release/ <ec2>:/srv/festa/webgl/current/   # 경로는 #127 확정값
```

Nginx `location /unity/` 에 `.wasm → application/wasm`, 해시 파일 `immutable`, `manifest.json`·`index.html` `no-cache`. Brotli 산출물(`.br`)은 `Content-Encoding: br` 이 있으면 빠르고, 없어도 Fallback 으로 뜬다(느림).

FE 이미지는 `VITE_UNITY_BUILD_BASE=<E 의 URL>` 로 **빌드 시점**에 박아야 한다(런타임 주입은 `apiBaseUrl` 만). FE 가 런타임 주입으로 바꾸면 재빌드 없이 된다.

## 7. 게임 파트 실측 (오후 사용자 테스트 전)

| 도구 | 키 | 재는 것 |
|---|---|---|
| `PerfHud` | F3 토글(기본 숨김) / F4 리셋 / F9 아바타 LOD | 프레임 ms·힙·GC·드로우콜·접속 인원 — **접속 패널(F2)은 꺼둔 채 잰다**(둘 다 켜면 IMGUI 할당으로 GC/1s 1 이 계측 오염) |
| `DevConnectionHud` | F2 토글(접속 후 기본 숨김) | Host/Server/Client 직접 접속·Disconnect (Development 빌드·에디터만) |
| `WorldLoadTimeline` | 없음 — 브라우저 콘솔 `[WorldLoadTimeline] 요약` | 로비 입장 클릭 → main 로드 → 세션 → StartClient → 승인 → 스폰 → 게이트 개방 단계별 초 |
| `AvatarStressSpawner` | F6 +1 / F7 −1 / F8 전체 제거 | 내 화면의 원격 아바타 N기 비용 (접속 없이) |
| `LoadTestBot` | `festa.exe -batchmode -nographics -bot -addr world.ssafesta.world -port 443 -botName botNN` | 인원수별 수신 bytes/s (RNSM 과 함께) — 1→10→20→30→40 (-206) |

합격선: 데스크톱 WebGL2 에서 프레임 유지, 아바타당 렌더러 7(T-236), 40명 구간 10분 유지.

## 8. 사용자 테스트 범위 (오후)

발표 흐름 중 지금 돌아가는 것: 로그인 → 월드 입장·이동 → 임대 → 스튜디오 → 퍼블리시 → 부스 방문·프로젝트 패널·AI NPC 진입·설문 키오스크 진입·관리 데스크. **AI 상담 답변·설문 제출·사람 상담은 담당 파트 진척에 따른다.**

수집: `PerfHud` 스크린샷, 브라우저 콘솔 에러, 접속 실패 사례, 로비→월드 진입 시간(콘솔 `[WorldLoadTimeline] 요약` 줄 복사).
