# SSAFY FESTA Realtime 통신 명세서

> **문서 목적**: 실시간성이 필요한 기능을 통신 종류별로 분리하고 이벤트 계약을 정의한다.  
> **원칙**: 모든 데이터를 WebSocket/게임 네트워크로 보내지 않는다. 영구 CRUD는 REST, AI Stream은 SSE 우선, Player 상태는 NGO, 사람 상담·Presence는 Spring Realtime을 사용한다.

---

## 1. 통신 분류

| 기능 | 통신 | Source of Truth |
|---|---|---|
| 일반 CRUD | HTTPS REST | Spring |
| Player 위치/회전 | NGO + Dedicated Server | Unity Server |
| Player Spawn/Despawn | NGO | Unity Server |
| World/Channel 접속 | REST + NGO | Spring Session + Unity Server |
| Booth Layout | REST | Spring |
| AI 응답 | SSE 우선 | FastAPI |
| AI 문서 상태 | REST Polling 우선 | FastAPI/Spring |
| Staff Presence | WebSocket 후보 | Spring + Redis |
| 사람 상담 메시지 | WebSocket 후보 | Spring |
| 상담 요청/수락 알림 | WebSocket | Spring + Redis |
| Minigame 실시간 상태 | NGO | Unity Server |

---

## 2. REST로 유지할 대상

다음 데이터는 실시간 socket으로 상시 동기화하지 않는다.

- Booth Slot
- Lease
- Wallet / Coin Ledger
- Draft / Published Layout
- Agent Config
- Project
- Survey 정의
- Survey 결과
- Inventory
- Dashboard

변경 빈도가 낮고 영구 데이터이므로 REST가 기본이다.

---

## 3. Unity Multiplayer 연결 Flow

```text
React/Unity Client
→ POST /api/v1/world-sessions
→ channelId / endpoint / connectionToken
→ Unity NetworkManager Connect
→ Dedicated Server Connection Approval
→ Player Spawn
```

### Session Response 후보

```json
{
  "sessionId": "ws_123",
  "worldId": "11F",
  "channelId": "11F-01",
  "serverEndpoint": "<transport endpoint>",
  "connectionToken": "short-lived-token",
  "expiresAt": "2026-08-08T17:00:00+09:00"
}
```

실제 endpoint format은 NGO Transport POC 후 확정한다.

---

## 4. Unity Network Message 범위

### NetworkVariables/State 후보

```text
userId
nickname
avatarCode
currentBoothId
emoteId
```

### Transform

```text
Position
Rotation
```

### Server RPC 후보

```text
RequestEmote(emoteId)
EnterBooth(boothId)
ExitBooth()
MinigameInput/Event(...)
```

구체 RPC는 실제 게임 구현에 따라 최소화한다.

---

## 5. Player 접속 이벤트

논리 이벤트:

```text
PLAYER_CONNECTED
PLAYER_SPAWNED
PLAYER_DESPAWNED
PLAYER_ENTER_BOOTH
PLAYER_EXIT_BOOTH
```

이 이벤트를 별도 WebSocket으로 중복 전송하지 않는다. Unity Client 간 월드 표현은 NGO를 사용한다.

---

## 6. AI Streaming

### Endpoint

```http
POST /ai/v1/conversations/{conversationId}/stream
Accept: text/event-stream
```

### Event 종류

```text
start
token
source
done
error
```

### start

```json
{
  "messageId": "msg_124"
}
```

### token

```json
{
  "delta": "이 프로젝트의 "
}
```

### source

```json
{
  "documentId": 152,
  "title": "project.pdf",
  "chunkId": "chunk_152_03"
}
```

### done

```json
{
  "messageId": "msg_124",
  "handoffRecommended": false
}
```

### error

```json
{
  "code": "LLM_TIMEOUT",
  "message": "AI 응답이 지연되고 있습니다."
}
```

---

## 7. SSE 연결 정책

- Conversation 단위 요청에 사용한다.
- 연결이 끊겨도 Unity World Network 연결은 유지한다.
- 동일 Message 자동 재전송은 중복 답변 위험이 있어 Client UX를 명확히 한다.
- Proxy/ALB timeout을 Infra에서 검증한다.
- 브라우저/Unity Web 환경에서 POST SSE가 불편하면 WebSocket 대안을 검토한다.

---

## 8. Staff Presence WebSocket

### 연결 Endpoint 후보

```text
/ws
```

또는 STOMP 사용 여부는 팀 Spring 구조에 따라 결정한다.

### Client → Server

```json
{
  "type": "STAFF_PRESENCE_SET",
  "boothId": 7,
  "status": "AVAILABLE"
}
```

### Server → Client

```json
{
  "type": "STAFF_PRESENCE_CHANGED",
  "boothId": 7,
  "userId": 45,
  "status": "AVAILABLE",
  "occurredAt": "..."
}
```

실제 사용자 권한은 Server가 검증한다.

---

## 9. Presence 저장

Redis 후보:

```text
staff:booth:{boothId}:{userId} = AVAILABLE
presence:user:{userId} = ONLINE
```

### TTL

WebSocket 연결 종료 누락에 대비해 Heartbeat/TTL을 둔다. 정확한 초 값은 구현 시 결정한다.

---

## 10. Consultation Flow

```text
Visitor
→ POST Consultation Request
→ Spring 저장
→ FastAPI Summary 요청
→ Spring이 Staff에게 Realtime Notify
→ Staff Accept
→ Spring 원자적 상태 변경
→ Visitor/Staff 양측 CONNECTED Event
→ Realtime Text Message
→ End
```

요청 생성 자체는 REST, 상태 변화 알림과 메시지는 WebSocket으로 분리한다.

---

## 11. Consultation Event

### `CONSULTATION_REQUESTED`

```json
{
  "type": "CONSULTATION_REQUESTED",
  "consultationId": 901,
  "boothId": 7,
  "visitor": {
    "userId": 12,
    "nickname": "Visitor"
  },
  "agentId": 78,
  "summary": "프로젝트 참여 방법을 문의하고 싶어함",
  "requestedAt": "..."
}
```

### `CONSULTATION_ACCEPTED`

```json
{
  "type": "CONSULTATION_ACCEPTED",
  "consultationId": 901,
  "staffUserId": 45,
  "acceptedAt": "..."
}
```

### `CONSULTATION_ENDED`

```json
{
  "type": "CONSULTATION_ENDED",
  "consultationId": 901,
  "endedAt": "..."
}
```

### `CONSULTATION_EXPIRED`

`requested_at + 10분`(C-01, spec 011) 경과 후 아무도 Accept하지 않으면 Visitor에게 전송한다.

```json
{
  "type": "CONSULTATION_EXPIRED",
  "consultationId": 901,
  "expiredAt": "..."
}
```

---

## 12. Consultation Message

### Client → Server

```json
{
  "type": "CONSULTATION_MESSAGE_SEND",
  "consultationId": 901,
  "clientMessageId": "uuid",
  "content": "프로젝트 역할이 궁금합니다."
}
```

### Server → Client

```json
{
  "type": "CONSULTATION_MESSAGE",
  "consultationId": 901,
  "messageId": 10231,
  "clientMessageId": "uuid",
  "senderUserId": 12,
  "content": "프로젝트 역할이 궁금합니다.",
  "sentAt": "..."
}
```

`clientMessageId`는 UI 중복 표시 방지에 활용할 수 있다.

원문 저장 여부·보존 기간은 P2 spec 착수 시 확정한다 (C-03, spec 011 — 2026-08-31 보류 확정, GitLab work_items#118).

---

## 13. 상담 상태 머신

```text
REQUESTED
  ├─> ACCEPTED → ACTIVE → ENDED
  ├─> REJECTED
  └─> EXPIRED   (requested_at + 10분, Accept 없을 시 — C-01)
```

### 동시 Accept

여러 Staff가 `accept`를 보내더라도 DB에서 한 명만 성공한다.

실패 Staff에는:

```json
{
  "code": "CONSULTATION_ALREADY_ACCEPTED"
}
```

를 반환한다.

---

## 14. WebSocket 인증

연결 시 인증 Token을 검증한다.

후보:

- Handshake Header
- Query Token은 노출 위험 때문에 신중히 사용
- STOMP CONNECT Header

정확한 방식은 Spring WebSocket 구현체 선택 후 확정한다.

---

## 15. Reconnect

### Spring WebSocket

Client 상태:

```text
DISCONNECTED
CONNECTING
CONNECTED
RECONNECTING
```

재접속 시:

- 인증 재검증
- Staff Presence 재등록
- 진행 중 Consultation 조회
- 누락 Message History 필요 시 REST로 재조회

### Unity NGO

별도 Network Reconnect 정책을 따른다.

### AI SSE

각 Message 단위 Stream이므로 일반 WebSocket처럼 장기 reconnect를 하지 않는다.

---

## 16. Heartbeat

### Staff/Service Realtime

WebSocket ping/pong 또는 STOMP heartbeat를 사용한다.

### Unity Instance

Unity Server → Session/Redis heartbeat는 별도 내부 통신으로 관리할 수 있다.

---

## 17. Event Envelope 권장안

Spring Realtime Event는 공통 Envelope를 사용한다.

```json
{
  "type": "CONSULTATION_REQUESTED",
  "eventId": "evt_uuid",
  "occurredAt": "2026-08-08T16:00:00+09:00",
  "payload": {}
}
```

장점:

- 중복 처리 추적
- 로그 검색
- Client Event Router 단순화

---

## 18. Ordering

상담 메시지는 같은 Consultation 안에서 순서를 확인할 수 있어야 한다.

방법 후보:

- Server `messageId`
- `sentAt`
- 필요하면 sequence

Client clock만으로 순서를 확정하지 않는다.

---

## 19. Delivery 보장

MVP에서 일반 채팅을 금융 거래 수준 exactly-once로 만들 필요는 없다.

그러나:

- Coin
- Lease
- Reward

는 Realtime event로 직접 상태를 바꾸지 않고 REST/DB Transaction을 통해 처리한다.

---

## 20. Realtime과 DB의 관계

```text
Realtime Notification != Source of Truth
```

예:

Staff가 `CONSULTATION_ACCEPTED` Event를 놓쳐도:

```text
GET /consultations/{id}
```

으로 최종 상태를 복구할 수 있어야 한다.

---

## 21. 오류 코드

```text
REALTIME_UNAUTHORIZED
REALTIME_FORBIDDEN
INVALID_EVENT
CONSULTATION_NOT_FOUND
CONSULTATION_ALREADY_ACCEPTED
CONSULTATION_CLOSED
MESSAGE_TOO_LONG
RATE_LIMITED
SERVER_ERROR
```

메시지 최대 길이는 TBD.

---

## 22. 로그 항목

- connectionId
- userId
- eventId
- eventType
- consultationId
- connect/disconnect reason
- error code

상담 내용 원문 로그는 개인정보 정책 확정 전 최소화한다.

---

## 23. P1 완료 조건

- [ ] Staff Presence 실시간 반영
- [ ] Consultation Request 알림
- [ ] 여러 Staff 중 1명만 Accept
- [ ] Visitor에게 Accept Event 전달
- [ ] Text Message 양방향 전달
- [ ] 종료 Event
- [ ] WebSocket 재접속 후 상태 복구
- [ ] AI SSE 실패가 World 연결을 끊지 않음
