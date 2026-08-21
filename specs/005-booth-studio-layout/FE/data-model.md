# Data Model: Booth Studio Layout (FE 편집기)

> 필드명·타입·Object Type 10종은 `spec.md` §공통 계약 기준과 `contracts/layout-api.md`(BE 작성, 공동 정본)의 전사이며, 이 문서에서 임의로 변경할 수 없다(헌법 24조). [Issue #36](https://github.com/kanghyunsoon/ssafesta/issues/36)에 따르면 **계약에 없는 필드를 하나라도 보내면 저장 자체가 거부되므로**(`409 LAYOUT_VALIDATION_FAILED`, `rule: MALFORMED_LAYOUT`), `entities/layout/types.ts`의 전사 정확성은 스타일이 아니라 기능 요건이다.

---

## BoothLayout (`BoothLayoutDto`)

| 필드 | 타입 | 설명 |
|---|---|---|
| `boothId` | number | 부스 식별자 |
| `template` | string | 템플릿 키. **`PROJECT_EXHIBITION` 단독**([#19](https://github.com/kanghyunsoon/ssafesta/issues/19) — `DEFAULT` 제거, V11 이관). 목록은 `GET /booth-layout-templates`(§9) 조회 |
| `objects` | `LayoutObject[]` | 배치된 오브젝트 목록, 최대 12개(`maxObjects`도 §9 응답에서 옴 — 하드코딩 금지) |

### 버전 필드 3분리 (Issue #36 — spec.md 예시의 `"version": 2` 정정 대상)

| 필드 | 등장 위치 | 의미 | 증가 시점 | FE 취급 |
|---|---|---|---|---|
| `revision` | Draft GET/PUT | Draft **편집 회차** | Draft 저장마다 +1 | 읽은 값을 `expectedRevision`으로 echo (R-06) |
| `version` | Published 조회 | **공개 회차** | Publish마다 +1 | 표시용, 편집기 로직에 관여 안 함 |
| `schemaVersion` | Layout JSON 전체 | **구조 버전** — JSON의 모양이 바뀔 때만 | 3파트 합의 시에만 (현재 1) | 고정값 전송, FE가 직접 올리지 않음 |

**불변식**:
- `objects.length ≤ 12` (헌법 22조)
- `objectId`는 부스 내 유일 (FE가 UUID로 생성해 자동 충족 — research.md R-02)
- `scale`류 필드는 계약에 없다(C-03 확정) — 편집기가 크기 조절 UI를 만들지 않는다

## LayoutObject (`BoothObjectDto`)

| 필드 | 타입 | 설명 |
|---|---|---|
| `objectId` | string | UUID(R-02). 편집기 내부 식별 키 |
| `type` | `ObjectType` | canonical 10종 중 하나 (아래 판정표) |
| `position` | `{x: number, y: number, z: number}` | 부스 기준 미터. `y`는 항상 `0`(편집기는 노출하지 않고 고정 기록) |
| `rotationY` | number | degree, `[0, 360)`로 정규화해 저장. `0` = `+Z`(부스 정면) |
| `configId` | number? | 기능형 오브젝트의 연결 콘텐츠 ID |
| `assetCode` | string? | 장식형 오브젝트의 외형 선택 코드 |

**불변식**:
- 좌표 3필드는 전부 finite(FE 검증 4번 — 입력단에서 비숫자를 차단해 도달 자체를 막는다)
- 앵커 위치가 `|x| ≤ BOOTH_SIZE.width / 2`, `|z| ≤ BOOTH_SIZE.depth / 2`, `0 ≤ y ≤ BOOTH_SIZE.height`(=2.72) 안 (research.md R-05 — 상수에서 도출, 하드코딩 금지)
- **앵커가 안이어도 실물이 밖일 수 있다** — 타입별 크기가 원점 기준 비대칭이라(`contracts/layout-api.md` §10-1), 회전 적용 후 AABB가 부스 밖으로 나가면 서버가 `AREA_OUT_OF_BOUNDS`로 거부한다. FE 사전 검증도 같은 회전식(`x' = x·cos + z·sin`, `z' = −x·sin + z·cos`)을 써야 서버와 답이 갈리지 않는다 — 아래 "서버 검증 규칙" 표 참조
- 좌표 직렬화는 FE가 환산한 값을 그대로 보내고 BE가 값을 고치지 않는다. 단 `-0.0`→`0.0`, `1e2`→`100`처럼 **표기**가 바뀔 수 있음(PostgreSQL `numeric` 동작, #36) — 값 자체가 다른 게 아니므로 버그로 취급하지 않는다

## ObjectType — canonical 10종 판정표

> **이 표는 FE 사전 경고(UX 보조)의 원본이다.** 공개 가부의 최종 판정은 서버가 `errors`/`warnings`로 내려준다(FR-016, research.md R-01) — 이 표는 요청을 보내기 전에 같은 경고를 미리 띄우기 위한 것이다. "연결 요건" 열이 `validate.ts`가 참조하는 데이터이며, 판정 로직을 `configId` 필드 검사로 하드코딩하지 않고 이 표를 조회하는 방식으로 짠다 — 요건이 바뀌는 타입(LAPTOP 등)을 표 갱신만으로 흡수하기 위해서다. **서버 응답과 이 표의 판정이 갈리면 서버가 이긴다**(헌법 16조).

| type | 분류 | 연결 필드 | 연결 요건 | 사전 경고 대상 |
|---|---|---|---|---|
| `AI_AGENT` | 기능 | `configId` | agentId 필요 | ✅ |
| `VIDEO_SCREEN` | 기능 | `configId` | 영상 설정 ID 필요 | ✅ |
| `PROJECT_PANEL` | 기능 | `configId` | projectId 필요 | ✅ |
| `SURVEY_KIOSK` | 기능 | `configId` | surveyId 필요 | ✅ |
| `CONSULTATION_DESK` | 기능 | `configId` | 상담 설정 ID 필요 | ✅ |
| `LAPTOP` | 기능 | `configId` | ⚠️ spec 016에서 URL 계약으로 바뀔 수 있음(정수 ID가 아닐 가능성 — spec.md 미결 표) | ✅ (요건 정의는 016 확정 후 갱신) |
| `RECRUITMENT_BOARD` | ⚠️ 미판정 | — | 연결 요건 자체가 spec Key Entities에 없음 | ✕ (요건 확정 전까지 경고 제외) |
| `LIKE_VOTE` | ⚠️ 미판정 | — | 부스 자체가 대상일 가능성 — 오브젝트별 연결이 필요한지 불명 | ✕ (요건 확정 전까지 경고 제외) |
| `FURNITURE` | 장식 | `assetCode` | 없음 — `assetCode`는 외형 선택이지 콘텐츠 연결이 아님 | ✕ |
| `DECORATION` | 장식 | `assetCode` | 없음 | ✕ |

Unity는 하위 호환을 위해 `SURVEY`·`CONSULT_DESK`도 읽지만, FE는 신규 Layout에 canonical 문자열만 저장한다(spec.md).

## EditorState (`docs/10` §4.3 확장)

| 필드 | 타입 | 설명 |
|---|---|---|
| `boothId` | number | |
| `template` | string | |
| `objects` | `LayoutObject[]` | 편집 중인 배열 |
| `selectedObjectId` | string \| null | |
| `dirty` | boolean | 마지막 저장 이후 변경 여부 |
| `saveStatus` | `'idle' \| 'dirty' \| 'saving' \| 'saved' \| 'error' \| 'conflict'` | 아래 상태 전이 참조 |
| `baseRevision` | number | 마지막으로 읽은 `revision` — PUT 시 `expectedRevision`으로 echo (research.md R-06) |
| `history` | — | P1. 필드 자리만 확보, 이번 구현에서 채우지 않음 |

**액션**(reducer, research.md R-07): `ADD_OBJECT` / `MOVE_OBJECT` / `ROTATE_OBJECT` / `REMOVE_OBJECT` / `SELECT_OBJECT` / `LINK_CONTENT` / `SET_TEMPLATE` / `LOAD_DRAFT`(서버 응답으로 전체 교체, 409 재로드 시에도 사용)

## ApiError

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | string | 분기는 이 필드로만. 문자열 매칭 금지 |
| `message` | string | 한글, 그대로 사용자에게 노출 가능(#36) |
| `requestId` | string | |
| `errors` | `ApiErrorDetail[]` | 항상 존재(빈 배열 가능). 아래 참조 |
| `warnings` | `ApiErrorDetail[]` | 동일 |

**`ApiErrorDetail`** — `{ rule: string; objectId?: string; message: string }`. 구현이 `@JsonInclude(NON_NULL)`이라 **`objectId`가 없으면 키 자체가 빠진다**(계약 문서 예시는 `"objectId": null`이지만 실제로는 키 부재) — FE 타입은 `objectId`를 optional로 둔다.

🔴 **409 `LAYOUT_REVISION_CONFLICT`에서 revision 값을 구조적으로 꺼내지 않는다.** 서버는 숫자 필드를 주지 않는다:

```
errors: [ { rule: "CURRENT_REVISION", message: "서버의 현재 revision은 N입니다. 다시 불러온 뒤 저장하세요." } ]
```

숫자가 **한글 메시지 문장 안에만** 있다. FE는 이 문자열을 파싱하지 않고, `code === 'LAYOUT_REVISION_CONFLICT'`를 확인하는 즉시 `GET /draft`를 재호출해 전체 갱신한다(research.md R-06).

`rule` 값은 계약 문서에 7개(`MALFORMED_LAYOUT`·`OBJECT_LIMIT`·`AREA_OUT_OF_BOUNDS`·`CONFIG_NOT_LINKED`·`CONFIG_UNVERIFIED`·`FRONT_BLOCKED`·`ISOLATED_AREA`)만 있고, 구현에는 12개가 더 있으나 문서화돼 있지 않다(research.md R-12). FE는 `rule`로 분기하지 않고 **목록을 그대로 렌더링**하는 것을 기본으로 두며, 분기는 `CURRENT_REVISION` 하나뿐이다.

## `GET /draft`와 `PUT /draft` 응답 — 타입을 합치면 안 된다

| 필드 | `GET /draft` 200 | `PUT /draft` 200 |
|---|---|---|
| `boothId` `revision` `schemaVersion` `template` `objects` `updatedAt` | ✅ | ✅ |
| `updatedByUserId` | ✅ | ❌ |
| `publishedVersion`(null 가능 — 공개본과 다름 표시용) | ✅ | ❌ |
| `warnings` | ❌ | ✅ (항상, 빈 배열 가능) |

`entities/layout/types.ts`에 `DraftResponse`·`DraftSaveResponse` 두 타입을 따로 둔다. 작업본이 없으면 `GET /draft`는 **204 No Content**를 반환하고, 편집기는 빈 배치로 시작해 첫 저장에 `expectedRevision: 0`을 보낸다.

## FE 사전 검증 6종

| 규칙 | 시점 | 결과 |
|---|---|---|
| `objectId` 중복 | publish 전 | 차단 (UUID 채택으로 사실상 방어 코드) |
| 미지원 `type` | draft 로드 시 | **보존하되 미지원으로 표시** — 삭제·drop하지 않는다(spec SC-005, spec 006과 대칭) |
| 연결 요건 미충족 (ObjectType 판정표 기준) | publish 전 | **경고 + 진행 가능** (C-04 — research.md R-01. 최종 표시는 서버 `warnings`) |
| 비숫자 transform | 입력 시 | 입력단 차단(도달 불가로 설계) |
| 경계 밖 위치 (`|x|>width/2` 또는 `|z|>depth/2`) | 드래그 중 + publish 전 | 드래그 중 클램프, publish 전 재검증으로 이중 방어 |
| 12개 초과 | 추가 시 + publish 전 | 팔레트 비활성 + publish 전 재검증. **Draft 저장 시점에도 서버가 같은 상한을 건다**(spec.md §BE 검토 상세 A) |

이 표는 UX용 빠른 검증이며, 서버가 전체를 독립적으로 재검증한다(헌법 16조). FE 검증 통과가 저장 성공을 보장하지 않는다.

**서버 검증 규칙과의 관계** — `contracts/layout-api.md` §10(기하 계약, #19 확정)이 FE 사전 검증 6종에 없는 규칙 2개를 추가한다. 이 둘은 "서버 전용"이 아니라 **FE도 같은 알고리즘으로 실시간 구현해야** 서버와 답이 갈리지 않는다(BE: *"FE 실시간 경고와 서버 경고가 다른 답을 내면 '편집기는 괜찮다는데 공개하니 경고가 뜬다'가 문의로 옵니다"*):

| 규칙 | 분류 | Draft 저장에도? | FE 대응 |
|---|---|---|---|
| **실물(회전 반영 AABB)이 부스 영역 안** — `AREA_OUT_OF_BOUNDS` | error | ✅ | **FE가 같은 회전식으로 실시간 클램프.** 타입별 로컬 bounds는 §10-1(`objectTypes.ts`로 옮김), 회전 공식은 §10-2(허용 오차 `1e-9`) |
| **통행 판정** — 관람 띠 도달 <50% `FRONT_BLOCKED` / 고립 ≥1㎡ `ISOLATED_AREA` | warning | ❌(공개 시점만) | **FE가 §10-3 알고리즘으로 실시간 경고 구현**(래스터 0.05m·침식 0.22m·flood fill 4방향·관람 띠 0.7m·기준선 50%·고립 1㎡). 리드가 명시적으로 FE 몫으로 배분(#19) — "공개 버튼을 눌렀을 때 거부되는 것보다 배치하는 순간 보여주는 쪽이 낫다" |

**FE가 검사할 수 없는 것은 이 규칙 하나뿐**: `configId`가 가리키는 콘텐츠가 **그 부스 소유**인지(헌법 16·17조, error) — 편집기가 그 콘텐츠의 소유 여부를 알 방법이 없어 서버 `errors`를 렌더링하는 것 외에 할 일이 없다. `template`이 화이트리스트에 있음(error)도 편집기가 서버 목록(`GET /booth-layout-templates`)에서만 고르게 해 도달 자체를 막는다.

## BoothFacade (FR-018 — research.md R-11)

Layout과 **별개 엔티티**다. Draft/Publish를 타지 않고 `PUT /booths/{boothId}/facade`로 즉시 반영되며 낙관적 잠금이 없다.

| 필드 | 타입 | 제약 |
|---|---|---|
| `themeCode` | string | 화이트리스트 `DEFAULT` \| `SSAFY_BLUE` \| `WARM` \| `MONO` — ⚠️ 값은 `docs/08_Backend_API_명세서.md`·구현에만 있고 **계약 문서(`contracts/layout-api.md`)에는 없음**(research.md R-12) |
| `primaryColor` | string | hex `#RRGGBB` 6자리만. `themeCode`와 **독립**. `#RGB`·`#RRGGBBAA`·이름 문자열은 전부 400 |
| `signText` | string | 간판 문구. `booths.name`과의 화면상 관계는 FE 몫으로 미정 |
| `logoUrl` | string | **업로드가 아니라 https URL 참조**, ≤2048자 |

만료 부스는 편집이 거부된다(`BOOTH_LEASE_EXPIRED`). `EditorState`와 상태를 합치지 않는다 — 저장 경로·잠금 방식이 달라 `dirty`·`saveStatus`의 의미가 갈리기 때문이다(R-11).

**팔레트**: `GET /booth-facade-palette`는 응답 형태가 합의됐으나(`{colors:[{code,hex,label}], themeCodes:[...]}`, 12색, 전역 1개) **아직 구현되지 않았다**(research.md R-11). 구체 hex 12개는 FE↔BE 구현 트랙에서 정해야 하는 FE 착수 항목이다.

## Publish 검증 응답 (FR-016)

Publish 요청의 검증 결과는 **차단 사유와 경고를 서버가 나눠서** 준다. FE는 이 둘을 그대로 렌더링한다.

| 리스트 | 의미 | FE 동작 |
|---|---|---|
| `errors` | 공개를 **막는** 사유 | 목록 표시 + 진행 버튼 비활성 |
| `warnings` | 막지 않는 경고 | 목록 표시 + 진행 버튼 활성 |

C-04(연결 요건 미충족)는 현재 `warnings`에 있다. 기획이 차단으로 확정하면 서버가 `errors`로 옮기고 **FE는 코드 변경 없이 따라간다** — 이것이 FR-016의 설계 의도다.

## 상태 전이

**Draft → Published**: Publish는 현재 Draft의 **스냅샷 복제**다. Draft는 Publish 이후에도 계속 존재하고 편집 가능하며, Published는 불변이다. 방문자는 Published만 본다(FR-005·006). 재임대 시에는 Draft만 존재하고 Published가 없는 상태가 된다(FR-011 — BE 소관, FE는 "비공개" 표시만 한다).

**`saveStatus`**:

```text
idle --(편집 발생)--> dirty --(저장 요청)--> saving --+--> saved
                                                       +--> error (일반 오류)
                                                       +--> conflict (409 LAYOUT_REVISION_CONFLICT)

conflict --(GET /draft 재로드)--> idle   # 자동 병합 없음 — 유일한 탈출 경로
```

## 엔티티 관계

```text
Booth (1) ──── (1) BoothFacade      # FR-018, 즉시 반영·잠금 없음
  │
  └──── (1) BoothLayout (1) ──< objects >── (0..12) LayoutObject
                │                                        │
                │ template                               │ type
                ▼                                        ▼
     GET /booth-layout-templates              ObjectType 판정표(연결 요건)
       (PROJECT_EXHIBITION 단독,                          │
        footprint·maxObjects 응답)                        ▼
                │                             ┌─ 연결 요건 미충족 → FE 사전 경고 대상
                ▼                             │  (최종 판정은 서버 errors/warnings — FR-016)
     §10-1 로컬 bounds(회전 후 AABB)  ────────┘
                │
                ▼
     AREA_OUT_OF_BOUNDS(error) / FRONT_BLOCKED·ISOLATED_AREA(warning)
     — FE가 §10-2·§10-3 알고리즘으로 실시간 구현, 서버가 최종 판정
```
