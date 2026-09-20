# WebGL 로컬 접속 복구 실측 (S15P21A604-333)

측정일 2026-08-31 · 구성: 게임 서버(봇 빌드 `-server`, 7777) + 목 서버(`serve.mjs`, 8000) + WebGL Development 빌드
· **셋이 같은 `CONNECTION_TOKEN_SECRET`** (게임 서버·목 서버만. 브라우저에는 주지 않는다)

## 배경

S15P21A604-85 로 게임 서버가 HS256 서명을 검증하게 된 뒤 **브라우저에서 로컬 접속이 불가능**했다.
S15P21A604-331 은 에디터·데스크톱만 살렸다 — WebGL 에는 환경변수가 없어 클라이언트가 서명할 수 없다.
시크릿을 빌드에 박는 것은 답이 아니므로(누구나 grant 를 만들 수 있다) **목 서버가 BE 자리를 대신한다.**

## 결과 — 전 구간 통과

```
[mock-api] world-session 발급 jti=mockapi-1788140188-0-vpmm6b4u
[ConnectionManager] Approved client=1 slot=0 pos=(-29.50, 0.18, -248.00) nickname=MockUser

[mock-api] world-session 발급 jti=mockapi-1788140355-1-r4i5xxfp
[ConnectionManager] Client 1 disconnected, session cleaned (남은 접속 0)
[ConnectionManager] Approved client=2 slot=0 pos=(-29.50, 0.18, -248.00) nickname=MockUser
```

| 완료 조건 | 결과 |
|---|---|
| 목 서버 토큰이 Unity 검증 통과 | ✅ 서버가 `Approved` 로 승인 |
| WebGL 빌드가 실제 접속·스폰 | ✅ 월드 진입, RTT 18 ms, 119 FPS |
| 연속 2회 접속 (jti 재사용 원장) | ✅ **서로 다른 `jti` 로 2회 승인** — 원장에 걸리지 않았다 |

브라우저는 `ApiConfig.useMockApi = 0` + `springBaseUrl = http://localhost:8000`(같은 오리진)으로
`HttpUserApiClient` 경로를 탄다. 부스 레이아웃 정적 JSON 도 같은 서버가 준다
(`HttpBoothApiClient` 가 슬롯 5 만 200, 나머지는 404 — 목 데이터 설계 그대로다).

## 곁들여 확인한 것 — S15P21A604-334 (릴리즈 재확인 대신)

같은 빌드에서 **상의 "없음" → 재장착** 을 태웠고 **검은 얼룩이 없다.** `-334` 수정이 WebGL 에서도
성립한다. (⚠ 이 빌드는 **Development** 다. 릴리즈 확인은 다음 배포 빌드에서 한 번 더 한다.)

## 남은 것 — S15P21A604-305

NPC·노트북 클릭 payload 검증은 **부스 슬롯 5 내부까지 걸어가야** 한다. 슬롯 5 published 에
`AI_AGENT`(ai-1 configId 4, ai-2 configId 5)·`LAPTOP`(laptop-1 configId 8)이 모두 있어 데이터는
준비돼 있다. 자동화의 키 입력이 **탭(누르고 떼기)** 이라 WASD 이동이 사실상 불가능해 중단했다 —
사람이 30초면 걸어갈 거리다.

**확인 방법:** 위 3개 프로세스를 띄운 상태로 슬롯 5 부스에 들어가 NPC·노트북을 클릭하고
브라우저 콘솔에서 `[BoothInteractBridge] onBoothInteract → {...}` 줄의 3필드를 본다.

## 재현 절차

```bash
export CONNECTION_TOKEN_SECRET="$(head -c 32 /dev/urandom | base64 -w0)"

# 1) 게임 서버
cd festa-unity/Builds/bot
CONNECTION_TOKEN_SECRET="$CONNECTION_TOKEN_SECRET" ./festa-bot.exe -server -port 7777 -batchmode -nographics

# 2) 목 서버 = 정적 서빙 + world-session 서명 발급 (WebGL 산출물과 같은 오리진)
node festa-unity/Tools/mock-api/serve.mjs --root festa-unity/Builds/web-verify --port 8000

# 3) 브라우저에서 http://localhost:8000/index.html
```

⚠ 빌드 전에 `ApiConfig.useMockApi` 를 **0** 으로 둬야 한다. **이 값은 커밋하지 않는다** —
`1`(Mock)이 커밋 기본값이어야 에디터가 서버 없이도 돈다.
