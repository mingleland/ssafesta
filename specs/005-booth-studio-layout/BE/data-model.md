# Data Model: Booth Studio / Layout (spec 005)

**Date**: 2026-08-20 | **Phase**: 1 | **기준 스키마**: V1 + 신규 V8·V9

---

## 1. 엔티티

### `booth_layout_drafts` — 작업본 (V1 그대로, 변경 없음)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `booth_id` | BIGINT **PK** → `booths(id)` | **PK가 곧 "부스당 작업본 하나"다** (I-1) |
| `schema_version` | INTEGER NOT NULL | Layout JSON 구조 버전 (R-10) |
| `layout_json` | JSONB NOT NULL | 요청 본문을 변형 없이 저장 (R-04) |
| `revision` | BIGINT NOT NULL DEFAULT 0 | 낙관적 잠금 (R-03) |
| `updated_by_user_id` | BIGINT NOT NULL → `users(id)` | 마지막 저장자 (owner 또는 staff) |
| `updated_at` | TIMESTAMPTZ NOT NULL | |

### `booth_layout_published_versions` — 공개본 이력 (V1 그대로, 변경 없음)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | BIGINT PK | |
| `booth_id` | BIGINT NOT NULL → `booths(id)` | |
| `version_no` | INTEGER NOT NULL | 공개 회차. `UNIQUE(booth_id, version_no)` (I-2) |
| `schema_version` | INTEGER NOT NULL | 공개 시점의 구조 버전 — Draft에서 복사 |
| `layout_json` | JSONB NOT NULL | 공개 시점 스냅샷. **공개 후 수정하지 않는다** |
| `published_by_user_id` | BIGINT NOT NULL → `users(id)` | |
| `published_at` | TIMESTAMPTZ NOT NULL | |

> 행을 **지우지 않는다.** C-07의 "보관은 한다"가 여기서 성립하고, 되돌리기 API를 나중에 열 수 있는 근거가 된다. 안 쌓아 두면 못 연다.

### `booths` — 변경분만 (V8·V9)

| 컬럼 | 상태 | 의미 |
|---|---|---|
| `published_layout_version` | **V8 신규** INTEGER NULL | 현재 공개 중인 회차. `NULL` = 공개된 것이 없음 (I-3) |
| `facade_theme_code` | **V9 rename** (`facade_code` ←) VARCHAR(50) NOT NULL DEFAULT `'DEFAULT'` | docs/09 정렬 |
| `facade_primary_color` | **V9 신규** VARCHAR(7) NULL | `#RRGGBB` |
| `facade_sign_text` | **V9 신규** VARCHAR(60) NULL | 간판 문구 |
| `facade_logo_url` | **V9 신규** VARCHAR(2048) NULL | |

### `booth_staffs` — 읽기 전용 참여 (V1 그대로)

`PRIMARY KEY(booth_id, user_id)`. 005는 **행의 존재만** 본다. 행을 만드는 초대·수락 흐름은 spec 011 (R-07).

---

## 2. 불변식

| # | 불변식 | 지키는 수단 | 뚫리면 |
|---|---|---|---|
| **I-1** | 부스당 작업본은 정확히 0 또는 1개 | `booth_layout_drafts` **PK = `booth_id`** | 편집기가 어느 작업본을 여는지 비결정적이 된다 |
| **I-2** | 공개 회차는 한 부스 안에서 유일 | `UNIQUE(booth_id, version_no)` | 같은 회차 번호가 둘 — Unity가 캐시를 못 믿는다 |
| **I-3** | `booths.published_layout_version`은 **실재하는 회차이거나 NULL** | **V8 복합 FK**: `(id, published_layout_version) → booth_layout_published_versions(booth_id, version_no)`. NULL이면 MATCH SIMPLE로 검사 자체가 면제된다 | 포인터가 없는 버전을 가리켜 공개본 조회가 500이 된다 |
| **I-4** | 작업본은 방문자에게 도달하지 않는다 | 조회 경로 분리 — `GET /layouts/draft`는 `BoothAccessGuard` 통과 필수, `GET /layouts/published`는 **published 테이블만** 읽는다 | SC-003 위반. "작업 중인 것이 보였다"는 되돌릴 수 없다 |
| **I-5** | 임대가 유효하지 않은 부스의 공개본은 제공되지 않는다 | 004 `BoothLeaseRepository.findValidByBoothId` **재사용** (R-06) | 만료 부스가 월드에 계속 보인다 (FR-015) |
| **I-6** | 저장은 앞선 저장을 덮어쓰지 않는다 | `revision` 비교 후 `UPDATE … WHERE revision = :expected` (영향 행 0이면 충돌) | 남의 편집이 조용히 사라진다 (FR-014) |
| **I-7** | 공개본은 공개 시점 스냅샷이며 이후 Draft 수정에 영향받지 않는다 | 공개 = **복사**. 공개 후 `layout_json` 갱신 경로 없음 | 저장만 했는데 방문자 화면이 바뀐다 (FR-006, SC-003) |

> **I-3의 함정 주의.** 004에서 `ux_booth_leases_active_slot`이 `ends_at`을 보지 않아 재임대가 막혔던 것(T-110)과 같은 종류의 덫을 만들지 않으려면, 포인터를 **해제하는 경로**가 반드시 있어야 한다 → FR-017 (§4).

---

## 3. 검증 규칙 (FR-007·FR-016)

`LayoutValidator`가 두 목록을 만든다. **`errors`가 비어 있어야 공개된다.** Draft 저장에는 표시된 것만 적용한다.
두 목록은 응답에 **항상** 실린다 — 비어 있어도 키가 빠지지 않는다.

| 규칙 | 분류 | Draft 저장에도? | 근거 |
|---|---|---|---|
| 오브젝트 12개 이하 | error | ✅ | FR-010, 헌법 22조. 저장 때 막지 않으면 JSONB만 부푼다 (R-05) |
| `objectId`가 배치 안에서 유일 | error | ✅ | 중복이면 Unity가 어느 것을 남길지 비결정적 |
| `objectId` 형식 (1~64자, `[A-Za-z0-9_-]`) | error | ✅ | |
| `type`이 canonical 10종에 있음 | error | ✅ | spec 005 §공통 계약 |
| `position.{x,y,z}`가 유한한 수 | error | ✅ | `NaN`·`Infinity`는 Unity에서 오브젝트를 사라지게 한다 |
| `position`이 부스 영역 안 (`|x| ≤ 4.7`, `|z| ≤ 3`, `0 ≤ y ≤ 5.9`) | error | ✅ | **부스는 9.4m × 6m × 5.9m** (§6) — x·z의 한계가 다르다. 원점이 바닥 중앙이라 수평만 반값이다 |
| **실물(회전 반영 AABB)이 부스 영역 안** — `AREA_OUT_OF_BOUNDS` | error | ✅ | 앵커는 안인데 실물이 옆 슬롯에 걸치는 배치를 점 검사는 못 잡는다. 타입별 실측 bounds·회전 규칙은 contracts §10 (#19 ③) |
| **통행 판정** — 관람 띠 도달 <50% `FRONT_BLOCKED` / 고립 ≥1㎡ `ISOLATED_AREA` | warning | ❌ (공개 시점만) | 뒷공간 활용은 소유자의 선택일 수 있어 공개를 막지 않는다. 래스터 파라미터는 contracts §10-3 (#19 ⑤) |
| `rotationY`가 `[0, 360)` | error | ✅ | |
| `template`이 화이트리스트에 있음 | error | ✅ | C-06 확정 시 목록만 확장 |
| `schemaVersion`이 서버가 아는 값 | error | ✅ | 미래 버전을 저장해 두면 조용히 못 읽는다 |
| `configId`가 **그 부스 소유** 콘텐츠인지 | error | ❌ (공개 시점만) | 헌법 16·17조. 편집 중에는 아직 안 만들었을 수 있다 |
| 기능 오브젝트에 `configId` 미연결 | **warning** | ❌ | **C-04 확정: 경고 유지, 공개 허용** (#45 기획 승인, 2026-08-21) — 자리 확보 후 콘텐츠를 나중에 채우는 운영 시나리오를 막지 않는다 |

`configId` 소유 검증은 타입별로 대상 테이블이 다르다 — `AI_AGENT` → `ai_agents.booth_id`. **아직 판정하지 않는 타입은 검증 없이 통과시키되 warning(`CONFIG_UNVERIFIED`)으로 남긴다** (검증이 없다는 사실이 조용해지지 않게).

**`LAPTOP`·`SURVEY_KIOSK`·`PROJECT_PANEL`은 `configId` 소유 질문이 성립하지 않는다.** 연결 대상이 부스당 하나뿐이라 부스 단위 술어로 답한다 — 노트북은 `booths.homepage_url` 등록 여부(016 C-01), 키오스크는 `surveys`에 그 부스 행이 있는지(010 C-06), 그래픽 패널은 `projects`에 그 부스 행이 있는지(009 C-01). 셋 다 `CONFIG_NOT_LINKED` warning 을 쓰고 `configId` 체인을 타지 않는다 (`S15P21A604-699`·`-765`, GitLab #181·#194).

**`VIDEO_SCREEN` 은 네 번째지만 이유가 다르다** (`S15P21A604-889`, GitLab #194 ②): 부스 단위 술어로 옮긴 것이 아니라 **장식으로 내려갔다**. 영상 기능화를 이번 축제에서 하지 않기로 확정했으므로 가리킬 콘텐츠 자체가 없다. `configId` 가 실려 와도 저장만 하고 무시하며 `CONFIG_NOT_LINKED`·`CONFIG_UNVERIFIED` 둘 다 붙지 않는다 — 무시하면서 경고만 남기면 FE 는 고칠 것이 없는 경고를 영구히 본다. 관람 정면(`FRONT_BLOCKED`) 검사에서도 빠진다(장식에는 관람할 정면이 없다). 바운드는 그대로다.

---

## 4. 상태 전이

```text
[작업본 없음]
   └─ PUT draft (expectedRevision=0) ──▶ [Draft rev=1]  ── PUT draft ──▶ [Draft rev=2] ─ … 
                                              │
                                              ├─ POST publish ──▶ 공개본 v1 INSERT
                                              │                   booths.published_layout_version = 1
                                              │                   (같은 트랜잭션)
                                              │
                                              └─ POST publish ──▶ 공개본 v2 INSERT + 포인터 = 2

[임대 만료 후 재임대 / 슬롯 재연결]
   └─ booths.published_layout_version = NULL   ← FR-017
      Draft·공개본 이력은 **그대로 둔다** (FR-011: 보존하되 자동 공개 금지)
```

**공개 트랜잭션이 하는 일 (하나의 트랜잭션)**

1. 편집 권한 확인 (owner 또는 staff)
2. 임대 유효성 확인 — 만료된 부스는 공개할 수 없다 (004 술어 재사용)
3. Draft 검증 → `errors` 있으면 `409 LAYOUT_VALIDATION_FAILED`로 중단
4. `next = COALESCE(MAX(version_no), 0) + 1` 로 공개본 INSERT
5. `booths.published_layout_version = next`

004의 "코인 차감과 임대 생성이 한 트랜잭션"과 같은 이유다 — **중간 상태가 표현 자체로 불가능**해야 한다. 4만 되고 5가 실패하면 "공개했는데 아무도 못 보는 버전"이 남는다.

**FR-017의 실행 위치**: 임대 만료를 전이시키는 004의 `releaseStaleLeases()` / 슬롯 해제 경로에서 `Booth.detachSlot()`과 **같은 트랜잭션**으로 포인터를 `NULL`로 만든다. `detachSlot()`이 이미 그 지점이므로 여기에 얹는다 — 새 스케줄러를 만들지 않는다.

---

## 5. 마이그레이션

| 파일 | 내용 | 되돌릴 수 있나 |
|---|---|---|
| `V8__booth_published_layout_version.sql` | `booths.published_layout_version INTEGER NULL` 추가 + 복합 FK(I-3) | 컬럼 삭제로 가능. 기존 데이터 없음 |
| `V9__booth_facade_fields.sql` | `facade_code` → `facade_theme_code` **rename**, `facade_primary_color`·`facade_sign_text`·`facade_logo_url` 추가 | rename 역방향으로 가능. 값은 전부 `'DEFAULT'`라 이관 없음 |

두 관심사를 한 파일에 넣지 않는다 — V6(임대 제약)·V7(부스 제약)을 나눈 것과 같은 이유로, 되돌릴 때 하나만 되돌릴 수 있어야 한다.

**V9 주의**: `Booth` 엔티티의 `facadeCode` 필드도 함께 고친다. 지금은 아무도 읽지 않는 필드라 마이그레이션만 하고 엔티티를 빠뜨려도 **테스트가 통과해 버린다** — 그 상태로 남으면 facade API를 붙일 때 컬럼 없음 오류가 난다.

---

## 6. 부스 치수 — `6 × 6 × 2.72` → `9.4 × 6 × 5.9` (`S15P21A604-698`)

**결정** (GitLab #181, 2026-09-15 · 게임 파트 실측 + FE·BE 합의): 배치 가능 범위를 x 축만 넓히고 높이를 셸 실측으로 정정한다. z 는 그대로 둔다.

| | 이전 | 이후 | 검증 상수 | 근거 |
|---|---|---|---|---|
| width (x) | 6 (±3) | **9.4 (±4.7)** | `LayoutValidator.MAX_X` | 셸 내부 x −4.804 ~ +4.926. 서쪽에서 실제로 닿는 면은 구조 벽이 아니라 `PanelGraphic` −4.804 라 ±4.8 은 여유 4mm — `DEVICE_TABLET`(half-x 0.087)조차 못 놓으면서 편집기에는 빈자리로 보인다 |
| depth (z) | 6 (±3) | **6 유지** | `LayoutValidator.MAX_Z` | 입구 쪽 여백은 추적 카메라(기본 1.7m·최대 3.4m) 공간이다. 더 쓰려면 방의 z 중심이 +1.578 이라 비대칭 범위(−3.6 ~ +5.0)가 돼 `{width, depth}` 대칭 스키마를 벗어난다 |
| height | 2.72 | **5.9** | `LayoutValidator.MAX_HEIGHT` | `2.72` 는 방 높이가 아니라 **셸 교체 이전** 벽 패널 높이였다. 방 내부는 6.0 이고 천장 램프가 5.94 부터라 실사용 상한을 쓴다 |

**왜 상수 하나를 둘로 가르는가.** `MAX_HORIZONTAL` 하나를 x·z 양쪽에 쓰던 구조에서는 x 를 넓히는 순간 z 도 같이 열린다 — 부스 뒤로 1.8m 삐져나온 배치가 통과한다. 앵커 검증·회전 AABB 검증·통행 격자 세 곳이 전부 그 상수를 읽고 있었다.

**통행 격자도 같은 상수에서 유도한다.** `LayoutPassageChecker` 는 `GRID`(120)·`FIRST_CENTER`(−2.975)로 ±3 을 독립으로 박고 있었다. 검증만 넓히면 공개 시 통행 판정은 6×6 바닥 위에서 돌아, 넓어진 양옆 1.7m 는 바닥으로 존재하지도 않고 거기 놓인 오브젝트는 점유 0 으로 계산된다 — 고립 경고가 조용히 사라진다. 이제 `188 × 120`(x 첫 셀 중심 −4.675 · z −2.975)이 상수에서 계산된다. FE 도 같은 유도를 쓴다 (`S15P21A604-785`).

**마이그레이션 없음.** 넓히는 방향이라 기존 게시본은 전부 유효하다. JSON 필드 형태가 그대로라 `schemaVersion` 은 **1** 을 유지한다.

**남는 것 (이 변경의 범위 밖)**:

- **assetCode 단위 AABB 표** — `LayoutObjectType.localBounds()` 를 `resolveLocalBounds(type, assetCode)` 로 가르는 작업. 지금 타입 대표값 하나라 의자와 카운터가 같은 몸으로 판정된다. 게임 파트 회신 2건(`DECOR_PLANT_01` 이 `PLANT` 별칭인지, `FURN_SET_TABLE_CHAIRS`·`DISP_SET_BOX_01` 의 AABB) 대기 중이다 — **타입 기본값이라 틀리면 미등록 assetCode 가 전부 그 자리로 조용히 떨어진다**
- **지지대 검사 부재** — y 상한이 2.72 에서 5.9 로 오르면서 공중에 뜬 집기가 통과하는 범위도 넓어진다. 램프 트러스를 매달려면 필요한 자유도라 이번에 막지 않기로 FE·BE 합의했다 (별건)
