# Mock HTTP API (정적 JSON)

Spring 없이 `HttpBoothApiClient`를 검증하기 위한 정적 endpoint.
`api/v1/booths/7/layouts/published` 파일이 실제 API 경로 모양 그대로 배치되어 있다.
`api/v1/booth-slots/5/layouts/published` 는 visitor 경로(slot 기준, S15P21A604-103) 검증용 —
슬롯 5 를 부스 7 이 임차 중인 상황이다 (MockBoothApiClient 의 MockDetailJson 과 동일 설정).
다른 슬롯은 파일이 없으므로 404 = 미게시 경로를 그대로 재현한다.

## 사용법 — 같은 오리진 서빙 (CORS 회피)

Web 빌드를 서빙하는 폴더에 `api/`를 복사해서 **웹페이지와 같은 오리진**(localhost:8000)으로 제공한다.
브라우저에서 cross-origin 요청은 CORS 헤더가 필요하지만, 같은 오리진이면 불필요하다.

```powershell
# 1. api 폴더를 웹 서빙 폴더로 복사 (Web 재빌드 후에도 다시 실행)
robocopy Tools\mock-api\api Builds\web\api /E

# 2. 웹 서버 실행 (이미 켜져 있으면 생략)
py -m http.server 8000 --directory Builds/web   # python 별칭이 안 잡히면 py 사용

# 3. 브라우저로 직접 확인 (JSON이 보여야 함)
#    http://localhost:8000/api/v1/booths/7/layouts/published
```

- ApiConfig의 Local 환경 springBaseUrl이 `http://localhost:8000`인 이유가 이것 (웹페이지와 동일 오리진)
- **에디터**에서도 같은 URL로 검증 가능 (에디터는 CORS 제약 없음)
- 404 테스트: 존재하지 않는 boothId(예: 99)로 요청하면 웹 서버가 404 반환 → graceful 처리 확인
- 실제 Spring이 준비되면 ApiConfig의 base URL만 바꾸면 된다 (코드 변경 없음)

---

## world-session 발급 (S15P21A604-333) — 브라우저에서 접속하려면 이게 필요하다

**정적 파일만으로는 WebGL 에서 월드에 접속할 수 없다.** S15P21A604-85 로 게임 서버가 입장 토큰의
HS256 서명을 검증하고 `GrantReplayLedger` 가 `jti` 재사용을 거부하므로, world-session 응답은
**매번 새로 서명된 토큰**이어야 한다 — 정적 JSON 으로는 원리적으로 불가능하다(한 번은 통해도
두 번째 접속에서 원장이 막는다).

시크릿을 WebGL 빌드에 박는 것은 답이 아니다 — 그러면 누구나 grant 를 만들 수 있어 입장 토큰
체계가 무의미해진다. 실제 배포에서도 서명은 BE 가 한다. `serve.mjs` 는 그 자리를 개발 중에만
대신하며 **시크릿은 서버 프로세스에만 있고 브라우저로 나가지 않는다.**

```bash
# 게임 서버와 같은 시크릿을 써야 한다 — 다르면 서명 검증에서 거부된다.
export CONNECTION_TOKEN_SECRET="$(head -c 32 /dev/urandom | base64 -w0)"

# 1) 게임 서버 (같은 시크릿)
CONNECTION_TOKEN_SECRET="$CONNECTION_TOKEN_SECRET" ./festa-bot.exe -server -port 7777 -batchmode -nographics

# 2) 목 서버 = 정적 서빙 + world-session 발급 (WebGL 산출물과 같은 오리진)
node festa-unity/Tools/mock-api/serve.mjs --root festa-unity/Builds/web --port 8000
```

옵션: `--world-host` `--world-port` `--world-scheme`(기본 `127.0.0.1` / `7777` / `ws`).

**Unity 쪽 설정** — `ApiConfig` 의 `useMockApi` 를 끄고 `springBaseUrl` 을 이 서버(같은 오리진)로
둬야 `HttpUserApiClient` 가 이 엔드포인트를 쓴다. `useMockApi` 가 켜져 있으면 `MockUserApiClient`
경로로 가고, 그쪽은 환경변수가 필요해 **브라우저에서는 실패한다**(S15P21A604-331).

시크릿이 없으면 서버는 **뜨지 않는다**(exit 78). 조용히 가짜 토큰을 주면 게임 서버 로그에
"JWT 형식이 아니다" 만 남아 진짜 원인(키 없음)이 가려지기 때문이다 (T-24).
