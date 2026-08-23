# Contract: Booth Layout / Facade API (spec 005)

**Date**: 2026-08-20 | **소비자**: React Booth Studio (FE) · Unity Client (spec 006)
**기준**: docs/08 §3·§4 · 헌법 21·22·24조 · Unity `BoothLayoutDto`(origin/game)

> **변경 규칙**: 이 문서의 필드는 3파트 계약이다. 추가는 통보로 가능하지만 **이름 변경·삭제는 합의 없이 불가**하다 (헌법 24조).
> Unity는 `JsonUtility`로 파싱하므로 **모르는 필드는 조용히 무시된다** — 필드 추가는 Unity 재빌드 없이 안전하다. 반대로 이름을 바꾸면 값이 `0`/`null`로 떨어지며 **오류 없이 잘못 렌더링된다.**

---

## 0. 공통

**Base**: `/api/v1` · **인증**: 편집 계열은 `Authorization: Bearer <access token>` (MEMBER only, GUEST 거부)

### 오류 봉투 — **전 endpoint 공통** (research R-09, 005에서 신설)

```json
{ "code": "LAYOUT_VALIDATION_FAILED", "message": "배치를 공개할 수 없습니다.", "requestId": "req_…",
  "errors":   [ { "rule": "OBJECT_LIMIT", "message": "오브젝트는 12개까지입니다. (현재 14개)" } ],
  "warnings": [ { "rule": "CONFIG_NOT_LINKED", "objectId": "ai-1", "message": "AI 직원이 연결되지 않았습니다." } ] }
```

**모든 `message`는 한글이다.** 프레임워크가 만드는 영문 문구(Spring의 `No static resource …`, Jackson의 `Unrecognized field …`)는 응답에 싣지 않고 로그로만 남긴다. FE는 `message`를 그대로 노출해도 된다 — 단 **분기는 `code`·`rule`로** 한다.

**`errors`·`warnings`는 항상 있다** — 보고할 것이 없으면 빈 배열이다. 없을 때 키를 빼면 `errors.length`가 클라이언트에서 터지고 빈 배열이면 `0`이 된다. 저장·공개 성공 응답의 `warnings`도 같다. `requestId`는 요청당 발급되며 응답 헤더 `X-Request-Id`·서버 로그와 **같은 값**이라 문의가 들어오면 바로 추적된다.

**`errors[].objectId`는 해당 오브젝트가 있을 때만 실린다** — 배치 전체에 대한 항목(개수 초과 등)에는 `null`이 아니라 **키 자체가 빠진다**(`@JsonInclude(NON_NULL)`). optional로 파싱할 것 (#36, 2026-08-21 정정).

> **003·004 오류 응답도 이 형태로 바뀐다.** 지금까지는 코드 없이 한국어 문장만 나갔다 — docs/08 §1.3과 Bruno 문서가 약속한 형태에 구현을 맞추는 것이다 (R-09). 오류 본문을 읽는 소비자가 아직 없음을 확인하고 결정했다.

| code | HTTP | 언제 |
|---|---|---|
| `BOOTH_NOT_FOUND` | 404 | 부스 없음 |
| `BOOTH_EDITOR_FORBIDDEN` | 403 | owner도 staff도 아님 (FR-012) |
| `BOOTH_LEASE_EXPIRED` | 409 | 임대가 유효하지 않음 — **004와 같은 코드를 재사용한다** |
| `LAYOUT_REVISION_CONFLICT` | 409 | 다른 편집자가 먼저 저장함 (FR-014) |
| `LAYOUT_VALIDATION_FAILED` | 409 | `errors`가 비어 있지 않음 (FR-007) |
| `LAYOUT_NOT_PUBLISHED` | 404 | 공개된 배치가 없음 — Unity는 이미 404를 **경고 후 graceful skip**으로 처리한다 |
| `VALIDATION_FAILED` | 400 | Layout 외 일반 필드 검증 실패 — facade 등 (§6) |

### `rule` 목록 — **Layout 한정** (#36 FE 요청으로 명문화, 2026-08-21)

`errors[]`·`warnings[]`의 `rule`은 FE가 분기해도 되는 계약값이다. 아래는 **Layout 계열 endpoint(§2~§5·§11)가 내는 전부**이며, 여기 없는 Layout rule이 응답에 나오면 계약 위반이다.

> **전 endpoint 공통 rule은 `docs/08` §1.3의 전역 rule 절에 있다** (#58, 2026-08-23). 예: Bean Validation 실패는 어느 endpoint에서든 `rule: "FIELD_INVALID"` + `field`로 온다 — Layout 전용 목록과 섞지 않는다.

**error (공개 차단, ✅ = Draft 저장에도 적용)**

| rule | 적용 | 뜻 |
|---|---|---|
| `MALFORMED_LAYOUT` | ✅ | 계약에 없는 필드 포함, 또는 JSON이 Layout 문서가 아님 |
| `UNSUPPORTED_SCHEMA_VERSION` | ✅ | `schemaVersion`이 서버가 아는 값(1)이 아님 |
| `UNKNOWN_TEMPLATE` | ✅ | `template`이 화이트리스트에 없음 (`DEFAULT` 포함 — 제거됨) |
| `MISSING_OBJECTS` | ✅ | `objects` 배열 자체가 없음 (빈 배치는 `[]`) |
| `OBJECT_LIMIT` | ✅ | 오브젝트 12개 초과 (헌법 22조) |
| `INVALID_OBJECT_ID` | ✅ | `objectId` 형식 위반 (1~64자 `[A-Za-z0-9_-]`) |
| `DUPLICATE_OBJECT_ID` | ✅ | 한 배치 안에서 `objectId` 중복 |
| `UNKNOWN_OBJECT_TYPE` | ✅ | canonical 10종에 없는 `type` (POC 표기 `SURVEY`·`CONSULT_DESK` 포함) |
| `MISSING_POSITION` | ✅ | `position`의 x·y·z 중 누락 |
| `POSITION_OUT_OF_BOUNDS` | ✅ | 앵커 점이 부스 영역 밖 (`\|x\|,\|z\| ≤ 3`, `0 ≤ y ≤ 2.72`) |
| `MISSING_ROTATION` | ✅ | `rotationY` 없음 |
| `ROTATION_OUT_OF_RANGE` | ✅ | `rotationY`가 `[0, 360)` 밖 |
| `AREA_OUT_OF_BOUNDS` | ✅ | **실물(회전 반영 AABB)**이 부스 영역 밖 (§10-2, #19 ③) |
| `CONFIG_NOT_OWNED` | 공개만 | `configId`가 그 부스 소유 콘텐츠가 아니거나 존재하지 않음 (헌법 16·17조) |

**warning (공개 허용 — 성공 응답의 `warnings`에 실림)**

| rule | 적용 | 뜻 |
|---|---|---|
| `CONFIG_NOT_LINKED` | 공개만 | 기능 오브젝트에 `configId` 미연결 (C-04 확정: 경고 유지 — #45) |
| `CONFIG_UNVERIFIED` | 공개만 | 연결 대상의 종류를 아직 서버가 확인할 수 없음 |
| `FRONT_BLOCKED` | 공개만 | 관람 띠 도달 가능 비율 50% 미만 (§10-3, #19 ⑤) |
| `ISOLATED_AREA` | 공개만 | 통행 불가 고립 공간 1㎡ 이상 — 배치 전체 항목이라 `objectId` 없음 (§10-3) |

**특수 — 409 `LAYOUT_REVISION_CONFLICT` 전용**

| rule | 뜻 |
|---|---|
| `CURRENT_REVISION` | 충돌 시 `errors[0]`에 실린다. `message`에 서버의 현재 revision이 문장으로 담기지만 **구조화된 숫자 필드는 없다** — **재호출 트리거로만 쓴다**(값을 정규식으로 뽑지 않는다). 값이 필요한 화면이 생기면 그때 타입 있는 필드를 추가한다 (#58 결론, 2026-08-23) |

---

## 1. Layout JSON — 공통 본문

```json
{
  "schemaVersion": 1,
  "template": "PROJECT_EXHIBITION",
  "objects": [
    { "objectId": "screen-1", "type": "VIDEO_SCREEN",
      "position": { "x": 2.1, "y": 0.0, "z": 1.4 }, "rotationY": 90.0, "configId": 152 },
    { "objectId": "sofa-1", "type": "FURNITURE", "assetCode": "SOFA_A",
      "position": { "x": -1.0, "y": 0.0, "z": 0.5 }, "rotationY": 180.0 }
  ]
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `schemaVersion` | int | ✅ | 구조 버전. 현재 **1**. 공개 회차(`version`)와 **다른 값이다** (research R-10) |
| `template` | string | ✅ | `PROJECT_EXHIBITION` 단독 — `DEFAULT`는 #19 ④·#45 C-06(2026-08-21)으로 제거, 기존 저장분은 V11이 이관. 종수 확장은 C-06 확정 시 (목록은 §9로 조회) |
| `objects[].objectId` | string | ✅ | 1~64자 `[A-Za-z0-9_-]`, 배치 안에서 유일. FE는 UUID 사용(규칙 부분집합 — #36 확인) |
| `objects[].type` | string | ✅ | `AI_AGENT` `VIDEO_SCREEN` `PROJECT_PANEL` `SURVEY_KIOSK` `RECRUITMENT_BOARD` `CONSULTATION_DESK` `LAPTOP` `LIKE_VOTE` `FURNITURE` `DECORATION` |
| `objects[].position` | {x,y,z} number | ✅ | **미터**. 원점 = 부스 바닥 중앙, `y=0`이 바닥, +Z가 정면 (헌법 21조). 부스는 **6×6×2.72m** (높이는 셸 벽 패널 실측 — #19 ②, 2026-08-21 확정) → 앵커는 `|x|,|z| ≤ 3`, `0 ≤ y ≤ 2.72`, **실물(회전 반영 AABB)도 같은 영역 안이어야 한다** (§10) |
| `objects[].rotationY` | number | ✅ | 도(degree), `[0,360)`. `0`이면 +Z를 바라봄 |
| `objects[].configId` | int | ❌ | 연결된 콘텐츠 ID — **signed Int32, `1 ~ 2,147,483,647`, 0 금지**(Unity가 필드 부재를 0으로 읽어 미연결 판정에 씀 — #45 전제, #34에서 DB `CHECK (config_id > 0)`로 강제). 공개 시 **그 부스 소유인지 서버가 확인**한다 (헌법 16조) |
| `objects[].assetCode` | string | ❌ | `FURNITURE`·`DECORATION`의 구체 자산 식별자 |

**표에 없는 필드는 거부된다** — 계약에 없는 필드가 하나라도 있으면 저장 자체가 `409 LAYOUT_VALIDATION_FAILED` + `rule: MALFORMED_LAYOUT`로 실패한다(조용히 버리지 않는다 — 버리면 편집기는 저장됐다고 믿는데 서버에는 없는 T-24 모양이 된다). 필드 추가 순서는 **3파트 합의 → BE가 `schemaVersion` 올리고 배포 → 그다음 FE 전송**이다 (헌법 24조, #36 명문화 2026-08-21).

**BE는 이 값들을 변형하지 않는다** — 반올림·정규화·기본값 주입을 하지 않고 저장하고 그대로 돌려준다 (research R-04). 서버가 유일하게 덧붙이는 것은 `version`·`revision` 같은 **메타 필드**다.

보낸 자릿수도 유지된다 — `2.10`을 보내면 `2.10`으로 돌아온다. 다만 PostgreSQL `numeric`이 다시 쓰는 표기가 **두 가지** 있다. 둘 다 값은 같다.

| 보낸 값 | 저장·응답 | 이유 |
|---|---|---|
| `-0.0` | `0.0` | `numeric`에 부호 있는 0이 없다. 같은 지점이다 |
| `1e2` | `100` | 지수 표기를 평문으로 편다 |

JSON 키 순서도 `jsonb`가 정규화한다. 의미에 영향이 없다.

`scale`은 **없다** (C-03 미정). 도입 시 `schemaVersion`을 2로 올린다.

---

## 2. `GET /booths/{boothId}/layouts/draft`

편집기용 작업본 조회. **권한 필요** — 방문자에게는 어떤 경로로도 노출되지 않는다 (I-4).

**200**
```json
{ "boothId": 7, "revision": 3, "schemaVersion": 1, "template": "PROJECT_EXHIBITION",
  "objects": [ … ], "updatedAt": "2026-08-20T10:05:00Z", "updatedByUserId": 42,
  "publishedVersion": 2 }
```

- 작업본이 아직 없으면 **204 No Content** — 편집기는 빈 배치로 시작하고 첫 저장에 `expectedRevision: 0`을 보낸다.
- `publishedVersion`은 현재 공개 중인 회차(`null` 가능). 편집기가 "공개본과 다름"을 표시하는 데 쓴다.

**오류**: 403 `BOOTH_EDITOR_FORBIDDEN` · 404 `BOOTH_NOT_FOUND`

---

## 3. `PUT /booths/{boothId}/layouts/draft`

작업본 저장. **공개되지 않는다** (FR-005·FR-006).

**Request**
```json
{ "expectedRevision": 3, "schemaVersion": 1, "template": "PROJECT_EXHIBITION", "objects": [ … ] }
```

| 필드 | 규칙 |
|---|---|
| `expectedRevision` | ✅ 필수. 최초 저장은 `0`. 서버의 현재 `revision`과 다르면 409 |

**200**
```json
{ "boothId": 7, "revision": 4, "schemaVersion": 1, "template": "PROJECT_EXHIBITION",
  "objects": [ … ], "updatedAt": "2026-08-20T10:07:11Z", "warnings": [ … ] }
```

`warnings`는 있어도 저장을 막지 않는다. 저장 단계에서 적용되는 **error** 규칙은 data-model §3의 "Draft 저장에도 ✅" 표시 항목뿐이다.

**오류**: 403 · 404 · 409 `LAYOUT_REVISION_CONFLICT` (본문에 서버의 현재 `revision` 포함) · 409 `LAYOUT_VALIDATION_FAILED`

> **409 REVISION_CONFLICT 본문에 최신 Draft는 실리지 않는다** — 오류 봉투에 자리가 없어 서버의 현재 `revision`만 알린다. 충돌 시 FE가 `GET /draft`를 한 번 더 호출한다. *(초안의 "최신 Draft 동봉"(research R-03)은 구현하지 않았고, FE가 구현 쪽을 채택해 확정 — #36, 2026-08-21. FE는 자동 병합을 하지 않으므로 동봉본을 쓸 자리가 없다.)*

---

## 4. `POST /booths/{boothId}/layouts/publish`

현재 작업본을 새 공개 회차로 만든다. **복사이며, 이후 Draft 수정은 공개본에 영향을 주지 않는다** (I-7).

**Request**: 본문 없음

**200**
```json
{ "boothId": 7, "publishedVersion": 4, "publishedAt": "2026-08-20T10:09:00Z", "warnings": [ … ] }
```

**오류**: 403 · 404 · 409 `BOOTH_LEASE_EXPIRED`(만료된 부스는 공개 불가) · 409 `LAYOUT_VALIDATION_FAILED`

---

## 5. `GET /booths/{boothId}/layouts/published`

**Unity가 부스 진입 시 호출하는 경로** (spec 006 `BoothRuntime`). **인증 불필요** — 방문자 전원이 본다.

**200** — Unity `BoothLayoutDto`와 필드가 일치해야 한다
```json
{ "boothId": 7, "version": 4, "schemaVersion": 1, "template": "PROJECT_EXHIBITION",
  "objects": [ … ] }
```

| 필드 | Unity DTO 대응 |
|---|---|
| `boothId` | `int boothId` |
| `version` | `int version` — **공개 회차**다. `schemaVersion`과 혼동 금지 |
| `template` | `string template` |
| `objects[]` | `BoothObjectDto[]` (`objectId`·`type`·`assetCode`·`position`·`rotationY`·`configId`) |

- `schemaVersion`은 Unity DTO에 없다 → `JsonUtility`가 무시한다. **추가해도 안전하다**.
- 공개된 것이 없으면 **404 `LAYOUT_NOT_PUBLISHED`**.
- 임대가 유효하지 않으면 **409 `BOOTH_LEASE_EXPIRED`** (FR-015, I-5).

---

## 6. `PUT /booths/{boothId}/facade` — **신설** (docs/08에 없음, 통보 대상)

외부 슬롯 표현값 수정. 006이 렌더링하는 값이며 자유 배치가 아니다.

**Request**
```json
{ "themeCode": "SSAFY_BLUE", "primaryColor": "#1677C8", "signText": "AI 프로젝트 전시관", "logoUrl": null }
```

| 필드 | 규칙 |
|---|---|
| `themeCode` | ✅ 화이트리스트: **`DEFAULT` · `SSAFY_BLUE` · `WARM` · `MONO`** (#17 합의, #36에서 명문화 요청). 기본 `DEFAULT` |
| `primaryColor` | **6자리 `#RRGGBB`만** — 축약형(`#RGB`)·알파 불허 (#17 팀 합의). **아래 12색 팔레트 안의 값이어야 한다.** 또는 `null` |
| `signText` | 최대 60자 또는 `null` |
| `logoUrl` | `https://` URL 최대 2048자 또는 `null` |

**200**: 저장된 facade 전체. **오류**: 400 `VALIDATION_FAILED`(필드 검증 실패) · 403 · 404 · 409 `BOOTH_LEASE_EXPIRED`

검증 실패 응답 형태 — **#17에서 확정** (2026-08-21, 구현·문구 일치):

```json
{ "code": "VALIDATION_FAILED", "message": "대표색은 #RRGGBB 형식이어야 합니다.",
  "requestId": "req_a1b2c3d4", "errors": [], "warnings": [] }
```

### 팔레트 — 12색 확정 (#17, 2026-08-23)

**팔레트는 테마와 무관한 전역 1개**(A안)다. `themeCode` 4종과 곱집합이 아니다. Tailwind 500 계열이며 값의 정본은 이 표다 — Figma는 참조이고, 색이 바뀌면 이 표를 고치고 Figma를 맞춘다.

| code | hex | label |
|---|---|---|
| `RED` | `#EF4444` | 레드 |
| `ORANGE` | `#F97316` | 오렌지 |
| `AMBER` | `#F59E0B` | 앰버 |
| `YELLOW` | `#EAB308` | 옐로 |
| `LIME` | `#84CC16` | 라임 |
| `GREEN` | `#22C55E` | 그린 |
| `TEAL` | `#14B8A6` | 틸 |
| `CYAN` | `#06B6D4` | 시안 |
| `BLUE` | `#3B82F6` | 블루 |
| `INDIGO` | `#6366F1` | 인디고 |
| `PURPLE` | `#A855F7` | 퍼플 |
| `PINK` | `#EC4899` | 핑크 |

- 저장 필드는 `primaryColor`(hex)다. `code`·`label`은 FE가 "몇 번째 칸인지" 찾고 접근성 표기에 쓰는 계약값이며 서버는 저장하지 않는다.
- **표기 정규화 1건** — 저장 시 hex를 **대문자로 정규화**한다(`#ef4444` → `#EF4444`로 저장·응답). §1의 "BE는 값을 변형하지 않는다"의 예외이며, 좌표와 달리 hex 대소문자는 같은 색의 다른 표기라 `numeric`의 `-0.0` → `0.0`과 같은 부류다.
- 팔레트 밖 색은 **400 `VALIDATION_FAILED`**. 화이트리스트 시행 이전에 저장된 값은 그대로 렌더링되고 **다음 저장 때 검증**된다(강제 이관하지 않는다).
- 목록을 서버가 서빙하는 `GET /booth-facade-options`는 **후속 제안**이다 (#17) — 그때까지 FE는 이 표를 상수로 쓴다.

---

## 7. `GET /booths/{boothId}` — 기존 응답 **확장** (004 소유)

docs/08이 이미 정의한 두 필드를 실제로 채운다. **추가일 뿐 기존 필드는 그대로다.**

```json
{ "boothId": 7, "slotId": 5, "name": "AI 프로젝트 전시관", "leaseStatus": "ACTIVE", "entryAvailable": true,
  "facade": { "themeCode": "SSAFY_BLUE", "primaryColor": "#1677C8", "signText": "AI 프로젝트 전시관", "logoUrl": null },
  "publishedLayoutVersion": 4,
  "endsAt": "2026-08-21T02:20:25Z" }
```

`endsAt`은 004가 이미 내보내는 필드다 — spec 008의 AI 대화 만료 계약이 이 값을 쓴다 (`docs/HDD/임대만료_AI대화_종료계약_검토.md` §0-1).

---

## 8. 왕복 검증 (spec 005 리뷰 ④칸 — ✅ 2026-08-20 통과)

**통과했다** — FE 작성 JSON을 Unity가 실측해 world 좌표·회전이 소수점까지 일치, 부호 오류 0건. `x` 양·음 / `z` 양·음 / `rotationY` 90·180 네 모호 축 전부 확인 ([#6](https://github.com/kanghyunsoon/ssafesta/issues/6), `docs/LJH/verify/block1-roundtrip.md`, FE 이정헌 / Unity 강형순).

BE가 보장하는 것은 **저장·조회 왕복에서 값이 바뀌지 않는다**는 것이다(SC-004의 절반). 부호와 원점은 위 실측으로 FE·Unity가 확인했다.

---

## 9. `GET /booth-layout-templates` — **신설** (#19 ④, 2026-08-21)

편집기가 Unity 없이 뜨는 데 필요한 카탈로그. footprint(6×6×2.72)와 오브젝트 상한(12)을 FE·Unity·서버가 각자 알던 것을 **한 곳에서 받는다**. 서버 구현도 이 값을 검증 상수에서 유도하므로 검증과 카탈로그가 어긋날 수 없다. **권한 필요** (편집기 전용 데이터).

**200**
```json
{
  "templates": [
    { "template": "PROJECT_EXHIBITION",
      "footprint": { "width": 6.0, "depth": 6.0, "height": 2.72 },
      "maxObjects": 12 }
  ]
}
```

- `template → 셸 프리팹`은 1:1이고 현재 셸이 1종이라 목록도 1개다. C-06(종수)이 확정되면 행이 늘어난다.
- `height`는 셸 유효 높이(벽 패널 상단 실측 2.725의 내림) — 정면 트러스(z 2.85~3.15 띠, y 1.85↑)는 부스 경계 밖이라 배치 공간을 제약하지 않는다.

---

## 10. 기하 계약 — type→로컬 bounds · 실물 검증 · 통행 판정 (#19 ③·⑤, 2026-08-21 확정)

> FE 편집기의 실시간 판정과 서버의 저장·공개 판정이 **같은 답**을 내야 하므로, 여기 값은 전부 계약이다. 바꾸려면 3파트 합의가 필요하다 (헌법 24조).

### 10-1. type → 로컬 AABB (Unity 프리팹 실측, rotationY=0 기준, 단위 m)

원점은 전 타입 바닥(min.y = 0)이고 **x·z는 비대칭**이다 — size만 들고 중앙 원점을 가정하면 회전 계산이 틀린다. 파츠의 "정면"은 로컬 **+z**다.

| type | min (x, y, z) | max (x, y, z) |
|---|---|---|
| `AI_AGENT` | (−0.31, 0, −0.16) | (0.31, 1.15, 0.16) |
| `VIDEO_SCREEN` | (−1.50, 0, −0.15) | (1.20, 2.10, 0.15) |
| `PROJECT_PANEL` | (−0.78, 0, −0.18) | (0.77, 2.72, 0.18) |
| `SURVEY_KIOSK` | (−0.31, 0, −0.16) | (0.31, 0.93, 0.16) |
| `RECRUITMENT_BOARD` | (−1.50, 0, −0.18) | (1.50, 2.72, 0.18) |
| `CONSULTATION_DESK` | (−0.93, 0, −1.00) | (0.93, 0.92, 0.16) |
| `LAPTOP` | (−0.40, 0, −0.40) | (0.40, 0.94, 0.40) |
| `LIKE_VOTE` | (−0.31, 0, −0.16) | (0.31, 1.23, 0.16) |
| `FURNITURE` | (−0.61, 0, −0.86) | (0.89, 0.75, 0.86) |
| `DECORATION` | (−0.30, 0, −0.30) | (0.30, 1.61, 0.30) |

이 값은 **프리팹이 바뀌면 같이 바뀐다.** 타입당 프리팹이 2개 이상이 되면 타입별 최대 포락(가장 큰 프리팹)으로 갱신한다 — 서버가 보수적인 쪽.

**회전 규칙**: `rotationY`(0~360 연속값)를 **원점 기준으로 네 모서리에 적용한 뒤 AABB를 다시 잡는다** — 90° 단위 스왑이 아니다. 행렬은 Unity Y축 회전과 같다: `x' = x·cos + z·sin`, `z' = −x·sin + z·cos` (위에서 볼 때 시계방향 +).

### 10-2. 실물 영역 검증 — **error `AREA_OUT_OF_BOUNDS`** (Draft 저장·공개 모두)

회전 반영 AABB + position이 부스 영역(`|x|,|z| ≤ 3`, `0 ≤ y ≤ 2.72`)을 벗어나면 거부. 남의 슬롯을 침범하는 객관적 결함이라 error다. 경계선상은 안이다(서버는 부동소수점 잡음 1e-9 m만 허용 — 판정을 뒤집을 수 없는 크기).

### 10-3. 통행 판정 — **warning** (공개 시점만, 공개는 막지 않음)

| 항목 | 계약값 |
|---|---|
| 래스터 해상도 | **0.05 m** — 부스 로컬 x·z ∈ [−3, +3], **셀 중심 = −2.975 + 0.05k** (k = 0…119), 120×120 |
| 점유 판정 | 회전 적용 후 AABB와 셀 중심의 포함 검사 (**경계선상은 점유** — 보수적) |
| 아바타 침식 | 점유 셀을 **유클리드 반경 0.22 m** 팽창 (`PlayerAvatar` 캡슐 반지름 2.2 world unit ÷ 10) |
| flood fill | **4-방향 연결**, 시작점은 **+z 경계(z = +3) 쪽 비점유 셀 전부** — 정면만 개방, 좌·우·후면 벽 |
| 관람 띠 | 상호작용 파츠(장식 `FURNITURE`·`DECORATION` 제외)의 **+z(정면) 면에서 바깥으로 0.7 m** 폭 |
| **`FRONT_BLOCKED`** (warning, objectId 포함) | 관람 띠 셀 중 도달 가능 비율 **50% 미만** |
| **`ISOLATED_AREA`** (warning, 배치 전체) | 침식 후 비점유인데 flood fill 미도달 셀이 **1 ㎡ 이상** |

publish 응답의 기존 `warnings` 채널(`CONFIG_NOT_LINKED`·`CONFIG_UNVERIFIED`와 동일 형식)에 실린다 — FE 파서 추가 작업 없음, rule 이름 두 개만 새로 안다.

---

## 11. `GET /booth-slots/{slotId}/layouts/published` — **신설** (#62, 2026-08-23)

**Unity가 부스 방(앵커)에서 호출하는 경로.** **인증 불필요** — §5와 같이 방문자 전원이 본다.

`boothId`는 방 번호가 아니다 — `booth_slots`(방, 시드로 고정)와 `booths`(소유자의 콘텐츠, 임대 시 발급)는 다른 축이고, **재임대하면 같은 방의 `boothId`가 바뀐다**(D05·FR-011·FR-017의 보존·격리 정책 때문). 그래서 앵커 번호로 §5를 호출하면 **404이거나 남의 부스가 그려진다.** 서버가 `슬롯 → 유효 임대 → boothId` 해석을 흡수한다.

**200** — **body는 §5와 완전히 동일**하다 (`boothId` 포함 — 어느 부스를 받았는지 알 수 있다)
```json
{ "boothId": 27, "version": 4, "schemaVersion": 1, "template": "PROJECT_EXHIBITION",
  "objects": [ … ] }
```

| 상황 | 응답 |
|---|---|
| 빈 슬롯(임대 없음) 또는 아직 공개하지 않음 | **404 `LAYOUT_NOT_PUBLISHED`** — Unity의 기존 graceful skip이 그대로 맞는다 |
| 임대 만료 | **409 `BOOTH_LEASE_EXPIRED`** (FR-015, I-5) — #36에서 "404와 동일 처리"로 합의 |
| 없는 슬롯 번호 | 404 |

- `slotId` **1~12가 Unity 앵커 `01~12`와 대응**한다 — V12 시드가 id를 명시 삽입해 고정한다. 표시용 `slotCode`(`F11-R03`)는 `GET /booth-slots` 목록에 있다.
- 같은 자원에 URL이 둘인 것은 **호출자의 자연 키가 달라서**다: FE 편집기는 "내 부스"(`boothId`, §5), Unity는 "이 방"(`slotId`, §11).
