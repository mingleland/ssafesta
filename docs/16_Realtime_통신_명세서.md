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
| 사람 상담 메시지 | **P2** — P1 범위 밖 (spec 011 C-12) | Spring |
| 상담 요청/수락 알림 | **STOMP over native WebSocket** (단방향) | Spring |
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

## 8. 상담 알림 채널 (STOMP)

> **2026-09-13 확정·구현** (`S15P21A604-137`). 이 절의 이전 판은 연결 endpoint 와 STOMP 사용
> 여부를 "후보" 로 두고 Presence 를 WebSocket 메시지로 받는 설계였다. **둘 다 실제와 다르다** —
> 정본은 `specs/011-staff-consultation/contracts/staff-consultation-api.md` §B 다.

```text
엔드포인트  wss://<host>/ws/consultation     native WebSocket + STOMP, SockJS 없음 (C-05)
            /api/v1 아래가 아니다 — STOMP 등록이 REST 매핑과 별개다
인증        POST /api/v1/consultation/ws-token 으로 5분 토큰을 받아
            STOMP CONNECT 의 Authorization 헤더에 Bearer 로 싣는다 (FR-019)
```

**URL query 로 토큰을 넘기지 않는다.** 그 자리의 값은 접속 로그와 referrer 에 남는다. Access
Token 재사용도 허용하지 않는다 — 토큰 4계층 분리의 이유다(헌법 13조).

### 구독

```text
/user/queue/consultation              방문자 — 내 요청의 상태 변화
/topic/booths/{boothId}/consultation  직원 — 그 부스 대기열 변화
```

### Client → Server: **없다**

P1 의 이 채널은 **서버에서 클라이언트로 가는 단방향 알림 전용**이다(C-12). 요청·취소·수락·종료는
전부 REST 이고, 그래서 서버에 SEND destination 자체가 등록돼 있지 않다.

**destination 미등록을 보안 경계로 삼지 않는다.** 클라이언트의 STOMP `SEND` 는 inbound
interceptor 가 기본 거부한다. 이 방어가 없으면 `/topic/**`·`/queue/**` 로 보낸 프레임이
컨트롤러를 우회해 simple broker 로 갈 수 있다. 개인 알림 구독은 `/user/queue/**` 만 쓰며,
raw `/queue/**` 구독은 거부한다. 토픽별 SUBSCRIBE 자격 검증은 별도 보안 검토 범위다.

### Server → Client 봉투

```json
{ "type": "requested", "requestId": "901", "occurredAt": "...", "visitorNickname": "...", "handoffSummary": null }
```

| 구독 | `type` |
|---|---|
| 방문자 | `accepted`(+`staffName`) · `expired` · `ended` |
| 직원 | `requested` · `cancelled` · `expired` · `taken`(+`staffName`) |

`taken` 이 있는 이유는 **진 직원의 대기열에서 카드를 내리기 위해서**다. 없으면 그 카드가 화면에
남아 있다가 누를 때 409 로 터진다.

### 유실은 계약이 인정한다

**이벤트 재전송이 P1 에 없다.** 끊긴 사이의 변화는 유실되고, 클라이언트는 재연결 직후 대기열과
요청 상태를 REST 로 다시 읽는다. `occurredAt` 이 그때 순서를 가르는 값이다.

**정본은 REST 이고 이것은 알림이다.**

### 한계 — 단일 인스턴스 전제

in-memory simple broker 다. Spring 을 두 대 이상 띄우면 A 에 붙은 직원이 B 가 발행한 이벤트를
받지 못한다. 스케일아웃이 정해지면 외부 브로커 릴레이가 필요하며, 그것은 **배포 형상 결정 뒤의
후속**이다.

---

## 9. Staff Presence — WebSocket 이 아니라 REST·DB

> **2026-09-13 정정** (`S15P21A604-136`). 이전 판은 `STAFF_PRESENCE_SET` WebSocket 메시지와
> Redis 저장을 적었다. 구현은 그 어느 쪽도 아니다.

```text
PUT /api/v1/booths/{boothId}/staff/me/presence   { "status": "AVAILABLE" }
저장  booth_staffs.consultation_status (V33)
```

`AVAILABLE`·`AWAY`·`OFFLINE` 만 받는다. **`BUSY` 는 서버가 관리한다** — 상담 수락이 넣고 종료가
되돌린다. 직접 지정하면 `400` 이다.

기본값은 `OFFLINE` 이다 — spec 011 US2 가 "담당자가 항상 있을 수 없다, **오프라인이 기본 상태**"
로 못박았으므로 행이 생기는 순간의 값이 곧 정상 경로다.

**Redis 를 쓰지 않은 이유**: `booth_staffs` 행이 이미 있고 조회 경로가 전부 REST 다(직원 목록·
대기열). Redis 에 따로 두면 재시작·정합·삭제 시점을 새로 관리해야 하는데 P1 이 얻는 것이 없다.

**연결 종료 시 자동 `OFFLINE` 전환은 P1 범위 밖이다.** 연결 수명과 상담 가능 여부는 별개다 —
직원이 창을 닫아도 자리에 있을 수 있다. 필요해지면 P2 에서 연결 이벤트와 묶는다.

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
