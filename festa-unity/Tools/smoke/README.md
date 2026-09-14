# Linux headless 승인 접속 smoke runner

`approvedAdmission` 판정기다. infra-003 승격 게이트의 네 조건 중 **밖에서는 볼 수 없는 것 하나**를 맡는다
(S15P21A604-675, GitLab #185).

| 조건 | 누가 본다 |
|---|---|
| `processRunning` | 인프라 |
| `internalListener` | 인프라 |
| `externalWebSocket` (101) | 인프라 |
| **`approvedAdmission`** | **이 실행기** |

앞의 셋은 "포트가 열려 있다" 까지다. 실제로 **새 grant 를 받아 공개 wss 로 들어가 Connection Approval 을
통과하고 플레이어가 생성되는지**는 사용자처럼 들어가 봐야 안다.

## 빌드

```bash
Unity -batchmode -nographics -projectPath festa-unity \
  -executeMethod Festa.EditorTools.CiBuild.Build -festaTarget linux-smoke
```

**`-quit` 을 붙이지 마라.** 유니티가 빌드 도중 종료 요청을 받아들여 셰이더 변형을 굽다 말고 내려가면,
`BuildPipeline.BuildPlayer` 가 반환하지 않아 결과 단언이 통째로 건너뛰어지고 **종료 코드 0 에 산출물 없음**이 된다 —
실패가 성공으로 보인다. 2026-09-14 Jenkins 첫 실행이 정확히 그랬다(`[9799s] 2784/3584 variants ready` 뒤로
결과 로그 없음, exit 0, 산출물 없음). 종료 코드는 `CiBuild` 가 `EditorApplication.Exit` 로 직접 정한다.
지금은 `-quit` 이 있으면 빌드를 시작하지 않고 그 이유를 찍으며 실패한다.

`festa-unity/ci/build --target linux-smoke` 를 쓰면 인자를 맞출 필요가 없다 — 이 규칙이 이미 들어 있다.

산출물: `festa-unity/Builds/linux-smoke/festa-world-smoke.x86_64`

**서버가 아니라 클라이언트 빌드다.** 서버 서브타깃으로 구우면 `UNITY_SERVER` 가 정의되어 접속하는 쪽이
아니라 받는 쪽이 된다. 필요한 라이선스 모듈은 `linux64_player_nondevelopment_mono` 다.

## 실행

```bash
./festa-world-smoke.x86_64 \
  -batchmode -nographics -smoke-admission \
  -api-base-url https://api.ssafesta.world \
  -world-host world.ssafesta.world \
  -world-port 443 \
  -world-scheme wss \
  -timeout-seconds 90 \
  -output /workspace/artifacts/approved-admission.json
```

`-smoke-admission` 이 없으면 이 코드는 **아무 일도 하지 않는다** — 일반 빌드에 영향이 없다.

| 인자 | 필수 | 뜻 |
|---|---|---|
| `-api-base-url` | ✅ | 게스트 토큰·world-session 을 받을 API 주소 |
| `-output` | ✅ | 결과 JSON 경로 (디렉터리는 알아서 만든다) |
| `-world-host` | | 기대하는 endpoint host. 다르면 `INVALID_ENDPOINT` |
| `-world-port` | | 기대하는 port |
| `-world-scheme` | | `wss` 기대. `ws` 로 오면 실패 |
| `-timeout-seconds` | | 기본 90 |

`-world-*` 를 주는 이유는 **엉뚱한 곳에 붙어 통과하는 것을 막기 위해서다.** 이것을 비워 두면
loopback·mock 으로 붙어도 PASS 가 나와 승격 판정이 거짓말이 된다.

## 흐름

```
POST /api/v1/auth/guest      → accessToken (실행할 때마다 새로 받는다)
POST /api/v1/world-sessions  → endpoint + connectionToken
endpoint 검사                 → 기대한 공개 경로인가
StartClient(session, ...)    → 실제 wss 접속
connected 대기               → NetworkManager 연결 확인
player_ready 대기            → LocalClient.PlayerObject 생성 확인
```

## 종료 코드

| 코드 | 뜻 | reasonCode |
|---|---|---|
| 0 | `connected` + `player_ready` 까지 성공 | — |
| 1 | 승인 거부 · 플레이어 미생성 | `CONNECTION_APPROVAL_REJECTED` · `PLAYER_NOT_READY` |
| 2 | 게스트 토큰 또는 world-session 발급 실패 | `SESSION_ISSUE_FAILED` |
| 3 | endpoint · TLS · WebSocket | `INVALID_ENDPOINT` · `WEBSOCKET_FAILED` |
| 4 | 제한시간 초과 | `TIMEOUT` |

## 결과 파일

성공:

```json
{
  "result": "PASS",
  "approvedAdmission": "PASS",
  "connected": "PASS",
  "playerReady": "PASS",
  "endpoint": "world.ssafesta.world",
  "channelId": "11F-01",
  "checkedAt": "2026-09-13T14:00:00Z"
}
```

실패:

```json
{
  "result": "FAIL",
  "approvedAdmission": "FAIL",
  "reasonCode": "CONNECTION_APPROVAL_REJECTED",
  "checkedAt": "2026-09-13T14:00:00Z"
}
```

**비밀은 결과 파일에도 로그에도 남기지 않는다.** Access/Connection/Refresh Token, World Entry Grant
원문, JTI, Secret, Authorization 헤더, nickname, avatar payload, 환경변수, private key — 어느 것도
기록 대상이 아니다. 남기는 것은 판정과 **어디에 붙었는지**(host·channelId) 뿐이다.

## 구현

`festa-unity/Assets/_Project/Scripts/Diagnostics/AdmissionSmokeRunner.cs`

빌드 타깃은 `Assets/_Project/Scripts/Editor/CiBuild.cs` 의 `linux-smoke`.
