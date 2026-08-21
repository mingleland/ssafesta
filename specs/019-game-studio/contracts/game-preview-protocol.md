# Game Studio Preview Protocol

> 상태: Draft v1.0 / #20에서 앱 origin과 iframe 배치 방식만 확정한다.

## 목적

Studio의 저장 전 GameProject snapshot을 Published API에 올리지 않고 격리된 Web Runtime에 전달한다.
Preview Runtime은 Published Runtime과 같은 validator/state/event core를 사용한다.

## Transport

- 브라우저 `window.postMessage`를 사용한다.
- Studio가 parent, Preview Runtime이 전용 iframe이다.
- `targetOrigin="*"`를 사용하지 않고 환경 설정의 정확한 Runtime origin을 사용한다.
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
2. `project.assets[].source`는 `builtin://` 또는 서버가 관리하는 `asset://` reference만 사용한다.
   binary, base64 `data:`, `blob:`, `file:`, 만료되는 서명 URL은 snapshot에 포함하지 않는다.
3. Runtime은 message의 `gameId`나 owner 정보를 권한 근거로 사용하지 않는다.
4. iframe Runtime은 parent DOM과 Unity instance를 직접 조작하지 않는다.
5. 반복/늦게 도착한 이전 `requestId` 응답은 현재 lifecycle 상태를 변경하지 않는다.
6. Preview와 Published Runtime은 같은 Asset resolver를 사용하며 Studio DOM이나 로컬 파일 선택 상태를 읽지 않는다.

## #20 결정 후 채울 값

- Studio origin / Preview origin 환경변수 이름
- same-origin route와 별도 Runtime 배포 중 선택
- iframe `sandbox`/CSP 최종값
- Preview UI가 modal, side panel, full-screen 중 어느 형태인지
