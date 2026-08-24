# Game Studio API 계약

> 상태: Draft v0.5 — #33·#34·#48·#56 및 전역 오류 봉투 #58 합의 반영. DTO·revision·오류 코드는 이 문서를 기준으로 구현한다.

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

## 오류 코드와 rule (제안 — #48 잔여 계약, 2026-08-24)

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
| `GAME_DRAFT_NOT_FOUND` | 404 | Draft가 아직 없음 — **§결정 필요 ① 참조** | 편집기: starter project 유지 |
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

세 가지는 의도적으로 **재사용**이다 — 새 이름을 만들면 같은 사건이 두 이름을 갖는다.

- `MEMBER_ONLY` — 013a·001과 같다. 게스트 차단은 게임의 사정이 아니라 계정의 사정이다.
- `VALIDATION_FAILED` + `FIELD_INVALID`/`field` — #58 C 확정(T058) 그대로.
- `BOOTH_LEASE_EXPIRED` — Portal의 임대 만료. 004·005·008과 같은 코드다 (§Booth Portal Resolution).

> `GAME_PROJECT_INVALID`만 500이다. 나머지는 클라이언트가 고칠 수 있는 사건이지만, 이것은
> **서버가 저장을 허용했던 데이터가 지금 검증을 통과하지 못한다**는 뜻이라 서버 결함이다.
> 조용히 200으로 빈 프로젝트를 돌려주지 않는다 (T-24).

### `rule` — `GAME_VALIDATION_FAILED`의 `errors[]`

`rule` 이름은 **`contracts/fixtures/`의 reference validator가 이미 정한 것을 그대로 쓴다** —
quickstart가 "같은 manifest의 positive/negative fixture와 오류 코드를 재현해야 한다"고 요구하므로
이름이 갈리면 fixture 테스트가 성립하지 않는다.

**★는 fixture에 없어서 이번에 새로 정하는 이름이다.** validator를 훑어 있는 것과 없는 것을 갈라
놓았다 — ★ 없는 행은 이미 코드로 고정된 어휘라 확인만 하면 된다. **승인이 필요한 것은 ★ 18개**다 —
`errors[].rule` **16개** + 봉투 `code` 1개(`GAME_VALIDATION_FAILED`, §봉투 code 표) +
`unavailableReason` 1개(`CONFIG_DISABLED`, §Booth Portal Resolution).

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
| `DIALOGUE_PRESENTATION_INVALID` | `presentation`이 `OVERLAY`/`FULL_SCREEN` 밖 |

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
| `PICKUP_ITEM_NOT_FOUND` | `PICKUP` Component가 없는 아이템을 가리킴 |
| `ITEM_ASSET_NOT_FOUND` · `SPRITE_ASSET_INVALID` · `TILESET_ASSET_INVALID` · `BACKGROUND_ASSET_INVALID` · `PORTRAIT_ASSET_INVALID` · `PROJECTILE_ASSET_INVALID` · `SPAWNER_ASSET_INVALID` | Asset 참조가 `assets[]`에 없거나 `kind`가 쓰임과 맞지 않음 |

**Dialogue 의미** — **Publish가 추가로 보는 "Dialogue 정책"**이다 (§Publish 재검증 목록, #48 2026-08-23 13:19 — *"차이는 Publish가 소유권·Dialogue·Asset 정책을 더 본다"*). 편집기가 같은 규칙을 매 편집마다 검증하므로 정상 흐름의 Draft에는 이 위반이 실리지 않는다. 상세는 `event-runtime-semantics.md`

| rule | 뜻 |
|---|---|
| `DIALOGUE_TARGET_INVALID` | `SHOW_DIALOGUE` 대상이 `OVERLAY` DIALOGUE Scene이 아님 |
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
| `OBJECTIVE_TARGET_INVALID` ★ | `target`이 `1~999,999,999` 정수 밖, 또는 objectives 5개 초과 (schema `gameRules`. 유형 불문 필드는 `target` 하나 — `event-runtime-semantics.md`의 `targetSeconds` 표기는 schema와 다른 오기다) |
| `PLAYER_DEFEAT_INVALID` ★ | `playerDefeat`이 `RESPAWN`/`END_GAME` 밖 |

`rules`는 Draft·Published snapshot에 **그대로 보존**한다(#78 요청분). MAJOR가 다른 값
(`2.x`)은 `GAME_SCHEMA_UNSUPPORTED`로 거부한다.

## Authoring

```text
POST /api/v1/games
GET  /api/v1/games/{gameId}/draft
PUT  /api/v1/games/{gameId}/draft
POST /api/v1/games/{gameId}/publish
GET  /api/v1/games/{gameId}/versions
```

Owner/Editor만 호출할 수 있고 Guest는 Authoring API를 사용할 수 없다.

### 게임 생성 (제안 — shape 미정이었음)

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

### 버전 목록 (제안 — shape 미정이었음)

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

- `GET`에서 Draft가 아직 없으면 HTTP 404 + `GAME_DRAFT_NOT_FOUND`를 반환한다. FE는 새 starter project를 유지한다.
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

### `unavailableReason` 어휘 (제안 — 어휘 미정이었음)

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

## 결정 필요 (BE 구현 착수 전, 2026-08-24)

헌법 30조에 따라 구현자가 임의로 정하지 않는다. 위에서 **"제안"** 으로 표기한 것과 별개로, 아래는
**이미 머지된 계약·구현과 서로 어긋나 있어** 한쪽이 움직여야 하는 것들이다.

> ①·⑦의 FE 근거(`gameAuthoringApi.ts`·`gamePortalRepository.ts`·`shared/api/client.ts`)는 **PR #72·#82
> 브랜치 기준이다** — `origin/front`·develop에는 아직 없다. 그 PR이 머지되면 그대로 성립하고, 다른
> 모양으로 머지되면 이 두 항목을 다시 본다.

| # | 무엇 | 지금 상태 | 왜 지금 정해야 하나 |
|---|---|---|---|
| ① | **`GET /draft`에 Draft가 없을 때** | 계약은 `404` + `GAME_DRAFT_NOT_FOUND`. FE `createApiGameDraftRepository.load`는 **HTTP 204만** "Draft 없음"(→`null`)으로 읽고, 404는 throw해서 `GameStudioShell`의 `.catch`가 **저장 상태를 `error`로 바꾸고 오류 문구를 띄운다** | 계약 문장("FE는 새 starter project를 유지한다")이 **새 게임 첫 방문마다** 깨진다. 예외도 테스트 실패도 없이 편집기가 오류 상태로 열린다 — 통합을 붙이기 전에는 안 잡히는 종류다. **BE가 204로 바꾸거나 FE `load`가 이 코드를 잡거나** 둘 중 하나. BE 쪽 변경을 권하는 이유: 204면 "Game은 있고 Draft만 없다"와 "Game이 없다/내 것이 아니다"(404·403)가 **구조적으로** 갈린다 |
| ② | **공개 중단·삭제 endpoint가 없다** | `visibility` 전환과 soft delete 경로가 계약에 없다. 그런데 BE tasks **T085**가 "unpublish, soft/hard delete, Published-history 정책을 통합 테스트로 고정"을 요구하고 #33에서 정책은 이미 확정됐다 | 정책만 있고 문이 없어서 T085를 구현할 수 없다. 최소 2개가 필요하다 — `PATCH /api/v1/games/{gameId}` (`visibility`) · `DELETE /api/v1/games/{gameId}` (soft). 회원 탈퇴 hard delete는 기존 `AccountDeletionService`에 연쇄를 붙이는 것이라 새 endpoint가 아니다 |
| ③ | **`docs/08` §18의 게임 코드 표** — 같은 MR에 **수정안**을 담았다 | §18의 `GAME_*` **6행**(전부 *(P2 후보)* 표기) 중 2개(`GAME_PROJECT_VALIDATION_FAILED`·`GAME_PORTAL_UNAVAILABLE`)가 **어디에서도 쓰이지 않는 이름**이다. 실제는 `GAME_PROJECT_INVALID`(FE `apiErrorCopy` 소비)와 `CONFIG_NOT_FOUND`다. `GAME_DELETED`·`GAME_NOT_PUBLIC`·`GAME_FORBIDDEN`·`GAME_DRAFT_NOT_FOUND`는 아예 없다 | §18은 전 파트가 읽는 코드 목록이고 **`docs/08`은 리드가 소유하는 정본**이다. 그래서 이 MR의 §18 변경은 **수정안이고 확정이 아니다** — 6행 → 11행 교체를 그대로 받으실지, 다르게 쓰실지, 아니면 019 계약이 소유하고 §18은 링크만 둘지 정해 주십시오. 틀린 이름이 남으면 다음 사람이 그 이름으로 분기를 만든다는 것이 제가 손댄 이유입니다 (#59와 같은 뿌리) |
| ④ | **사용자당 게임 수 상한** | 없다. `title` 하나로 무한히 만들 수 있다 | 부스는 1인 1임대(`ACTIVE_LEASE_LIMIT`)로 막았는데 게임은 열려 있다. Draft 하나가 2MB까지 커질 수 있어 계정 하나로 DB를 부풀릴 수 있다. **제안: 계정당 20개**, 초과 시 `GAME_LIMIT_EXCEEDED`(409) |
| ⑤ | **게임 목록 endpoint** | 없다. FE 라우트는 `/app/games/:gameId/edit`·`/play`뿐이고 spec US3은 "게임 목록이나 공유된 진입점"이라 적혀 있다 | 사용자가 자기 게임으로 돌아갈 방법이 없다 — 생성 응답의 `gameId`를 잃으면 끝이다. **`GET /api/v1/games?mine=true` 최소 1개**가 필요한지 FE와 확인 |
| ⑥ | **#81 Coin 차감** | #81이 Published 플레이에 Coin 차감·세션을 요청한다. 그런데 spec 019 **FR-022는 "첫 MVP는 Coin, Reward, Ranking을 포함하지 않아야 한다"**이고 이 문서 §MVP 제외도 같다 | 범위 확장이라 spec 개정이 선행이다. #48 구현 중에 끼워 넣으면 헌법 20조(Ledger·idempotency)와 28조(범위 통제)를 동시에 건드린다. **#48 완료 후 별건**으로 두는 것을 권한다 — 추적은 이미 갈라져 있다(`S15P21A604-108` BE · `S15P21A604-117` FE) |

> ⑦ 참고 — FE `gameAuthoringApi.ts`는 재시도 가능 판정에 `error.code === 'INTERNAL_SERVER_ERROR'`를
> 쓰지만 서버가 보내는 코드는 `INTERNAL_ERROR`다(`ErrorCode.java`, `docs/08` §18). 지금은 5xx가
> 재시도 불가로 분류된다. 서버 코드를 바꾸면 전 endpoint에 영향이라 **FE 한 줄**이 맞다고 본다.

## MVP 제외

- Coin/Reward 지급
- 경쟁 Ranking과 MVP score endpoint. 표시 전용 Ranking은 P1 별도 범위
- 클라이언트 점수 기반 서버 정산
- AI 생성 요청
- 사용자 Asset upload·가공
