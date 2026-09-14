# Contract: 직원 / 사람 상담 API (P1)

**Spec**: `011-staff-consultation` | **Date**: 2026-09-13 | **Status**: 확정 계약의 문서화

> **이 파일은 새 계약을 만드는 것이 아니다.** 상담 축은 2026-09-07 BE 회신(GitLab #133)으로 이미 확정됐고 FE가 `S15P21A604-519`로 선반영을 마쳤다(`festa-frontend/src/entities/consultation/channel.port.ts`). 여기에 그 확정본을 spec 옆에 고정한다.
>
> **`docs/08` §12는 이것과 다른 경로를 적고 있다.** 정본은 이 파일이며 `docs/08`을 맞춘다 — 근거는 [research.md R-01](../research.md).

공통 규약은 `docs/08` §1을 따른다 — 전역 오류 봉투 `{code, message, requestId, errors, warnings}`, 모든 경로는 `/api/v1` 아래(STOMP 엔드포인트는 예외).

---

## A. 직원 초대·권한 (`S15P21A604-136`)

### `POST /api/v1/booths/{boothId}/staff-invitations`

Owner·`ADMIN`만. 초대를 만든다.

```json
{ "nickname": "덕", "role": "CONSULTANT" }
```

→ `201 { "invitationId": 12, "boothId": 7, "boothName": "…", "nickname": "덕", "role": "CONSULTANT", "expiresAt": "..." }` — `expiresAt` 은 생성 + **48시간**(C-07).

> **대상은 닉네임이다 — `userId` 가 아니다.** `docs/08` §10 은 `userId` 를 적었지만 Owner 가 남의 숫자 id 를 알아낼 경로가 없다. 사용자 검색 endpoint 를 열면 닉네임 훑기·id 수집 표면이 함께 생기므로 만들지 않았고, `users.nickname` 이 V1 부터 `UNIQUE` 라 식별자로 충분하다. `docs/08` §10 정정은 이 구현과 함께 나간다 (헌법 24조 통보).

| 오류 | 조건 |
|---|---|
| `403 STAFF_MANAGER_FORBIDDEN` | 호출자가 Owner·`ADMIN` 이 아니다 — `CONTENT_EDITOR` 는 콘텐츠를 고치지만 사람을 들이지는 못한다 |
| `404 STAFF_INVITEE_NOT_FOUND` | 그 닉네임의 회원이 없다 |
| `409 STAFF_INVITATION_PENDING` | 같은 사용자에게 대기 중 초대가 이미 있다 (`ux_staff_invitations_pending`) |
| `409 STAFF_ALREADY_MEMBER` | 이미 소유자이거나 직원이다 |
| `400 VALIDATION_FAILED` | `nickname` 누락, 또는 `role` 이 `ADMIN`·`CONTENT_EDITOR`·`CONSULTANT` 밖이다 |

### `GET /api/v1/staff-invitations/mine`

**본인에게 온 대기 중 초대만** 반환한다 (FR-016, C-08).

→ `200 [{ "invitationId": 12, "boothId": 7, "boothName": "…", "role": "CONSULTANT", "expiresAt": "..." }]`

### `POST /api/v1/staff-invitations/{invitationId}/accept`

초대받은 본인만. `booth_staffs` 행을 만들고 초대를 `ACCEPTED` 로 옮긴다.

| 오류 | 조건 |
|---|---|
| `403 STAFF_INVITATION_FORBIDDEN` | 내게 온 초대가 아니다 |
| `404 STAFF_INVITATION_NOT_FOUND` | 그런 초대가 없다 |
| `409 STAFF_INVITATION_NOT_PENDING` | 이미 수락·취소됐거나 48시간이 지났다. **만료는 스위퍼를 기다리지 않고 읽는 쪽이 판정한다** |
| `409 STAFF_ALREADY_MEMBER` | 초대를 받는 사이에 이미 직원이 됐다 |

### `DELETE /api/v1/booths/{boothId}/staff-invitations/{invitationId}`

Owner·`ADMIN`만. 대기 중 초대를 취소한다 (FR-017). → `204`

> **초대받은 사용자의 거절 API는 제공하지 않는다**(C-10). 수락하지 않으면 48시간 뒤 만료된다.

### `GET /api/v1/booths/{boothId}/staff`

Owner·직원 누구나. **Owner를 읽기 전용 `OWNER` 행으로 포함한다**(FR-018, C-11).

```json
[
  { "userId": 3,  "nickname": "…", "role": "OWNER",      "consultationStatus": null,        "readOnly": true },
  { "userId": 45, "nickname": "…", "role": "CONSULTANT", "consultationStatus": "AVAILABLE", "readOnly": false }
]
```

`OWNER` 행은 `PATCH`·`DELETE` 대상이 아니다 — 시도하면 `409 STAFF_OWNER_IMMUTABLE`. 직원이 아닌 사람을 가리키면 `404 STAFF_NOT_FOUND` 다. 둘을 가르는 이유는, 목록에 분명히 보이던 Owner 를 404 로 답하면 "이 부스와 무관하다" 로 읽히기 때문이다.

조회 게이트는 **부스 구성원**이다 — 편집 게이트가 아니다. `CONSULTANT` 도 같은 부스에 누가 있는지는 알아야 한다. 구성원이 아니면 `403 STAFF_MANAGER_FORBIDDEN`.

### `PATCH /api/v1/booths/{boothId}/staff/{userId}` · `DELETE …`

Owner·`ADMIN`만. 역할 변경 / 직원 제거.

### `PUT /api/v1/booths/{boothId}/staff/me/presence`

직원 본인. → `{ "status": "AVAILABLE" }`

`AVAILABLE`·`AWAY`·`OFFLINE` 만 받는다. **`BUSY` 는 서버가 관리한다** — 수락 시 자동 전환되고 종료 시 직전 값으로 돌아간다. 직접 지정하면 `400 VALIDATION_FAILED`.

---

## B. 상담 (`S15P21A604-137`) — #133 확정본

### `POST /api/v1/consultation/ws-token`

인증된 회원. STOMP 연결용 **단기 토큰**을 발급한다.

→ `201 { "token": "…", "expiresInSeconds": 300 }`

- **5분**(FR-020). 연결 성립 후에는 만료로 끊지 않으며, 재연결 시 새로 발급받는다.
- STOMP `CONNECT` 의 `Authorization: Bearer <token>` 헤더로 전달한다.
- **URL query 로 토큰을 넘기지 않는다**(FR-019, 헌법 13조). Access Token 재사용·Cookie handshake도 허용하지 않는다.

### STOMP

```text
엔드포인트  wss://<host>/ws/consultation     native WebSocket + STOMP, SockJS 없음 (C-05)
            `/api/v1/*` 아래가 아니다 — STOMP 등록이 REST 와 별도다
구독        /user/queue/consultation              방문자 — 내 요청의 상태 변화
            /topic/booths/{boothId}/consultation  직원 — 그 부스 대기열 변화
SEND        없다. P1 은 서버→클라이언트 단방향 알림 전용이고 행동은 전부 REST 다 (C-12)
```

**부스 토픽 구독은 그 부스의 구성원(Owner·직원, 역할 무관)만 할 수 있다** (2026-09-13, S15P21A604-693). 신원은 `CONNECT` 의 WS Token 으로 확정되고, 구성원 여부는 `SUBSCRIBE` 시점에 `boothId` 별로 검사한다 — `CONNECT` 에는 `boothId` 가 없다. 구성원이 아닌 회원·없는 부스·주체 없는 구독은 STOMP `ERROR` 프레임과 함께 **연결이 끊긴다**. 클라이언트는 자기 부스의 토픽만 구독하면 되고, 끊긴 뒤에는 WS Token 을 새로 발급받아 재연결한다. `/user/queue/consultation` 은 user destination 이 세션 주인을 고르므로 별도 검사가 없다.

이벤트 봉투: `{ type, requestId, occurredAt, … }`

| 구독 | `type` | 추가 필드 |
|---|---|---|
| 방문자 | `accepted` | `staffName` |
| 방문자 | `expired` · `ended` | — |
| 직원 | `requested` | `visitorNickname`, `requestedAt`, `handoffSummary` |
| 직원 | `cancelled` · `expired` | — |
| 직원 | `taken` | `staffName` — 다른 직원이 먼저 수락했다 |

> **이벤트 재전송은 P1에 없다.** 끊긴 사이의 변화는 유실되며, 클라이언트는 재연결 직후 `getQueue`(직원)·요청 상태(방문자)를 REST로 다시 읽는다. `occurredAt` 이 그때 순서를 가른다. **정본은 REST이고 STOMP는 알림이다.**

### `POST /api/v1/consultation/requests`

인증된 회원만. **게스트는 `403 MEMBER_ONLY`** 와 함께 소셜 로그인 안내를 받는다(FR-014, 헌법 12조).

> 구현하며 `GUEST_FORBIDDEN` 에서 고쳤다 (2026-09-13). 게스트 거부는 이미 `MEMBER_ONLY` 라는 이름을 갖고 있고, 같은 사건에 두 이름을 만들면 FE 가 분기를 두 벌 관리한다 (`docs/08` §1.3).

```json
{ "boothId": 7, "conversationId": "conv_01JABCXYZ" }
```

→ `201 { "requestId": "901", "expiresInSeconds": 600 }` — **10분**(C-01).

- `conversationId` 는 선택이다. 대화 없이 바로 요청하면 요약이 `null` 이다.
- **요약 텍스트는 FE가 만들지도 보내지도 않는다.** 서버가 이 id로 FastAPI에 요약을 요청해 **요청 생성 시점의 스냅샷으로 굳힌다**. 이후 대화가 이어져도 갱신하지 않는다.
- **요약 생성 실패는 요청을 막지 않는다** — `null` 로 들어간다(헌법 3조).

| 오류 | 조건 |
|---|---|
| `403 MEMBER_ONLY` | 게스트 |
| `409 CONSULTATION_REQUEST_PENDING` | 이 방문자의 대기 중 요청이 이미 있다 |
| `404 BOOTH_NOT_FOUND` | 그런 부스가 없다 (2026-09-13, S15P21A604-693 — 이전에는 임대 검사만 해 `409 BOOTH_LEASE_EXPIRED` 로 답했다) |
| `409 BOOTH_LEASE_EXPIRED` | 부스 임대가 만료됐다 |

### `DELETE /api/v1/consultation/requests/{requestId}`

방문자 본인. 대기 중 요청을 취소한다. → `204`

상태는 `CANCELLED` 로 남는다 — `EXPIRED` 와 가르는 이유는 대기열에서 사라진 **까닭**이 다르고, 직원 화면이 다른 이벤트(`cancelled` / `expired`)를 받기 때문이다.

| 오류 | 조건 |
|---|---|
| `403 CONSULTATION_FORBIDDEN` | 내 요청이 아니다 |
| `404 CONSULTATION_NOT_FOUND` | 그런 요청이 없다 |
| `409 CONSULTATION_NOT_REQUESTED` | 이미 수락·취소·만료됐다 |

### `GET /api/v1/booths/{boothId}/consultation/requests`

직원. 그 부스의 **대기 중** 요청 목록.

```json
[{ "requestId": "901", "visitorNickname": "…", "requestedAt": "...", "handoffSummary": null }]
```

### `POST /api/v1/consultation/requests/{requestId}/accept`

직원. **정확히 한 명만 성공한다**(FR-007, SC-001).

→ `200 { "requestId": "901", "sessionId": "901", "visitorNickname": "…", "handoffSummary": null }`

> `sessionId` 는 `requestId` 와 **같은 값**이다 — 한 행이 요청과 세션을 겸한다([research.md R-06](../research.md)). 어댑터가 둘 중 무엇을 들고 있어도 `end` 를 부를 수 있게 둘 다 싣는다.

| 오류 | 조건 |
|---|---|
| `409 CONSULTATION_ALREADY_ACTIVE` | **호출한 직원에게 활성 상담이 이미 있다**(C-06, FR-021) |
| `409 CONSULTATION_NOT_REQUESTED` | 이미 다른 직원이 가져갔거나 만료·취소됐다 |
| `403 STAFF_MANAGER_FORBIDDEN` | 그 부스의 구성원(Owner·직원)이 아니다 — 대기열 조회(`GET .../consultation/requests`)와 같은 코드다. 2026-09-13 정정(S15P21A604-693): 이전 표기 `BOOTH_FORBIDDEN` 은 `ErrorCode` 에 존재하지 않는 이름이었다 |

### `POST /api/v1/consultation/sessions/{sessionId}/end`

방문자·직원 **누구나** 종료할 수 있다. 상대에게 `ended` 이벤트가 간다(FR-010). → `204`

| 오류 | 조건 |
|---|---|
| `403 CONSULTATION_FORBIDDEN` | 내 상담이 아니다 |
| `404 CONSULTATION_NOT_FOUND` | 그런 세션이 없다 |
| `409 CONSULTATION_NOT_REQUESTED` | 진행 중인 상담이 아니다 |

---

## C. P1에 없는 것

| 항목 | 어디로 |
|---|---|
| 실시간 메시지 송수신·저장 | **P2** (C-12). `consultation_messages` 테이블은 V1부터 있지만 P1에서 쓰지 않는다 |
| 오프라인 비동기 문의(메시지 남기기) | **P2** (C-02) |
| 상담 원문 History 조회 endpoint | P2 spec 착수 시 저장 여부부터 재검토 (C-03) |
| 초대 거절 API | 제공하지 않는다 (C-10) |
| 연결 끊김 시 자동 `OFFLINE` 전환 | P1 범위 밖 ([research.md R-04](../research.md)) |
