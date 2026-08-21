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
  "errors":   [ { "rule": "OBJECT_LIMIT", "objectId": null, "message": "오브젝트는 12개까지입니다. (현재 14개)" } ],
  "warnings": [ { "rule": "CONFIG_NOT_LINKED", "objectId": "ai-1", "message": "AI 직원이 연결되지 않았습니다." } ] }
```

**모든 `message`는 한글이다.** 프레임워크가 만드는 영문 문구(Spring의 `No static resource …`, Jackson의 `Unrecognized field …`)는 응답에 싣지 않고 로그로만 남긴다. FE는 `message`를 그대로 노출해도 된다 — 단 **분기는 `code`·`rule`로** 한다.

`errors`·`warnings`는 검증 응답에만 있다. `requestId`는 요청당 발급되며 응답 헤더 `X-Request-Id`·서버 로그와 **같은 값**이라 문의가 들어오면 바로 추적된다.

> **003·004 오류 응답도 이 형태로 바뀐다.** 지금까지는 코드 없이 한국어 문장만 나갔다 — docs/08 §1.3과 Bruno 문서가 약속한 형태에 구현을 맞추는 것이다 (R-09). 오류 본문을 읽는 소비자가 아직 없음을 확인하고 결정했다.

| code | HTTP | 언제 |
|---|---|---|
| `BOOTH_NOT_FOUND` | 404 | 부스 없음 |
| `BOOTH_EDITOR_FORBIDDEN` | 403 | owner도 staff도 아님 (FR-012) |
| `BOOTH_LEASE_EXPIRED` | 409 | 임대가 유효하지 않음 — **004와 같은 코드를 재사용한다** |
| `LAYOUT_REVISION_CONFLICT` | 409 | 다른 편집자가 먼저 저장함 (FR-014) |
| `LAYOUT_VALIDATION_FAILED` | 409 | `errors`가 비어 있지 않음 (FR-007) |
| `LAYOUT_NOT_PUBLISHED` | 404 | 공개된 배치가 없음 — Unity는 이미 404를 **경고 후 graceful skip**으로 처리한다 |

---

## 1. Layout JSON — 공통 본문

```json
{
  "schemaVersion": 1,
  "template": "PROJECT_EXHIBITION",
  "objects": [
    { "objectId": "screen-1", "type": "VIDEO_SCREEN",
      "position": { "x": 2.1, "y": 0.0, "z": 2.4 }, "rotationY": 90.0, "configId": 152 },
    { "objectId": "sofa-1", "type": "FURNITURE", "assetCode": "SOFA_A",
      "position": { "x": -1.0, "y": 0.0, "z": 0.5 }, "rotationY": 180.0 }
  ]
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `schemaVersion` | int | ✅ | 구조 버전. 현재 **1**. 공개 회차(`version`)와 **다른 값이다** (research R-10) |
| `template` | string | ✅ | `DEFAULT` \| `PROJECT_EXHIBITION` (C-06 확정 시 확장) |
| `objects[].objectId` | string | ✅ | 1~64자 `[A-Za-z0-9_-]`, 배치 안에서 유일 |
| `objects[].type` | string | ✅ | `AI_AGENT` `VIDEO_SCREEN` `PROJECT_PANEL` `SURVEY_KIOSK` `RECRUITMENT_BOARD` `CONSULTATION_DESK` `LAPTOP` `LIKE_VOTE` `FURNITURE` `DECORATION` |
| `objects[].position` | {x,y,z} number | ✅ | **미터**. 원점 = 부스 바닥 중앙, `y=0`이 바닥, +Z가 정면 (헌법 21조). 부스는 **6×6×6m** → `|x|,|z| ≤ 3`, `0 ≤ y ≤ 6` |
| `objects[].rotationY` | number | ✅ | 도(degree), `[0,360)`. `0`이면 +Z를 바라봄 |
| `objects[].configId` | int | ❌ | 연결된 콘텐츠 ID. 공개 시 **그 부스 소유인지 서버가 확인**한다 (헌법 16조) |
| `objects[].assetCode` | string | ❌ | `FURNITURE`·`DECORATION`의 구체 자산 식별자 |

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

> **409 REVISION_CONFLICT 응답에는 최신 Draft를 함께 싣는다.** FE가 "다시 불러오기"와 "병합" 중 무엇을 택하든 추가 왕복이 필요 없다 (research R-03).

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
| `themeCode` | ✅ 화이트리스트. 기본 `DEFAULT` |
| `primaryColor` | `#RRGGBB` 또는 `null` |
| `signText` | 최대 60자 또는 `null` |
| `logoUrl` | `https://` URL 최대 2048자 또는 `null` |

**200**: 저장된 facade 전체. **오류**: 403 · 404 · 409 `BOOTH_LEASE_EXPIRED`

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

## 8. 왕복 검증 (spec 005 리뷰 ④칸 — 미수행)

계약 문서로는 닫히지 않는 항목이다. **오브젝트 1개짜리 Layout을 FE가 저장 → Unity가 같은 위치에 놓는지 눈으로 확인**해야 한다 (헌법 21조).

BE가 대신 보장할 수 있는 것은 **저장·조회 왕복에서 값이 바뀌지 않는다**는 것뿐이다(SC-004의 절반). 부호와 원점이 맞는지는 FE·Unity가 확인한다.
