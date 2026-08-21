# SSAFY FESTA Backend API 명세서

> **대상**: Spring Boot 서비스 API  
> **Base Path 제안**: `/api/v1`  
> **상태**: Draft — 업로드된 기획을 구현 가능한 REST 계약으로 구체화한 제안안  
> AI 추론 API는 `14_AI_Server_API_명세서.md`에서 별도 관리한다.

---

## 1. 공통 규격

### 1.1 인증

```http
Authorization: Bearer <access-token>
```

Public Endpoint를 제외한 모든 API는 JWT 인증을 기본으로 한다.

### 1.2 성공 응답

단순 리소스는 HTTP Status와 JSON Body로 반환한다.

```json
{
  "id": 123,
  "createdAt": "2026-08-08T16:00:00+09:00"
}
```

### 1.3 오류 응답 제안

```json
{
  "code": "BOOTH_SLOT_ALREADY_LEASED",
  "message": "이미 임대 중인 부스입니다.",
  "requestId": "req_..."
}
```

### 1.4 Idempotency

금전·보상·임대 등 중복 위험 요청은 다음 Header 사용을 권장한다.

```http
Idempotency-Key: <client-generated-uuid>
```

---

## 2. Auth / User

### POST `/auth/signup`

회원가입.

### POST `/auth/login`

로그인 및 Token 발급.

### POST `/auth/refresh`

Access Token 갱신. Refresh 정책은 보안 설계에서 확정한다.

### POST `/auth/logout`

현재 인증 세션 종료.

### GET `/users/me`

내 기본 정보 조회.

#### Response 예시

```json
{
  "userId": 12,
  "nickname": "FESTA_USER",
  "wallet": {
    "balance": 250
  },
  "boothId": 7
}
```

---

## 3. Booth Slot / Lease

### GET `/booth-slots`

전체 Booth Slot 상태 조회.

```json
[
  {
    "slotId": 5,
    "type": "USER_RENTAL",
    "status": "AVAILABLE",
    "boothId": null,
    "leaseEndsAt": null,
    "entryAvailable": false,
    "facade": null
  }
]
```

### POST `/booth-slots/{slotId}/leases`

빈 부스를 임대한다.

#### Request

```json
{
  "durationDays": 1
}
```

#### 처리 조건

- USER_RENTAL Slot인지 검증
- 현재 AVAILABLE인지 검증
- 사용자 잔액 검증
- 동시 임대 충돌 방지
- Lease 생성
- Coin Transaction 생성
- Booth 연결

#### Response

```json
{
  "leaseId": 301,
  "boothId": 7,
  "slotId": 5,
  "startsAt": "2026-08-08T16:00:00+09:00",
  "endsAt": "2026-08-09T16:00:00+09:00",
  "chargedCoin": 100,
  "balanceAfter": 150
}
```

#### 오류

- `BOOTH_SLOT_NOT_RENTABLE`
- `BOOTH_SLOT_ALREADY_LEASED`
- `INSUFFICIENT_COIN`
- `DUPLICATE_REQUEST`

### GET `/booths/mine`

내 Booth와 현재 Lease 조회.

### GET `/booths/{boothId}`

공개 가능한 Booth 기본 정보 조회.

```json
{
  "boothId": 7,
  "slotId": 5,
  "name": "AI 프로젝트 전시관",
  "leaseStatus": "ACTIVE",
  "entryAvailable": true,
  "facade": {
    "themeCode": "SSAFY_BLUE",
    "primaryColor": "#1677C8",
    "signText": "AI 프로젝트 전시관",
    "logoUrl": null
  },
  "publishedLayoutVersion": 4
}
```

### POST `/booths/{boothId}/leases/extend` — P1

임대 연장. 정확한 정책은 TBD.

### Lease 만료 처리 계약

- `ACTIVE → EXPIRED` 전환과 `booths.current_slot_id` 해제는 하나의 트랜잭션으로 처리한다.
- 만료 즉시 해당 슬롯의 `entryAvailable`을 `false`로 반환한다.
- 외부 Facade는 슬롯 응답에서 숨기고 기본 빈 슬롯으로 표시한다.
- 기존 Published Layout은 일반 Runtime 조회 대상에서 제외하되 Owner의 Draft/보존 데이터는 삭제하지 않는다.
- 현재 내부 방문자 퇴장은 Unity Server가 처리할 수 있도록 Realtime event 또는 주기 검증 계약을 별도로 확정한다.

---

## 4. Booth Layout

### GET `/booths/{boothId}/layouts/draft`

Owner/Editor용 최신 Draft 조회.

### PUT `/booths/{boothId}/layouts/draft`

Draft 저장.

#### Request 예시

```json
{
  "template": "PROJECT_EXHIBITION",
  "objects": [
    {
      "objectId": "screen-1",
      "type": "VIDEO_SCREEN",
      "position": {"x": 2.1, "y": 0.0, "z": 3.4},
      "rotationY": 90.0,
      "configId": 152
    },
    {
      "objectId": "ai-1",
      "type": "AI_AGENT",
      "position": {"x": 1.2, "y": 0.0, "z": 1.5},
      "rotationY": 0.0,
      "configId": 78
    }
  ]
}
```

### POST `/booths/{boothId}/layouts/publish`

현재 유효 Draft를 새 Published Version으로 만든다.

#### Response

```json
{
  "boothId": 7,
  "publishedVersion": 4,
  "publishedAt": "2026-08-08T16:30:00+09:00"
}
```

### GET `/booths/{boothId}/layouts/published`

Unity가 사용할 Published Layout 조회.

#### Response

```json
{
  "boothId": 7,
  "version": 4,
  "template": "PROJECT_EXHIBITION",
  "objects": []
}
```

활성 Lease가 없거나 입장이 닫힌 Booth는 일반 Unity Client에 Published Layout을 제공하지 않는다. Layout Object 식별자는 `objectId`, 장식·가구 자산 식별자는 `assetCode`를 사용한다. 신규 `type` 값은 기능 명세의 canonical 문자열을 사용하며 `SURVEY_KIOSK`, `CONSULTATION_DESK`, `LAPTOP`을 포함한다.

---

## 5. Project

### POST `/booths/{boothId}/projects`

프로젝트 정보 등록.

### GET `/booths/{boothId}/projects`

부스 프로젝트 목록 조회.

### GET `/projects/{projectId}`

프로젝트 상세 조회.

### PATCH `/projects/{projectId}`

Owner/Editor가 프로젝트 정보 수정.

#### 필드 후보

```json
{
  "name": "SSAFY FESTA",
  "description": "...",
  "thumbnailUrl": "...",
  "videoUrl": "...",
  "deployUrl": "...",
  "gitUrl": "...",
  "portfolioUrl": "..."
}
```

---

## 6. AI Agent Config 관리

AI 실행 자체는 FastAPI가 담당하지만 Agent 설정 Source of Truth는 Spring을 기본으로 한다.

### POST `/booths/{boothId}/agents`

### GET `/booths/{boothId}/agents`

### GET `/agents/{agentId}`

### PATCH `/agents/{agentId}`

#### Agent 예시

```json
{
  "name": "FESTA 프로젝트 도슨트",
  "role": "PROJECT_DOCENT",
  "tone": "FRIENDLY",
  "systemPrompt": "등록 문서를 기반으로 답변한다.",
  "responseLength": "MEDIUM",
  "servicePrice": 20,
  "handoffEnabled": true,
  "forbiddenTopics": ["PERSONAL_INFORMATION"]
}
```

---

## 7. AI Document Metadata / Upload

### POST `/agents/{agentId}/documents/upload-url`

S3 Presigned Upload URL 발급 구조를 권장한다.

#### Request

```json
{
  "fileName": "project.pdf",
  "contentType": "application/pdf",
  "size": 1048576
}
```

#### Response

```json
{
  "documentId": 152,
  "uploadUrl": "<presigned-url>",
  "s3Key": "booths/7/agents/78/documents/152/project.pdf"
}
```

### POST `/documents/{documentId}/complete`

업로드 완료 후 AI 처리 요청을 시작하기 위한 Spring 측 상태 변경/연계 Endpoint 후보.

### GET `/agents/{agentId}/documents`

문서 목록과 처리 상태 조회.

### PATCH `/documents/{documentId}`

ACTIVE / DISABLED 등 상태 관리.

---

## 8. Wallet / Coin Ledger

### GET `/wallets/me`

```json
{
  "balance": 150
}
```

### GET `/wallets/me/transactions`

Cursor 또는 Page 기반 거래 내역 조회.

### POST `/rewards/daily`

일일 지급 요청.

중복 요청 시 같은 날 한 번만 지급한다.

## 9. Survey

### POST `/booths/{boothId}/surveys`

### GET `/booths/{boothId}/surveys`

### GET `/surveys/{surveyId}`

### PUT `/surveys/{surveyId}`

### POST `/surveys/{surveyId}/responses`

#### Request 예시

```json
{
  "answers": [
    {"questionId": 1, "selectedOptionIds": [3]},
    {"questionId": 2, "text": "좋았습니다."}
  ]
}
```

검증:

- 마감
- 1인 1응답
- 질문 유효성
- 보상 중복

### GET `/surveys/{surveyId}/results`

Owner/허용된 Staff용 결과 조회.

---

## 10. Staff / Permission

### POST `/booths/{boothId}/staff-invitations`

직원 초대.

```json
{
  "userId": 45,
  "role": "CONSULTANT"
}
```

### POST `/staff-invitations/{invitationId}/accept`

### GET `/booths/{boothId}/staff`

### PATCH `/booths/{boothId}/staff/{userId}`

권한 변경.

### DELETE `/booths/{boothId}/staff/{userId}`

Owner 본인 제거 금지 등 정책 검증 필요.

---

## 11. Staff Presence

실시간 상태 자체는 Redis/WebSocket 중심이지만 상태 변경 API가 필요하면 다음 구조를 사용할 수 있다.

### PUT `/booths/{boothId}/staff/me/presence`

```json
{
  "status": "AVAILABLE"
}
```

실시간 구독 event는 Realtime 명세서에서 관리한다.

---

## 12. Consultation

### POST `/booths/{boothId}/consultations`

사람 상담 요청 생성.

```json
{
  "conversationId": "conv_01JABCXYZ",
  "agentId": 78
}
```

### GET `/consultations/{consultationId}`

### POST `/consultations/{consultationId}/accept`

한 명의 Staff만 성공해야 한다.

### POST `/consultations/{consultationId}/end`

상담 종료.

메시지는 WebSocket event 중심으로 처리하고, 기록 저장 정책에 따라 별도 REST History Endpoint를 둘 수 있다.

---

## 13. Inventory / Decoration — P1

### GET `/inventory/me`

### GET `/catalog/items`

### POST `/catalog/items/{itemId}/purchases`

검증:

- 판매 상태
- 가격
- 잔액
- 중복 요청

---

## 14. Minigame — P1

### POST `/minigames/{gameType}/sessions`

게임 시작용 세션/nonce 발급 후보.

### POST `/minigames/{gameType}/results`

결과 제출과 보상 처리.

```json
{
  "sessionId": "game_...",
  "score": 1820,
  "elapsedMs": 71320
}
```

클라이언트 결과를 무조건 신뢰하지 않고 최소한 세션 유효성·일일 제한을 검증한다.

---

## 15. Dashboard — P1

### GET `/booths/{boothId}/dashboard/summary?from=&to=`

```json
{
  "visits": 120,
  "aiUsages": 43,
  "consultations": 8,
  "surveyResponses": 31,
  "revenueCoin": 320
}
```

고급 체류 시간·전환율은 P2다.

---

## 16. World Session / Channel

자동 Channel은 P2지만 Client와 Dedicated Server 연결을 위해 최소 Session 계약이 필요하다.

### POST `/world-sessions`

```json
{
  "preferredPartyId": null
}
```

#### Response 후보

```json
{
  "sessionId": "ws_...",
  "worldId": "11F",
  "channelId": "11F-01",
  "serverEndpoint": "wss://...",
  "connectionToken": "short-lived-token",
  "expiresAt": "..."
}
```

실제 Unity Transport가 요구하는 형태에 따라 변경한다.

### DELETE `/world-sessions/{sessionId}`

정상 종료 신호. 비정상 종료는 TTL/Heartbeat로 정리한다.

---

## 17. Event / Competition — P2

### Event 후보

- `GET /events`
- `POST /admin/events`
- `POST /events/{eventId}/coupons`
- `POST /event-coupons/{couponId}/redeem`

### Competition 후보

- `GET /competitions/current`
- `POST /competitions/{id}/entries`
- `POST /competitions/{id}/votes`
- `GET /competitions/{id}/results`

정확한 토너먼트 주기가 미정이므로 P2 계약은 확정하지 않는다.

---

## 18. 주요 오류 코드

| Code | 의미 |
|---|---|
| `UNAUTHORIZED` | 인증 실패 |
| `FORBIDDEN` | 권한 없음 |
| `USER_NOT_FOUND` | 사용자 없음 |
| `BOOTH_NOT_FOUND` | Booth 없음 |
| `BOOTH_SLOT_ALREADY_LEASED` | 이미 임대됨 |
| `INSUFFICIENT_COIN` | Coin 부족 |
| `LAYOUT_VALIDATION_FAILED` | Layout 검증 실패 |
| `AGENT_NOT_FOUND` | Agent 없음 |
| `SURVEY_CLOSED` | 설문 마감 |
| `SURVEY_ALREADY_RESPONDED` | 1인 1응답 위반 |
| `CONSULTATION_ALREADY_ACCEPTED` | 다른 Staff가 먼저 수락 |
| `DUPLICATE_REQUEST` | 중복 요청 |
| `INTERNAL_ERROR` | 서버 오류 |

---

## 19. P0 API 우선 구현 순서

1. Auth / User
2. Booth Slot 조회
3. Lease 생성
4. Wallet / Coin Ledger
5. Draft Layout 저장
6. Publish
7. Published Layout 조회
8. Project
9. Agent Config
10. Document Upload Metadata
11. World Session 최소 계약

P1은 Survey → Staff → Consultation → Inventory → Dashboard → Minigame 순으로 연결하는 것을 권장한다.
