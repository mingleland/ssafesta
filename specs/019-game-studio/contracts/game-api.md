# Game Studio API 계약

> 상태: **v1.0 — 확정.** #33·#34·#48·#56 및 전역 오류 봉투 #58 합의 반영. 미결 항목은 2026-08-25 #104·#48에서 전부 확정됐다(§확정 기록).
> DTO·revision·오류 코드는 이 문서를 기준으로 구현한다.

## 공통 오류 봉투

기존 FESTA의 단일 5필드 오류 봉투를 재사용한다. `errors`와 `warnings`는 값이 없어도 빈 배열로 항상 존재하며 `details` 필드는 추가하지 않는다.

```json
{
  "code": "GAME_REVISION_CONFLICT",
  "message": "다른 편집 내용이 먼저 저장되었습니다.",
  "requestId": "req_a1b2c3d4",
  "errors": [
    {
      "rule": "CURRENT_REVISION",
      "message": "8"
    }
  ],
  "warnings": []
}
```

서버 예외 클래스명, stack trace, Provider 원문을 응답하지 않는다.

## 오류 코드와 rule

> **상태: 제안.** BE 구현자(strdeok)가 #48에서 맡은 마지막 계약 작업이다. 형식은 005
> `layout-api.md` §0의 rule 표를 따르되 **"Draft 저장에도 ✅" 열은 만들지 않는다** — 019는 Draft와
> Publish가 같은 집합을 보고, 차이는 Publish가 소유권·Dialogue·Asset 정책을 더 본다는 것뿐이다
> (#48 2026-08-23 철회 코멘트). 확정되면 이 블록의 "제안" 표기를 지운다.

### 봉투 `code` — 이 spec이 쓰는 전부

`code`는 FE가 분기하는 계약값이다. 여기 없는 `GAME_*` 코드가 응답에 나오면 계약 위반이다.
★는 새로 정하는 이름이고, 나머지는 이 문서 본문이나 FE `apiErrorCopy`가 이미 쓰는 것이다.

| Code | HTTP | 언제 | 소비자 |
|---|:---:|---|---|
| `GAME_VALIDATION_FAILED` ★ | 409 | Draft 저장·Publish 검증 실패 — `errors[]`에 rule이 실린다 | 편집기: rule별 수정 위치 표시 |
| `GAME_REVISION_CONFLICT` | 409 | `expectedRevision` 불일치 (FR-025) | `errors[0].rule=CURRENT_REVISION` → 충돌 복구 UI |
| `GAME_NOT_FOUND` | 404 | Game이 없음 | Runtime·편집기 공통 |
| `GAME_DELETED` | 404 | soft delete된 Game | Runtime 오류 화면 |
| `GAME_NOT_PUBLISHED` | 404 | Published Version이 없음 | Runtime 오류 화면 |
| `GAME_NOT_PUBLIC` | 403 | `visibility=PRIVATE` | Runtime 오류 화면 |
| `GAME_FORBIDDEN` | 403 | 소유자가 아닌 사용자의 Authoring 호출 (비공개 Published 조회는 `GAME_NOT_PUBLIC`) | 편집기 |
| `GAME_SCHEMA_UNSUPPORTED` | 409 | `schemaVersion`이 서버 지원 범위 밖 | Runtime: 지원 버전 안내 |
| `GAME_PROJECT_INVALID` | 500 | **저장된** Published/Draft snapshot이 서버 재검증에 실패 | Runtime 손상 안내 |
| `CONFIG_NOT_FOUND` | 404 | Portal `configId`에 해당하는 Binding이 없음 | Booth Host: 연결 없음 안내 |
| `MEMBER_ONLY` | 403 | **게스트의 Authoring 호출** — 신규 코드를 만들지 않고 기존 전역 코드를 쓴다 (FR-023) | 로그인 유도 |
| `VALIDATION_FAILED` | 400 | GameProject 밖의 일반 필드 오류 (`title` 등) — `rule=FIELD_INVALID` + `field` | 필드 하이라이트 |
| `BOOTH_LEASE_EXPIRED` | 409 | Portal의 부스 임대 만료 — 004·005·008과 같은 코드다 (§Booth Portal Resolution) | Booth Host: 임대 안내 |
| `GAME_LIMIT_EXCEEDED` ★ | 409 | 활성 Game이 계정 상한(기본 20)에 도달 — §게임 생성 | 편집기: 삭제 후 재시도 안내 |

세 가지는 의도적으로 **재사용**이다 — 새 이름을 만들면 같은 사건이 두 이름을 갖는다.

- `MEMBER_ONLY` — 013a·001과 같다. 게스트 차단은 게임의 사정이 아니라 계정의 사정이다.
- `VALIDATION_FAILED` + `FIELD_INVALID`/`field` — #58 C 확정(T058) 그대로.
- `BOOTH_LEASE_EXPIRED` — Portal의 임대 만료. 004·005·008과 같다. 표에 행으로 두되 이름은 재사용이라 ★가 아니다.

> `GAME_PROJECT_INVALID`만 500이다. 나머지는 클라이언트가 고칠 수 있는 사건이지만, 이것은
> **서버가 저장을 허용했던 데이터가 지금 검증을 통과하지 못한다**는 뜻이라 서버 결함이다.
> 조용히 200으로 빈 프로젝트를 돌려주지 않는다 (T-24).

### `rule` — `GAME_VALIDATION_FAILED`의 `errors[]`

`rule` 이름은 **`contracts/fixtures/`의 reference validator가 이미 정한 것을 그대로 쓴다** —
quickstart가 "같은 manifest의 positive/negative fixture와 오류 코드를 재현해야 한다"고 요구하므로
이름이 갈리면 fixture 테스트가 성립하지 않는다.

**★는 fixture에 없어서 이번에 새로 정하는 이름이다.** validator를 훑어 있는 것과 없는 것을 갈라
놓았다 — ★ 없는 행은 이미 코드로 고정된 어휘라 확인만 하면 된다.

**신규 이름은 19개이며 2026-08-25 GitLab #48에서 전부 승인됐다** — `errors[].rule` **16개** +
봉투 `code` 2개(`GAME_VALIDATION_FAILED` · `GAME_LIMIT_EXCEEDED`, §봉투 code 표) +
`unavailableReason` 1개(`CONFIG_DISABLED`, §Booth Portal Resolution).
`GAME_LIMIT_EXCEEDED`는 같은 날 승인된 계정당 활성 Game 상한(§게임 생성)과 함께 추가된 것이라
승인 코멘트의 "★ 18개"에 뒤이어 더해진 1개다.

> rule 16개는 이 절의 표들에 흩어져 있고, `VARIABLE_COUNT_INVALID`·`ITEM_COUNT_INVALID`는 상한이
> 같아 **한 행을 공유한다**. 행 수로 세면 15개로 잘못 나오니 이름으로 센다.

**구조 (schema 위반)**

| rule | 뜻 |
|---|---|
| `MALFORMED_PROJECT` ★ | JSON이 GameProject 문서가 아님, 또는 `additionalProperties` 위반 (알 수 없는 필드) |
| `STABLE_ID_INVALID` ★ | id 형식 위반 — `^[A-Za-z][A-Za-z0-9_-]{0,63}$` |
| `OBJECT_POSITION_INVALID` | 좌표가 Scene 격자 밖 또는 형식 위반 — **clamp하지 않는다** (FR-034) |
| `PLAYER_SPAWN_COUNT_INVALID` | TOP_DOWN·PLATFORMER Scene당 `PLAYER_SPAWN`이 정확히 1개가 아님 (DIALOGUE는 제외 — validator `:146`) |
| `PLATFORMER_GRAVITY_INVALID` | PLATFORMER `gravity`가 `1~30` 정수 밖 (validator `:144`) |
| `VARIABLE_INITIAL_VALUE_INVALID` | 변수 초기값이 선언 타입과 불일치 |
| `TILE_COUNT_INVALID` | Tile 배열 길이가 Scene 크기와 불일치 (또는 10,000 초과) |
| `DIALOGUE_PRESENTATION_INVALID` | `presentation`이 `OVERLAY`/`FULL_SCREEN` 밖. **이 이름은 네 자리에서 쓰인다** — 여기(enum) 외에 §참조 무결성 2건, §Dialogue 의미 1건. 아래 표들을 함께 본다 |

**중복 id**

| rule | 뜻 |
|---|---|
| `DUPLICATE_SCENE_ID` · `DUPLICATE_OBJECT_ID` · `DUPLICATE_EVENT_ID` · `DUPLICATE_ITEM_ID` · `DUPLICATE_VARIABLE_ID` · `DUPLICATE_ASSET_ID` · `DUPLICATE_DIALOGUE_NODE_ID` | 같은 종류 안에서 id 중복 |
| `DUPLICATE_COMPONENT_TYPE` | 한 Object에 같은 Component 타입 2개 |

**참조 무결성** — Draft·Publish 양쪽에서 검증한다 (#48 2026-08-23 확정)

| rule | 뜻 |
|---|---|
| `START_SCENE_NOT_FOUND` | `startSceneId`가 없는 Scene을 가리킴 |
| `SCENE_REFERENCE_NOT_FOUND` | `GO_TO_SCENE` 대상 Scene 없음 |
| `OBJECT_REFERENCE_NOT_FOUND` · `TRIGGER_TARGET_NOT_FOUND` | Event가 없는 Object를 가리킴 |
| `VARIABLE_REFERENCE_NOT_FOUND` · `ITEM_REFERENCE_NOT_FOUND` | 없는 변수·아이템 참조 |
| `VARIABLE_VALUE_TYPE_INVALID` | `SET_VARIABLE`·`VARIABLE_EQUALS`의 `value`가 변수 선언 타입과 불일치. `event-runtime-semantics`가 **변환 없는 strict equality**를 규정하므로 타입이 어긋난 비교는 영원히 참이 되지 않고, 대입은 그 뒤의 모든 비교를 함께 어긋나게 한다. 변수가 아예 없으면 `VARIABLE_REFERENCE_NOT_FOUND` 하나만 낸다 — 대조할 선언 타입이 없는데 두 이름을 겹쳐 보내면 편집기가 문제 아닌 칸으로 커서를 옮긴다. `initialValue`에 대한 같은 검사는 `VARIABLE_INITIAL_VALUE_INVALID`(§구조)다 |
| `PICKUP_ITEM_NOT_FOUND` | `PICKUP` Component가 없는 아이템을 가리킴 |
| `DIALOGUE_PRESENTATION_INVALID` | **`OVERLAY` DIALOGUE를 시작 Scene(`startSceneId`)이나 `GO_TO_SCENE` 대상으로 쓴 것.** `OVERLAY`는 자기를 연 Scene 위에 겹쳐 그려지고 `CLOSE_DIALOGUE`가 그 아래로 돌아가므로, 거기서 시작하면 밑에 아무것도 없고 `GO_TO_SCENE`은 현재 Scene을 교체하므로 설 자리가 없다 (`README.md`·`event-runtime-semantics.md`가 함께 금지). §구조·§Dialogue 의미의 같은 이름과 한 어휘다 |
| `ITEM_ASSET_NOT_FOUND` · `SPRITE_ASSET_INVALID` · `TILESET_ASSET_INVALID` · `BACKGROUND_ASSET_INVALID` · `PORTRAIT_ASSET_INVALID` · `PROJECTILE_ASSET_INVALID` · `SPAWNER_ASSET_INVALID` | Asset 참조가 `assets[]`에 없거나 `kind`가 쓰임과 맞지 않음 |

**Dialogue 의미** — **Publish가 추가로 보는 "Dialogue 정책"**이다 (§Publish 재검증 목록, #48 2026-08-23 13:19 — *"차이는 Publish가 소유권·Dialogue·Asset 정책을 더 본다"*). 편집기가 같은 규칙을 매 편집마다 검증하므로 정상 흐름의 Draft에는 이 위반이 실리지 않는다. 상세는 `event-runtime-semantics.md`

| rule | 뜻 |
|---|---|
| `DIALOGUE_TARGET_INVALID` | `SHOW_DIALOGUE` 대상이 **DIALOGUE Scene이 아님** |
| `DIALOGUE_PRESENTATION_INVALID` | `SHOW_DIALOGUE` 대상이 DIALOGUE Scene이긴 하나 `presentation`이 `OVERLAY`가 아님. **위 행과 갈라 쓴다** — 고칠 자리가 다르다. 앞은 `sceneId`를 바꿔야 하고 이것은 그 Scene의 `presentation`을 바꿔야 한다. 한 이름으로 합치면 편집기가 어느 칸으로 커서를 보낼지 고를 수 없다 (`validate-fixtures.mjs:108·111`·`gameProject.ts:693`이 이미 갈라 쓴다) |
| `DIALOGUE_START_NODE_NOT_FOUND` · `NEXT_DIALOGUE_NODE_NOT_FOUND` | 대화 노드 참조 없음 |
| `DIALOGUE_NEXT_WITH_TERMINAL_ACTION` | 선택지에 `nextNodeId`와 terminal Action이 함께 있음 |
| `DIALOGUE_CLOSE_CONTEXT_INVALID` | Overlay 없는 상태 또는 `FULL_SCREEN`에서의 `CLOSE_DIALOGUE` |
| `TERMINAL_ACTION_NOT_LAST` | terminal Action 뒤에 Action이 더 있음 |

**상한** — Draft·Publish 공통. 상한값은 확정분이고 rule 이름만 새로 정한다

| rule | 상한 | 근거 |
|---|---|---|
| `PROJECT_SIZE_INVALID` ★ | JSON 2,000,000 bytes | FR-050. 측정은 **UTF-8 직렬화 바이트 길이** — FE `TextEncoder(JSON.stringify()).byteLength`와 같은 값이어야 한다 |
| `SCENE_COUNT_INVALID` ★ | Scene 1~50 | schema `minItems:1, maxItems:50` |
| `OBJECT_COUNT_INVALID` ★ | Scene당 Object 500 | schema |
| `EVENT_COUNT_INVALID` ★ | Scene당 Event 300 | schema |
| `ASSET_COUNT_INVALID` ★ | Asset 300 | schema |
| `VARIABLE_COUNT_INVALID` ★ · `ITEM_COUNT_INVALID` ★ | 각 100 | schema. **계약 본문에 없던 상한**이라 함께 명문화한다 |

> 이름을 `*_LIMIT`(005 `OBJECT_LIMIT`)이 아니라 `*_COUNT_INVALID`로 둔 이유: 019 fixture가
> 이미 `TILE_COUNT_INVALID`를 쓰고 있어서, 한 spec 안에서 두 어휘가 섞이는 것이 spec 간에
> 다른 것보다 나쁘다. **005는 건드리지 않는다.**
> 이 표에 없는 나머지 개수 제약(대화 노드 300·선택지 6·objectives 5 등)은 schema가
> 정의하며, 위반은 별도 rule 없이 `MALFORMED_PROJECT`다 — 계약 본문이 이름 붙인 상한만 rule을 가진다.

**Asset source** — Draft·Publish 공통 (§Draft 저장이 명시, #69에서 "Draft는 경고로 통과" 안을 검토 후 기각)

| rule | 뜻 |
|---|---|
| `ASSET_SOURCE_INVALID` | `builtin://` 또는 서버 발급 stable `asset://`가 아님. `asset://local`·base64·`data:`·`blob:`·`file:` 전부 이 rule로 거부한다 |

**Asset 정책** — Publish 전용 (#69 연계 — 소유권·READY는 서버 Asset 등록이 생겨야 판정 가능)

| rule | 뜻 |
|---|---|
| `ASSET_KIND_UNSUPPORTED` ★ | `kind=AUDIO` — schema는 허용하지만 서버는 v1에서 받지 않는다 (#69 §3-②) |
| `ASSET_NOT_OWNED` ★ | 다른 사용자가 업로드한 `asset://` 참조 (#69 — 업로드 구현 시점) |

> ⚠️ **fixture validator는 이보다 느슨하다.** `validate-fixtures.mjs:50`의 정규식은
> `/^(builtin|asset):\/\//`라서 **`asset://local/...`을 통과시킨다.** 서버는 authority까지 본다.
> fixture는 **하한**이고 서버가 상한이다 — 이 차이를 문서에 적지 않으면, fixture를 통과한
> 프로젝트가 서버에서 거부되는 것을 계약 위반으로 오해한다.

**Publish 전용 — 완료 경로**

| rule | 뜻 |
|---|---|
| `COMPLETION_PATH_MISSING` ★ | 목표(`rules.completion.objectives`)도 `COMPLETE_GAME` Action도 없음 — 끝낼 수 없는 게임 (`event-runtime-semantics.md` §v1.1) |

**warning** — **v1은 정의하지 않는다.** `warnings[]`는 성공 응답에 항상 있지만 빈 배열이다.
spec·FE·fixture 어디에도 019 warning을 요구하는 곳이 없어, 소비자가 없는 서버 판정을 계약으로
굳히지 않는다. 첫 후보는 #56의 "소유한 비활성·비공개 Game"인데 그것은 **Booth Publish**(005
endpoint)의 warning이라 이 계약의 몫이 아니다. 필요해지면 그때 이 절에 추가한다.

**특수 — 409 `GAME_REVISION_CONFLICT` 전용**

| rule | 뜻 |
|---|---|
| `CURRENT_REVISION` | `errors[0]`에 실린다. **`message`는 십진수 문자열**이며 사람이 읽는 문구를 넣지 않는다 (`"8"`). 사용자에게 보여줄 문장은 봉투 최상위 `message`가 담는다. 005는 이 값을 읽는 소비자가 없어 문장을 유지한다 — 같은 rule 이름의 모양은 spec이 소유한다 (#58 §5 재확정, 2026-08-24) |

### schemaVersion 허용 범위 — **#78 답변**

서버는 `1.0.0`과 `1.1.0`을 **둘 다 저장·발행한다** (`game-project-v1.schema.json`의
`enum ["1.0.0","1.1.0"]`). 서버가 버전을 승격하지 않는다 — v1.0을 v1.1로 바꾸는 것은 편집기의
명시적 조작이다 (`studio-authoring-model.md` §7, FR-034의 자동 보정 금지와 같은 결).

| rule | 뜻 |
|---|---|
| `RULES_PRESENCE_INVALID` ★ | `1.1.0`인데 `rules`가 없음, 또는 `1.0.0`인데 `rules`가 있음 (schema `allOf`) |
| `DUPLICATE_OBJECTIVE_TYPE` ★ | 같은 목표 유형을 두 번 사용 (#78 — BE 검증 요청분) |
| `OBJECTIVE_TARGET_INVALID` ★ | `target`이 `1~999,999,999` 정수 밖 (schema `gameRules`. objectives **5개 초과는 이 rule이 아니라 `MALFORMED_PROJECT`**다 — §상한 절 말미 참조. 유형 불문 필드는 `target` 하나 — `event-runtime-semantics.md`의 `targetSeconds` 표기는 schema와 다른 오기다) |
| `PLAYER_DEFEAT_INVALID` ★ | `playerDefeat`이 `RESPAWN`/`END_GAME` 밖 |

`rules`는 Draft·Published snapshot에 **그대로 보존**한다(#78 요청분). MAJOR가 다른 값
(`2.x`)은 `GAME_SCHEMA_UNSUPPORTED`로 거부한다.

## Authoring

```text
POST   /api/v1/games
GET    /api/v1/games/mine
PATCH  /api/v1/games/{gameId}
DELETE /api/v1/games/{gameId}
POST   /api/v1/games/{gameId}/restore
GET    /api/v1/games/{gameId}/draft
PUT    /api/v1/games/{gameId}/draft
POST   /api/v1/games/{gameId}/publish
GET    /api/v1/games/{gameId}/versions
```

Owner/Editor만 호출할 수 있고 Guest는 Authoring API를 사용할 수 없다.

### 게임 생성

```json
{ "title": "열쇠를 찾아라" }
```

```json
{
  "gameId": 123,
  "title": "열쇠를 찾아라",
  "visibility": "PRIVATE",
  "publishedVersion": null,
  "createdAt": "2026-08-24T10:00:00Z"
}
```

- `201 Created` + `Location: /api/v1/games/123`.
- `title`은 1~100자다 (GameProject `title`의 schema 상한과 같다). 위반은 `VALIDATION_FAILED` +
  `rule=FIELD_INVALID`, `field="title"`.
- **생성은 Draft를 만들지 않는다.** 직후 `GET draft`는 "Draft 없음"이며 편집기가 starter project를
  들고 있다가 첫 저장에서 `expectedRevision: 0`으로 만든다 — 서버가 빈 프로젝트를 대신 만들면
  "사용자가 만들지 않은 데이터"가 생기고, starter template 선택(6종, FR-044)이 서버로 새어 나온다.
- `visibility` 기본값은 `PRIVATE`다. 만들자마자 공개되는 것이 놀라운 쪽이다.
- 게스트는 `MEMBER_ONLY`. 소유자는 JWT subject로만 정하고 요청 body로 받지 않는다 (헌법 16조).
- **계정당 활성 Game 20개**를 넘으면 `409` + `GAME_LIMIT_EXCEEDED`다. 상한은 서버 설정값이고 v1 기본이 20이다.
  - **soft-delete된 Game은 이 상한에서 제외한다** — 지우고 새로 만들 수 있어야 한다. 삭제본은 §삭제의 별도 상한을 받는다.
  - Draft 하나가 2,000,000 bytes까지 커질 수 있어 상한이 없으면 계정 하나로 DB를 부풀릴 수 있다.
    부스가 1인 1임대(`ACTIVE_LEASE_LIMIT`)로 막은 것과 같은 이유다.

> **`GAME_LIMIT_EXCEEDED` 알림 정책** — 사용자가 스스로 풀 수 있는 상태이므로 *"한도 초과"* 로 끝내지 않는다.
>
> **봉투 최상위 `message`에 상한값과 해결 방법을 담고 클라이언트는 그 문장을 그대로 표시한다.**
> `errors[]`에 별도 rule을 만들지 않는다 — 사용자에게 보여줄 문장은 최상위 `message`의 자리이고(#58 확정, T058),
> 상한이 서버 설정값이라 클라이언트가 숫자를 하드코딩하면 설정을 바꿀 때 문구가 어긋난다.
>
> ```json
> { "code": "GAME_LIMIT_EXCEEDED",
>   "message": "게임은 최대 20개까지 만들 수 있습니다. 기존 게임을 삭제한 뒤 다시 시도해 주세요.",
>   "requestId": "req_a1b2c3d4", "errors": [], "warnings": [] }
> ```
>
> 복구(§복구)에서 같은 code가 나올 때는 사용자가 할 행동이 다르므로 문장을 구분한다 —
> *"게임은 최대 20개까지 활성화할 수 있습니다. 다른 게임을 삭제한 뒤 복구해 주세요."*

### 내 게임 목록

```text
GET /api/v1/games/mine
```

```json
{
  "games": [
    { "gameId": 123, "title": "열쇠를 찾아라", "visibility": "PRIVATE", "publishedVersion": 5, "updatedAt": "2026-08-25T13:20:00Z", "deletedAt": null },
    { "gameId": 118, "title": "미로", "visibility": "PUBLIC", "publishedVersion": null, "updatedAt": "2026-08-24T09:10:00Z", "deletedAt": "2026-08-24T18:02:00Z" }
  ]
}
```

- 호출자 소유의 Game만 반환한다. 소유자는 JWT subject로 정한다.
- **soft-delete된 Game도 포함한다.** 활성은 `deletedAt: null`이고 삭제본은 삭제 시각을 갖는다.
  별도 `deleted` 파라미터나 전용 endpoint를 만들지 않는다 — 복구(§복구) 대상을 찾을 곳이 여기다.
- 정렬은 **활성 먼저, 각 그룹 안에서 `updatedAt` 내림차순**이다.
- item은 위 6필드 고정이다. **GameProject 본문을 싣지 않는다** — 목록 화면이 쓰는 것은 식별과 상태뿐이다.
- 활성 20 + 삭제본 5로 최대 25건이므로 **v1에 페이지네이션을 두지 않는다**.
- `deletedAt`은 클라이언트가 **삭제 확인 시 밀려날 대상을 계산**하는 재료이기도 하다(§삭제).
- 게스트는 `MEMBER_ONLY`.

> **경로가 `?mine=true`가 아니라 `/mine`인 이유** — `GET /api/v1/booths/mine` 선례와 같은 모양으로 맞춘다.
> query 파라미터로 두면 "파라미터를 빼면 무엇이 나오는가"가 계속 따라붙는데, 공개 게임 전체 목록은
> 페이지네이션·정렬·검색이 붙는 **다른 기능**이고 v1 범위가 아니다. 경로로 고정하면 그 질문이 생기지 않고,
> 나중에 전체 목록이 필요해지면 `GET /api/v1/games`를 그때 따로 설계하면 된다.

### 공개 설정 변경

```text
PATCH /api/v1/games/{gameId}
```

```json
{ "visibility": "PUBLIC" }
```

```json
{ "gameId": 123, "visibility": "PUBLIC", "publishedVersion": 5, "updatedAt": "2026-08-25T13:40:00Z" }
```

- 소유자만 호출한다. 아니면 `403` + `GAME_FORBIDDEN`. 없으면 `404` + `GAME_NOT_FOUND`,
  soft-delete된 Game이면 `404` + `GAME_DELETED`.
- 값은 `PRIVATE` | `PUBLIC` 둘뿐이다. 그 밖은 `400` + `VALIDATION_FAILED` + `rule=FIELD_INVALID`, `field="visibility"`.
- **`publishedVersion`이 `null`이어도 `PUBLIC` 전환을 허용한다.** 공개 설정과 발행은 독립 축이고
  (`games.published_version`이 nullable인 이유), 발행본이 없는 `PUBLIC`은 `GET /published`에서
  `GAME_NOT_PUBLISHED`로 나타난다. 편집기가 "아직 발행하지 않았습니다"를 안내할 일이지 서버가 막을 일이 아니다.
- `PUBLIC` → `PRIVATE` 전환은 #33 확정대로 **Published Version 이력과 Draft를 보존**한다. 이미 GameProject를
  로드한 무보상 로컬 세션은 완료까지 허용하되 새 진입은 거부한다(§Runtime).

### 삭제

```text
DELETE /api/v1/games/{gameId}
POST   /api/v1/games/{gameId}/restore
```

- 성공은 `204 No Content`다. **일반 삭제는 soft delete**이고 `games.deleted_at`을 남긴다.
- 소유자만 호출한다. 아니면 `403` + `GAME_FORBIDDEN`. 없으면 `404` + `GAME_NOT_FOUND`.
- **이미 soft-delete된 Game에 다시 호출하면 `404` + `GAME_DELETED`다.**
  - **클라이언트는 이 응답을 실패가 아니라 "이미 완료된 상태"로 처리한다.** 응답이 유실된 뒤의 재시도가
    여기로 오기 때문이다 — 오류 화면을 띄우면 실제로는 성공한 삭제를 실패로 보고하게 된다.
- Draft와 Published Version 이력은 **보존**한다. 활성 상한(§게임 생성)에서만 빠진다.

#### 삭제본 보관 — 계정당 5개, 초과분은 오래된 것부터 영구 삭제

soft delete가 무제한이면 활성 20을 지키면서 삭제본을 무한히 쌓을 수 있고, 각각 최대 2,000,000 bytes의
Draft를 단다. 그래서 **삭제본에도 상한을 둔다.**

- **계정당 삭제본 5개.** 서버 설정값이고 v1 기본이 5다. 활성 상한과 **독립**이라 서로 잠식하지 않는다.
- 6번째를 삭제하면 **가장 오래된 삭제본(`deleted_at` 최소)이 hard delete**되고 삭제는 성공한다.
  삭제를 거부하지 않는다 — 사용자가 원하는 것은 지우는 것인데 *"먼저 보관함을 비우세요"* 는 목적을 막는다.
- 밀려난 Game은 Draft·Published Version·Asset·Score까지 함께 제거한다(탈퇴 hard delete와 같은 경로).
- **보관 기간(시간)은 두지 않는다.** 개수로 유계이므로 sweeper·배치가 필요 없고, 밀어내기가
  삭제 트랜잭션 안에서 끝난다. 계정당 최대 저장량은 (20 + 5) × 2MB로 확정적으로 유계다.

> **밀려남은 사전에 고지한다** — 응답이 `204`라 사후에 알릴 자리가 없다. 편집기가 **삭제 확인 단계에서**
> *"보관함이 가득 차 가장 오래된 '미로'가 영구 삭제됩니다"* 를 보여준다. 서버는 그 판단 재료로
> §내 게임 목록에 `deletedAt`을 싣는다 — 클라이언트가 삭제본 개수와 최고령 항목을 셀 수 있다.
>
> 서버가 사후 통보하려면 `204`를 `200` + `warnings`로 바꿔야 하는데, 이미 지워진 뒤에 알리는 것은
> 사용자가 손쓸 수 없는 통보라 사전 고지보다 나쁘다.

### 복구

```text
POST /api/v1/games/{gameId}/restore
```

```json
{ "gameId": 118, "title": "미로", "visibility": "PRIVATE", "publishedVersion": null, "updatedAt": "2026-08-24T09:10:00Z", "deletedAt": null }
```

- 성공은 `200`이고 본문은 §내 게임 목록의 item과 같은 모양이다. `deleted_at`을 지우고 `visibility`는
  **삭제 전 값을 그대로 되살린다** — 삭제가 공개 설정을 바꾸는 동작이 아니었기 때문이다.
- 소유자만 호출한다. 아니면 `403` + `GAME_FORBIDDEN`. 없으면 `404` + `GAME_NOT_FOUND`.
- **활성 Game이 이미 상한(20)이면 `409` + `GAME_LIMIT_EXCEEDED`다.** 복구는 활성 슬롯을 차지하는
  행위이므로 생성과 같은 게이트를 받는다. 문구는 §게임 생성의 알림 정책대로 복구용으로 구분한다.
  이때 삭제본은 그대로 남으므로 사용자가 다른 게임을 삭제한 뒤 다시 복구하면 된다.
- 밀려나 hard delete된 Game은 복구할 수 없다. `404` + `GAME_NOT_FOUND`다 — 행 자체가 없다.

> **삭제되지 않은 Game에 호출하면 `200` + 현재 상태다(멱등).** §삭제의 재호출이 `404 GAME_DELETED`인 것과
> **의도적으로 비대칭**이다. 대칭으로 맞추려면 `GAME_NOT_DELETED` 같은 이름을 새로 만들어야 하는데,
> 복구 대상이 이미 활성이면 목적은 달성된 상태라 오류로 만들 값이 없다.
> `DELETE`가 `404`를 쓰는 이유는 반대다 — *"이미 삭제됨"* 은 클라이언트가 알아야 할 상태 정보다.
- 회원 탈퇴 hard delete는 기존 계정 삭제 흐름에 연쇄한다. **별도 endpoint를 만들지 않는다** —
  Game, Draft, Published Version, Asset, Score를 모두 제거한다(§Persistence Boundary).

### 버전 목록

```json
{
  "gameId": 123,
  "publishedVersion": 5,
  "versions": [
    { "versionNo": 5, "schemaVersion": "1.1.0", "publishedAt": "2026-08-23T13:22:00Z" },
    { "versionNo": 4, "schemaVersion": "1.0.0", "publishedAt": "2026-08-22T09:10:00Z" }
  ]
}
```

- 소유자 전용이다. `versionNo` 내림차순, 최신 50건까지 — v1에 페이지네이션을 만들지 않는다.
- **`project` 본문을 싣지 않는다.** 목록이 이력 화면용이고, 상한이 2MB인 snapshot 50개를 한 응답에
  담으면 100MB가 된다. 특정 version 본문은 후속 version 고정 URL의 몫이다 (§Runtime).
- `publishedVersion`은 현재 공개 포인터다. `versions`가 비어 있지 않은데 `null`이면 공개 중단 상태다.

### Draft 저장

요청은 `expectedRevision`과 전체 GameProject snapshot을 포함한다. 서버는 Draft 저장과 Publish 양쪽에서
같은 구조·schema·용량·개수 상한과 내부 참조 무결성을 검증한다. JSON은 2,000,000 bytes, Scene은 50,
Scene당 Object 500/Event 300, Asset 300을 넘을 수 없다. Asset source는 `builtin://` 또는 서버가 발급한
stable `asset://`만 허용하고 `asset://local`, binary/base64, `data:`, `blob:`, `file:`은 거부한다.

```json
{
  "expectedRevision": 7,
  "project": "<GameProject v1 object>"
}
```

`GET draft`와 `PUT draft` 성공 응답은 같은 shape을 사용한다.

```json
{
  "gameId": 123,
  "revision": 8,
  "project": "<GameProject v1 object; gameId=123, revision=8>",
  "updatedAt": "2026-08-23T13:20:00Z",
  "warnings": []
}
```

- `GET`에서 Draft가 아직 없으면 **HTTP 204 No Content**를 반환한다 (본문 없음). FE는 새 starter
  project를 유지한다. 204이므로 `code`가 없고, `GAME_DRAFT_NOT_FOUND`는 **더 이상 쓰지 않는다** —
  §봉투 `code` 표에서 뺐다.
  - 204를 쓰는 이유는 **"Game은 있고 Draft만 없다"와 "Game이 없다"(404)·"내 것이 아니다"(403)가
    상태 코드만으로 갈리기 때문**이다. 셋을 다 404로 두면 FE가 본문 `code`를 읽어야 구분된다.
- 응답의 `gameId`, `revision`과 `project.gameId`, `project.revision`은 반드시 일치해야 한다.
  - `revision`은 **서버가 발급하는 카운터**이고 사용자 데이터가 아니다. 그래서 서버는 저장 시
    `project.revision`을 새 값으로 **기록한다** — FE가 `expectedRevision`에 옛 값을 실어 보내고
    응답에서 새 값과의 일치를 strict 검사하므로, 기록하지 않으면 응답 일치가 성립하지 않는다.
    이것은 **FR-034(자동 보정 금지)의 예외가 아니다.** 금지 대상은 사용자가 만든 값의 무단 수정
    (좌표 clamp·알 수 없는 필드 삭제·참조 치환)이고, 그것들은 그대로 금지다.
  - `project.gameId`가 경로와 다르면 **보정하지 않고 `MALFORMED_PROJECT`로 거부한다** — 경로가 정본이다.
- FE는 `VITE_GAME_STUDIO_API_ENABLED=true`일 때만 서버 Draft adapter를 활성화한다. false이면 같은 UI와 validator를 IndexedDB/local adapter로 실행한다.

- 성공: revision 8의 새 Draft snapshot 반환
- 충돌: HTTP 409 + `GAME_REVISION_CONFLICT` + 현재 revision
- **Draft row가 없는 상태의 첫 저장**: `expectedRevision: 0`이면 최초 생성으로 처리하고 revision 1을
  반환한다. 그 밖의 값이면 409 + `GAME_REVISION_CONFLICT` + `CURRENT_REVISION` `"0"`.
  - §게임 생성이 "생성은 Draft를 만들지 않는다"이고 starter project의 `revision`이 0이므로
    (`createStarterProject.ts:13`), 첫 저장은 **반드시** `expectedRevision: 0`으로 온다. 이 규칙이
    없으면 위의 "충돌" 줄만 읽고 구현했을 때 **모든 게임의 첫 저장이 409로 튕긴다.**
- 좌표 clamp, 알 수 없는 필드 삭제, 참조 치환 같은 자동 보정 금지
- 별도 `/validate`가 후속으로 생겨도 Draft 저장과 Publish에서 각각 다시 검증

### Publish

요청과 성공 응답은 다음 shape으로 고정한다.

```json
{
  "expectedRevision": 8
}
```

```json
{
  "gameId": 123,
  "publishedVersion": 5,
  "publishedAt": "2026-08-23T13:22:00Z",
  "warnings": []
}
```

Publish는 다음 순서를 **단일 DB 트랜잭션**으로 수행한다.

```text
Draft read
→ schema/상한/참조/소유권/Dialogue·Asset 정책 재검증
→ game_published_versions append
→ games.published_version pointer update
→ commit
```

검증 실패 시 Published 행과 포인터 변경을 남기지 않는다. 성공 후에도 `game_drafts`를 삭제하지 않아
제작자가 현재 Draft에서 계속 편집할 수 있게 한다. 기존 Published Version은 update하지 않는다.

## Persistence Boundary

```text
games
game_drafts                  PK(game_id), mutable revision
game_published_versions      UNIQUE(game_id, version_no), immutable
game_portal_bindings
```

`games.published_version`은 nullable이고 `(games.id, published_version)`이 같은 Game의 Published Version만
가리키도록 복합 FK를 둔다. 공개본 삭제 시 PostgreSQL의
`ON DELETE SET NULL (published_version)`처럼 nullable 포인터 컬럼만 명시해 `games.id`가 NULL 대상이
되지 않게 한다.

- `games.deleted_at`은 일반 삭제의 soft-delete marker다.
- Game이 존속하는 동안 Published Version 이력을 유지한다.
- 회원 탈퇴 hard delete는 Game, Draft, Published Version, Asset, Score를 모두 제거한다.
- 표시 전용 Score가 P1에서 추가되더라도 Coin·Reward·Inventory와 FK로 연결하지 않는다.

## Runtime

```text
GET /api/v1/games/{gameId}/published
```

- Draft는 Runtime 공개 endpoint로 노출하지 않는다.
- Published가 없거나 비공개·삭제된 게임은 새로 실행할 수 없다.
- 응답은 `schemaVersion`, `gameId`, `publishedVersion`, GameProject를 포함한다.

```json
{
  "schemaVersion": "1.0.0",
  "gameId": 123,
  "publishedVersion": 5,
  "project": "<immutable Published GameProject v1 object>",
  "publishedAt": "2026-08-23T13:22:00Z"
}
```

- FE는 `gameId`, `schemaVersion`, GameProject 내부 참조를 다시 검증하고 불일치·손상 응답을 Runtime 밖의 오류 화면으로 격리한다.
- `GAME_NOT_FOUND`, `GAME_DELETED`, `GAME_NOT_PUBLISHED`, `GAME_NOT_PUBLIC`, `GAME_FORBIDDEN`, `GAME_SCHEMA_UNSUPPORTED`, `GAME_PROJECT_INVALID`를 사용자용 한국어 상태로 구분한다.
- 현재 공개 포인터를 따라가는 `/games/{gameId}/published`는 재공개 즉시 새 version을 보도록 `Cache-Control: no-cache`와 ETag 재검증을 사용한다.
- 긴 `max-age, immutable`은 후속 version 고정 URL(`/games/{gameId}/versions/{versionNo}`)을 제공할 때 해당 URL에만 적용한다.
- Runtime은 지원하지 않는 MAJOR를 거부한다.
- Asset binary나 만료 주소가 아니라 안정적인 Asset reference만 반환한다.
- route 진입 시 한 번 조회해 공개 상태를 판정한다. Game Studio 전용 socket은 사용하지 않는다.
- 비공개 전환 뒤 이미 GameProject를 로드한 무보상 로컬 세션은 완료까지 허용하되 새 진입은 거부한다.

## Asset Boundary

- MVP는 Game Studio가 소유하는 versioned `builtin://` Asset catalog를 사용한다.
- 서버가 관리하는 `asset://` reference를 추가하더라도 metadata/resolver와 binary 저장소는
  Draft/Published JSONB와 분리한다.
- MVP에 사용자 upload endpoint를 포함하지 않는다. 업로드·용량·검사·보존·탈퇴 삭제와 `asset://local`→stable `asset://` 승격은 [Issue #69](https://github.com/kanghyunsoon/ssafesta/issues/69)에서 추적한다.
- Preview/Runtime이 실제 전달 URL을 얻어도 blob/file/data/서명 URL을 Draft나 Published JSON에 저장하지 않는다.

## Booth Portal Resolution

```text
GET /api/v1/game-portals/{configId}
Cache-Control: no-store
```

응답 예시:

```json
{
  "configId": 42,
  "boothId": 7,
  "objectId": "game-npc-01",
  "gameId": 123,
  "publishedVersion": 5,
  "playable": true,
  "unavailableReason": null
}
```

- 요청자의 접근 권한, Booth 임대 상태, Game 공개 상태와 Binding 활성 상태를 매번 서버가 판정한다.
- Unity가 보낸 `boothId`, `objectId`, `configId`는 조회 힌트이며 권한 근거가 아니다.
- `LayoutConfigResolver`는 `GAME_PORTAL`을 검사하되 Layout JSON에 GameProject를 포함하지 않는다.
- wire/Unity `configId`는 signed Int32 `1..2147483647`, FE는 정수 `number`다. 0은 미연결 sentinel이므로 유효 ID로 발급하지 않는다.
- DB는 내부 `id BIGINT`와 외부 `config_id INTEGER UNIQUE NOT NULL CHECK (config_id > 0)`를 분리하고 전용 sequence를 1부터 시작한다.
- `GAME_PORTAL`은 Layout canonical whitelist에 `requiresConfig=true`로 추가한다. BE whitelist 배포 후 FE가 이 type을 전송한다.
- 없는/남의 Binding은 `CONFIG_NOT_OWNED` error로 Publish를 차단하고, 소유한 비활성·비공개 Game은 Booth Publish warning으로 처리한다. 실제 Runtime 진입은 엄격히 차단한다.
- 임대 만료는 기존 `BOOTH_LEASE_EXPIRED` 의미를 재사용하고 Game Draft/Published 데이터는 보존한다.
- Portal resolution이 공개·임대·Binding 상태를 판정하므로 추가 왕복이나 game socket을 만들지 않는다.

### `unavailableReason` 어휘

`playable: false`일 때 채운다. FE가 문구를 여기에 매핑하므로(`gamePortalRepository.ts`
`unavailableCopy`) 계약값이다.

| unavailableReason | 뜻 |
|---|---|
| `GAME_NOT_PUBLISHED` | Binding은 있으나 연결된 Game에 Published Version이 없음 |
| `GAME_NOT_PUBLIC` | 연결된 Game이 `PRIVATE` |
| `GAME_DELETED` | 연결된 Game이 soft delete됨 |
| `BOOTH_LEASE_EXPIRED` | 부스 임대가 만료됨 — 004·005·008과 같은 어휘 |
| `CONFIG_DISABLED` ★ | Binding `enabled=false` (**신규 — FE 문구 추가 필요**) |

- 위 다섯은 **HTTP 200 + `playable:false`**다. 오류가 아니다 — Booth Host는 월드를 끊지 않고 안내만
  띄운다 (FR-020).
- **`CONFIG_NOT_FOUND`만 HTTP 404 + 봉투 `code`다.** 200으로 보낼 수 없다: 응답 필수 필드인
  `boothId`를 채울 수 없고, FE `parseGamePortalResolution`이 `boothId` 양수를 요구하므로 200으로
  보내면 파싱 단계에서 깨진다.
- `gameId`·`publishedVersion`은 nullable이다. Binding은 있고 Game이 미발행이면 `gameId`는 값이 있고
  `publishedVersion`이 `null`이다.

## 확정 기록

이 문서의 미결 항목은 **2026-08-25에 전부 확정됐다.** 근거는 GitLab #104(①⑦)와 #48(★ 승인·②③④⑤⑥)이다.

| # | 무엇 | 확정 | 근거 |
|---|---|---|---|
| ① | `GET /draft`에 Draft가 없을 때 | **204 No Content**. `GAME_DRAFT_NOT_FOUND` 폐기. 첫 저장은 `expectedRevision: 0` = 최초 생성, revision 1 반환 | #104 |
| ② | 공개 중단·삭제 endpoint | `PATCH /games/{gameId}`(visibility) · `DELETE /games/{gameId}`(soft) · `POST /games/{gameId}/restore` 신설. 탈퇴 hard delete는 기존 계정 삭제 흐름에 연쇄하고 별도 endpoint를 만들지 않는다 | #48 |
| ③ | `docs/08` §18 게임 코드 표 | 실제 wire 이름으로 정정하고 상세 code·rule 정본은 이 문서가 갖는다. 쓰이지 않는 옛 이름은 남기지 않는다 | #48 |
| ④ | 사용자당 게임 상한 | **활성 20개**(soft-delete 제외), 초과 시 `GAME_LIMIT_EXCEEDED`(409). 서버 설정값이고 v1 기본 20. **삭제본은 별도로 5개**이며 초과분은 오래된 것부터 hard delete (§삭제본 보관) | #48 |
| ⑤ | 내 게임 목록 | `GET /games/mine`. soft-delete 포함(`deletedAt`), 활성 먼저 `updatedAt` 내림차순, 6필드, 페이지네이션 없음 | #48 |
| ⑥ | #81 Coin 차감 | **#48 범위에서 제외.** MVP는 무료·무보상을 유지하고 #81 계열에서 spec 개정 후 별도 구현한다 (FR-022·§MVP 제외 그대로) | #48 |
| ⑦ | `INTERNAL_SERVER_ERROR` vs `INTERNAL_ERROR` | 서버는 **`INTERNAL_ERROR` 유지**. 전 endpoint 공통 코드라 서버를 바꾸지 않고 FE 재시도 판정을 맞춘다 | #104 |
| — | 신규 이름 19개 | `errors[].rule` 16 + `code` 2(`GAME_VALIDATION_FAILED`·`GAME_LIMIT_EXCEEDED`) + `unavailableReason` 1(`CONFIG_DISABLED`) 전부 승인 | #48 |
| — | 패키지 배치 | **기존 Backend와 같은 flat 구조.** `auth`·`booth`·`user`·`wallet` 관례를 따르고 019만 4계층 선례를 만들지 않는다 (`BE/plan.md`) | #48 |

> **`5개`·`20개`는 서버 설정값이다.** 문서와 테스트는 v1 기본값 기준으로 맞추되, 값 자체를 코드에 박지 않는다.

## MVP 제외

- Coin/Reward 지급
- 경쟁 Ranking과 MVP score endpoint. 표시 전용 Ranking은 P1 별도 범위
- 클라이언트 점수 기반 서버 정산
- AI 생성 요청
- 사용자 Asset upload·가공
