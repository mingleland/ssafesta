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
| Booth Layout | REST + **STOMP 무효화 신호** (§8-2) | Spring |
| AI 응답 | SSE 우선 | FastAPI |
| AI 문서 상태 | REST Polling 우선 | FastAPI/Spring |
| Staff Presence | WebSocket 후보 | Spring + Redis |
| 사람 상담 메시지 | **P2** — 아직 없다. §12 는 미구현 초안이다 (spec 011 C-12) | Spring |
| 상담 요청/수락 알림 | **STOMP over native WebSocket** (단방향) | Spring |
| **월드 공용 채팅** | **STOMP** — 클라이언트가 보내는 유일한 경로 (§8-1) | Spring (저장 안 함) |
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

## 8-1. 월드 공용 채팅 (STOMP) — 구현 완료 (S15P21A604-687)

**클라이언트가 서버로 보내는 유일한 경로다.** 나머지 STOMP 는 전부 단방향 알림이다.

### 연결

`wss://<host>/ws` — §14 의 WS Token 을 `CONNECT` 헤더에 싣는다. **한 소켓이 상담 알림과 이 채팅을 함께 나른다.** `wss://<host>/ws/consultation` 도 같은 것으로 남겨 두었다.

**읽기는 게스트도 한다** (2026-09-14, `S15P21A604-687` 최초 판 정정). 처음에는 WS Token 이 회원에게만 발급돼 연결 자체가 회원 게이트였는데, §8-2 부스 변경 신호를 게스트 화면까지 보내려고 게스트에게도 토큰을 연다(`S15P21A604-727`). **보내는 것은 여전히 회원만이다** — 게스트가 `SEND` 하면 `MEMBER_ONLY` 가 오류 큐로 오고 **연결은 끊기지 않는다.**

### 보내기 — `SEND /app/world/chat`

```json
{ "content": "안녕하세요" }
```

**보낸 사람도 시각도 받지 않는다.** 클라이언트가 정할 수 있는 값이면 사칭과 조작이 된다. 본문에 `nickname` 이나 `senderUserId` 를 실어도 서버가 읽지 않는다.

### 받기 — `SUBSCRIBE /topic/world/chat`

```json
{ "senderUserId": 7, "nickname": "덕", "content": "안녕하세요", "sentAt": "2026-09-13T20:10:00Z" }
```

`nickname` 은 **서버가 조회한 값**이고, `content` 는 `strip()` 을 거친 정규화본이다 — 보낸 그대로가 아니다.

### 오류 — `SUBSCRIBE /user/queue/world/chat/errors`

`SEND` 에는 응답이 없어서 별도 큐로 간다. **보낸 세션에만** 전달된다 — 같은 계정의 다른 탭에는 가지 않는다.

```json
{ "code": "CHAT_TOO_FAST", "message": "잠시 후 다시 보내 주세요." }
```

| code | 조건 |
|---|---|
| `VALIDATION_FAILED` | 빈 내용·공백만, 또는 **100 code point** 초과 |
| `CHAT_TOO_FAST` | 3초 안에 두 번째 |
| `CHAT_UNAVAILABLE` | 도배 방지를 판정할 수 없다 (Redis 장애) |
| `MEMBER_ONLY` | 정지·탈퇴된 계정 — WS Token 이 5분 남아 있어도 거부된다 |

**길이는 code point 로 센다.** `String.length()` 는 UTF-16 단위라 이모지 하나가 2자가 되고, 상한이 사람이 보는 길이와 어긋난다. 이모지 100개는 통과한다.

**Redis 가 답하지 않으면 막는다**(fail-closed). 도배 방지가 필수 조건이라, 판정할 수 없을 때 열어 두면 Redis 가 흔들리는 순간 광장이 도배된다.

### 한계

- **저장하지 않는다.** 표가 없다 — 재접속하면 이전 대화가 없고, 인스턴스가 죽으면 그 대화는 사라진다. 광장 잡담은 스크롤백 가치가 낮고, 남기면 보존 기간과 탈퇴 삭제 경로가 함께 붙는다(D11)
- **토픽이 하나다.** 채널별로 가르지 않는다 — 채널을 클라이언트가 지정하게 하면 **서버가 누가 어느 채널에 있는지 몰라 검증할 수 없다**. 채널 배정(`S15P21A604-499`)이 서면 그때 쪼갠다
- **월드 접속 여부를 보지 않는다.** "유효한 WS Token 을 가진 회원" 이면 월드 밖에서도 보낼 수 있다
- **단일 인스턴스 전제.** §8 의 한계와 같다 — 서버를 둘로 늘리면 서로의 말이 보이지 않는다
- **신고·차단·금칙어가 없다.** 운영 인력을 전제하는 기능이라 시연 규모에 맞지 않는다

---

## 8-2. 부스 변경 신호 (STOMP) — `S15P21A604-727`, GitLab #193

**부스가 바뀌었다는 것만 알린다.** 월드에 이미 들어와 있는 사람에게 변경을 알릴 길이 없어, A 가 입장한 뒤 B 가 게시하면 **A 와 C 가 같은 공간에서 다른 것을 보는 상태가 세션 내내** 이어졌다(`docs/HDD/부스_변경_신호_계약.md` §1).

### 받기 — `SUBSCRIBE /topic/world/booths`

```json
{ "slotId": 7 }
```

**서버 → 클라이언트 단방향이다.** 클라이언트 `SEND` allowlist 는 늘지 않는다(§8-1 의 `/app/world/chat` 하나 그대로).

### 신호에 데이터를 싣지 않는다

Layout JSON 을 방송하지 않는다. 부스당 오브젝트 12개(헌법 22조)가 접속자 수만큼 복제되고, 받는 쪽은 어차피 간판·외관·프로젝트를 따로 읽어야 한다. **"바뀌었다" 만 알리고 재조회는 기존 REST 가 한다.**

`boothId` 가 아니라 **`slotId`** 다 — 월드는 슬롯으로 말한다(`GET /booth-slots/{slotId}/layout/published`, Unity `BoothLayoutBridge.ReloadBoothSlot`). 슬롯에 서 있지 않은 부스(미임대·만료)는 월드에 보이는 것이 없으므로 신호도 나가지 않는다.

### 언제 나가는가

| 경로 | |
|---|---|
| 배치 공개 | `POST /booths/{id}/layouts/publish` |
| 외관 | `PUT /booths/{id}/facade` — **배치 회차를 올리지 않아** 이 신호가 없으면 간판만 낡는다 |
| 홈페이지 | `PUT /booths/{id}/homepage` |
| 임대 | `POST /booth-slots/{slotId}/leases` — 새 임차인의 부스가 그 슬롯에 선다 |
| 만료 | 게으른 판정·주기 배치 둘 다 같은 자리를 지난다 |

### 커밋 뒤에 나간다

트랜잭션 안에서 보내면 받는 쪽이 커밋 전에 재조회해 **옛 값**을 읽고 그 서명을 캐시한다 — 다음 변경이 올 때까지 낡은 부스가 굳는다. 화면에는 "게시했는데 그대로" 로만 보이고 오류는 남지 않는다.

### 받는 쪽

FE 가 이 토픽을 듣고 `instance.SendMessage('BoothLayoutBridge', 'ReloadBoothSlot', String(slotId))` 로 넘기면, Unity `WorldBoothPublishedBootstrap.RequestReload` 가 그 슬롯만 재조회해 간판·외관·슬롯 목록까지 갱신하고 **서명이 다를 때만** 다시 짓는다. **Unity 수정은 0이다.**

### 한계

- **회차를 싣지 않는다.** Unity 의 서명 비교가 이미 중복 재생성을 걸러내므로 읽을 소비자가 없다. 필요해지면 그때 필드를 더한다
- **스냅샷이 없다.** 재연결 시 FE 가 보이는 슬롯에 같은 호출을 한 번씩 쏘는 것으로 대신한다
- **유실은 계약이 인정한다**(§19). 놓친 신호는 다음 신호나 포탈 진입 시 재조회로 따라잡는다
- **단일 인스턴스 전제.** §8·§8-1 과 같다

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

## 12. Consultation Message — **미구현 초안**

> ⚠️ **이 절은 구현돼 있지 않다.** 상담 메시지는 P2 이고(spec 011 C-12), 아래 envelope 를 받는 곳이 서버에 없다.
>
> 그리고 지금 보내면 **연결이 끊긴다.** `S15P21A604-686` 이후 클라이언트 `SEND` 는 allowlist 밖이면 거부되고, 상담 메시지 destination 은 그 목록에 없다. 구현할 때 그 티켓의 목록에 추가하면서 이 절을 확정본으로 고쳐야 한다.
>
> 실제로 동작하는 클라이언트 → 서버 경로는 **월드 공용 채팅(§8-1) 하나뿐**이다.

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

## 14. WebSocket 인증 — 확정 (S15P21A604-137·-686)

**STOMP `CONNECT` 프레임의 `Authorization: Bearer <token>` 헤더 하나다.** 아래 후보 목록은 구현 전 초안이었고, §8 이 정본이다.

| | |
|---|---|
| 토큰 | `POST /api/v1/realtime/ws-token` 이 주는 **5분짜리 전용 토큰** (헌법 13조). Access Token 재사용 불가 |
| 전달 | STOMP `CONNECT` 헤더. **URL query 는 보지 않는다** — 접속 로그·referrer 에 남기 때문이다 (FR-019) |
| 핸드셰이크 | HTTP 인증을 타지 않는다. `/ws`·`/ws/**` 가 `permitAll` 이고 신원은 `CONNECT` 에서 본다 |
| 만료 | 연결 성립 후에는 만료가 연결을 끊지 않는다 (FR-020) |
| 주체 | 회원은 **id 문자열**, 게스트는 **`guest:<uuid>`** (`S15P21A604-727`). **숫자인 주체는 곧 회원**이고, 회원만 할 수 있는 일(채팅 발신·부스 대기열 구독)이 그 판정을 읽는다 — 그래서 숫자 주체를 가진 게스트 토큰은 발급 자체를 거부한다 |

**연결 뒤에도 경계가 있다** (`S15P21A604-686`·`-692`). 클라이언트 `SEND` 는 allowlist 밖이면 거부되고, 개인 큐는 `/user/queue/**` 로만 구독한다 — raw `/queue/**` 직접 구독은 거부된다. 토픽별 SUBSCRIBE 자격 검증은 별도 보안 검토 범위다.

**판정 기준은 `SimpMessageType` 이지 STOMP command 가 아니다** (`S15P21A604-692`). command 는 와이어 표기일 뿐이고 브로커·핸들러가 보는 것은 simpType 이라, command 로 가르면 같은 simpType 의 다른 표기가 정책을 지나간다. 실제로 그렇게 새어 나간 것이 둘이다.

| 표기 | simpType | 무엇을 우회했나 |
|---|---|---|
| `STOMP` | `CONNECT` | **토큰 검증** — STOMP 1.2 가 `CONNECT` 의 동의어로 규정한다 |
| `MESSAGE` | `MESSAGE` | **destination 차단** — `SEND` 와 같은 simpType 이라 브로커가 그대로 처리한다 |

서버 전용 command(`CONNECTED`·`MESSAGE`·`RECEIPT`·`ERROR`)는 인바운드에서 이름만으로도 거부한다.

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
