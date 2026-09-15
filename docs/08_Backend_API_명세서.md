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
계보가 폐기된 경우**가 전부 같은 코드다 — 사용자에게는 "로그인돼 있지 않다" 하나의 사건이라 두 이름을 주지 않는다.

**한 가지만 갈라져 있다 — `401 REFRESH_TOKEN_ROTATED`** (`S15P21A604-764`, GitLab #198). 방금 회전된 토큰이
다시 온 경우이고, 탭을 하나 더 열면 그 탭도 부트스트랩에서 이 endpoint 를 부르기 때문에 정상 사용에서 생긴다.
**세션은 살아 있고 쿠키는 이미 새 값으로 교체돼 있다** — 이 응답은 `Set-Cookie` 를 내지 않으며, 한 번 더 보내면
성공한다. 로그인 화면으로 보내면 안 된다.

> **즉시 한 번이 아니라 짧은 backoff 를 둔 제한 재시도로 붙인다.** 이 401 이 이긴 쪽의 `Set-Cookie` 보다 먼저
> 도착할 수 있고, 그때 곧바로 재시도하면 옛 쿠키를 다시 보내게 된다. 서버가 보장하는 것은 "이 401 은 재시도
> 가능하다" 까지이고 언제 재시도할지는 클라이언트 몫이다 — 응답 순서는 서버가 정할 수 없다.

유예는 `app.auth.refresh-reuse-grace`(기본 `PT30S`)다. 그 창 밖의 재사용은 그대로 계보째 끊는다(spec 001 시나리오 7).

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
  "providers": ["GOOGLE", "SSAFY"],
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
| 형식 검증 | **길이 ≤ 3800자**, **인쇄 가능 ASCII `0x20`–`0x7E`** |
| 소유권 검증 | 저장 문자열은 변형하지 않되 `fa|` 형식의 `i=` 8슬롯만 읽는다. 0은 미착용. preset·legacy·형식 불일치는 호환을 위해 품목 주장 없음으로 통과 |
| 저장 컬럼 | `users.avatar_code` **`TEXT`** (헌법 23조 — `VARCHAR(32)` 금지, T-24) |
| 거부 | `400 VALIDATION_FAILED` + `errors[0] = { "rule": "FIELD_INVALID", "field": "avatarCode", "message": … }`. 빈 값·길이 초과·문자셋 위반이 **서로 다른 문장**을 받는다 |
| 미보유 거부 | `409 AVATAR_ITEM_NOT_OWNED` + 미보유 품목마다 `{ "rule": "ITEM_NOT_OWNED", "objectId": "<assetKey>", "message": … }` |
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

내 Booth와 현재 Lease 조회. 응답에 **`homepageUrl`**(spec 016 신설)이 포함되며, 이쪽은 공개 여부와 무관하게 **항상 저장값**이다 — 미공개 상태에서도 스튜디오 폼을 프리필해야 하기 때문이다. 미등록이면 `null`.

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
  "publishedLayoutVersion": 4,
  "homepageUrl": "https://my-team-project.example.com"
}
```

- `homepageUrl`: 부스 노트북이 여는 홈페이지 (spec 016 FR-003 신설). **`publishedLayoutVersion`이 `null`이면 이 값도 `null`로 내려간다** — "공개 상태"를 *공개된 Layout이 있는 상태*로 해석한다(노트북은 공개 Layout 안에만 있으므로 방문자가 URL을 쓰는 순간과 일치).
  - **등록값이 없으면 그 부스 프로젝트의 `deployUrl`(서비스 주소)로 폴백한다** (2026-09-14 결정). `booths.homepage_url`은 등록 endpoint만 있고 **화면이 없어** 실서비스에서는 늘 비어 있었고, 소유자가 실제로 주소를 입력하는 칸은 프로젝트 관리의 "서비스 주소" 하나다. 우선순위는 **등록값 > 프로젝트 `deployUrl`** — 폴백은 빈 자리만 메우므로 등록 화면이 생기면 저절로 사라진다.
  - 둘 다 없으면 `null`이라 FE는 여전히 `null` 하나로 "미등록/미공개" 안내 분기를 끝낸다.
  - `GET /booths/mine`(소유자 프리필)에는 **폴백을 적용하지 않는다** — 등록한 적 없는 값을 폼에 채우면 소유자가 그것을 다시 저장해 한 주소가 두 컬럼으로 복제된다.
- ⚠️ **회차 필드명은 endpoint마다 다르고 합치지 않는다** (2026-08-26 리드 확정, #97). 이 Booth 상세는 **`publishedLayoutVersion`**, Layout Draft 조회·Publish 결과는 **`publishedVersion`**이다.

### DELETE `/booth-slots/{slotId}/leases/mine` — spec 004 신설 (D12, 2026-09-14)

만료를 기다리지 않고 임차인이 자리를 내놓는다 (FR-020). **`204 No Content`, 본문 없음.**

- **환불 없다** (FR-021). D06의 "변심 환불 없음"이 그대로 적용되고, 반납은 자리만 비운다. 재임대는 새 결제다.
- 반납 즉시 슬롯이 `AVAILABLE`이 되고 활성 임대 한도(D01)가 풀린다. 콘텐츠는 보존된다 (FR-010).
- 경로의 `slotId`는 **확인용**이다. 활성 임대는 하나뿐이라 없어도 찾을 수 있지만, 낡은 화면이 엉뚱한 부스를 날리는 것을 막는다.

| 코드 | 오류 코드 | 상황 |
|---|---|---|
| 204 | — | 반납 완료 |
| 403 | `MEMBER_ONLY` | 게스트 토큰 |
| 404 | `ACTIVE_LEASE_NOT_FOUND` | 이 자리에 반납할 내 임대가 없다 |

> ⚠️ **재시도한 요청도 `404`다. 오류로 표시하지 않는다** — 슬롯 목록과 내 부스를 다시 읽는 신호다. `ACTIVE_LEASE_NOT_FOUND`는 "임대가 없다"·"내 임대가 다른 자리에 있다"·"방금 만료됐다"를 가르지 않는다. 셋 다 화면이 낡았다는 뜻이고 클라이언트가 할 일이 같다.

### POST `/booths/{boothId}/leases/extend` — P1

임대 연장. 정확한 정책은 TBD.

### Lease 종료 처리 계약

- `ACTIVE`에서 나가는 전환(`EXPIRED` 만료 · `CANCELLED` 반납)과 `booths.current_slot_id` 해제는 하나의 트랜잭션으로 처리한다. **만료와 반납은 같은 경로를 지나고 기록에 남는 단어만 다르다** (D12).
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

부스 이름과 외부 표현 수정. 내부 Layout과 달리 자유 배치가 아니라 정해진 5필드다.

```json
{
  "name": "AI 프로젝트 전시관",
  "themeCode": "SSAFY_BLUE",
  "primaryColor": "#3B82F6",
  "signText": "AI 프로젝트 전시관",
  "logoUrl": null
}
```

- `name`: 부스 이름. 1~100자. **생략하거나 `null`이면 현재 이름을 유지한다** — 나머지 네 필드가 "안 보내면 비운다"인 것과 반대다(`booths.name`이 `NOT NULL`이라 비울 수 없고, 필수로 막으면 이름 칸이 없던 기존 저장이 전부 400이 된다). 빈 문자열·공백만 있는 값은 `400`. **응답에는 포함되지 않는다** — 저장된 이름은 `GET /booths/{boothId}`·`GET /booths/mine`의 `name`에서 읽는다 (2026-09-15 신설, S15P21A604-756)
- `themeCode`: `DEFAULT` / `SSAFY_BLUE` / `WARM` / `MONO`
- `primaryColor`: `#RRGGBB` 또는 null. **12색 팔레트 안의 값만 허용**하고 저장 시 **대문자로 정규화**한다 (#17, 2026-08-23 확정 — 값의 정본은 `specs/005-booth-studio-layout/contracts/layout-api.md` §6. 팔레트는 테마와 무관한 전역 1개)
- `signText`: 60자 이하 또는 null
- `logoUrl`: **https만 허용**, 2048자 이하 또는 null (http는 mixed content로 차단되어 조용히 안 보인다)

소유자·Staff만 호출할 수 있고, 필드 검증 실패는 `400 VALIDATION_FAILED`(#17 확정 — 예:
`"대표색은 #RRGGBB 형식이어야 합니다."`), 만료된 부스는 `409 BOOTH_LEASE_EXPIRED`다. 조회는
`GET /booths/{boothId}`의 `facade` 필드를 쓴다.

활성 Lease가 없거나 입장이 닫힌 Booth는 일반 Unity Client에 Published Layout을 제공하지 않는다. Layout Object 식별자는 `objectId`, 장식·가구 자산 식별자는 `assetCode`를 사용한다. 신규 `type` 값은 기능 명세의 canonical 문자열을 사용하며 `SURVEY_KIOSK`, `CONSULTATION_DESK`, `LAPTOP`을 포함한다.

### PUT `/booths/{boothId}/homepage` — spec 016 신설

부스 노트북이 여는 홈페이지 주소 등록·수정·해제. **부스당 1개**이며 `booths.homepage_url`에 저장한다 — **Layout JSON에는 넣지 않는다**(C-01·C-02, 2026-08-26 확정 #97). facade와 같이 Draft/Publish를 타지 않고 즉시 반영되며, 방문자 **노출**은 위 `GET /booths/{boothId}`의 게이트가 따로 건다.

```json
{
  "homepageUrl": "https://my-team-project.example.com"
}
```

→ `200 { "homepageUrl": "https://my-team-project.example.com" }` (저장한 그대로 echo)

- 저장은 **원문 그대로** — trim·정규화·대소문자 변경이 없다(왕복 무손실).
- 검증은 009 프로젝트 URL 5 종과 **같은 검증기**(`common/HttpUrlValidator`)를 탄다 — `http`/`https` · ≤2048자 · **포트가 있으면 1~65535** · 한글 도메인 허용(판정만 punycode, 저장은 원문). 2026-08-28 공용화 때 포트 규칙이 016 에도 함께 걸렸다.
- `{ "homepageUrl": null }` = **등록 해제**. 빈 문자열 `""`은 해제가 아니라 400이고, **필드가 없는 `{}`도 400**이다 — 해제는 명시적 `null`만 인정한다(직렬화 실수로 URL이 조용히 지워지는 것을 막는다).
- 검증은 순서대로 첫 위반에서 거부하고 **사유별 다른 문장**을 준다: 필드 부재 → blank → 길이 ≤ 2048 → URI 파싱·절대 URI → scheme ∈ {`http`, `https`} → host 존재. **scheme을 host보다 먼저 본다** — `javascript:`·`data:`는 host가 없어 순서가 뒤바뀌면 스킴 위반이라는 실제 사유가 전달되지 않는다.
- **`http`를 허용한다**(facade `logoUrl`과 의도적 비대칭) — 로고는 페이지 안에 임베드되어 mixed content로 조용히 죽지만, 홈페이지는 이동 대상이고 iframe이 막히면 새 탭으로 연다. 혼합콘텐츠 경고 UX는 React 몫.
- 소유자·Staff만 호출할 수 있다(facade·layout과 동일한 편집자 범위). 실패: `400 VALIDATION_FAILED` + `errors[0] = { rule: "FIELD_INVALID", field: "homepageUrl", message }` · `401` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 BOOTH_NOT_FOUND` · `409 BOOTH_LEASE_EXPIRED`. **신규 오류 코드·rule 없음.**
- 서버는 URL의 도달성·iframe 삽입 가능 여부를 판정하지 않는다 — 사전 판정이 불가능하고, 시도·감지·fallback은 React 레이어다.
- **Publish 검증 연동**: `LAPTOP` 오브젝트가 있는데 이 URL이 미등록이면 Publish 응답에 warning `CONFIG_NOT_LINKED`("홈페이지 주소가 등록되지 않았습니다.")가 실린다. `LAPTOP`은 `configId`를 갖지 않으므로 판정 근거가 `configId` 부재가 아니라 **URL 미등록**이다 — 코드·봉투는 기존 그대로. FE는 `LAPTOP`에 `configId`를 보내지 않는다(보내면 `CONFIG_UNVERIFIED`가 붙는다).
- **`SURVEY_KIOSK`도 같은 모양이다** (`S15P21A604-699`, GitLab #181): 설문 바인딩이 부스 기준이라(spec 010 C-06) 부스당 설문이 1개고 `GET /booths/{boothId}/survey/run`이 부스로 찾는다. 그래서 판정 근거가 `configId` 부재가 아니라 **그 부스에 설문이 없음**이고, warning `CONFIG_NOT_LINKED`("이 부스에 설문이 없습니다.")로 나간다. **게시는 막지 않는다**(C-04) — 키오스크를 먼저 놓고 설문을 나중에 만드는 순서가 정상이다. `configId`를 실어 보내도 서버가 읽지 않으며 `CONFIG_UNVERIFIED`도 붙지 않는다.

---

## 5. Project

부스가 전시하는 프로젝트. **부스당 1개**다 (spec 009 C-01, 2026-08-28 확정). 정본 계약은
`specs/009-project-exhibition/contracts/project-api.md`.

편집 권한은 **소유자 또는 스태프**(facade·layout과 같은 편집자 범위, `BoothAccessGuard`).
회원만 — 게스트는 `403 MEMBER_ONLY`. 쓰기는 **유효 임대**를 요구하고, 읽기는 만료돼도 된다
(009 FR-008 — 만료돼도 데이터는 보존된다).

**예외는 방문자 조회 하나다** — `GET /booths/{boothId}/projects/published`는 게스트가 정상
경로이고 토큰 없이 `200`이다. 편집·편집자 조회는 위 규칙 그대로다.

> ⚠️ 직원 역할 게이트(011 C-09 `ADMIN`·`CONTENT_EDITOR`)는 **아직 걸려 있지 않다.**
> `BoothAccessGuard`가 `role`을 읽지 않으며 005·016도 같은 상태다 — 011 구현 시 가드 한 곳에서
> 일괄로 닫는다 ([GitLab #116](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/116)).

### 공통 표현

```json
{
  "projectId": 1,
  "name": "SSAFY FESTA",
  "description": "메타버스 축제 플랫폼",
  "thumbnailUrl": "https://cdn.example.com/thumb.png",
  "videoUrl": "https://youtu.be/xxxx",
  "deployUrl": "https://festa.example.com",
  "gitUrl": "https://lab.ssafy.com/team/festa",
  "portfolioUrl": null
}
```

`name` 외 전부 `null` 가능. **키는 항상 있고 값이 없으면 `null`이다** — 서버가 기본값을 채우지
않는다 (`avatarCode`와 같은 규칙, §2).

**URL 5종 규칙** (`thumbnailUrl`·`videoUrl`·`deployUrl`·`gitUrl`·`portfolioUrl`):

- `http`/`https`만, 최대 2048자. **형식 검증만 하고 제공자 allowlist는 없다** — 좁히면 정상
  배포·포트폴리오 URL을 거부해 009 SC-002(링크 도달률 100%)를 깬다
- 저장 바이트 = 반환 바이트. trim·소문자화·정규화 없음
- **`@`(userinfo) 금지** — `https://oauth2:token@host` 는 400. 공개 전시 필드라 자격증명이 노출되고 목적지를 오인하게 만든다
- **한글 도메인 허용** — 판정만 punycode, 저장은 원문 그대로
- 포트를 붙이려면 **`1~65535`** — `:99999`는 파서가 받아주지만 연결이 안 되므로 400
- 빈 문자열 `""`는 400. **지우려면 `null`을 보낸다**
- 대표 이미지는 **업로드가 아니라 URL 참조**다 (009 C-03)

> `videoUrl`의 제공자 범위(009 C-02)는 **기획 미결**이다. 정해지면 서버가 등록 시점에
> `400 VALIDATION_FAILED`로 거부하도록 이 절을 갱신한다. 그 전에 저장된 URL은 보존하고
> 조회에서 숨기지 않는다.

### POST `/booths/{boothId}/projects`

프로젝트 등록. `name`만 필수이고 **생략한 필드는 `null`로 저장**된다. 성공 `201` + 공통 표현.

실패: `400 VALIDATION_FAILED`(`errors[0] = { rule: "FIELD_INVALID", field, message }`) ·
`401` · `403 MEMBER_ONLY` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 BOOTH_NOT_FOUND` ·
`409 BOOTH_LEASE_EXPIRED` · **`409 PROJECT_ALREADY_EXISTS`**(이미 있음 — 수정은 `PATCH`).
동시 요청에서도 같은 코드가 나온다(유니크 제약 위반을 같은 코드로 번역).

### GET `/booths/{boothId}/projects`

**편집자용** 조회. published 게이트를 걸지 않는다 — 미게시 부스의 소유자도 자기 값을 봐야
수정 폼을 채운다(`GET /booths/mine`과 같은 이유).

```json
{ "projects": [ { "projectId": 1, "name": "SSAFY FESTA", "…": "…" } ] }
```

**0개 또는 1개 배열이고, 없으면 `{ "projects": [] }`다 — 404가 아니다.** 배열 형태를 유지하는
것은 상한이 오르더라도 계약 모양이 바뀌지 않게 하기 위함이다.

실패: `401` · `403 MEMBER_ONLY` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 BOOTH_NOT_FOUND`.

> 방문자용 조회는 이 endpoint가 아니라 아래 `GET /booths/{boothId}/projects/published`다.
> `GET /projects/{projectId}`는 **신설하지 않았다** — 부스당 1개라 이 목록이 같은 값을 준다.

### GET `/booths/{boothId}/projects/published`

**방문자용** 조회 (009 FR-005). **토큰이 없어도 `200`이다** — 게스트가 정상 경로라 `403`이 없다.
토큰이 있으면 `likedByMe` 판정에만 쓴다.

> ⚠️ **토큰을 실었는데 만료·손상됐으면 `401`이다** — 헤더가 없을 때만 `200`이다. 이유와 FE 우회는
> [계약 §6](../specs/009-project-exhibition/contracts/project-api.md)에 있다.

편집자 경로와 URL을 나눈 것은 같은 URL에서 신원에 따라 200과 403이 갈리지 않게 하기
위함이다. `/published` 접미사는 `GET /booths/{boothId}/layouts/published`(005) ·
`GET /games/{gameId}/published`(019)와 같은 뜻이다.

```json
{ "projects": [ { "projectId": 1, "name": "SSAFY FESTA", "…": "…",
                  "likeCount": 12, "likedByMe": false } ] }
```

편집자 응답의 8필드 + `likeCount`(int, 없으면 `0`) + `likedByMe`(boolean, 게스트는 `false`).
**두 키는 항상 있다.** 좋아요 **토글**은 `S15P21A604-135`이고 아직 없다 — 그때까지 `likeCount`는
항상 `0`이다.

**게이트 순서가 계약이다.**

| 순서 | 조건 | 응답 |
|---|---|---|
| 1 | 부스 없음 | `404 BOOTH_NOT_FOUND` |
| 2 | 유효 임대 없음 | `409 BOOTH_LEASE_EXPIRED` (004 FR-019) |
| 3 | 미게시 (`published_layout_version IS NULL`) | `404 LAYOUT_NOT_PUBLISHED` |
| 4 | 통과·프로젝트 없음 | `200 { "projects": [] }` |

**미게시는 404이고 빈 배열이 아니다** — "부스가 방문자에게 열려 있지 않다"와 "부스는 열렸고
전시가 없다"는 다른 사실이라, 뭉치면 클라이언트가 구분할 수단을 잃는다. 게시 게이트는
배치의 게시 여부이고, 프로젝트에 별도 게시 상태는 없다(016 홈페이지와 같은 술어).

### PATCH `/projects/{projectId}`

보낸 필드만 바꾼다 (009 C-06).

| 본문 | 뜻 |
|---|---|
| 키 **누락** | 그 필드는 손대지 않는다 |
| 키 있고 값 `null` | 그 필드를 **삭제**한다 |
| `{}` | **400** — 조용한 no-op을 만들지 않는다 |

**FE를 제약하지 않는다** — 바뀐 필드만 보내도, 전체를 보내도 의도대로 동작한다. 다만 "지운다"는
키를 빼지 말고 `null`을 명시해야 한다. `name`은 `NOT NULL`이라 `null`이면 400이다.

성공 `200` + 변경 후 공통 표현. 실패: `400 VALIDATION_FAILED` · `401` · `403 MEMBER_ONLY` ·
`403 BOOTH_EDITOR_FORBIDDEN`(**타 부스 프로젝트 수정 차단**) · **`404 PROJECT_NOT_FOUND`** ·
`409 BOOTH_LEASE_EXPIRED`.

### PUT `/projects/{projectId}/like` · DELETE `/projects/{projectId}/like`

방문자가 전시에 좋아요를 누르고 취소한다 (009 §8, `S15P21A604-135`). **회원만** — 게스트는
`403 MEMBER_ONLY`다. 편집자 가드는 없다 — 남의 부스에서 누르는 것이 정상 경로다.

```json
{ "likeCount": 13, "likedByMe": true }
```

**토글 하나가 아니라 멱등 둘이다.** `PUT`을 두 번 보내도 좋아요는 하나이고, `DELETE`를 누른 적
없이 보내도 `200`이다 — 더블탭이나 재시도가 방금 누른 것을 취소하면 안 되기 때문이다. FE는
`likedByMe`를 보고 메서드를 고른다.

`likedByMe`는 `PUT` 뒤 항상 `true`, `DELETE` 뒤 항상 `false`다 — 성공 반환이 곧 행의 유무다.
1인 1좋아요는 `project_likes PRIMARY KEY(project_id, user_id)`(V1)가 보장한다.

응답은 `GET /booths/{boothId}/projects/published`의 좋아요 두 필드와 같은 이름·같은 타입이고,
프로젝트의 나머지 필드는 담지 않는다 — 좋아요가 그것들을 바꾸지 않는다.

게이트는 방문자 조회와 **같은 함수**다: `404 PROJECT_NOT_FOUND` → `409 BOOTH_LEASE_EXPIRED` →
`404 LAYOUT_NOT_PUBLISHED`. 실패는 이 셋과 `401` · `403 MEMBER_ONLY` · `404 BOOTH_NOT_FOUND`.
**신규 오류 코드는 없다** — §18에 추가할 행이 없다.

---

## 6. AI Agent Config 관리

AI 실행 자체는 FastAPI가 담당하지만 Agent 설정 Source of Truth는 Spring을 기본으로 한다.
정본 계약은 `specs/007-ai-agent-document/spec.md`(C-12·C-13·C-14·C-15).

편집 권한은 **소유자 또는 스태프**(`BoothAccessGuard` — §4·§5와 같은 편집자 범위, spec 007 C-15).
회원만 — 게스트는 `403 MEMBER_ONLY`. 쓰기는 **유효 임대**를 요구하고, 읽기는 만료돼도 된다
(007 FR-015 — 만료돼도 설정은 보존된다).

> ⚠️ 직원 역할 게이트(011 C-09 `ADMIN`·`CONTENT_EDITOR`)는 **아직 걸려 있지 않다.**
> `BoothAccessGuard`가 `role`을 읽지 않으며 005·009·016도 같은 상태다 — 011 구현 시 가드 한
> 곳에서 일괄로 닫는다
> ([GitLab #116](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/116)).

### 공통 표현

```json
{
  "agentId": 1,
  "boothId": 7,
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

**키는 항상 있다.** `name`·`role`·`systemPrompt` 외에는 클라이언트가 안 보내도 **서버가 기본값을
채운다** — `null`로 두지 않는다. `forbiddenTopics`만 기본값이 빈 배열이고, 없어도 키가 사라지지
않는다 (조건부로 사라지면 클라이언트가 `undefined`와 `[]`를 둘 다 다뤄야 한다).

`boothId`를 응답에 넣는 것은 `/agents/{agentId}` 경로에 부스 좌표가 없기 때문이다.
`status`와 시각 필드는 **내보내지 않는다** — 소비자가 없고, 나중에 추가하는 것은 가산적이다.

**필드 규칙**

| 필드 | 규칙 |
|---|---|
| `name` | 필수. 1~100자 |
| `role` | 필수. 아래 화이트리스트 |
| `systemPrompt` | 필수. 공백만은 안 된다 |
| `tone` · `responseLength` | 선택. 아래 화이트리스트, 기본 `FRIENDLY`·`MEDIUM` |
| `servicePrice` | 선택. **0 이상 정수**, 기본 `0`. 상한은 정의된 바 없어 두지 않았다 |
| `handoffEnabled` | 선택. boolean, 기본 `false` |
| `forbiddenTopics` | 선택. 문자열 배열, 기본 `[]`. **화이트리스트 없음** — 항목이 공백만 아니면 된다 |

> `forbiddenTopics`의 **개수·길이 상한은 미정**이다 (docs/26 등록). 근거 없는 수치를 서버가
> 정하면 그것이 사실상의 계약이 된다. 정해지면 `400 VALIDATION_FAILED`로 거부하도록 갱신한다.
> 위 예시의 `PERSONAL_INFORMATION`은 **예시일 뿐 열거형이 아니다** — 자유 문자열이다.

### POST `/booths/{boothId}/agents`

AI 직원 등록. 성공 `201` + 공통 표현.

실패: `400 VALIDATION_FAILED`(`errors[0] = { rule: "FIELD_INVALID", field, message }`) ·
`401` · `403 MEMBER_ONLY` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 BOOTH_NOT_FOUND` ·
`409 BOOTH_LEASE_EXPIRED` · **`409 AGENT_LIMIT_EXCEEDED`**(이미 있음 — 수정은 `PATCH`).
동시 요청에서도 같은 코드가 나온다(유니크 제약 위반을 같은 코드로 번역).

### GET `/booths/{boothId}/agents`

**편집자용** 조회. 만료된 부스도 200이다.

```json
{ "agents": [ { "agentId": 1, "boothId": 7, "…": "…" } ] }
```

**0개 또는 1개 배열이고, 없으면 `{ "agents": [] }`다 — 404가 아니다.** 배열 형태를 유지하는 것은
상한이 오르더라도 계약 모양이 바뀌지 않게 하기 위함이다 (§5와 같은 판단).

실패: `401` · `403 MEMBER_ONLY` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 BOOTH_NOT_FOUND`.

### GET `/agents/{agentId}`

단건 조회. 성공 `200` + 공통 표현.
실패: `401` · `403 MEMBER_ONLY` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 AGENT_NOT_FOUND`.

### PATCH `/agents/{agentId}`

보낸 필드만 바꾼다 (spec 007 C-06 — §5 프로젝트와 같은 규칙).

| 본문 | 뜻 |
|---|---|
| 키 **누락** | 그 필드는 손대지 않는다 |
| `{}` | **400** — 조용한 no-op을 만들지 않는다 |
| `"forbiddenTopics": null` | **비운다** (`[]`와 같다) |
| 그 밖의 키에 `null` | **400**, `errors[0].field`가 그 필드를 지목한다 — 전부 `NOT NULL`이라 지울 수 있는 필드가 아니다 |

`handoffEnabled: null`도 예외가 아니다 — `false`로 읽지 않는다. 끄려면 `false`를 명시한다.
등록(`POST`)에서도 같다: 명시적 `null`은 "기본값을 달라"가 아니라 400이다.

**같은 값을 다시 보내면 `updatedAt`을 흔들지 않는다.** 전체 폼을 매번 보내는 FE가 "방금 수정됨"을
만들어 내지 않게 하기 위함이다.

성공 `200` + 변경 후 공통 표현. 실패: `400 VALIDATION_FAILED` · `401` · `403 MEMBER_ONLY` ·
`403 BOOTH_EDITOR_FORBIDDEN`(**타 부스 직원 수정 차단**) · **`404 AGENT_NOT_FOUND`** ·
`409 BOOTH_LEASE_EXPIRED`.

### DELETE `/agents/{agentId}`

AI 직원 삭제. 성공 `204`(본문 없음). **하드 삭제라 같은 부스에 다시 등록할 수 있다.**

> 이 엔드포인트는 spec 007 본문에는 없었고 **Jira S15P21A604-105 완료 조건 문면**에서 왔다.
> 2026-08-30 확정하며 참조 거부 정책과 함께 spec 007 **C-14**로 적었다.

**참조가 하나라도 있으면 `409 AGENT_DELETE_CONFLICT`다.** `message`가 **무엇이 막는지** 말한다 —
다음 행동이 셋 다 다르기 때문이다(배치에서 빼기 / 문서 지우기 / 상담 끝나기를 기다리기).

| 막는 것 | 근거 |
|---|---|
| Draft 배치 또는 **현재 공개 중인** 배치가 이 직원을 배치해 둠 | JSON 참조 — 외래키 없음 |
| `ai_documents`에 등록된 문서가 있음 | 외래키 |
| `consultations`에 상담 기록이 있음 | 외래키 |

- "현재 공개 중"은 **`booths.published_layout_version` 포인터** 기준이다. 최고 버전 번호가 아니다 —
  재임대 뒤에는 최신 행이 옛 임차인의 것일 수 있고, 아무도 볼 수 없는 과거 버전 때문에 삭제를
  막는 것은 틀렸다. **과거 버전에만 있는 참조는 삭제를 막지 않는다.**
- Draft 검사는 **기존 편집 중인 작업의 보호**일 뿐 강한 불변식이 아니다. Draft는 원래 존재하지
  않는 `configId`도 허용하므로(미완성 허용이 Draft의 정의) 삭제 후 옛 ID를 Draft에 다시 넣는 것은
  막지 않는다. 그건 공개 시점 검증(`CONFIG_NOT_OWNED`)이 잡는다. **강한 불변식은 "공개된 배치는
  존재하는 직원만 가리킨다" 하나다.**
- 그 불변식에는 외래키가 없다. 그래서 **삭제와 배치 공개가 같은 잠금**(부스 행)을 잡는다 — 검사를
  마친 직후 상대가 끼어드는 경쟁을 직렬화한다. 어느 쪽이 먼저 끝나든 공개된 배치가 사라진 직원을
  가리키는 상태는 나오지 않는다.

실패: `401` · `403 MEMBER_ONLY` · `403 BOOTH_EDITOR_FORBIDDEN` · `404 AGENT_NOT_FOUND` ·
`409 BOOTH_LEASE_EXPIRED` · **`409 AGENT_DELETE_CONFLICT`**.

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

#### 아직 안 올린 발급은 중복이 아니라 재발급이다

같은 해시로 다시 요청했을 때 셋으로 갈린다 (#84 멱등 재발급 합의).

| 기존 행 | 응답 |
|---|---|
| `QUEUED` 이고 **아직 업로드 확인 전**, `storageProvider` 가 현재 활성 쓰기 Provider 와 같음 | `duplicate:false` + **같은 `documentId`** + **새 `uploadUrl`** + 기존 `objectKey` |
| `QUEUED` 이고 아직 업로드 확인 전인데 **Provider 가 바뀜** | 기존 행을 `EXPIRED` 로 전환하고 **새 `documentId`·새 `objectKey`** 로 발급 (FR-032) |
| 업로드가 확인된 `QUEUED`, `PROCESSING`, `READY` | `duplicate:true` (URL·key 없음) |

발급 URL 은 15분이고 만료 판정은 1시간이다. 그 사이에 다시 요청하는 것은 정상 경로라서, 중복으로
막으면 **URL 이 만료된 사용자가 1시간을 기다려야 한다.**

재발급의 `uploadUrl` 은 **기존 행의 `objectKey`·`contentType`·`size` 로 서명**한다. 요청의 값이 아니다 —
상한 검사와 첫 서명이 그 값으로 이뤄졌고, 해시는 클라이언트의 주장일 뿐 서버가 확인한 값이 아니다(FR-019a).

**해시가 같은데 `fileName`·`contentType`·`size` 중 하나라도 기존 행과 다르면 `400 VALIDATION_FAILED`**
(`errors[0].field = contentSha256`) 다. 2026-08-31 확정 — #84 에 없던 자리이며 세 선택지(400 / 기존 값으로
재발급 / 중복 처리) 중 가장 보수적인 쪽을 골랐다. 서로 다른 파일이 같은 해시를 주장하는 요청을 조용히
통과시키면 서명한 것과 다른 파일이 올라간다.

### PUT `/documents/{documentId}/replacement`

**준비 완료(`READY`) 문서를 새 파일로 갈아 끼운다** (spec 007 FR-019 · FR-027a, `S15P21A604-386`).

요청·응답은 위 `upload-url` 과 **같은 모양**이다 — `UploadCommand` 를 그대로 보내고 `UploadGrantView`
를 그대로 받는다. 전용 DTO 는 없다. 다만 응답의 `documentId` 는 **새로 만들어진 문서**의 것이고,
이어서 그 id 로 `POST /documents/{newDocumentId}/complete` 를 부른다. 2단계는 일반 업로드와 완전히 같다.

`PUT` 인 이유는 요청이 "무엇을 교체할지" 를 이름으로 지목하고, 같은 요청을 반복하면 같은 상태로
수렴하기 때문이다 — 두 번째 호출은 교체를 하나 더 만들지 않는다.

#### 언제 무엇이 바뀌는가

| 시점 | 교체본 | 원본 |
|---|---|---|
| 이 호출 | `QUEUED` 로 생김 (`uploadedAt` 없음) | **`READY` 그대로. 검색에 계속 쓰인다** |
| 2단계 완료 | `QUEUED` (`uploadedAt` 채워짐), 처리 Job 생성 | **`READY` 그대로** |
| 처리 중 | `PROCESSING` | **`READY` 그대로** |
| 처리 **성공**(finalize) | `READY` | `EXPIRED` + `replacedAt` — 활성 Job `CANCELLED`, chunk 삭제 |
| 처리 **실패** | `FAILED` | **`READY` 그대로** |

**퇴역은 `complete` 가 아니라 finalize 다.** 완료는 "바이트가 도착했다" 일 뿐이고 그 파일이 읽히는지는
아직 아무도 모른다 — 거기서 원본을 물리면 파싱 불가 파일 하나로 AI 직원이 답할 근거가 통째로 사라진다.

#### 대상 상태

**`READY` 만 교체할 수 있다.** 나머지 다섯은 `409 DOCUMENT_NOT_REPLACEABLE` 이다 —
`QUEUED`·`PROCESSING` 은 이미 사용자가 원하는 버전이 되는 중이고, `FAILED`·`EXPIRED`·`DISABLED` 는
중복 판정 밖(FR-019b)이라 같은 파일을 그냥 새 문서로 올리면 된다. 판정은 **행 잠금 안에서** 하므로,
호출 직전에 임대가 만료돼 `DISABLED` 가 된 경우도 여기서 걸린다.

#### 같은 해시로 다시 불렀을 때

| 상황 | 응답 |
|---|---|
| **대상 문서 자신**과 같은 해시 | `200 duplicate:true` + 대상의 `documentId`. **행을 만들지 않고 원본도 안 건드린다** |
| 같은 AI 직원의 **다른 활성 문서**와 같은 해시 | `409 DOCUMENT_NOT_REPLACEABLE` (강행하면 `ux_ai_documents_agent_active_sha` 위반) |
| 진행 중인 교체본이 **아직 업로드 전**, 같은 파일 | `duplicate:false` + **같은 `documentId`** + 새 `uploadUrl` (#84 멱등 재발급) |
| 〃, 해시는 같은데 이름·형식·크기가 다름 | `400 VALIDATION_FAILED` (`errors[0].field = contentSha256`) |
| 〃, **다른 파일** | 그 교체본을 `EXPIRED` 로 버리고(**`replacedAt` 은 찍지 않는다**) 새 `documentId` 로 발급 |
| 진행 중인 교체본이 **업로드 완료**(`QUEUED`), 같은 파일 | `200 duplicate:true` + 그 교체본의 `documentId` |
| 〃, 다른 파일 | `409 DOCUMENT_NOT_REPLACEABLE` |
| 진행 중인 교체본이 `PROCESSING` | `409 DOCUMENT_NOT_REPLACEABLE` |

버려진 대기 교체본에 `replacedAt` 을 찍지 않는 것은 중요하다. 그 칸은 "더 새 버전이 자리를 넘겨받았다"
는 뜻인데, 이 행은 아무것도 공개하지 못한 채 버려진 발급일 뿐이다 — 찍으면 복구 판정(FR-027)이
이 행을 교체 원본으로 오해한다.

#### 상한 (FR-018)

**10개를 다 쓴 AI 직원도 교체할 수 있다.** 교체본은 원본의 자리를 이어받으므로 논리 합계가 늘지 않는다 —
활성 상태인 행이 11개여도 `quota.usedCount` 는 10이다. 총량(100MB)은 교체본의 크기로 다시 계산하므로
큰 파일로 갈아 끼우다 넘기면 `409 DOCUMENT_LIMIT_EXCEEDED` 이고, 이때 **교체본 행은 남지 않는다**.

실패: `401` · `403 MEMBER_ONLY`(게스트)·`BOOTH_EDITOR_FORBIDDEN` · `404 DOCUMENT_NOT_FOUND` ·
`409 DOCUMENT_NOT_REPLACEABLE`·`DOCUMENT_LIMIT_EXCEEDED`·`BOOTH_LEASE_EXPIRED` ·
`503 STORAGE_UNAVAILABLE` · `507 STORAGE_QUOTA_EXCEEDED`. 발급과 같은 업로드 게이트(#100)를 지난다.

### POST `/documents/{documentId}/complete`

브라우저가 presigned URL 로 업로드를 마친 뒤 부르는 확인 Endpoint. **요청 본문이 없다** — 판정에 필요한
것은 전부 문서 행에 있다.

HEAD 는 **문서 행의 `storageProvider` + `bucket` + `objectKey`** 로 한다. 전역 활성 Provider 가 아니다
(FR-030) — 전환 전에 올라간 파일은 옛 Provider 에 있고, 새 Provider 에 물으면 "없다" 는 엉뚱한 답이 온다.

#### Response

```json
{ "documentId": 153, "processingStatus": "QUEUED" }
```

#### 상태별 판정 (spec 007 data-model 업로드 만료 상태 전이)

| 문서 상태 | 저장소 | 결과 |
|---|---|---|
| `QUEUED`, 업로드 확인됨 | 확인 안 함 | `200` (멱등 — 재시도가 오류로 보이면 안 된다) |
| `QUEUED`, 미확인 | 객체 있고 **크기 일치** | `200`, 업로드 확인 기록 |
| `QUEUED`, 미확인 | 객체 없음 또는 크기 불일치 | `409 DOCUMENT_UPLOAD_INCOMPLETE` |
| `EXPIRED`, **전환 후 24시간 이내** | 객체 있고 크기 일치 | `200`, **같은 `documentId` 로 `QUEUED` 복구** (FR-027) |
| `EXPIRED`, 24시간 이내 | 객체 없음 | `410 DOCUMENT_UPLOAD_GONE` |
| `EXPIRED`, **24시간 경과** | **객체가 남아 있어도** | `410 DOCUMENT_UPLOAD_GONE` |
| `EXPIRED` + **`replacedAt` 있음** (교체로 물러난 원본) | 확인 안 함 | `410 DOCUMENT_UPLOAD_GONE` — 시간과 무관하다 (FR-027a) |
| `EXPIRED`, 교체가 **대기 중** (이 문서를 가리키는 활성 교체본이 있다) | 객체 있어도 | `410 DOCUMENT_UPLOAD_GONE` |
| `PROCESSING`·`READY` | 확인 안 함 | `200` (현재 상태 그대로) — `PROCESSING` 은 AI 워커가 이미 파일을 쥔 상태라 저장소를 다시 묻지 않는다 |
| `FAILED`·`DISABLED` | 확인 안 함 | `409 DOCUMENT_UPLOAD_INCOMPLETE` |
| 아무 상태 | 저장소가 답하지 못함 | `503 STORAGE_UNAVAILABLE` |

24시간 경과분을 객체 존재와 무관하게 `410` 으로 두는 것은 의도다 — 삭제는 sweeper 가 자기 주기로 하므로
남아 있다고 받아 주면 유예 기간이 무의미해진다.

교체로 물러난 원본을 시간과 무관하게 `410` 으로 두는 것도 같은 결이다. 24시간 복구는 "바이트가 안 왔다"
는 만료에만 해당하고, 이미 새 버전이 자리를 넘겨받은 문서는 되돌릴 자리가 없다 — 되살리면 한 (직원,
해시) 에 활성 행이 둘이 된다.

### GET `/agents/{agentId}/documents`

이 AI 직원의 문서를 **모든 상태**로, `createdAt` 내림차순으로 돌려준다 (US2 시나리오 2·7 · US3 시나리오 1).
**교체로 물러난 원본도 숨기지 않는다** — 사용자가 올린 것이 조용히 사라지면 안 되고, 대신 `replacedAt`
으로 "교체됨" 임을 말한다 (FR-006).

```json
{
  "documents": [
    {
      "documentId": 153,
      "fileName": "발표자료.pdf",
      "sizeBytes": 1048576,
      "status": "READY",
      "createdAt": "2026-09-09T01:15:02Z",
      "uploadedAt": "2026-09-09T01:20:11Z",
      "replacedAt": null
    }
  ],
  "quota": { "countLimit": 10, "bytesLimit": 104857600, "usedCount": 1, "usedBytes": 1048576 }
}
```

| status | 화면 | 뜻 |
|---|---|---|
| `QUEUED` | 대기 중 | 접수됨. `uploadedAt` 이 `null` 이면 **아직 업로드가 안 끝났다** |
| `PROCESSING` | 처리 중 | 임베딩 진행 중 |
| `READY` | 준비 완료 | 검색에 쓰인다 |
| `FAILED` | 실패 | 처리에 실패했다 |
| `EXPIRED` | **업로드 만료** | 발급 후 1시간 안에 업로드가 끝나지 않았다 (FR-026). 다시 올리면 된다 |
| `DISABLED` | 사용 중지 | 임대 만료로 꺼졌다 (FR-015). 사용자가 되돌릴 수 없다 |

**`EXPIRED` 는 `replacedAt` 으로 갈린다** (FR-027a). 값이 없으면 위 표대로 업로드 만료이고, 값이 있으면
수정본으로 교체돼 물러난 원본이다 — 화면에 **교체됨**으로 적고 "다시 올려 주세요" 안내를 띄우지
않는다. 복구되지 않는 행에 복구 버튼을 띄우면 눌러도 아무 일이 없는 컨트롤이 된다(T-24 와 같은 모양).
**상태 값 자체는 여섯 그대로다** — `replacedAt` 은 구분자이지 상태가 아니다.

여섯 값이 전부 실제로 나온다. `DISABLED` 는 `S15P21A604-496` 이 임대 만료 경로에 붙으면서 마지막으로
채워졌다 — 임대가 `EXPIRED` 로 넘어가는 **같은 트랜잭션**에서 그 부스의 활성 문서
(`PROCESSING`·`READY`, 그리고 업로드가 끝난 `QUEUED`)가 `DISABLED` 로, 활성
Job(`QUEUED`·`RUNNING`·`RETRY_WAIT`)이 `CANCELLED` 로 바뀌고 chunk·staging 이 정리된다
(FR-015·FR-041). 원본과 메타데이터는 남는다. `FAILED`·`EXPIRED` 문서와 이미 끝난 Job 은 건드리지
않는다 — 왜 못 쓰는 문서인지가 지워지면 안 된다.

**발급만 받고 아직 올라오지 않은 `QUEUED`(`uploaded_at` 없음)는 예외다.** 그 행은 1시간 뒤
`EXPIRED`(FR-026)로 가야 24시간 뒤 원본 삭제(FR-028) 경로에 오른다 — 임대 만료가 먼저 `DISABLED` 로
바꾸면 두 sweeper 의 조건에서 동시에 벗어나 원본을 지울 방법이 없어진다. 그렇다고 `READY` 로 살아날
수도 없다: 완료 요청은 활성 임대를 요구하므로 `409 BOOTH_LEASE_EXPIRED` 로 막힌다.

돌고 있던 FastAPI attempt 에는 **커밋 뒤에** 멱등 cancel(`POST /ai/v1/documents/cancel`)을 한 번
보낸다. 전달이 실패해도 DB 전이는 확정이고 재시도하지 않는다 — 취소를 못 들은 워커의 결과는 끝난
Job 이라 `410 JOB_GONE` 으로 거부된다. 한 건이 실패하면 **남은 Job 의 취소도 보내지 않는다**: 상대는
같은 FastAPI 프로세스 하나라 성공할 여지가 없고, 이 전송은 호출 스레드(재임대 요청일 수 있다)에서
돌기 때문에 건당 read timeout 만 쌓인다.

- `PROCESSING` 은 **AI 워커가 첫 신호를 보낸 순간**부터다. 위임이 나간 순간이 아니라서, AI 서버가
  응답하지 않는 동안에는 `QUEUED` 로 남는다 — 아무 일도 일어나지 않는 문서를 "처리 중" 이라고
  말하지 않는다
- `FAILED` 는 처리 Job 이 재시도를 다 썼거나 재시도 불가로 끝났을 때다. **재시도가 남아 있는 동안은
  `PROCESSING` 그대로**다. 실패한 문서는 상한과 중복 판정에서 빠지므로 **같은 파일을 그대로 다시
  올릴 수 있다**

**`quota` 는 상한과 쓴 양을 함께 준다.** 자리를 차지하는 것은 `QUEUED`·`PROCESSING`·`READY` 인
행뿐이고 `FAILED`·`EXPIRED`·`DISABLED` 는 목록에만 나온다 — 실패한 업로드가 슬롯을 잡으면 안 되기
때문이다 (FR-019b). **행 10개가 보여도 업로드가 될 수 있다.**

**"n/10" 의 n 은 `usedCount` 다.** `documents.length` 도, 활성 3상태 행의 수도 아니다 —
교체가 진행 중이면 원본과 교체본이 둘 다 활성이지만 교체본이 원본의 자리를 이어받으므로, 활성 행이
11개여도 `usedCount` 는 10이다. `usedBytes` 도 같은 뺄셈을 거친다.

> 2026-09-11 변경 (`S15P21A604-386`). 이전에는 "쓴 양은 클라이언트가 배열에서 센다" 였다. 수정본 교체가
> 그 전제를 깼다 — 배열만 세면 실제로는 성공할 업로드 앞에서 "11/10" 을 띄우게 된다. 폴링마다 질의가
> 둘 늘어나는 것이 모든 클라이언트가 같은 뺄셈을 각자 다시 구현하는 것보다 싸다.

`uploadedAt` 이 `null` 인 `QUEUED` 와 값이 있는 `QUEUED` 는 다르다 — 앞은 바이트가 아직 안 온
것이고 뒤는 처리를 기다리는 것이다. `status` 만으로는 구분되지 않는다.

**페이지네이션 없음.** FR-018 이 직원당 활성 문서를 10개로 제한한다.

**AI 처리 서버가 죽어 있어도 답한다** (US2 시나리오 7). 모든 값이 Spring 의 문서 행에 있다.

권한은 부스 편집자(소유자·Staff)다. 발급·완료와 달리 **활성 임대를 요구하지 않는다** — FR-015 가
만료 시 원본과 메타데이터를 보존하므로, 조회까지 막으면 그 보존이 의미가 없다.
실패: `401` · `403 MEMBER_ONLY`(게스트)·`BOOTH_EDITOR_FORBIDDEN` · `404 AGENT_NOT_FOUND`.

> **실패 사유는 아직 응답에 없다.** FR-007 은 정제된 사유를 요구하고 `ai_document_jobs.last_error`
> 원문 노출을 금지하는데, 워커가 보내는 `failureCode` 가 50자 이하 자유 문자열이라 옮길 집합이
> 없다. 문서에 `FAILED` 를 쓰는 코드도 아직 없다 (GitLab #119). 그 writer(`S15P21A604-400`)가
> 사유를 함께 넣는다.

### PATCH `/documents/{documentId}`

**미구현이고, 이 형태가 맞는지 확인이 필요하다.** 이 항목은 "ACTIVE / DISABLED 등 상태 관리" 로
적혀 있었는데, `DISABLED` 는 임대 만료가 쓰는 값이라(FR-015) 사용자가 손으로 옮길 값이 아니다.
사용자가 문서를 잠시 끄는 기능이 실제로 필요한 것인지 정해지기 전에는 열지 않는다.

삭제(FR-012 · US3 시나리오 2)는 별개다 — `DELETE /documents/{documentId}` 로 열릴 자리이고
아직 구현되지 않았다.

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

> 계약 정본은 `specs/010-survey/contracts/survey-api.md` 다. 이 절은 그 요약이다.
> **부스당 설문 1개**이고(C-06) **저장하면 바로 공개된다**(C-07) — 게시 단계가 없다.

| endpoint | 권한 | 티켓 |
|---|---|---|
| `GET /booths/{boothId}/survey` | 편집자. **임대 만료여도 조회된다**(FR-011) | -130 |
| `PUT /booths/{boothId}/survey` | 편집자 + 유효 임대. 없으면 생성, 있으면 갱신 — 항상 200 | -130 · -190 |
| `GET /booths/{boothId}/survey/run` | 방문자(회원·게스트). 응답의 `surveyId` 가 아래 두 경로의 키다 | -130 |
| `POST /surveys/{surveyId}/responses` | 회원·게스트 | -131 · -192 |
| `GET /surveys/{surveyId}/results` | 편집자 | -132 |
| `GET /surveys/{surveyId}/text-answers?questionId=&page=&size=` | 편집자 | -193 |

문항 유형 6종: `SINGLE_CHOICE` · `MULTIPLE_CHOICE` · `RATING` · `SHORT_TEXT` · `LONG_TEXT` · `APPLICATION` (FR-002).

### `PUT /booths/{boothId}/survey`

`title`·`questions` 는 필수다. **`description`·`rewardCoin`·`closesAt` 은 키를 보내지 않으면 기존 값을 유지**하고 명시적 `null` 이면 비운다 — FE 가 일부 필드만 보내도 나머지가 지워지지 않는다.

```json
{
  "title": "A604 부스 설문",
  "rewardCoin": 5,
  "questions": [
    {"type": "SINGLE_CHOICE", "prompt": "어떻게 알았나요?", "required": true,
     "options": [{"label": "돌아다니다가"}, {"label": "추천"}]},
    {"type": "RATING", "prompt": "만족도", "required": true, "scale": {"min": 1, "max": 5}},
    {"type": "LONG_TEXT", "prompt": "개선할 점", "required": false}
  ]
}
```

문항은 **전체 교체**다(`questionId` 를 받지 않는다). **응답이 1건 이상이면 문항 구조가 잠겨** 유형·문구·필수·선택지·척도가 다르면 `409 SURVEY_LOCKED` 이고, 제목·설명·보상·마감만 바꾸는 저장은 성공한다 (C-08).

검증 위반은 `400 VALIDATION_FAILED` + `errors[0].field` 이며 문항 안이면 `questions[2].options` 같은 경로다. 상한 셋(문항 수·선택지 수·보상 코인)은 **기획 미결이라 서버 설정값**이다 (C-01·C-02, docs/26 row 20·21).

### `GET /booths/{boothId}/survey/run`

Unity 가 `{boothId, objectId}` 만 보내고 설문 식별자를 모르므로 부스 기준 경로다 (S15P21A604-415).

```json
{"surveyId": 12, "closed": false, "rewardCoin": 5, "questions": [...]}
```

마감된 설문도 문항을 그대로 돌려준다 — 화면은 마감을 표시하고 제출이 `409 SURVEY_CLOSED` 로 막는다. `rewardCoin > 0` 이면 게스트는 제출할 수 없어(지갑 없음, 헌법 12조) 그 값을 미리 싣는다.

### `POST /surveys/{surveyId}/responses`

```json
{
  "answers": [
    {"questionId": 101, "selectedOptionIds": [1001]},
    {"questionId": 102, "rating": 4},
    {"questionId": 103, "text": "좋았습니다."}
  ]
}
```

→ `201 {"responseId": 55, "rewardedCoin": 5}`. **`rewardedCoin` 키는 항상 있고 보상이 없으면 `0`** 이다.

검증: 마감 / 1인 1응답(회원은 `userId`, 게스트는 토큰 주체) / 문항·선택지가 이 설문 것인지 / 유형별 payload / 보상 중복(원장 멱등키).

게스트 응답의 세션 흔적은 **세션을 더 쓸 수 없게 되면 지워진다**(헌법 12조). 기준은 제출 때 적어 둔 토큰의 `exp` **+ `app.auth.jwt-clock-skew`**(60초) — 디코더가 그때까지 토큰을 받아 주므로 그 전에 지우면 아직 통과하는 토큰이 재응답할 수 있다. 지워지는 것은 접속 토큰 주체와 만료 시각 둘이고, 답과 집계는 그대로다.

### `GET /surveys/{surveyId}/results`

Owner·허용된 Staff 용. 집계는 **서버가 계산**하고 원본 응답은 내려가지 않는다(FR-007). 전체 응답 수·최초/최근 응답 시각·문항별 응답 수·선택지별 수·별점 평균과 분포·주관식 첫 페이지. **비율은 싣지 않는다** — 복수선택은 합이 100% 를 넘으므로 화면이 `count / answeredCount` 로 계산한다. 응답 0건이면 모든 문항이 0 이고 `average` 는 `null` 이다(FR-012).

응답자 식별 정보는 어떤 필드에도 없다(FR-009). 주관식 항목의 `responseId` 는 같은 사람의 답을 묶는 열쇠일 뿐이다.

```json
{
  "surveyId": 12, "totalResponses": 20,
  "firstRespondedAt": "2026-09-08T04:11:02Z", "lastRespondedAt": "2026-09-08T07:55:40Z",
  "perQuestion": [
    {"questionId": 101, "type": "SINGLE_CHOICE", "answeredCount": 18,
     "counts": [{"optionId": 1001, "label": "월드를 돌아다니다가", "count": 11}],
     "average": null, "distribution": []},
    {"questionId": 102, "type": "RATING", "answeredCount": 20, "counts": [],
     "average": 4.2, "distribution": [{"value": 1, "count": 0}, {"value": 2, "count": 1}]}
  ],
  "textAnswers": {"content": [{"responseId": 55, "questionId": 103, "text": "무대 일정 안내가…"}],
                  "page": 0, "size": 20, "totalElements": 12, "totalPages": 1}
}
```

`counts`·`average`·`distribution` 은 **유형과 무관하게 항상 있다** — 값이 없으면 `[]`·`null` 이다. `answeredCount` 는 그 문항에 답한 응답 수이므로 선택 문항을 건너뛴 사람이 있으면 `totalResponses` 보다 작다.

### `GET /surveys/{surveyId}/text-answers?questionId&page&size`

주관식 답변 페이지. 결과 조회의 `textAnswers` 가 첫 페이지이고 그 다음을 이 endpoint 로 넘긴다(FR-008). `page`(0부터, 기본 0) · `size`(1~100, 기본 20) · `questionId`(선택 — 없으면 텍스트 3유형 전체).

**정렬은 답변 id 오름차순으로 고정**이다. 새 답변은 항상 뒤에 붙으므로 페이지를 넘기는 중에 제출이 들어와도 경계에서 중복·누락이 없다(C-09). 페이지 모양은 전역 규약(`content`·`page`·`size`·`totalElements`·`totalPages`)이고 `hasNext` 는 `page + 1 < totalPages` 로 판단한다.

`page` 음수 · `size` 범위 밖 · 이 설문의 문항이 아닌 `questionId` 는 `400 VALIDATION_FAILED` 이고 `errors[0].field` 가 문제 필드다.

## 10. Staff / Permission

> **정본은 `specs/011-staff-consultation/contracts/staff-consultation-api.md` §A 다** (2026-09-13, `S15P21A604-136` 구현과 함께 정정). 이 절은 그 요약이다.

### POST `/booths/{boothId}/staff-invitations`

직원 초대. **Owner 와 `ADMIN` 만** 보낸다 — `CONTENT_EDITOR` 는 콘텐츠를 고치지만 사람을 들이지는 못한다(FR-002).

```json
{ "nickname": "덕", "role": "CONSULTANT" }
```

> **대상은 닉네임이다.** 이전 판에는 `userId` 로 적혀 있었는데 **Owner 가 남의 숫자 id 를 알아낼 경로가 없다** — 사용자 조회·검색 endpoint 가 없고, 만들면 닉네임 훑기·id 수집 표면이 함께 생긴다. `users.nickname` 이 V1 부터 `UNIQUE` 다.

응답 `201`: `{ invitationId, boothId, boothName, nickname, role, expiresAt }`. 초대는 **48시간** 유효하다(C-07).

오류: `403 STAFF_MANAGER_FORBIDDEN` · `404 STAFF_INVITEE_NOT_FOUND` · `409 STAFF_ALREADY_MEMBER` · `409 STAFF_INVITATION_PENDING`

### GET `/staff-invitations/mine`

본인에게 온 **대기 중이고 만료되지 않은** 초대만(FR-016, C-08). 만료 배치를 기다리지 않는다 — 눌러 봐야 409 인 카드를 보여 주지 않는다.

### POST `/staff-invitations/{invitationId}/accept`

초대받은 본인만. 수락 시점에 `booth_staffs` 행이 생기고 역할이 적용된다. → `204`

### DELETE `/booths/{boothId}/staff-invitations/{invitationId}`

Owner·`ADMIN` 이 대기 중 초대를 거둔다(FR-017). → `204`

> **초대받은 사용자의 거절 API 는 없다**(C-10). 수락하지 않으면 48시간 뒤 만료된다.

### GET `/booths/{boothId}/staff`

부스 **구성원이면 누구나** 본다 — `CONSULTANT` 도 같은 부스에 누가 있는지는 알아야 한다.

**Owner 가 역할 `OWNER` 의 읽기 전용 행으로 함께 나온다**(FR-018). Owner 는 `booth_staffs` 에 저장되지 않으며 `readOnly: true` 로 표시된다. `consultationStatus` 는 직원 행에만 있고 Owner 행에서는 `null` 이다.

### PATCH `/booths/{boothId}/staff/{userId}` · DELETE `…`

Owner·`ADMIN` 만. **Owner 를 가리키면 `409 STAFF_OWNER_IMMUTABLE`** 이다 — 행이 없어서 나는 404 와 구분한다. 목록에 분명히 보이던 사람을 404 로 답하면 "이 부스와 무관하다" 로 읽힌다.

---

## 11. Staff Presence

### PUT `/booths/{boothId}/staff/me/presence`

```json
{ "status": "AVAILABLE" }
```

`AVAILABLE`·`AWAY`·`OFFLINE` 만 받는다. **`BUSY` 는 서버가 관리한다** — 상담 수락이 넣고 종료가 되돌린다. 직접 지정하면 `400 VALIDATION_FAILED` 다.

기본값은 `OFFLINE` 이다 — US2 가 "담당자가 항상 있을 수 없다, 오프라인이 기본 상태" 로 못박았다.

---

## 12. Consultation

> **정본은 `specs/011-staff-consultation/contracts/staff-consultation-api.md` §B 다.** 이 절의 이전 판(`POST /booths/{boothId}/consultations` 계열)은 **무효다** — 실제 계약은 2026-09-07 BE 회신(GitLab #133)으로 확정됐고 FE 가 `S15P21A604-519` 로 선반영을 마쳤다. 2026-09-13 `S15P21A604-137` 구현과 함께 정정한다.

### POST `/consultation/ws-token`

STOMP 연결용 **5분짜리** 토큰(FR-019·FR-020, C-14). → `201 { token, expiresInSeconds: 300 }`

`Authorization: Bearer <token>` 헤더로 STOMP `CONNECT` 에 싣는다. **URL query 로 넘기지 않으며 Access Token 재사용도 허용하지 않는다**(헌법 13조). 연결 성립 후에는 만료가 연결을 끊지 않는다.

### STOMP

```text
엔드포인트  wss://<host>/ws/consultation     native WebSocket + STOMP, SockJS 없음 (C-05)
구독        /user/queue/consultation              방문자 — 내 요청의 상태 변화
            /topic/booths/{boothId}/consultation  직원 — 그 부스 대기열 변화
SEND        없다 — P1 은 서버에서 클라이언트로 가는 단방향 알림이고 행동은 전부 REST 다 (C-12)
봉투        { type, requestId, occurredAt, … }
```

방문자 `accepted`·`expired`·`ended` / 직원 `requested`·`cancelled`·`expired`·`taken`.

> **이벤트 재전송은 P1 에 없다.** 끊긴 사이의 변화는 유실되고 클라이언트는 재연결 직후 대기열과 요청 상태를 REST 로 다시 읽는다. **정본은 REST 이고 STOMP 는 알림이다.**

### POST `/consultation/requests`

**회원 전용**이다 — 게스트는 `403 MEMBER_ONLY`(FR-014). 요청은 **10분** 유효하다(C-01).

```json
{ "boothId": 7, "conversationId": "conv_01JABCXYZ" }
```

→ `201 { requestId, expiresInSeconds: 600 }`

**요약 텍스트는 클라이언트가 보내지 않는다.** 서버가 `conversationId` 로 FastAPI 에 요약을 청해 **요청 생성 시점 스냅샷**으로 굳힌다(FR-012). 생성 실패는 `null` 일 뿐 요청을 막지 않는다(헌법 3조).

오류: `409 CONSULTATION_REQUEST_PENDING` · `409 BOOTH_LEASE_EXPIRED`

### DELETE `/consultation/requests/{requestId}`

방문자 본인. 상태는 `CANCELLED` 로 남는다 — 만료와 가르는 이유는 직원 화면이 다른 이벤트를 받기 때문이다. → `204`

### GET `/booths/{boothId}/consultation/requests`

부스 구성원이면 누구나. **대기 중이고 만료되지 않은** 것만 준다.

### POST `/consultation/requests/{requestId}/accept`

**정확히 한 명만 성공한다**(SC-001). **직원 한 명에게 활성 상담은 1건**이고(C-06, FR-021) 위반은 `409 CONSULTATION_ALREADY_ACTIVE` 이며 **기존 상담은 유지된다**.

→ `200 { requestId, sessionId, visitorNickname, handoffSummary }` — `sessionId` 는 `requestId` 와 **같은 값**이다(한 행이 요청과 세션을 겸한다).

### POST `/consultation/sessions/{sessionId}/end`

방문자·직원 **누구나** 끝낸다(FR-010). → `204`

### P1 에 없는 것

실시간 메시지 송수신·저장은 **P2**(C-12). 오프라인 비동기 문의도 **P2**(C-02). 원문 History 조회는 P2 spec 착수 시 저장 여부부터 재검토한다(C-03). P1 메타데이터·Handoff Summary 는 프로젝트 종료 시 일괄 삭제한다.

---

## 13. Inventory / Avatar Parts — P1

### GET `/catalog/items?type=AVATAR_PART`

회원·게스트 모두 Access Token으로 호출한다. 97판매 단위를 한 번에 반환하며 `owned`는 호출자 기준이다. 무료(`price=0`) 12종은 보유 행 없이도 항상 `true`다.

```json
{ "items": [
  { "itemId": 1, "code": "F_Bot.01", "name": "일자 팬츠", "equipSlot": "BOTTOM",
    "assetKey": "656603128", "price": 0, "onSale": true, "owned": true }
] }
```

`assetKey`는 `avatarCode`의 `i=` 슬롯 값과 같다. 비모자는 Unity `itemId`, 모자는 UI 판매 단위인 `familyId`다. 성별 필터는 Unity 카탈로그가 담당한다.

### POST `/catalog/items/{itemId}/purchases`

회원 전용. 성공 시 `201`과 해당 품목(`owned: true`)을 반환한다. 지갑 잠금 → 보유 재확인 → `PURCHASE:{userId}:{itemId}` 멱등 차감 → `user_inventory_items` 지급을 한 트랜잭션으로 처리한다.

| 오류 | 의미 |
|---|---|
| `404 CATALOG_ITEM_NOT_FOUND` | 없는 품목 |
| `409 ITEM_NOT_ON_SALE` | 판매 중지 |
| `409 ITEM_ALREADY_OWNED` | 무료 품목 또는 이미 구매한 품목 |
| `409 INSUFFICIENT_COIN` | 잔액 부족. 차감·지급 모두 롤백 |
| `403 MEMBER_ONLY` | 게스트 구매 |

별도 `GET /inventory/me`는 구현하지 않는다. 팔레트 소비자는 카탈로그 응답의 `owned`만으로 충분하다.

---

## 14. Minigame — 타이밍 스톱

> **정본은 `specs/014-minigame/contracts/minigame-api.yaml` 이다** (2026-09-10, GitLab #134 합의,
> `S15P21A604-502`). 아래는 요약이고, 어긋나면 계약 파일이 맞다.
>
> 이 절의 예전 초안(`POST /minigames/{gameType}/results`, `{sessionId, score, elapsedMs}`)은
> 실제로 만들어지지 않았다. `gameType` 경로 변수는 **미니게임이 1종뿐이라**(헌법 28조,
> spec 014 FR-009) 필요가 없고, `score`·`elapsedMs` 는 클라이언트가 계산한 값이라 C-06 이
> 신뢰를 금지한다.

### POST `/minigames/timer-stop/sessions` → `201`

한 판을 시작한다. 요청 본문은 읽지 않는다.

```json
{ "sessionId": "3f1a6d2c-…", "targetSeconds": 7.381,
  "failAfterSeconds": 10.381, "serverStartedAt": "2026-09-10T02:11:04.117Z" }
```

목표 시간은 **서버가 5~10초에서 무작위로 발급**한다 (FR-001a). 일일 한도에 도달한 회원에게도
세션은 발급된다 — 게임은 할 수 있고 보상만 없다 (Acceptance Scenario 4).

### POST `/minigames/timer-stop/sessions/{sessionId}/result` → `200`

```json
// 요청 — 서버가 읽는 것은 이 필드 하나다
{ "stoppedSeconds": 7.41 }

// 응답
{ "accepted": true, "errorSeconds": 0.029, "tier": 2, "timedOut": false,
  "rewardedCoins": 5, "dailyLimitReached": false, "dailyRemainingCoins": 45,
  "message": "+5 coins (tier 2)" }
```

- 오차·구간·보상·일일 한도는 **전부 서버가 계산한다** (C-06, FR-008, 헌법 16조). 배포된 Unity
  클라이언트가 함께 보내는 `targetSeconds`·`errorSeconds`·`timedOut` 은 **조용히 무시**된다
- 신고된 정지 시각은 서버 경과 시간과 **양방향**으로 대조한다 — 어긋나면 `accepted: false` 이고
  세션은 소진된다. 이것이 막는 것과 못 막는 것은 계약 §3 에 적혀 있다
- **판정 실패는 HTTP 오류가 아니다.** 검증 거부·실패 종료·일일 한도 도달·재제출은 전부 `200`
  이다. **`429` 는 이 계약에서 나오지 않는다**
- 재제출은 최초 판정을 그대로 돌려준다 (FR-004). 단 `dailyRemainingCoins`·`dailyLimitReached`
  는 재제출 시점의 현재 상태다
- 일일 한도 **50 Coin/일**, 기준일은 **제출 시각의 KST 날짜**. 남은 한도보다 보상이 크면
  **남은 만큼만 잘라서** 지급한다
- 오류: `400 VALIDATION_FAILED` (`stoppedSeconds` 누락·음수·범위 밖, `sessionId` 형식) ·
  `401 UNAUTHORIZED` · `403 MEMBER_ONLY` (게스트) · `404 MINIGAME_SESSION_NOT_FOUND`
  (없는 세션이거나 남의 세션 — 둘을 구분하지 않는다)

**슬롯머신 API 는 없다.** 미니게임 1종 제한(헌법 28조·FR-009) 때문이고, 2종으로 늘릴지는
`docs/26` 에 올라간 리드 결정이다 (`S15P21A604-569`).

---

## 14-1. Booth Metrics — 방문·체류 계측

> **BE 구현 완료 (`S15P21A604-240`, 2026-09-13). 발신 쪽 합의는 대기 중이다** — 아래 "묻는 것" 참조.
>
> 계측이 배포 **전에** 심겨 있어야 하는 이유는 간단하다. "Coin 시스템 유효성은 배포 후 사용자 반응으로 검증하라" 는 피드백을 실행하려면 그 반응을 기록할 자리가 먼저 있어야 한다 — 배포 후에 붙이면 첫 사용자들의 행동이 남지 않는다(GitLab #94).

### 누가 부르는가 — **React 호스트**

Unity 는 Spring 에 아무 신호도 보내지 않는다(2026-09-07 확정). 부스 구역 진입·이탈을 **브릿지 이벤트로 받은 React** 가 아래 경로를 부른다.

### POST `/booths/{boothId}/visits`

**요청 본문 없음.**

→ `201 { "visitId": "123", "enteredAt": "..." }`

- **회원과 게스트 모두 센다.** 부스 구경은 게스트에게 열려 있고(헌법 12조가 막는 것은 소유·결제다), 통계에서 빼면 실제 트래픽을 절반만 본다.
- **회원의 입장 신호가 두 번 오면 새 기록을 만들지 않는다** — 같은 `visitId` 가 돌아온다. 브릿지 이벤트 재전송이 방문 수를 부풀리지 않게 한다. 게스트는 식별자가 없어 합치지 못한다.
- 공개되지 않았거나 임대가 끝난 부스는 거부된다 — 들어갈 수 없는 부스의 방문 기록은 집계를 오염시킨다.
- **어느 월드 채널에서 들어왔는지는 서버가 채운다** (`S15P21A604-240`, GitLab #186 에서 확정). 예전에는 `worldChannel` 을 본문으로 받았는데 클라이언트가 그 값을 가질 길이 없었다 — Access Token 클레임에 채널이 없고, `channelId` 를 담은 world entry token 은 게임 서버로 가며, 월드 세션은 저장되지 않아 조회 경로도 없다. 채널 정체성을 클라이언트 주장에서 받지 않는 것은 `world-sessions` 가 이미 지키는 규칙과 같다(헌법 16조).

### POST `/booths/{boothId}/visits/{visitId}/exit`

→ `204`. 본인 방문만 닫을 수 있다.

**닫히지 않은 방문이 정상이다.** 브라우저를 그냥 닫으면 이 신호가 오지 않는다. 서버는 그런 행을 오류로 다루지 않고 **평균 체류에서 빼고 그 수를 따로 보고**한다.

> 임의의 timeout 으로 닫지 않는 이유: 그러면 체류시간이 실제 값이 아니라 **서버가 고른 상수**가 된다. "평균 3분" 이 사용자 행동인지 timeout 설정인지 구분할 수 없게 된다.

### GET `/booths/{boothId}/visit-metrics?from=&to=`

부스 운영자(Owner·`ADMIN`·`CONTENT_EDITOR`)용.

```json
{
  "from": "...", "to": "...",
  "visits": 120,
  "uniqueVisitors": 87,
  "averageDwellSeconds": 154,
  "openVisits": 3
}
```

`uniqueVisitors` 는 **회원 기준**이다(게스트는 식별자가 없어 각 방문이 따로 세어진다). `averageDwellSeconds` 는 **닫힌 방문만**의 평균이다.

### 이 endpoint 가 하지 않는 것

| 항목 | 왜 | 어디로 |
|---|---|---|
| 코인 순환량 | 이미 `coin_ledger_entries` 에 사유 코드와 함께 남는다. 같은 사실을 두 곳에 쓰면 둘이 갈린다 | §15 (`S15P21A604-501`) |
| 플랫폼 전체 집계 | "관리자용" 은 전역 관리자 개념을 전제하는데 그 권한 모델이 미정이다 | `S15P21A604-165`, `docs/26` 등재됨 |
| `booth_daily_metrics` 선집계 | 원본 스캔이 느려질 때 얹는 것이다. 부스 12개 규모에서는 실시간 계산이 맞다 | 느려지면 그때 |

### 발신 쪽에 묻는 것

1. **Unity 가 부스 구역 이탈을 브릿지로 알리는가?** 알린다면 이벤트 이름은? 알리지 않는다면 체류시간 표본이 `openVisits` 쪽으로 쏠린다 — 그 경우 **React 가 오버레이 종료·페이지 이탈에서 부르는 것**을 대안으로 제안한다.
2. 진입 이벤트 재전송을 발신 쪽에서 억제할 수 있는가 — 서버는 회원만 합칠 수 있어, 억제되면 게스트 통계도 정확해진다.

> ~~`worldChannel` 값의 형식~~ — **닫혔다.** 서버가 채우므로 발신 쪽이 정할 것이 없다 (`S15P21A604-240`).

---

## 15. Dashboard — P1

> 구현·계약 정본: `specs/015-dashboard/contracts/dashboard-summary-api.md` (`S15P21A604-501`).

### GET `/booths/{boothId}/dashboard/summary?from=&to=`

Owner · `ADMIN` · `CONTENT_EDITOR` 만. `CONSULTANT` 는 제외된다 — 상담원은 상담을 하지 부스 운영 지표를 보지 않는다. `from` 포함, `to` 제외, 둘 다 필수(ISO-8601).

```json
{
  "from": "2026-09-01T00:00:00Z",
  "to": "2026-09-14T00:00:00Z",
  "visits": 120,
  "uniqueVisitors": 88,
  "averageDwellSeconds": 143,
  "openVisits": 4,
  "consultations": 12,
  "consultationsEnded": 6,
  "surveyResponses": 31,
  "leaseCostCoin": 300,
  "surveyRewardCoin": 155,
  "aiUsages": null
}
```

**`null` 은 0 이 아니다.** `null` 은 **집계할 원천이 아직 없다**, `0` 은 **원천은 있고 그 기간에 0건이었다**. 지금 `null` 인 칸은 `aiUsages` 하나다.

| 필드 | 원천 |
|---|---|
| `visits` · `uniqueVisitors` · `averageDwellSeconds` · `openVisits` | `booth_visit_events` — §14-1 과 같은 정의다 |
| `consultations` · `consultationsEnded` | `consultations`. **요청 시각 기준**이라 아직 안 끝난 상담도 요청한 기간에 센다 |
| `surveyResponses` | 이 부스의 설문에 달린 응답. 게스트 응답도 센다 |
| `leaseCostCoin` · `surveyRewardCoin` | `coin_ledger_entries` 를 `reference_id` 로 되짚는다. 아래 참조 |
| `aiUsages` | **없다** — AI 대화를 남기는 표가 저장소에 아직 없다 (AI 파트 소관) |

#### 코인 칸은 "수익" 이 아니다

**부스로 코인이 들어오는 경로가 없다.** 원장에서 부스와 닿는 사유는 둘뿐이고 둘 다 수익이 아니다.

- **임대료** (`LEASE_PAYMENT` / `BOOTH_LEASE`) — 부스 소유자가 **낸다**. 비용이다
- **설문 보상** (`SURVEY_REWARD` / `SURVEY`) — 응답자에게 **발행된다**. 차감되는 지갑이 없어 부스가 내는 것이 아니다

그래서 "수익" 대신 이 둘을 그대로 준다 — **쓴 코인**과 **뿌린 코인**이다(2026-09-13 백엔드 결정, `docs/26`). 둘 다 양수로 나간다.

`surveyRewardCoin` 은 `surveyResponses` 옆에 놓고 읽으면 그대로 의미가 된다 — **코인 155개를 뿌려 응답 31개를 받았다.** 보상 구조가 먹히는지가 그 두 숫자에 있다.

진짜 유입 경로가 생기면(`S15P21A604-634` AI 상담 이용료 차감·운영자 수익 배분) 그때 `revenueCoin` 을 이 둘 **옆에 더한다**. `booth_daily_metrics.revenue_coin` 컬럼은 그 자리로 남겨 둔다.

#### 그 밖

- **집계는 실시간 계산이다.** 사전 집계 표(`booth_daily_metrics`)는 비워 둔다 — 갱신이 실패해도 조용히 그럴듯한 숫자를 계속 보여주기 때문이다. 부스 12개 규모에서는 원본 스캔이 더 싸다.
- **임대가 끝난 부스도 지난 기간을 볼 수 있다.** 행사 뒤 정산을 해야 한다 (spec 015 C-03).
- **플랫폼 전체 집계는 없다.** 전역 관리자 권한 모델이 미정이다 (`docs/26`, `S15P21A604-165`).
- 고급 전환율은 P2다.

| 오류 | 조건 |
|---|---|
| `400 VALIDATION_FAILED` | `from` 이 `to` 보다 뒤이거나 같다 |
| `403 BOOTH_EDITOR_FORBIDDEN` | 호출자가 Owner·`ADMIN`·`CONTENT_EDITOR` 가 아니다 |
| `404 BOOTH_NOT_FOUND` | 그런 부스가 없다 |

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
- GameProject에는 Asset binary·브라우저 임시 URL을 저장하지 않는다. builtin Asset catalog(`builtin://`)
  외에 **사용자 업로드(`asset://`)를 지원한다** — 아래 Asset Upload 절이 그 계약이다.
- 사용자 Asset 업로드: `POST /api/v1/games/{gameId}/assets` (grant 발급) →
  브라우저가 `uploadUrl`로 직접 `PUT` → `POST /api/v1/games/{gameId}/assets/{assetId}/complete` (검증) →
  `GET /api/v1/games/{gameId}/assets/{assetId}/content` (전달).
  - `uploadUrl`은 **R2 버킷으로 가는 presigned PUT**이고 응답의 `requiredHeaders`를 그대로 보내야 한다
    (`Content-Type`이 서명에 포함된다). grant와 서명 수명은 모두 10분이다.
  - `/content`는 **Spring이 R2에서 읽어 바이트를 중계한다** — 302도, presigned GET도 FE로 내보내지 않는다.
    버킷 CORS가 `PUT`만 허용하고(`object-storage-contract.md` §CORS), FE는 `Authorization`을 붙인
    `fetch`로 받기 때문이다. `Content-Type`은 검증으로 확정된 실제 타입이고 `X-Content-Type-Options: nosniff`,
    `Cache-Control: private, max-age=300`이다.
  - 상한: 5 MiB · 4096×4096 · 게임당 300개 · PNG·JPEG·GIF·WebP (SVG 거부). 검증은 선언값이 아니라
    올라온 바이트로 한다. 실패는 `200` + `status: FAILED` + `failureRule`이며 행이 사유를 보관한다.
  - 인증되지 않은 경로는 하나도 없다. 업로드 `PUT`이 버킷으로 직접 가므로 Spring에 무인증 수신 endpoint가
    필요하지 않고, grant 토큰이 쿼리 문자열에 실리는 일도 없다.
  - 저장 좌표(`provider`·`storage_bucket`)는 발급 시점에 행에 박는다. 읽기·삭제는 그 행을 따르며
    지금 활성인 write provider를 따르지 않는다 (spec 007 FR-030과 같은 불변식).
  - 객체 삭제는 DB 트랜잭션에서 분리한다 — 행을 지우는 트랜잭션이 `game_asset_delete_queue`에 좌표를
    남기고 `@Scheduled` sweeper(1분)가 비운다. 탈퇴·검증 실패·죽은 행 청소 세 지점이 모두 이 경로다.
- 현재 공개 포인터를 따라가는 `GET /games/{gameId}/published`는 `Cache-Control: no-cache` + ETag 재검증이다 — 재공개하면 같은 URL이 다른 본문을 가리키므로 장기 cache를 걸면 옛 version이 나온다. 긴 `max-age`·`immutable`은 후속 version 고정 URL에만 붙인다. Portal 실행 가능 여부는 `Cache-Control: no-store`다.
- 독립 play route와 Portal overlay open 시 REST 조회로 신규 진입을 판정하며 Game Studio 전용 socket은 만들지 않는다.
- 공개 중단 전에 이미 GameProject를 로드한 무보상 로컬 세션은 완료까지 허용한다.
- 일반 삭제는 soft delete, 회원 탈퇴는 Game·Draft·Published·Asset·Score hard delete다. Published 이력은 Game 존속 중 유지한다. Asset은 행을 지우고 객체는 삭제 큐로 넘긴다 — 저장소 장애가 탈퇴를 막지 않는다.
- Portal 공개 `configId`는 signed Int32 `1..2147483647`; DB는 별도 `INTEGER UNIQUE NOT NULL CHECK (>0)`를 사용한다.
- MVP 플레이 결과·보상·랭킹 API는 만들지 않는다.
- 오류 코드·`rule` 어휘와 생성·버전 목록 shape은 019 계약 문서가 소유한다 (`game-api.md` §오류 코드와 rule). `rule` 이름은 `contracts/fixtures/`의 reference validator가 정한 것을 그대로 쓰고, 서버가 새 어휘를 만들 때만 계약에 추가한다.

상세 계약은 [`specs/019-game-studio/contracts/game-api.md`](../specs/019-game-studio/contracts/game-api.md)이고,
Asset 업로드는 [`contracts/game-asset-upload.md`](../specs/019-game-studio/contracts/game-asset-upload.md)다.
#21의 기술 답변과 [#33](https://github.com/kanghyunsoon/ssafesta/issues/33)·
[#34](https://github.com/kanghyunsoon/ssafesta/issues/34)의 교차 계약을 반영했다.

---

## 17B. Admin — 관리자 권한

전역 관리자 권한 모델 (`S15P21A604-742` 상위, 블록 1은 `S15P21A604-743`). 이 절은 **구현된 것만** 적는다.

**판정 방식** — 관리자 여부는 `users.account_type = 'ADMIN'` 을 **매 요청 DB 조회**한다. JWT 의 `role` 클레임은 관리자에게도 `MEMBER` 다. `MemberPrincipal` 이 `"MEMBER".equals(role)` 로 판정하고 컨트롤러 20여 곳이 그것을 타므로, `role` 을 `ADMIN` 으로 발급하면 관리자 토큰이 게스트로 읽혀 본인 지갑·부스까지 `403 MEMBER_ONLY` 가 된다. 부수 효과로 **강등이 즉시 반영**된다 — 토큰 만료를 기다리지 않는다.

**마스터 계정** — `users.is_master` 가 `true` 인 계정 하나(최대 1명, 부분 유니크 인덱스로 DB 가 보장). 강등·정지·탈퇴·닉네임 강제 변경·코인 조정·강제 로그아웃 등 **대상을 지정하는 모든 관리자 동작의 대상이 될 수 없다**(`MASTER_PROTECTED`). 소유 자원(부스·게임·프로젝트·문서)을 경유한 간접 조치도 같은 코드로 막는다. **마스터를 지정하는 API 는 없다** — 마이그레이션이 유일한 경로다. 조회는 막지 않는다.

### GET `/admin/admins`

관리자 목록. `master` 가 `true` 인 행은 보호된 계정이라 콘솔이 조치 버튼을 잠근다.

```json
[{ "userId": 1, "nickname": "구글 황덕", "master": true }]
```

### POST `/admin/admins/{userId}`

관리자 승격. 본문은 선택이며 `{ "note": "사유" }` 는 감사 기록에 그대로 남는다. → `200` 승격된 계정

정지 계정은 승격할 수 없다(`403`). 이미 관리자면 `409 ADMIN_ALREADY`.

### DELETE `/admin/admins/{userId}`

관리자 강등. `?note=` 로 사유를 남긴다. → `204`

관리자가 아닌 회원을 강등하면 아무 일도 없이 `204` 다(요청이 바라는 상태가 이미 참이다). **마지막 관리자는 강등할 수 없다**(`409 ADMIN_LAST_ONE`) — 승격 API 자체가 관리자 전용이라 0명이 되면 API 로 되돌릴 수 없다.

**감사** — 승격·강등은 `admin_actions` 에 행위자·대상·사유와 함께 남는다. 이 테이블은 **FK 를 걸지 않는다**: 탈퇴 정리의 마지막 문장이 `DELETE FROM users` 라, 참조가 있으면 한 번이라도 승격된 계정이 탈퇴하지 못한다.

> ⚠️ **아직 없는 것** — 계정 정지·해제, 코인 조정, 부스 강제 회수, 신고, 운영 부스는 같은 상위 이슈의 다음 블록이다. 이 절에 없으면 구현되지 않은 것이다.

---

## 18. 주요 오류 코드

| Code | 의미 |
|---|---|
| `UNAUTHORIZED` | 인증 실패 |
| `FORBIDDEN` | 권한 없음 |
| `MASTER_PROTECTED` *(742)* | 마스터 계정(또는 그 소유 자원)을 관리자 조치 대상으로 지정했다 |
| `ADMIN_LAST_ONE` *(742)* | 마지막 관리자는 강등·정지·탈퇴할 수 없다 |
| `ADMIN_ALREADY` *(742)* | 이미 관리자다 |
| `USER_NOT_FOUND` | 사용자 없음 |
| `BOOTH_NOT_FOUND` | Booth 없음 |
| `BOOTH_SLOT_ALREADY_LEASED` | 이미 임대됨 |
| `INSUFFICIENT_COIN` | Coin 부족 |
| `CATALOG_ITEM_NOT_FOUND` *(012)* | 카탈로그 품목 없음 |
| `ITEM_NOT_ON_SALE` / `ITEM_ALREADY_OWNED` *(012)* | 판매 중지 / 이미 보유 |
| `AVATAR_ITEM_NOT_OWNED` *(012·013)* | 아바타 저장값에 미보유 파츠 포함. `errors[].rule=ITEM_NOT_OWNED`, `objectId=assetKey` |
| `LAYOUT_VALIDATION_FAILED` | Layout 검증 실패 (`errors` 배열 동반) |
| `LAYOUT_REVISION_CONFLICT` | 다른 편집자가 먼저 저장 (Draft 낙관적 잠금) |
| `LAYOUT_NOT_PUBLISHED` | 공개된 배치 없음 |
| `PROJECT_NOT_FOUND` *(009)* | 프로젝트 없음 |
| `PROJECT_ALREADY_EXISTS` *(009)* | 이 부스에는 이미 프로젝트가 있다 — 부스당 1개(C-01). 수정은 `PATCH` |
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
| `AGENT_NOT_FOUND` *(007)* | Agent 없음 |
| `AGENT_LIMIT_EXCEEDED` *(007)* | 이 부스에는 이미 AI 직원이 있다 — 부스당 1명(C-13). 수정은 `PATCH`. `message`가 상한을 담는다 |
| `AGENT_DELETE_CONFLICT` *(007)* | 배치·문서·상담 중 하나가 아직 이 직원을 가리킨다 (C-14). `message`가 무엇이 막는지 말한다 |
| `DOCUMENT_NOT_FOUND` *(007)* | 문서 없음 |
| `DOCUMENT_LIMIT_EXCEEDED` *(007)* | AI 직원당 10개·100MB 상한 (FR-018). 둘 다 설정값이라 `message`가 숫자를 담는다 |
| `DOCUMENT_UPLOAD_INCOMPLETE` *(007)* | 발급한 URL 로 올린 것이 저장소에 없거나 크기가 다르다 — 다시 올리면 되는 상태다 |
| `DOCUMENT_UPLOAD_GONE` *(007)* | **410.** 만료된 업로드의 원본이 없거나 24시간 유예가 지났다 (FR-027). 재시도가 아니라 **새 업로드 권한**이 필요하다 — 그래서 409 와 갈린다 |
| `STORAGE_UNAVAILABLE` *(007)* | **503.** 저장소 장애 또는 감시 불능(`STALE_BLOCKED`)으로 발급을 막았다 (C-10). **재시도 가능**하다 |
| `STORAGE_QUOTA_EXCEEDED` *(007)* | **507.** usage guard 90% 초과로 발급을 막았다 (C-10, #100). **재시도로 풀리지 않아** 503 과 가른다. 둘 다 **행을 만들기 전에** 거절한다 — 차단 중 만든 행은 FR-018 의 10개 슬롯을 먹는다 |
| `RECONCILIATION_INVALID` *(007)* | **422.** reconcile 결과가 계약을 벗어났거나 자기모순이다 (FR-035). **이 API 만 422 를 쓴다** — 소비자가 Infra 스크립트라 사용자 입력 오류(400)와 구분한다 |
| `RECONCILIATION_STALE` *(007)* | **409.** 결과는 적재했으나 문서가 그 source provider·object key 를 더는 갖지 않아 반영할 수 없다. **재시도로 풀리지 않는다** — 늦게 도착한 결과가 최신 저장 위치를 되돌리지 않게 하는 거부다 |
| `RECONCILIATION_REPLAY_CONFLICT` *(007)* | **409.** 같은 `runId + documentId` 로 다른 내용이 왔다. 먼저 저장된 결과가 남는다 — 멱등은 같은 요청을 다시 보내도 안전하다는 뜻이지 같은 키로 다른 것을 보내도 된다는 뜻이 아니다 |
| `RECONCILIATION_CONFIGURATION_ERROR` *(007)* | **500.** 요청은 유효한데 Spring 배포에 그 provider 설정이 없거나 문서 행의 bucket 이 설정과 어긋난다. 넷 중 **유일하게 재시도가 의미 있고**, 고칠 것은 payload 가 아니라 배포 설정이다 |
| `JOB_ATTEMPT_STALE` *(007)* | **409.** 늦게 도착한 이전 attempt 의 결과. lease 만료로 Job 을 회수하고 `attempt_no` 를 올린 뒤 죽은 줄 알았던 워커가 보내온 경우다 — 받으면 두 attempt 의 chunk 가 섞인다. **재시도로 풀리지 않는다** |
| `JOB_GONE` *(007)* | **410.** 처리 Job 이 끝났거나(`SUCCEEDED`·`DEAD`·`CANCELLED`) 문서 삭제로 사라졌다. 같은 Job 으로 다시 시도할 곳이 없다는 뜻이라 409 와 갈린다 |
| `SURVEY_NOT_FOUND` | **404.** 부스에 설문이 없거나 `surveyId` 가 없다. 편집자 조회의 404 는 "아직 만들지 않았다"는 뜻이라 오류 상태가 아니다 |
| `SURVEY_CLOSED` | **409.** 설문 마감 — `closesAt` 이 지났다 |
| `SURVEY_ALREADY_RESPONDED` | **409.** 1인 1응답 위반. 회원은 `userId`, 게스트는 접속 토큰 주체 기준이다 |
| `SURVEY_LOCKED` | **409.** 응답이 있는 설문의 문항 구조를 바꾸려 했다 (C-08). 제목·설명·보상·마감은 수정된다 |
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

---

## 20. 내부 API — Spring ↔ FastAPI

**사용자 API가 아니다.** `/api/v1` 아래가 아니라 **`/internal/*`** 이고, 사용자 Access Token으로는
열리지 않는다. 인프라가 이 접두어를 공개 리스너에서 막고, 서버는 그것과 **독립적으로 항상**
토큰을 검증한다 — SG 룰은 설정 한 줄로 조용히 깨지고 그 순간 토큰이 유일한 방어선이다.

### 인증 (2026-08-25 확정 — [GitLab #102](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/102))

`Authorization: Bearer <service-token>`이고 **토큰은 방향별로 분리**한다.

| 변수 | 방향 | 송신 | 검증 |
|---|---|---|---|
| `INTERNAL_SPRING_TO_AI_TOKENS` | Spring → FastAPI | Spring | FastAPI |
| `INTERNAL_AI_TO_SPRING_TOKENS` | FastAPI → Spring | FastAPI·Worker | **Spring** |

합치지 않는 이유는 노출 표면이 다르기 때문이다 — AI→Spring 토큰은 **사용자가 올린 PDF를 파싱하는
Worker와 같은 메모리**에 있다. 하나로 묶으면 넓은 쪽의 위험이 좁은 쪽으로 그대로 전파된다.

- **콤마 구분 1~2개.** 송신자는 첫 값을 쓰고 수신자는 목록 전체를 받아들인다 — "현재 토큰"이
  값 자체로 표현되므로 승격 절차가 따로 필요 없다. 회전: `[old]` → `[old,new]` → `[new,old]` → `[new]`
- 비교는 **상수 시간**(`MessageDigest.isEqual`)이고 목록 전체를 **조기 반환 없이** 돈다
- 앞뒤 공백이 붙은 값·빈 항목·중복·3개 이상은 **기동을 실패**시킨다. Secret을 말없이 다듬으면
  설정한 값과 비교하는 값이 달라지고 그 차이는 런타임 401로만 드러난다
- 누락·오류·**반대 방향 토큰**은 전부 `401 UNAUTHORIZED`
- mTLS는 P2다 — 두 서비스가 같은 VPC 안이라 mTLS가 막는 위협이 현 배치에 없다

> **배포 주입 (Infra, S15P21A604-356).** Backend·FastAPI 서비스별 Secret File과 환경별
> `INTERNAL_AI_TO_SPRING_TOKENS` Secret Text를 Jenkins credential → Pipeline → Compose `env_file`·`environment`로
> 주입한다. `SPRING_PROFILES_ACTIVE=infra`와 `FESTA_ENVIRONMENT=dev|demo`는 Compose가 명시한다.
> 실제 Secret 값은 Jenkins Credentials에만 두며 저장소와 `infra/.env`에는 넣지 않는다.
>
> | 변수 | 기본값 | 없으면 |
> |---|---|---|
> | `JWT_SECRET`(base64)·`CONNECTION_TOKEN_SECRET`·`INTERNAL_AI_TO_SPRING_TOKENS` | 없음 | **기동 실패** |
> | `FESTA_ENVIRONMENT`(`dev`\|`demo`) | 없음 | **기동 실패** — Redis 키 네임스페이스다(`S15P21A604-349`). 조용히 빈 값으로 뜨면 dev·demo 가 세션과 일일 지급을 공유한다 |
> | `GOOGLE_CLIENT_ID/SECRET/REDIRECT_URI`·`KAKAO_REST_API_KEY/CLIENT_SECRET/REDIRECT_URI` | 없음 | **기동 실패** |
> | `SSAFY_CLIENT_ID/CLIENT_SECRET/REDIRECT_URI` | 없음 | SSAFY 로그인이 실패한다 (`S15P21A604-357`) |
> | `R2_ENDPOINT`·`R2_BUCKET`·`R2_ACCESS_KEY_ID`·`R2_SECRET_ACCESS_KEY` *(007, S15P21A604-106)* | 없음 | **기동 실패** |
> | `AI_STORAGE_UPLOAD_GATE`·`AI_STORAGE_ACTIVE_WRITE_PROVIDER` *(007, S15P21A604-106)* | 없음 | **기동 실패** |
> | `MINIO_ENDPOINT/BUCKET/ACCESS_KEY_ID/SECRET_ACCESS_KEY` *(007, fallback 시)* | 빈 값 | 전부 비면 미구성으로 빠진다. **부분 입력이면 기동 실패** |
> | `POSTGRES_HOST/PORT/DB/USER/PASSWORD`·`REDIS_HOST/PORT` | localhost 기본값 | 컨테이너 안 localhost 를 본다 |
> | `FRONTEND_BASE_URL`·`AUTH_COOKIE_SECURE`·`WORLD_SCHEME/HOST/PORT` | 로컬 기본값 | CORS·쿠키·월드 접속이 로컬 값으로 뜬다 |
> | `ROOT_DOMAIN` | 없음 | `application-infra.yml` 의 `app.world.host` 가 `world.` 만 남는다 |
> | `SPRING_PROFILES_ACTIVE` | `local` (`spring.profiles.default`) | 배포에서도 `local` 프로파일이 뜬다 — 아래 |
>
> **문서 저장소 (S15P21A604-106).** `R2_*` 는 Infra 가 소유하는 credential 로, 문서 bucket 과
> 서버가 만드는 prefix 로 scope 를 좁힌 것을 받는다 (GitLab #84). 삭제 유예 정리(FR-028)까지 하려면
> 그 prefix 에 대한 `DeleteObject` 가 필요하다.
>
> **업로드 허용 게이트는 한 칸이다** (GitLab #100, 2026-09-01 확정). 기본값을 두지 않은 것이 의도다 —
> 기본값이 "허용" 이면 키 이름을 틀렸을 때 Spring 이 조용히 무시하고 **차단이 열린 채로 뜬다.**
>
> | `AI_STORAGE_UPLOAD_GATE` | 뜻 | 응답 |
> |---|---|---|
> | `OPEN` | 정상·경고, `LOCAL_ACTIVE` 검증 완료 | 발급 |
> | `QUOTA_BLOCKED` | 사용량 90% 초과 — 재시도해도 풀리지 않는다 | `507 STORAGE_QUOTA_EXCEEDED` |
> | `UNAVAILABLE` | stale 지표·R2 장애·`FALLBACK_VALIDATING`·`R2_RECONCILING` | `503 STORAGE_UNAVAILABLE` |
>
> 운영자가 Usage Guard(`usage-guard.schema.json`)와 저장소 전환 상태를 읽고 위 한 칸으로 옮겨 적는다 —
> **서버는 상태 기계를 알지 못한다.** 507 과 503 을 가르는 것이 이 게이트를 boolean 이 아니라 enum 으로
> 둔 이유다: 용량이 찬 사용자에게 "잠시 후 다시" 를 주면 영원히 재시도한다.
>
> **`AI_STORAGE_ACTIVE_WRITE_PROVIDER` 는 신규 업로드가 갈 곳일 뿐이다.** 기존 객체의 읽기·HEAD·삭제는
> 문서 행의 `storage_provider` 를 따른다(FR-030) — 전환 전에 올라간 파일은 옛 provider 에 남는다.
> MinIO 로 옮길 때는 `MINIO_*` 4종을 채우고 이 값을 `MINIO_LOCAL` 로 바꾼다. MinIO 항목은 전 필드가
> 비면 미구성으로 보고 목록에서 빠지므로, R2 만 쓰는 배포는 그 4종을 주지 않아도 된다 (부분 입력은 기동 실패).
>
> **프로파일은 `SPRING_PROFILES_ACTIVE=infra` 다.** `FESTA_ENVIRONMENT` 는 Spring 프로파일이
> 아니라 **Redis 키 네임스페이스**(`app.redis.namespace`)다 — dev·demo 가 단일 EC2 의 Redis 한
> 인스턴스를 공유하므로 모든 키가 이 값을 맨 앞에 단다(`S15P21A604-349`, infra-002 T059·T060).
> 둘은 각각 주입해야 한다. 배포 프로파일을 `application-infra.yml` 로 두는 것은 확정됐고(GitLab #117,
> `specs/infra-002-environments/tasks.md` T059) 파일도 `a51f88a0`(`-170`, MR !150)로 들어왔다.
>
> ⚠️ **다만 지금 `infra` 로 띄우면 기동하지 않는다.** Spring 의 `application-{profile}.yml` 은
> 프로파일 간에 누적되지 않는데, `spring.datasource`·`jpa`·`flyway`·`data.redis`·`security.oauth2`
> 와 `app.*` 전체가 `application-local.yml` 96줄 안에만 있고 `application-infra.yml` 은 5줄
> (`app.world.*`)뿐이다. `infra` 를 켜면 그 96줄이 로드되지 않아 `spring.datasource.url` 이
> 사라지고 JPA·Flyway 자동설정이 실패한다. **환경변수를 전부 주입해도 읽을 설정이 없다.**
> 공통 설정을 `application.yml` 로 승격하는 것이 BE 몫이며 `S15P21A604-347` 로 추적한다.
>
> `REDIS_USERNAME`·`REDIS_PASSWORD` 도 함께 필요해진다 — infra-002 T015 가 Redis 기본 사용자를
> 비활성화하고 ACL 을 켜는데 현재 `spring.data.redis` 에는 host·port 만 있어 연결이 거부된다.
> 주입 자리 신설도 `S15P21A604-347` 범위다.

> **보안 체인은 하나다.** `/internal/**` 전체를 한 체인이 **먼저 소비**하고 규칙이 없는 경로는
> `denyAll`이다. 그러므로 Infra의 `/internal/storage/**`(spec 007 T078)는 **별도 체인을 만들지
> 말고 이 체인에 자기 필터와 규칙을 더한다** — 우선순위가 낮은 체인을 새로 만들면 요청이 그곳까지
> 가지 않는다. T078(S15P21A604-500)이 그대로 따랐다: 같은 체인에 두 번째 필터와
> `/internal/storage/**` 규칙이 붙었고 자격증명과 scope는 `INTERNAL_INFRA_TO_SPRING_TOKENS`로
> 분리돼 있다.
>
> **두 필터는 서로 겹치지 않는 경로에서만 동작한다.** 그래서 토큰은 자기 방향만 연다 — AI 토큰을
> `/internal/storage/**`에 내밀면 아무것도 인증되지 않아 **401**이고, 그 반대도 같다.
>
> 규칙이 **있는** 경로의 거부는 401, `denyAll`로 떨어지는 규칙 **없는** 경로의 거부는 **403**이다.
> 의도한 구분이 아니라 판정 방식의 차이다 — authority 규칙은 `Authentication`을 요구해서 없으면
> 인증 오류가 되고, `denyAll`은 아예 묻지 않아 접근 거부로 끝난다.

### GET `/internal/ai/booth-access`

FastAPI가 Conversation을 만들기 전에 묻는다 (spec 008 FR-024·C-08).
정본 계약은 `specs/008-ai-conversation-rag/contracts/spring-booth-access-api.yaml`.

```
GET /internal/ai/booth-access?boothId=7&agentId=3
Authorization: Bearer <INTERNAL_AI_TO_SPRING_TOKENS 의 첫 값>
```

**유효 임대 → Agent의 부스 소속 → Agent `ACTIVE`** 순으로 **단락 평가**한다.

```json
// 허용
{ "allowed": true, "boothId": 7, "agentId": 3, "agentStatus": "ACTIVE",
  "leaseEndsAt": "2026-08-31T09:00:00Z", "remainingSeconds": 57600,
  "serverTime": "2026-08-30T17:00:00Z" }

// 거부
{ "allowed": false, "boothId": 7, "agentId": 3,
  "leaseEndsAt": null, "remainingSeconds": 0,
  "serverTime": "2026-08-30T17:00:00Z", "denialCode": "BOOTH_LEASE_EXPIRED" }
```

**거부는 오류가 아니라 `200 + allowed:false`다.** `401`은 서비스 토큰 실패에만 쓴다.

| `denialCode` | 뜻 | `leaseEndsAt` | `agentStatus` |
|---|---|---|---|
| `BOOTH_LEASE_EXPIRED` | 유효 임대가 없다 | `null` | **없음** |
| `AGENT_NOT_IN_BOOTH` | 그 부스의 직원이 아니다 | 종료 시각 | **없음** |
| `AGENT_INACTIVE` | 직원이 비활성이다 | 종료 시각 | `INACTIVE` |

- **존재하지 않는 `boothId`·`agentId`도 404가 아니라 각각 첫·두 번째 거부**다. 내부 API라도
  존재 여부를 알려 줄 이유가 없다
- **`leaseEndsAt == null` ⟺ `denialCode == BOOTH_LEASE_EXPIRED`.** 만료 임대의 종료 시각은
  조회하지 않는다 — 과거 임대가 여럿일 때 어느 행인지 정할 근거가 없고, FastAPI가 저장하는 값은
  성공 응답의 종료 시각뿐이다
- **`agentStatus`가 있다 ⟺ 그 `agentId`가 그 `boothId`에 있다.** 저장값이 `ACTIVE`가 아니면 전부
  `INACTIVE`로 정규화한다 — 계약 어휘는 두 값뿐이다
- `serverTime`·유효성 판정·`remainingSeconds`는 **한 요청에서 같은 시각 하나**로 계산한다.
  FastAPI는 이 쌍을 저장해 이후 모든 질문의 만료를 스스로 판정한다(FR-024) — 셋이 어긋나면
  그 판정이 어긋난다

### POST `/internal/ai/chunk-search`

FastAPI가 질의 Embedding을 만든 뒤 **유일한 검색 진입점**으로 쓴다 (spec 008, `S15P21A604-398`).
정본 계약은 `specs/008-ai-conversation-rag/contracts/spring-chunk-search-api.yaml`.

```
POST /internal/ai/chunk-search
Authorization: Bearer <INTERNAL_AI_TO_SPRING_TOKENS 의 첫 값>
Content-Type: application/json

{ "boothId": 7, "agentId": 3, "queryEmbedding": [0.0123, ...], "topK": 5 }
```

```json
{ "items": [
  { "content": "부스 운영 시간은 …", "chunkNo": 0, "pageNumber": 3, "section": "운영 안내",
    "documentId": 41, "originalFilename": "부스안내.pdf", "distance": 0.1832 }
] }
```

**요청에 필터 필드가 없다.** `boothId` + `agentId` + `searchable = true` + 부모 Document `READY`는
서버 상수이고, 요청으로 넓힐 수 없다 (GitLab 이슈 #119). `boothId`·`agentId`는 **chunk와 부모
Document 양쪽에서 검사한다** — 두 테이블의 scope 컬럼 사이에 제약이 없어(V21) 값이 어긋난 행이
생기면 chunk 쪽만 보는 쿼리가 남의 문서 본문을 실어 준다.

| 항목 | 값 |
|---|---|
| `queryEmbedding` | **정확히 1536개.** 헌법 18조·FR-009 고정. null 원소·비유한 수 거부 |
| `topK` | 1~20 |
| 정렬 | `distance` 오름차순, 동률은 `documentId` → `chunkNo` |
| `distance` | pgvector 코사인 거리(`<=>`) 그대로. **낮을수록 유사**하고 서버가 뒤집거나 정규화하지 않는다 |
| 최소 유사도 임계값 | **없다** (2026-09-03 확정). 무엇을 버릴지는 답변을 만드는 쪽이 정한다 |
| 타임아웃 | 검색 statement에만 3초 |

- **결과 0건은 `404`가 아니라 `200` + `items: []`다.** scope에 chunk가 없는 것과 문서가 아직
  `READY`가 아닌 것을 **구분해 알려 주지 않는다**
- **계약에 없는 필드는 버리지 않고 `400` `VALIDATION_FAILED`로 거부한다.** 문제 필드 이름은
  `errors[].field`에 담고 `rule`은 전역 `FIELD_INVALID`다 — 조용히 버리면 보내는 쪽이 필터가
  먹었다고 믿는다(T-24의 모양). 전역 `FAIL_ON_UNKNOWN_PROPERTIES`는 켜지 않고 이 경로 전용
  strict parser로 읽는다
- 오류는 전역 봉투를 쓴다 — `400` `VALIDATION_FAILED` · `401` `UNAUTHORIZED` ·
  `500` `INTERNAL_ERROR`(3초 초과 포함, `504`를 쓰지 않는다)
- **`pageNumber`·`section`은 null일 수 있다.** V21이 nullable이고 계약도 같게 잡았다
- `originalFilename`은 `ai_documents`에서 조인해 가져온다. `ai_document_jobs`에도 같은 이름의
  컬럼이 있지만 검색 응답은 문서 기준이다
- **벡터 인덱스를 쓰지 않는다.** 이 필터·조인과 함께라면 HNSW는 후필터라 조건에 맞는 chunk가
  있어도 `topK`보다 적게 돌려줄 수 있다(over-filtering) — 오류 없이 인용이 사라지는 실패다.
  검색 트랜잭션에 `SET LOCAL enable_indexscan = off`를 걸어 **정확 스캔을 강제한다.** HNSW는
  ordered index scan으로만 닿으므로 그 노드만 막으면 되고, bitmap scan은 남아 있어
  `ix_ai_document_chunks_scope`와 `ai_documents` PK는 계속 쓰인다.
  `SET LOCAL`은 statement가 아니라 트랜잭션 끝까지 살기 때문에 **검색이 끝나면 곧바로 원래
  값으로 되돌린다** — 되돌리지 않으면 같은 트랜잭션의 이후 쿼리가 전부 index scan 없이
  계획된다. 끄기 전에 `current_setting`으로 읽어 둔 값을 `set_config(..., true)`로 되돌리며,
  `= DEFAULT`를 쓰지 않는다 — `DEFAULT`는 세션 값으로 되돌리므로 호출자가 같은 트랜잭션에서
  이미 걸어 둔 `SET LOCAL`을 지워 버린다. 복원이 실패해도 원래 예외를 덮지 않는다(경고 로그만
  남긴다): 3초 초과로 트랜잭션이 중단된 경우 복원 자체가 불가능하고, 그때는 롤백이 `SET LOCAL`을
  어차피 되돌린다. 현재 규모에서는 계획기가 어차피 scope 인덱스 + 정렬을 고르므로 이 설정은 보험이다.
  규모가 커져 느려지면 `hnsw.iterative_scan = strict_order`(pgvector 0.8+)가 후필터 손실을
  **줄여 주지만 recall을 보장하지는 않는다** — `hnsw.max_scan_tuples`·`scan_mem_multiplier`에서
  멈추므로 여전히 `topK`보다 적게 올 수 있다. 정확성이 계약인 동안 보장 수단은 이 GUC뿐이다
- **거리가 `NaN`인 chunk는 응답에서 배제한다.** V21은 `embedding`을 `NOT NULL`로만 두고 영벡터를
  금지하지 않는데, 값은 FastAPI가 계산해 보낸 것이다. 노름 0인 행과의 코사인 거리는 `NaN`이고,
  Jackson은 그것을 **문자열** `"NaN"`으로 쓴다 — 오류 없이 `{"distance":"NaN"}`이 200으로 나가고,
  `number` 타입을 지키는 소비자는 응답 전체를 버린다. 이 필터가 덮는 것은 **노름이 0인 저장
  벡터**(전부 0인 행과, `float`로 누적하면 0이 되는 행)다. 노름이 `float` 범위를 넘어 `Infinity`가
  되는 저장 벡터는 거리가 `NaN`으로 떨어질 때만 함께 걸러지고, 유한값이 되면 뜻 없는 거리로 순위에
  낀다 — **저장 벡터 자체의 검증은 chunk를 쓰는 쪽(S15P21A604-400) 몫이며 이 필터는 그 대체물이
  아니다**
- **`queryEmbedding`은 `float32`(`float4`)로 좁혀 저장된다.** 원소별로 `1e300`처럼 double로는
  유한한 값도 `float4` 범위를 넘으면 400이고, **제곱합이 `float32` 범위를 넘어도 400**이다 —
  pgvector가 노름을 `float`에 누적하므로 원소가 각자 멀쩡해도 1536개를 더하는 사이에 넘칠 수
  있다. **노름이 `float32`에서 0이 되는 벡터도 400**이다: 코사인 거리가 노름으로 나누므로 전부 위와
  같은 `NaN`이 된다. 판정은 pgvector와 같은 **`float` 누산기**로 한다 — `double` 제곱합으로 재면
  원소 자체가 0으로 반올림되는 값(`1e-50`)만 걸리고, 원소는 정상 `float4`인데 **제곱이 언더플로하는
  구간**(`1e-23`씩이면 `double` 합은 `1.5e-43`, `float` 합은 정확히 `0`)을 놓친다

### GET `/internal/ai/agent-config`

FastAPI의 프롬프트 빌더가 **질문마다** 호출한다 (spec 008, `S15P21A604-399`, GitLab #119 §5).
정본 계약은 `specs/008-ai-conversation-rag/contracts/spring-agent-config-api.yaml`.

```
GET /internal/ai/agent-config?boothId=7&agentId=3
Authorization: Bearer <INTERNAL_AI_TO_SPRING_TOKENS 의 첫 값>
```

```json
{ "found": true, "role": "PROJECT_DOCENT", "tone": "FRIENDLY", "responseLength": "MEDIUM",
  "systemPrompt": "문서를 근거로 답한다.", "forbiddenTopics": ["가격 협상"] }
```

- **거부는 오류가 아니라 `200` + `found: false` + `denialCode`다** — `/internal/ai/booth-access`와
  같은 관례. 다른 booth 소속 `agentId`와 존재하지 않는 `agentId`는 **구분해 알려주지 않는다**
  (둘 다 `AGENT_NOT_IN_BOOTH`). `status`가 `ACTIVE`가 아니면 `AGENT_INACTIVE`이며, **이 경우
  프롬프트 필드를 전혀 싣지 않는다** — 쓸 수 없는 값을 흘려 봐야 계약(`additionalProperties: false`
  분기)만 어긴다
- `forbiddenTopics`가 비어 있으면 `null`이 아니라 **빈 배열**이다(`AiAgent.getForbiddenTopics()`가
  이미 그렇게 정규화한다)
- **캐싱하지 않는다** (2026-09-07 확정, GitLab #119) — `system_prompt`가 길어도 매 요청 그대로
  싣는다. 버전·해시로 무효화만 알리는 방식은 필요해지면 그때 계약을 바꾼다
- 인증은 벡터 검색 API와 동일한 `/internal/**` 체인·`INTERNAL_AI_TO_SPRING_TOKENS` 재사용
### POST `/internal/ai/document-jobs/{jobId}/` — `chunk-batches` · `finalize` · `heartbeat` · `failed`

FastAPI 워커가 만든 결과를 Spring이 받는 경로 (spec 007, `S15P21A604-400`, GitLab #119 §3).
정본 계약은 `specs/007-ai-agent-document/contracts/document-result-api.yaml`.
cancel은 Spring→FastAPI 방향이라 여기 없다(#119 §4 — `S15P21A604-175`).

```
POST /internal/ai/document-jobs/41/chunk-batches
{ "attemptNo": 0, "batchSeq": 0, "chunks": [
  { "chunkNo": 0, "content": "...", "embedding": [0.01, ...1536개],
    "embeddingModelId": "text-embedding-3-small", "pageNumber": 3, "section": "운영 안내" } ] }
→ 204

POST /internal/ai/document-jobs/41/finalize
{ "attemptNo": 0, "sourceHash": "<64 hex>", "totalChunkCount": 128,
  "embeddingModelId": "text-embedding-3-small" }
→ 204
```

두 경로 모두 같은 관문 두 개를 먼저 지난다.

| 상황 | 응답 |
|---|---|
| `attemptNo`가 Job의 현재 값과 다름 | **`409` `JOB_ATTEMPT_STALE`** |
| Job이 `SUCCEEDED`·`DEAD`·`CANCELLED`이거나 없음 | **`410` `JOB_GONE`** |

- **`409`가 있는 이유**: lease가 만료돼 Job을 회수하고 `attempt_no`를 올린 뒤, 죽은 줄 알았던 이전
  워커가 결과를 보내오는 경우다. 받아 주면 **두 attempt의 chunk가 한 문서에 섞인다.** 보내는 쪽은
  자기가 밀려났다는 사실을 이 응답으로만 안다
- **없는 Job과 끝난 Job을 구분하지 않는다** — 문서가 지워지면 Job도 `ON DELETE CASCADE`로 사라지고,
  어느 쪽이든 결과를 보낼 attempt가 없다는 답은 같다
- **finalize 재전송은 `410`이 아니라 `204`다.** 같은 attempt가 같은 `sourceHash`·`totalChunkCount`로
  이미 끝낸 Job이면 아무것도 하지 않고 답한다 — 마지막 호출의 응답이 유실되는 것은 흔한 경우이고,
  여기서 `410`을 주면 워커가 **성공한 작업을 실패로 보고한다**(#119 §3의 멱등 요구). 숫자가 다르면
  그 Job이 한 일과 다른 주장이라 `410`이다
- **batch는 멱등하다.** staging PK가 `(job_id, batch_seq, chunk_no)`라 같은 batch 재전송이 아무것도
  바꾸지 않는다 — 워커가 응답을 못 받고 다시 보내는 것이 정상 경로다
- **첫 batch가 `QUEUED` Job을 `RUNNING`으로 올린다.** 워커가 실제로 시작했다는 증거가 이것뿐이다
- **finalize는 검증에서 걸리면 아무것도 바꾸지 않는다.** 기존 chunk도 문서 상태도 그대로이고
  staging도 남아 같은 attempt로 다시 finalize할 수 있다. 검증 4종은 전부 `400` `VALIDATION_FAILED`이고
  `errors[].field`가 지점을 가리킨다

  | 검증 | `field` | 막는 것 |
  |---|---|---|
  | 적재 개수 ≠ `totalChunkCount` | `totalChunkCount` | batch 유실 — 잘린 문서가 조용히 READY가 되는 것 |
  | batch 간 `chunkNo` 중복 | `chunks` | `UNIQUE(document_id, chunk_no)` 위반으로 500이 되는 것 |
  | 임베딩 모델 혼합 | `embeddingModelId` | 한 문서 안에서 거리 비교가 뜻을 잃는 것 |
  | `sourceHash` ≠ Job의 값 | `sourceHash` | 같은 jobId로 다른 파일이 실려 본문이 바뀌는 것 |

- **finalize 성공은 한 트랜잭션이다** — 기존 chunk 삭제 → staging 반영(`searchable = TRUE`) →
  staging 정리 → Job `SUCCEEDED`·`chunk_count` → Document `READY`. 읽는 쪽은 이전 판 전체 아니면
  새 판 전체만 본다
- **같은 batch 안의 `chunkNo` 중복도 `400`이다.** staging PK가 조용히 흡수하면 보낸 쪽은 N개를
  넣었다고 믿고 finalize에서 개수가 어긋난다 — 원인을 말할 수 있는 자리에서 막는다
- 저장 embedding도 `queryEmbedding`과 **같은 검증**을 받는다(1536차원, `float32` 범위, 노름
  overflow·underflow). #119에서 "저장 벡터 검증은 chunk를 쓰는 쪽 몫"이라고 넘긴 것이 이 자리다
- 계약에 없는 필드는 버리지 않고 `400`으로 거부한다 — 검색 API와 같은 strict parser

**heartbeat · failed · lease 회수**

```
POST /internal/ai/document-jobs/41/heartbeat   { "attemptNo": 0 }                        → 204
POST /internal/ai/document-jobs/41/failed
{ "attemptNo": 0, "failureCode": "PARSE_TIMEOUT", "retryable": true, "message": null }   → 204
```

- **워커는 보고만 하고, 다음에 무엇을 할지는 Spring이 정한다.** `retryable`은 워커의 판단이고
  재시도 예산(`max_retries` 기본 3)은 Job이 들고 있다
  - `retryable: false` → 바로 `DEAD`. 손상된 파일은 세 번 더 해도 똑같이 깨진다
  - 여지가 있으면 `RETRY_WAIT` + `next_retry_at` — backoff **1 · 5 · 15분**(#119, 2026-09-03)
  - `attempt_no + 1 > max_retries` → `DEAD`
- **어느 쪽이든 `attempt_no`는 오른다.** 방금 실패한 워커의 늦은 결과를 `409`로 막는 것이 그 값이다.
  그 attempt의 staging도 함께 지운다 — 남기면 다음 attempt의 batch와 섞여 finalize 개수 검증이
  엉뚱한 곳에서 걸린다
- **lease는 90초, heartbeat는 30초 주기**다(#119). 두 번까지 유실돼도 Job을 뺏기지 않는다.
  `QUEUED` Job은 heartbeat로도 `RUNNING`이 된다
- **lease가 만료되면 Spring의 sweeper(30초 주기)가 Job을 회수한다.** FastAPI는 DB 자격증명이 없어
  죽은 워커가 스스로 반납할 수 없고, 회수가 없으면 문서는 영원히 READY가 되지 않는다. 회수가
  `attempt_no`를 올리는 것이 **얼어 있다 깨어난 워커**를 막는 유일한 수단이다 —
  `last_error_code = LEASE_EXPIRED`로 남는다. 여러 인스턴스가 떠도 `SKIP LOCKED`로 서로 다른 행을 집는다
- `failureCode`는 **50자 이하**다(`last_error_code`가 `VARCHAR(50)`) — 넘기면 `400`이지 `500`이 아니다

### POST `/internal/storage/reconciliation-runs`

Infra가 실행한 R2↔MinIO reconcile 결과를 Spring이 받는다 (spec 007 FR-035, S15P21A604-500).
정본 계약은 `specs/007-ai-agent-document/contracts/spring-storage-reconciliation-api.yaml` **v0.2.0**.

인증은 이 체인의 **Infra 방향 토큰**(`INTERNAL_INFRA_TO_SPRING_TOKENS`)이다. AI 방향 토큰과
credential·scope가 분리돼 있어 서로의 경로를 열지 못한다. 세 토큰 집합 중 둘에 같은 값이 들어가면
**Spring이 기동하지 않는다**.

```text
POST /internal/storage/reconciliation-runs
Authorization: Bearer <INTERNAL_INFRA_TO_SPRING_TOKENS 의 첫 값>

{ "runId": "2026-09-10T03:00Z-r2-reconcile", "documentId": 42,
  "objectKey": "booth/7/agent/3/doc.pdf",
  "sourceProvider": "MINIO_LOCAL", "targetProvider": "R2",
  "status": "VERIFIED", "attemptCount": 1, "checkedAt": "2026-09-10T03:04:11Z" }
→ 204
```

- **적재는 `runId + documentId` 기준으로 멱등**하다. 같은 키의 재전송은 상태를 다시 반영하지 않고
  **최초 처리와 같은 응답**을 낸다 — 반영됐으면 204, 반영 못 했으면 계속 409다. 재전송을 무조건
  204로 만들면 같은 요청이 1회차 409, 2회차 204가 된다
- **`VERIFIED`만 문서를 옮긴다.** `MISMATCH`·`MISSING`은 적재만 한다
- **반영 조건은 넷이다**: `VERIFIED` · 문서의 object key가 요청과 같음 · **문서의 현재
  `storageProvider`가 요청의 `sourceProvider`와 같음** · 문서의 bucket이 그 provider의 배포 설정과
  같음. 세 번째가 늦게 도착한 결과가 최신 저장 위치를 과거로 되돌리는 것을 막는다
- 반영할 때 **`storageProvider`와 `storageBucket`을 함께** 옮긴다. 두 provider의 버킷 이름이 서로
  다른 env라 provider만 바꾸면 이후 읽기·삭제가 없는 좌표를 친다. bucket 값은 계약에 없어 Spring이
  배포 설정에서 해석한다. 계약에 `targetBucket`을 넣기로 Infra와 합의했으나 송신부
  (`storage-failover.sh`)가 아직 없어 **미반영**이고, 요청 스키마가 `additionalProperties: false`라
  **지금 보내면 422다.** 도입은 송신부 착수보다 먼저 한다(`S15P21A604-770`)
- **전제**: 한 document에 진행 중인 reconcile run은 최대 하나이며 이전 run 종료 전 반대 방향 전환을
  시작하지 않는다. 이 전제가 깨지면 위 세 번째 조건만으로는 저장 위치가 원래 값으로 돌아온 경우를
  구분하지 못한다(`checkedAt`은 검증을 끝낸 시각이라 그 구분에 쓸 수 없다)
- **멱등 보장 범위는 문서의 수명**이다. 문서가 삭제되면 reconcile 이력도 함께 삭제되고 이후 재전송은
  404다

| 응답 | 뜻 | Infra 처리 |
|---|---|---|
| `204` | 적재·반영됨, 또는 최초 처리와 같은 결과의 재전송 | 완료 |
| `404 DOCUMENT_NOT_FOUND` | 그 `documentId`가 없다 | terminal |
| `409 RECONCILIATION_STALE` | 적재는 됐고 반영은 못 했다 — 문서가 그 source·object key를 더는 갖지 않는다 | **terminal, 재시도 금지** |
| `409 RECONCILIATION_REPLAY_CONFLICT` | 같은 키로 다른 내용이 왔다. 먼저 저장된 결과가 남는다 | terminal + 송신 측 확인 |
| `422 RECONCILIATION_INVALID` | 계약을 벗어났거나 자기모순인 payload | terminal, payload 수정 |
| `500 RECONCILIATION_CONFIGURATION_ERROR` | 요청은 유효한데 Spring 배포에 그 provider 설정이 없거나 문서 행의 bucket이 설정과 어긋난다. 적재되지 않는다 | Spring 설정 수정 후 재시도 |

- **이 저장소에서 422를 쓰는 유일한 경로**다. 계약이 그렇게 정했고 소비자가 Infra 스크립트라
  사용자 입력 오류(400)와 구분되는 편이 낫다
- 상태별로 어떤 필드가 필수인지는 계약에 없고 Spring이 만들지 않는다. **자기모순만 거절**한다 —
  `VERIFIED`인데 기대값과 실측값이 다르거나 실패 사유가 있는 경우, `MISSING`인데 실측값이 있는 경우
- 결과는 `storage_reconciliation_log`(V25)에 남는다. `apply_result`가
  `APPLIED`·`LOGGED_ONLY`·`STALE`로 **왜 반영되지 않았는지**를 행 자체에 적는다
