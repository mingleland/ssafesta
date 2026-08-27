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

### 1.3 오류 응답 (확정 · 구현됨)

```json
{
  "code": "BOOTH_SLOT_ALREADY_LEASED",
  "message": "이미 임대 중인 부스입니다.",
  "requestId": "req_..."
}
```

**2026-08-20부터 전 endpoint가 실제로 이 형태로 응답한다** (spec 005). 그 전까지는 "제안"이었고 구현은
코드 없이 한국어 문장만 반환하고 있었다. Breaking Change가 아니라 문서와의 정합 회복이다.

- `requestId`는 응답 헤더 `X-Request-Id`·서버 로그와 **같은 값**이다. 사용자가 화면에서 본 id 하나로 로그를 찾을 수 있다.
- **`errors`·`warnings` 배열은 항상 있다** — 보고할 것이 없으면 빈 배열이다. `errors`가 비어 있지 않으면
  요청은 거부된 것이고, `warnings`는 진행을 막지 않는다. 저장·공개 **성공** 응답의 `warnings`도 같은 규칙이다.
  키를 조건부로 빼면 `errors.length`가 클라이언트에서 터지므로 빼지 않는다.

```json
{
  "code": "LAYOUT_VALIDATION_FAILED",
  "message": "배치를 공개할 수 없습니다.",
  "requestId": "req_1a2b3c4d",
  "errors":   [ { "rule": "OBJECT_LIMIT", "message": "오브젝트는 12개까지입니다. 현재 14개" } ],
  "warnings": [ { "rule": "CONFIG_NOT_LINKED", "objectId": "ai-1", "message": "AI 직원이 연결되지 않았습니다." } ]
}
```

### 1.3-1 전역 `rule` 목록 (#58, 2026-08-23 확정)

`errors[]`·`warnings[]`의 `rule`은 클라이언트가 분기해도 되는 계약값이다. **아래는 endpoint를 가리지 않고 나오는 전역 rule**이고, 기능별 rule은 각 spec 계약 문서가 소유한다(예: Layout 계열은 `specs/005-booth-studio-layout/contracts/layout-api.md` §0).

| rule | 어디서 | 뜻 |
|---|---|---|
| `FIELD_INVALID` | 전 endpoint (Bean Validation) | 요청 필드 값이 제약을 위반. **문제 필드는 `field`에 담는다** — `rule` 자리에 필드명을 넣지 않는다 |

```json
{ "code": "VALIDATION_FAILED", "message": "요청 값이 올바르지 않습니다.", "requestId": "req_…",
  "errors": [ { "rule": "FIELD_INVALID", "field": "nickname", "message": "닉네임을 입력해 주세요." } ],
  "warnings": [] }
```

- `field`는 `objectId`와 같이 **없으면 키 자체가 빠진다**(`@JsonInclude(NON_NULL)`). 둘은 가리키는 대상이 달라 합치지 않는다 — `objectId`는 배치된 오브젝트, `field`는 요청 필드 경로다.
- `rule`은 **항상 규칙 어휘**다. 필드명·식별자를 `rule`에 넣으면 클라이언트의 화이트리스트 분기가 깨진다 (#58 §3).
- **`errors[].message`의 모양은 `rule`이 정한다.** 사용자에게 보여줄 문장은 봉투 최상위 `message`가 담고, `errors[].message`는 대개 그 항목의 사유 문장이지만 **rule이 기계값을 정의했으면 기계값이 온다.** 클라이언트는 `rule`로 분기한 뒤 그 rule의 계약대로 읽는다 — 문장에서 값을 정규식으로 캐내지 않는다.
  - `CURRENT_REVISION`은 **spec별로 모양이 다르다** — 005(Layout)는 문장(값 소비자 없음), 019(Game Studio)는 **십진수 문자열**이다. 각 spec 계약 문서가 자기 모양을 소유한다.
  - `ApiErrorDetail`에 타입 있는 값 필드는 **추가하지 않는다.** 전 endpoint 공유 스키마인데 값이 필요한 rule이 아직 하나뿐이다. **기계값이 둘 이상 필요한 rule이 나오면 그때 필드로 올린다** (#58 §5 재확정, 2026-08-24).

### 1.3-2 프레임워크 거부의 code (#113, 2026-08-27 확정)

컨트롤러에 닿기 전에 Spring 이 거부한 요청도 같은 봉투로 나온다. **클라이언트 잘못은 전부 4xx 다** —
여기 있는 어느 것도 `INTERNAL_ERROR` 가 아니다.

| 상황 | status | code |
|---|---|---|
| 매핑되지 않은 경로 (미구현 endpoint 포함) | 404 | `NOT_FOUND` |
| 그 경로가 지원하지 않는 method | 405 | `METHOD_NOT_ALLOWED` |
| 필수 쿠키·헤더·파라미터 누락, 타입 불일치 | 400 | `VALIDATION_FAILED` |
| 지원하지 않는 `Content-Type` | 415 | `UNSUPPORTED_MEDIA_TYPE` |
> **`Accept` 가 JSON 을 허용하지 않으면 이 봉투 자체를 보낼 수 없다.** 예: `Accept: application/xml` 로
> 부르면 서버는 오류 봉투를 만들어 놓고도 그것을 기록하지 못해 `HttpMediaTypeNotAcceptableException` 이
> advice 밖으로 새어 나간다. 이 경우 응답 본문은 **우리 계약이 아니다.** 클라이언트는 `application/json` 을
> 받을 수 있어야 한다. (2026-08-27 최초 작성 시 `406 NOT_ACCEPTABLE` 행을 적었으나 실측에서 성립하지
> 않아 걷어냈다 — !56 7차 리뷰.)

- **미구현 endpoint 는 404 다.** 서버 장애(`INTERNAL_ERROR`)와 구분되지 않으면 클라이언트가 재시도할지
  포기할지 정할 수 없다 — `INTERNAL_ERROR` 는 재시도 가능 코드로 정렬돼 있으므로(#104·#48) 미구현 경로를
  500 으로 답하면 클라이언트가 그것을 재시도한다.
- 2026-08-27 이전에는 위 표의 네 줄이 **모두 500 `INTERNAL_ERROR`** 였다. `GlobalExceptionHandler` 가 프레임워크
  거부를 `ResponseStatusException` 으로 매칭했는데, Spring 7 의 프레임워크 예외는 그 클래스가 아니라
  `ErrorResponse` **인터페이스**로 상태를 싣기 때문이다. Breaking Change 가 아니라 정합 회복이다.
- 5xx 는 종전대로 `INTERNAL_ERROR` 이고 서버 로그에 error 레벨로 크게 남는다 (T-24).
- **`code` 가 선언한 status 와 응답 status 는 항상 같다.** `ErrorCode` 는 코드마다 status 를 들고 있고
  클라이언트는 `code` 로 분기하므로, 둘이 어긋나면 `ErrorCode.status()` 가 그 응답에 대해 거짓이 된다.
  그래서 서버는 **코드가 선언한 status 로** 답한다. 계약에 코드가 없는 status 는 일반 코드
  (`VALIDATION_FAILED`)로 답하며, 원래 status 는 debug 로그에 남는다.

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

Access Token 갱신. `refresh_token` 쿠키(HttpOnly)로 인증한다. Refresh 정책은 보안 설계에서 확정한다.

**세션이 없으면 `401 INVALID_MEMBER_TOKEN` 이다** (#113, 2026-08-27 확정). 쿠키가 **없는 경우·만료된 경우·
이미 쓰인 경우**가 전부 같은 코드다 — 사용자에게는 "로그인돼 있지 않다" 하나의 사건이라 두 이름을 주지 않는다.

- 쿠키가 없는 것은 **정상 상태**다. FE 는 페이지 로드마다 이 endpoint 를 1회 호출하는데, RT 는 HttpOnly 라
  FE 가 존재 여부를 읽을 수 없고 그게 설계 의도다(헌법 13조). 따라서 비로그인·게스트 방문자는 매번 이 401 을
  받으며, 이것을 서버 오류로 취급하면 안 된다.
- 2026-08-27 이전에는 이 세 경우가 모두 **500** 이었다. 방문자 전원이 페이지를 열 때마다 서버 오류 로그를
  하나씩 남겼다.
- Origin 이 신뢰 목록과 다르면 쿠키를 보기 전에 `403 UNTRUSTED_ORIGIN` 으로 먼저 거절한다.

### POST `/auth/logout`

현재 인증 세션 종료.

### GET `/users/me`

내 기본 정보 조회. 회원 전용(게스트 `403 MEMBER_ONLY`).

#### Response 예시

```json
{
  "userId": 12,
  "nickname": "FESTA_USER",
  "status": "ACTIVE",
  "providers": ["GOOGLE"],
  "avatarCode": "fa|3=SK_Hair_Long_01|c=FF8800"
}
```

> **예시 정정 (2026-08-24)** — 이전 예시의 `wallet.balance`·`boothId`는 이 응답에 **없다.** 잔액은 `GET /wallets/me`(§8), 부스는 `GET /booths/{id}`(§3)가 소유한다. 구현(`MyAccountController.MyAccountResponse`)에 맞춰 고쳤다.

`avatarCode`는 아직 저장하지 않은 사용자에게 **`null`** 이다(키는 존재). 서버가 기본 프리셋을 만들어 넣지 않는다 — 폴백은 클라이언트 몫이다(spec 013 FR-010).

### PUT `/users/me/avatar`

아바타 외형 저장 (spec 013a, #24 확정). 회원 전용.

```json
// 요청
{ "avatarCode": "fa|3=SK_Hair_Long_01|c=FF8800" }

// 200 — 저장한 값을 그대로 echo
{ "avatarCode": "fa|3=SK_Hair_Long_01|c=FF8800" }
```

| 항목 | 규칙 |
|---|---|
| 서버 검증 | **길이 ≤ 3800자**, **인쇄 가능 ASCII `0x20`–`0x7E`** 두 가지뿐 |
| 파싱 | **하지 않는다.** 문자열은 서버에게 불투명하며 trim·대소문자·정규화도 하지 않는다 — 저장한 바이트열이 그대로 돌아온다 |
| 저장 컬럼 | `users.avatar_code` **`TEXT`** (헌법 23조 — `VARCHAR(32)` 금지, T-24) |
| 거부 | `400 VALIDATION_FAILED` + `errors[0] = { "rule": "FIELD_INVALID", "field": "avatarCode", "message": … }`. 빈 값·길이 초과·문자셋 위반이 **서로 다른 문장**을 받는다 |
| 게스트 | `403 MEMBER_ONLY` (헌법 12조 — 외형을 영속 저장하지 않는다) |

상한 3800은 Unity `AvatarAppearance.MaxEncodedLength`가 소유한 값이다. **낮추지 않는다** — 모듈러 인코딩(`fa|…`)은 파츠 이름이 그대로 들어가 길다.

정본 계약: `specs/013-avatar-customization/contracts/avatar-profile-api.md`

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
    "primaryColor": "#3B82F6",
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
  "expectedRevision": 0,
  "schemaVersion": 1,
  "template": "PROJECT_EXHIBITION",
  "objects": [
    {
      "objectId": "screen-1",
      "type": "VIDEO_SCREEN",
      "position": {"x": 2.1, "y": 0.0, "z": 1.4},
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

Unity가 사용할 Published Layout 조회. **인증 불필요.**

#### Response

```json
{
  "boothId": 7,
  "version": 4,
  "schemaVersion": 1,
  "template": "PROJECT_EXHIBITION",
  "objects": []
}
```

`version`은 **공개 회차**, `schemaVersion`은 **Layout JSON 구조 버전**이다. 두 값을 같은 이름으로 부르면
Unity가 하나로 파싱한다 (`BoothLayoutDto`에는 `version`만 있다).

공개된 것이 없으면 `404 LAYOUT_NOT_PUBLISHED`, 임대가 유효하지 않으면 `409 BOOTH_LEASE_EXPIRED`다.

### GET `/booth-slots/{slotId}/layouts/published` — spec 005 신설 (#62, 2026-08-23)

Unity가 부스 방(앵커)에서 호출하는 경로. **인증 불필요.** 응답 body는 `/booths/{boothId}/layouts/published`와 **완전히 동일**하다.

`boothId`는 방 번호가 아니다 — 슬롯(고정)과 부스(임대 시 발급)는 다른 축이고 재임대하면 같은 방의 `boothId`가 바뀐다. 서버가 `슬롯 → 유효 임대 → boothId` 해석을 흡수한다.

- 빈 슬롯·미공개 → `404 LAYOUT_NOT_PUBLISHED` (Unity의 graceful skip 그대로)
- 임대 만료 → `409 BOOTH_LEASE_EXPIRED`
- `slotId` 1~12가 Unity 앵커 `01~12`와 대응 (V12 시드가 고정)

상세는 `specs/005-booth-studio-layout/contracts/layout-api.md` §11.

### GET `/booth-layout-templates` — spec 005 신설 (#19 ④, 2026-08-21)

편집기용 템플릿 카탈로그. footprint와 오브젝트 상한을 세 파트가 각자 알던 것을 한 곳에서 받는다. **권한 필요.**

```json
{
  "templates": [
    {
      "template": "PROJECT_EXHIBITION",
      "footprint": {"width": 6.0, "depth": 6.0, "height": 2.72},
      "maxObjects": 12
    }
  ]
}
```

- `template` 허용값은 `PROJECT_EXHIBITION` 단독 — `DEFAULT`는 셸 1종·1:1 확정으로 제거(V11 이관, #19 ④·#45 C-06).
- `height` 2.72는 셸 벽 패널 실측이다. Layout 좌표·실물 검증도 같은 값을 쓴다 (`0 ≤ y ≤ 2.72`).
- 검증 오류·경고 rule 추가분: 실물 영역 이탈 `AREA_OUT_OF_BOUNDS`(error), 통행 판정
  `FRONT_BLOCKED`·`ISOLATED_AREA`(warning, 공개 시점만). 기하 계약 상세는
  `specs/005-booth-studio-layout/contracts/layout-api.md` §10.

### PUT `/booths/{boothId}/facade` — spec 005 신설

부스 외부 표현 수정. 내부 Layout과 달리 자유 배치가 아니라 정해진 4필드다.

```json
{
  "themeCode": "SSAFY_BLUE",
  "primaryColor": "#3B82F6",
  "signText": "AI 프로젝트 전시관",
  "logoUrl": null
}
```

- `themeCode`: `DEFAULT` / `SSAFY_BLUE` / `WARM` / `MONO`
- `primaryColor`: `#RRGGBB` 또는 null. **12색 팔레트 안의 값만 허용**하고 저장 시 **대문자로 정규화**한다 (#17, 2026-08-23 확정 — 값의 정본은 `specs/005-booth-studio-layout/contracts/layout-api.md` §6. 팔레트는 테마와 무관한 전역 1개)
- `signText`: 60자 이하 또는 null
- `logoUrl`: **https만 허용**, 2048자 이하 또는 null (http는 mixed content로 차단되어 조용히 안 보인다)

소유자·Staff만 호출할 수 있고, 필드 검증 실패는 `400 VALIDATION_FAILED`(#17 확정 — 예:
`"대표색은 #RRGGBB 형식이어야 합니다."`), 만료된 부스는 `409 BOOTH_LEASE_EXPIRED`다. 조회는
`GET /booths/{boothId}`의 `facade` 필드를 쓴다.

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

#### 설정 허용값 (2026-08-27 확정 — spec 007 C-12, [GitLab #112](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/112))

세 필드는 자유 문자열이 아니라 **화이트리스트**다. 저장·검증은 Spring이 하고, 값을 해석해 프롬프트를 만드는 것은 FastAPI다.

| 필드 | 허용값 | 뜻 |
|---|---|---|
| `role` | `PROJECT_DOCENT` | 전시 프로젝트를 해설한다 |
| | `GUIDE` | 부스 운영·이용을 안내한다 |
| `tone` | `FRIENDLY` **(기본)** | 친근한 존댓말 |
| | `PROFESSIONAL` | 격식체. 정확·중립 |
| | `ENTHUSIASTIC` | 활기찬 어조 |
| `responseLength` | `SHORT` | 1~3문장 |
| | `MEDIUM` **(기본)** | 4~6문장 |
| | `LONG` | 7~12문장 |

- `responseLength`는 **문장 수** 기준이다. 문단은 길이가 정해지지 않아 기준이 되지 못한다.
- `responseLength → max_tokens` 매핑은 **AI 파트 소유**다 (제안값 200/400/800, 모델 확정 후 재검증). Spring은 어휘만 저장하고 토큰 수를 저장하지 않는다.
- `tone`에 길이를 뜻하는 값을 두지 않는다 — `responseLength`와 어긋났을 때 우선순위가 없어진다.
- **`tone` 값은 고정이 아니다.** 구현 후 튜닝 대상이며, AI 파트가 프롬프트 템플릿 수정으로 먼저 흡수하되 불가피하면 값이 바뀔 수 있다 (#112 AI 회신). 지금은 증감 근거가 없어 3종이다. **값 추가는 가산적이라 안전하지만 삭제·변경은 저장된 데이터를 무효로 만들므로 마이그레이션을 동반한다** — 클라이언트는 이 목록을 하드코딩하지 말고 서버 응답 스키마를 따르는 편이 안전하다.

#### 부스당 AI 직원 수

**1명이다** (spec 007 C-13, 2026-08-27 팀 합의). FE는 목록·다중 선택 없이 **단일 편집 폼**으로 만든다.

부스는 직원을 하나만 두고, 대신 **그 직원의 `role`이 부스 종류에서 갈린다** — 프로젝트 부스면 `PROJECT_DOCENT`, 이벤트 부스면 `GUIDE`. 위 표의 `role`이 정확히 2종인 이유가 이것이다.

> **다만 지금은 `role`을 클라이언트가 보낸다.** 부스 종류가 아직 코드에 없어서(`LayoutTemplate` 값이 `PROJECT_EXHIBITION` 하나) 서버가 파생시키면 `GUIDE`가 도달 불가능한 값이 된다. 부스 종류가 생기면 서버가 종류별 허용 `role`로 좁히며, **값 집합은 그대로라 그때 클라이언트 코드는 바뀌지 않는다** — 고를 수 있는 선택지만 줄어든다.

---

## 7. AI Document Metadata / Upload

### POST `/agents/{agentId}/documents/upload-url`

Presigned Upload URL 발급. 중복 판정을 겸한다 (#84, 2026-08-25 3파트 합의).

#### Request

```json
{
  "fileName": "project.pdf",
  "contentType": "application/pdf",
  "size": 1048576,
  "contentSha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
}
```

`contentSha256` 은 **필수**이며 `^[a-f0-9]{64}$` 를 만족해야 한다. 프론트가 업로드 전 파일 바이트로
계산해 보낸다. 이 값은 **사전 중복 확인에만** 쓰고, 최종 검증은 FastAPI 가 R2 원본을 다시 해싱해서 한다
(FR-019, spec 007 C-08). 형식 위반은 `400 VALIDATION_FAILED` 다.

#### Response — 신규

```json
{
  "duplicate": false,
  "documentId": 153,
  "uploadUrl": "<presigned-url>",
  "objectKey": "booths/7/agents/78/documents/153/project.pdf"
}
```

#### Response — 중복

```json
{ "duplicate": true, "documentId": 152 }
```

- **중복은 오류가 아니다.** 200 으로 답하고 `duplicate` 로 갈린다 — `DOCUMENT_DUPLICATE` 오류 코드는
  만들지 않는다. 요청 목적(그 파일을 등록하는 것)이 **이미 달성돼 있는** 상태이고, 같은 spec 의 FR-025
  (중복 처리 요청은 기존 활성 작업을 반환)와 019 Portal 의 `unavailableReason`(#33)이 같은 결이다.
- 중복일 때 `uploadUrl`·`objectKey` 는 **없다**(키 자체가 빠진다). 프론트는 `duplicate` 로 분기해
  "이미 등록된 문서입니다" 를 띄우고 기존 `documentId` 를 쓴다.
- **판정 대상은 같은 Agent 의 `QUEUED`·`PROCESSING`·`READY` 문서뿐**이다. `FAILED`·`DISABLED` 는 제외라
  실패한 문서와 같은 파일을 다시 올리는 것은 **허용**된다 — 막으면 사용자가 빠져나갈 길이 없다.
  따라서 같은 (agent, hash) 행이 복수 존재할 수 있고 유일성은 활성 상태 안에서만 성립한다.

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

## 17A. Game Studio — P2 Draft

Game Studio는 Unity 미니게임 API와 분리한다. Spring은 GameProject의 Draft/Published Version과
부스 Portal Binding의 Source of Truth이며, 웹 Runtime은 Published Version만 조회한다.

- 편집: `POST /api/v1/games`, `GET /api/v1/games/{gameId}/draft`, `PUT /api/v1/games/{gameId}/draft`
- 발행: `POST /api/v1/games/{gameId}/publish`, `GET /api/v1/games/{gameId}/versions`
- 실행: `GET /api/v1/games/{gameId}/published`
- 부스 연결: `GET /api/v1/game-portals/{configId}`
- 저장 요청은 `expectedRevision`과 GameProject를 포함하고 불일치 시 HTTP 409
  `GAME_REVISION_CONFLICT`를 반환한다. 좌표 clamp·unknown field 삭제 같은 자동 보정은 금지한다.
- Draft 저장은 구조·schema·상한을 검증하고, Publish는 참조·소유권·Asset·Dialogue 의미를 다시 검증한다.
- Publish는 Draft read→검증→`game_published_versions` append→`games.published_version` 갱신을
  단일 트랜잭션으로 처리하며 Draft와 기존 발행본은 유지한다.
- GameProject에는 Asset binary·브라우저 임시 URL을 저장하지 않는다. MVP는 Game Studio의 versioned
  builtin Asset catalog를 사용하고 사용자 업로드는 별도 Asset spec으로 분리한다.
- 현재 공개 포인터를 따라가는 `GET /games/{gameId}/published`는 `Cache-Control: no-cache` + ETag 재검증이다 — 재공개하면 같은 URL이 다른 본문을 가리키므로 장기 cache를 걸면 옛 version이 나온다. 긴 `max-age`·`immutable`은 후속 version 고정 URL에만 붙인다. Portal 실행 가능 여부는 `Cache-Control: no-store`다.
- 독립 play route와 Portal overlay open 시 REST 조회로 신규 진입을 판정하며 Game Studio 전용 socket은 만들지 않는다.
- 공개 중단 전에 이미 GameProject를 로드한 무보상 로컬 세션은 완료까지 허용한다.
- 일반 삭제는 soft delete, 회원 탈퇴는 Game·Draft·Published·Asset·Score hard delete다. Published 이력은 Game 존속 중 유지한다.
- Portal 공개 `configId`는 signed Int32 `1..2147483647`; DB는 별도 `INTEGER UNIQUE NOT NULL CHECK (>0)`를 사용한다.
- MVP 플레이 결과·보상·랭킹 API는 만들지 않는다.
- 오류 코드·`rule` 어휘와 생성·버전 목록 shape은 019 계약 문서가 소유한다 (`game-api.md` §오류 코드와 rule). `rule` 이름은 `contracts/fixtures/`의 reference validator가 정한 것을 그대로 쓰고, 서버가 새 어휘를 만들 때만 계약에 추가한다.

상세 계약은 [`specs/019-game-studio/contracts/game-api.md`](../specs/019-game-studio/contracts/game-api.md)다.
#21의 기술 답변과 [#33](https://github.com/kanghyunsoon/ssafesta/issues/33)·
[#34](https://github.com/kanghyunsoon/ssafesta/issues/34)의 교차 계약을 반영했다.

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
| `LAYOUT_VALIDATION_FAILED` | Layout 검증 실패 (`errors` 배열 동반) |
| `LAYOUT_REVISION_CONFLICT` | 다른 편집자가 먼저 저장 (Draft 낙관적 잠금) |
| `LAYOUT_NOT_PUBLISHED` | 공개된 배치 없음 |
| `BOOTH_EDITOR_FORBIDDEN` | 부스 편집 권한 없음 (소유자·Staff 아님) |
| `BOOTH_LEASE_EXPIRED` | 임대 만료 — 부스 입장·공개·AI 대화가 같은 코드를 쓴다 |
| `BOOTH_SLOT_NOT_RENTABLE` / `ACTIVE_LEASE_LIMIT` | 임대 불가 슬롯 / 1인 1임대 위반 |
| `VALIDATION_FAILED` | 요청 값 오류 (400) |
| `GAME_NOT_FOUND` *(019)* | Game 없음 |
| `GAME_DELETED` *(019)* | soft delete된 Game |
| `GAME_FORBIDDEN` *(019)* | 소유자 아님 — Authoring·비공개 접근 |
| `GAME_LIMIT_EXCEEDED` *(019)* | 계정당 활성 Game 상한(기본 20) 초과 — `message`가 상한과 해결 방법을 담는다 |
| `GAME_REVISION_CONFLICT` *(019)* | Draft revision 충돌. `errors[0].rule=CURRENT_REVISION`의 `message`는 **십진수**다 (§1.3) |
| `GAME_VALIDATION_FAILED` *(019 제안)* | GameProject 검증 실패 (`errors` 배열 동반) — rule 표는 019 계약이 소유 |
| `GAME_NOT_PUBLISHED` *(019)* | 실행 가능한 Published Version 없음 |
| `GAME_NOT_PUBLIC` *(019)* | Game이 `PRIVATE` |
| `GAME_SCHEMA_UNSUPPORTED` *(019)* | 서버·Runtime이 지원하지 않는 schemaVersion |
| `GAME_PROJECT_INVALID` *(019)* | **저장된** snapshot이 재검증 실패 — 500, 서버 결함 |
| `CONFIG_NOT_FOUND` *(019)* | Portal `configId`의 Binding 없음. 실행 불가 사유는 오류가 아니라 200 응답의 `unavailableReason`이다 |
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
