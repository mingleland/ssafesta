# Game Studio Preview Protocol

> 상태: v1.2 / same-origin local Preview route 구현. 아래 message envelope는 embedded Preview 확장 계약이다.

## 목적

Studio의 현재 GameProject snapshot을 Published API에 올리지 않고 Web Runtime에 전달한다.
Preview Runtime은 Published Runtime과 같은 validator/state/event core를 사용한다.

## 구현된 Local Preview transport

- Editor는 현재 snapshot을 동일한 validator로 확인하고 브라우저 Draft repository에 저장한다.
- `/app/games/:gameId/play?source=local`로 이동하며 Runtime은 저장본을 다시 읽고 다시 검증한다.
- Editor와 Play는 별도 lazy chunk이고, Runtime은 Studio store/DOM/undo history를 직접 읽지 않는다.
- GameProject, Asset reference, preview session port만 공유하며 Backend Published pointer와 revision은 변경하지 않는다.
- 인증 Token, Asset binary, 브라우저 파일 경로를 URL이나 GameProject에 넣지 않는다.

## Embedded Preview transport (후속 선택)

- 브라우저 `window.postMessage`를 사용한다.
- Studio가 parent, Preview Runtime이 전용 iframe이다.
- Studio와 Preview Runtime은 `festa-frontend`의 same-origin lazy module이다.
- `targetOrigin="*"`를 사용하지 않고 `window.location.origin`의 정확한 origin을 사용한다.
- 수신자는 `event.origin === allowedStudioOrigin`과 `event.source === parent/knownIframe`을 모두 검사한다.
- payload는 2 MiB 이하의 JSON 직렬화 가능 데이터만 허용한다. Asset binary와 Token은 포함하지 않는다.

## Envelope

```json
{
  "contractVersion": "1.0.0",
  "type": "PREVIEW_LOAD",
  "requestId": "preview_01JXYZ",
  "payload": {}
}
```

| Field | Rule |
|---|---|
| `contractVersion` | Preview protocol version. 지원하지 않는 major는 거부 |
| `type` | 아래 허용 message type 중 하나 |
| `requestId` | 한 Preview lifecycle 안에서 요청·응답을 연결하는 opaque ID |
| `payload` | message type별 typed object |

## Lifecycle

```text
iframe load
→ Runtime PREVIEW_READY
→ Studio PREVIEW_LOAD(snapshot)
→ Runtime validate + initialize
→ PREVIEW_LOADED | PREVIEW_ERROR
→ play
→ Studio PREVIEW_CLOSE 또는 Runtime user close
→ PREVIEW_CLOSED
```

### Runtime → Studio: `PREVIEW_READY`

```json
{
  "contractVersion": "1.0.0",
  "type": "PREVIEW_READY",
  "requestId": "preview_01JXYZ",
  "payload": {
    "supportedGameSchemaMajors": [1]
  }
}
```

### Studio → Runtime: `PREVIEW_LOAD`

```json
{
  "contractVersion": "1.0.0",
  "type": "PREVIEW_LOAD",
  "requestId": "preview_01JXYZ",
  "payload": {
    "project": "<GameProject v1 object>"
  }
}
```

Runtime은 수신 object를 자기 snapshot으로 복제한다. Studio store를 직접 참조하거나 Preview 중 변경을
자동 반영하지 않는다. 새 변경은 새 `requestId`의 `PREVIEW_LOAD`로 다시 시작한다.

### Runtime → Studio: `PREVIEW_LOADED`

```json
{
  "contractVersion": "1.0.0",
  "type": "PREVIEW_LOADED",
  "requestId": "preview_01JXYZ",
  "payload": {
    "startSceneId": "room"
  }
}
```

### Runtime → Studio: `PREVIEW_ERROR`

```json
{
  "contractVersion": "1.0.0",
  "type": "PREVIEW_ERROR",
  "requestId": "preview_01JXYZ",
  "payload": {
    "code": "START_SCENE_NOT_FOUND",
    "message": "시작 Scene을 찾을 수 없습니다.",
    "path": "/startSceneId"
  }
}
```

Provider raw error, stack trace, Token은 전송하지 않는다. 오류는 해당 iframe lifecycle만 실패시키며
Studio, FESTA Host, Unity WebGL을 reload/close하지 않는다.

### Close

- `PREVIEW_CLOSE`: Studio가 현재 Preview 종료를 요청한다. payload는 빈 object다.
- `PREVIEW_CLOSED`: Runtime이 입력 listener·animation frame·audio를 정리했음을 알린다.
- 2초 안에 `PREVIEW_CLOSED`가 없으면 Studio는 iframe을 제거할 수 있으나 다른 overlay는 닫지 않는다.

## Security Invariants

1. Access/Refresh/Connection Token을 message payload에 넣지 않는다.
2. `project.assets[].source`는 `builtin://` 또는 resolver가 관리하는 `asset://` reference만 사용한다.
   `asset://local/`은 local Preview 전용이며 Publish에서 거부한다. binary, base64 `data:`, `blob:`,
   `file:`, 만료되는 서명 URL은 snapshot에 포함하지 않는다.
3. Runtime은 message의 `gameId`나 owner 정보를 권한 근거로 사용하지 않는다.
4. iframe Runtime은 parent DOM과 Unity instance를 직접 조작하지 않는다.
5. 반복/늦게 도착한 이전 `requestId` 응답은 현재 lifecycle 상태를 변경하지 않는다.
6. Preview와 Published Runtime은 같은 Asset resolver를 사용하며 Studio DOM이나 로컬 파일 선택 상태를 읽지 않는다.

## 확정값과 후속 선택

- Studio/Preview origin: FESTA Web과 동일. 별도 인증 전달이나 Preview origin 환경변수 없음.
- Authoring route: `/app/games/:gameId/edit`.
- Published play route: `/app/games/:gameId/play`.
- 현재 수직 구현은 full route Local Preview다. embedded iframe이 실제로 필요해질 때만 이 문서의 message
  lifecycle과 함께 내부 route, `sandbox`/CSP, modal/side panel UI를 확정한다.
- 환경변수가 추가되면 기존 API 값과 섞지 않고 `VITE_GAME_*` namespace를 사용한다.
