# Data Model: Booth Studio Layout (FE 편집기)

> 필드명·타입·Object Type 10종은 Backend/Frontend/Unity 3파트 공통 계약(`spec.md` §공통 계약 기준)의 전사이며, 이 문서에서 임의로 변경할 수 없다(헌법 24조). [Issue #36](https://github.com/kanghyunsoon/ssafesta/issues/36)에 따르면 **계약에 없는 필드를 하나라도 보내면 저장 자체가 거부되므로**(`409 LAYOUT_VALIDATION_FAILED`, `rule: MALFORMED_LAYOUT`), `entities/layout/types.ts`의 전사 정확성은 스타일이 아니라 기능 요건이다.

---

## BoothLayout (`BoothLayoutDto`)

| 필드 | 타입 | 설명 |
|---|---|---|
| `boothId` | number | 부스 식별자 |
| `template` | string | 템플릿 키 |
| `objects` | `LayoutObject[]` | 배치된 오브젝트 목록, 최대 12개 |

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
- `|x| ≤ BOOTH_SIZE.width / 2`, `|z| ≤ BOOTH_SIZE.depth / 2` (research.md R-05 — 상수에서 도출, 하드코딩 금지)
- 좌표 직렬화는 FE가 환산한 값을 그대로 보내고 BE가 값을 고치지 않는다. 단 `-0.0`→`0.0`, `1e2`→`100`처럼 **표기**가 바뀔 수 있음(PostgreSQL `numeric` 동작, #36) — 값 자체가 다른 게 아니므로 버그로 취급하지 않는다

## ObjectType — canonical 10종 판정표

> **이 표가 C-04(research.md R-01) 판정의 원본이다.** "연결 요건" 열이 `validate.ts`가 참조하는 데이터이며, 판정 로직을 `configId` 필드 검사로 하드코딩하지 않고 이 표를 조회하는 방식으로 짠다 — 요건이 바뀌는 타입(LAPTOP 등)이나 기획 결정이 뒤집히는 경우 모두 표 갱신만으로 흡수된다.

| type | 분류 | 연결 필드 | 연결 요건 | C-04 경고 대상 |
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
| `errors` | `unknown[]` | ⚠️ 원소 타입은 Issue #17 BE 확답 대기 — 확정 전까지 `unknown[]`로 두고 `code`가 `LAYOUT_REVISION_CONFLICT`일 때만 `errors[0]`에서 서버 revision을 꺼내 쓴다 |
| `warnings` | `unknown[]` | 동일 |

## FE 검증 규칙 6종

| 규칙 | 시점 | 결과 |
|---|---|---|
| `objectId` 중복 | publish 전 | 차단 (UUID 채택으로 사실상 방어 코드) |
| 미지원 `type` | draft 로드 시 | **보존하되 미지원으로 표시** — 삭제·drop하지 않는다(spec SC-005, spec 006과 대칭) |
| 연결 요건 미충족 (ObjectType 판정표 기준) | publish 전 | **경고 + 진행 가능** (C-04 PROVISIONAL — research.md R-01) |
| 비숫자 transform | 입력 시 | 입력단 차단(도달 불가로 설계) |
| 경계 밖 위치 (`|x|>width/2` 또는 `|z|>depth/2`) | 드래그 중 + publish 전 | 드래그 중 클램프, publish 전 재검증으로 이중 방어 |
| 12개 초과 | 추가 시 + publish 전 | 팔레트 비활성 + publish 전 재검증 |

이 표는 UX용 빠른 검증이며, 서버가 전체를 독립적으로 재검증한다(헌법 16조). FE 검증 통과가 저장 성공을 보장하지 않는다.

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
BoothLayout (1) ──< objects >── (0..12) LayoutObject
     │                                        │
     │ template                               │ type
     ▼                                        ▼
  (string)                            ObjectType 판정표 ──> 연결 요건 ──> C-04 경고 대상 여부
```
