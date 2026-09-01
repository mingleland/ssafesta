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

- `homepageUrl`: 부스 노트북이 여는 홈페이지 (spec 016 FR-003 신설). **`publishedLayoutVersion`이 `null`이면 이 값도 `null`로 내려간다** — "공개 상태"를 *공개된 Layout이 있는 상태*로 해석한다(노트북은 공개 Layout 안에만 있으므로 방문자가 URL을 쓰는 순간과 일치). 미등록도 `null`이라 FE는 `null` 하나로 "미등록/미공개" 안내 분기를 끝낸다.
- ⚠️ **회차 필드명은 endpoint마다 다르고 합치지 않는다** (2026-08-26 리드 확정, #97). 이 Booth 상세는 **`publishedLayoutVersion`**, Layout Draft 조회·Publish 결과는 **`publishedVersion`**이다.

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

---

## 5. Project

부스가 전시하는 프로젝트. **부스당 1개**다 (spec 009 C-01, 2026-08-28 확정). 정본 계약은
`specs/009-project-exhibition/contracts/project-api.md`.

편집 권한은 **소유자 또는 스태프**(facade·layout과 같은 편집자 범위, `BoothEditorGuard`).
회원만 — 게스트는 `403 MEMBER_ONLY`. 쓰기는 **유효 임대**를 요구하고, 읽기는 만료돼도 된다
(009 FR-008 — 만료돼도 데이터는 보존된다).

**예외는 방문자 조회 하나다** — `GET /booths/{boothId}/projects/published`는 게스트가 정상
경로이고 토큰 없이 `200`이다. 편집·편집자 조회는 위 규칙 그대로다.

> ⚠️ 직원 역할 게이트(011 C-09 `ADMIN`·`CONTENT_EDITOR`)는 **아직 걸려 있지 않다.**
> `BoothEditorGuard`가 `role`을 읽지 않으며 005·016도 같은 상태다 — 011 구현 시 가드 한 곳에서
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

---

## 6. AI Agent Config 관리

AI 실행 자체는 FastAPI가 담당하지만 Agent 설정 Source of Truth는 Spring을 기본으로 한다.
정본 계약은 `specs/007-ai-agent-document/spec.md`(C-12·C-13·C-14·C-15).

편집 권한은 **소유자 또는 스태프**(`BoothEditorGuard` — §4·§5와 같은 편집자 범위, spec 007 C-15).
회원만 — 게스트는 `403 MEMBER_ONLY`. 쓰기는 **유효 임대**를 요구하고, 읽기는 만료돼도 된다
(007 FR-015 — 만료돼도 설정은 보존된다).

> ⚠️ 직원 역할 게이트(011 C-09 `ADMIN`·`CONTENT_EDITOR`)는 **아직 걸려 있지 않다.**
> `BoothEditorGuard`가 `role`을 읽지 않으며 005·009·016도 같은 상태다 — 011 구현 시 가드 한
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

사람 상담 요청 생성. `requested_at + 10분`을 `expiresAt`으로 계산해 응답에 포함한다 (C-01, spec 011 — 2026-08-31 확정, GitLab work_items#118).

```json
{
  "conversationId": "conv_01JABCXYZ",
  "agentId": 78
}
```

응답 예:

```json
{
  "consultationId": 901,
  "status": "REQUESTED",
  "requestedAt": "...",
  "expiresAt": "..."
}
```

10분 내 Accept가 없으면 `REQUESTED → EXPIRED`로 전환하고 `CONSULTATION_EXPIRED` WebSocket 이벤트로 알린다(docs/16 §11). FE는 `expiresAt`으로 잔여 시간을 안내하고 만료 후 재요청 버튼을 노출한다.

### GET `/consultations/{consultationId}`

응답 `status`에 `EXPIRED`가 포함된다.

### POST `/consultations/{consultationId}/accept`

한 명의 Staff만 성공해야 한다. 이미 `EXPIRED`/`REJECTED`/다른 Staff가 `ACCEPTED`한 요청은 거부한다.

### POST `/consultations/{consultationId}/end`

상담 종료.

메시지는 WebSocket event 중심으로 처리한다. 오프라인 시 메시지 남기기(비동기 문의)는 P1에서 제외하고 P2 후속 이슈로 분리했다(C-02, spec 011). 원문 History REST Endpoint 도입 여부·보존 기간은 P2 spec 착수 시 확정한다(C-03). P1 메타데이터·Handoff Summary(`consultations.summary`)는 프로젝트 종료 시 일괄 삭제한다(docs/09 §27).

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

> ⚠️ **배포 조치 (Infra).** `INTERNAL_AI_TO_SPRING_TOKENS` 는 **기본값이 없어 주입하지 않으면
> 애플리케이션이 기동하지 않는다.** 현재 `infra/deploy/compose/dev/back.compose.yaml` 은 환경변수를
> 하나도 넘기지 않고 `integration/compose.yaml` 도 `FESTA_ENVIRONMENT`·`AI_BASE_URL` 둘뿐이라,
> 이 값은 물론 아래 목록 전체가 아직 컨테이너에 도달하지 않는다. Jenkins credential →
> `with-credentials.sh` → compose `environment` 경로로 함께 wire 해야 한다.
>
> | 변수 | 기본값 | 없으면 |
> |---|---|---|
> | `JWT_SECRET`(base64)·`CONNECTION_TOKEN_SECRET`·`INTERNAL_AI_TO_SPRING_TOKENS` | 없음 | **기동 실패** |
> | `GOOGLE_CLIENT_ID/SECRET/REDIRECT_URI`·`KAKAO_REST_API_KEY/CLIENT_SECRET/REDIRECT_URI` | 없음 | **기동 실패** |
> | `POSTGRES_HOST/PORT/DB/USER/PASSWORD`·`REDIS_HOST/PORT` | localhost 기본값 | 컨테이너 안 localhost 를 본다 |
> | `FRONTEND_BASE_URL`·`AUTH_COOKIE_SECURE`·`WORLD_SCHEME/HOST/PORT` | 로컬 기본값 | CORS·쿠키·월드 접속이 로컬 값으로 뜬다 |
> | `ROOT_DOMAIN` | 없음 | `application-infra.yml` 의 `app.world.host` 가 `world.` 만 남는다 |
> | `SPRING_PROFILES_ACTIVE` | `local` (`spring.profiles.default`) | 배포에서도 `local` 프로파일이 뜬다 — 아래 |
>
> **프로파일은 `SPRING_PROFILES_ACTIVE=infra` 다.** `FESTA_ENVIRONMENT` 는 Spring 프로파일이
> 아니다. 배포 프로파일을 `application-infra.yml` 로 두는 것은 확정됐고(GitLab #117,
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
> 가지 않는다. 자격증명과 scope는 `INTERNAL_INFRA_TO_SPRING_TOKENS`로 그대로 분리된다.

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
