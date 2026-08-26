# BE Research: Persistence, Publish, and Portal

## 1. Draft와 Published 분리

`game_drafts`는 Game당 하나의 mutable JSONB와 revision을 가진다. `game_published_versions`는
`(game_id, version_no)`별 immutable append다. 한 JSON 행을 Draft/Published가 공유하면 편집 내용이
방문자에게 노출되고 rollback이 어려워 제외한다.

## 2. Publish transaction

Schema와 semantic validation이 모두 성공한 뒤 Published row append와 `games.published_version` 포인터
갱신을 한 transaction으로 처리한다. 실패하면 Published row, pointer, Draft 어느 것도 부분 반영하지 않는다.

## 3. 공개 중단과 세션

게임은 무보상 로컬 실행이라 서버가 진행 중 세션을 알지 못한다. 따라서 route/overlay 진입의 Published 또는
Portal resolver REST 조회가 유일한 차단점이다. 이미 로드된 세션을 끊기 위한 game socket은 만들지 않는다.

## 4. 삭제와 이력

일반 삭제는 soft delete로 Portal 참조와 복구 가능성을 보존한다. 회원 탈퇴는 개인정보 정책에 따라 관련
Game/Draft/Published/Asset/Score를 hard delete한다. Published 이력은 Game 존속 중 유지한다.

## 5. Public config ID

내부 PK/FK는 BIGINT를 유지하고 외부 `configId`만 별도 INTEGER로 둔다. DB 타입이 Int32 overflow를,
`CHECK (config_id > 0)`가 Unity의 0=미연결 전제를 물리적으로 보장한다. 기존 AI_AGENT ID는 소급하지 않는다.

## 6. Layout resolver strength

- missing/foreign binding: error `CONFIG_NOT_OWNED`, publish 차단
- own but unpublished/inactive game: warning, Booth publish 허용
- visitor runtime entry: inactive/non-public를 엄격히 차단

즉 배치는 소유권을 엄격히, 활성 상태는 경고로 다루고 실행 시점은 엄격히 검사한다.

---

이하 §7~§12는 2026-08-24 구현 계획(#48, strdeok)의 결정 기록이다. §1~§6(리드)은 계약 수준, 이쪽은 구현 수준.

## 7. 검증기 구조 — 005와 같은 `validateForDraft`/`validateForPublish` 분기

파싱(Jackson) → ① 바이트 상한(파싱 전) → ② JSON Schema 검증 → ③ 중복 id·참조 무결성 →
④ Publish 한정: 소유권·Dialogue 정책·완료 경로·Asset 정책.

005 `LayoutValidator`의 두 진입점을 그대로 쓴다 — Draft는 ①②③, Publish는 ①②③④. 019만의 새 구조를
만들지 않으므로 계약 문서에 층 개념을 설명할 필요도 없다.

**개수 상한을 트리에서 손으로 세는 층은 두지 않는다.** 이름 붙은 상한 7종 중 바이트를 뺀 6종이
schema `maxItems`에 이미 전부 있고(실측), `instanceLocation`에서 숫자 세그먼트를 지우면 서로 구별된다.

| rule | `instanceLocation` (숫자 제거) | schema 위치 · 값 |
|---|---|---|
| `SCENE_COUNT_INVALID` | `/scenes` | `/properties/scenes` · 50 |
| `OBJECT_COUNT_INVALID` | `/scenes/objects` | `{topDown,platformer}Scene/objects` · 500 |
| `EVENT_COUNT_INVALID` | `/scenes/events` | `{topDown,platformer}Scene/events` · 300 |
| `ASSET_COUNT_INVALID` | `/assets` | `/properties/assets` · 300 |
| `VARIABLE_COUNT_INVALID` | `/variables` | `/properties/variables` · 100 |
| `TILE_COUNT_INVALID` | `/scenes/tileLayers/data` | `tileLayer/data` · 10000 |

`keyword=maxItems` + 위 경로 6개를 Map으로 잡고, 매핑에 없는 `maxItems` 위반(schema에 19개가 있으므로
나머지 13개 — `items` 100 · `objectives` 5 · `choices` 6 · `nodes` 300 등)은 `MALFORMED_PROJECT`다.
계약이 "이름 붙인 상한만 rule을 가진다"고 적은 것과 같은 결론에 코드가 도달한다.

> **정정 (ponytail-review, 2026-08-24)** — 이 절의 앞선 판단은 "schema로 걸리면 rule 이름이
> `MALFORMED_PROJECT`가 되니 named 상한을 schema보다 **먼저** 손으로 검사해야 한다"였다. **틀렸다.**
> `ValidationMessage.getInstanceLocation()`이 위반 위치를 주므로 schema 출력에서 이름이 나온다.
> 005의 `OBJECT_LIMIT`(schema 없이 손검사) 모양을 그대로 가정하고, 019에는 계약 schema 파일이
> 있다는 사실을 논거에 넣지 않은 것이 원인이다. 층 하나와 트리 순회 코드가 통째로 불필요하다.

## 8. JSON Schema 라이브러리 — networknt, 계약 사본을 리소스로

`game-project-v1.schema.json`(draft 2020-12, `additionalProperties:false` 전면)을 손으로 재구현하면
계약과 서버가 조용히 어긋난다 — fixture 재현 요구(quickstart)가 있는데 drift가 생기면 테스트가
아니라 두 구현의 싸움이 된다. `com.networknt:json-schema-validator`가 2020-12를 지원하므로 계약
파일 그대로 검증한다. schema·fixture는 빌드 시점 계약 사본으로 `src/main/resources/game/`,
`src/test/resources/game/fixtures/`에 복사하고, 원본과의 동일성을 테스트로 고정한다(사본이 조용히
낡는 것 방지).

## 9. 바이트 상한 측정 — Jackson 컴팩트 UTF-8

FE는 `TextEncoder().encode(JSON.stringify(project)).byteLength`(공백 없음, 삽입 순서 키). 서버는
수신 `project` 노드를 Jackson 기본(컴팩트) writer로 직렬화한 UTF-8 길이로 잰다. 키 순서 차이로
바이트가 완전 동일하진 않지만 순서는 길이에 영향이 없고, 경계(2,000,000)에서 수 바이트 오차는
의미가 없다. 계약 표의 "UTF-8 직렬화 바이트 길이" 문구가 이 정의다.

## 10. revision·gameId는 서버 소유 메타 — 보정이 아니라 발급

계약이 "응답의 `gameId`·`revision`과 `project.gameId`·`project.revision`은 반드시 일치"를 요구한다.
FE adapter는 `expectedRevision: project.revision`(옛 값)으로 보내고 응답에서 `revision ==
project.revision`(새 값)을 strict 검사한다 — 즉 **서버가 저장 시 project 안의 revision을 새 값으로
기록해야** 응답 일치가 성립한다. 이것은 FR-034(자동 보정 금지)의 예외가 아니다: revision은
사용자 데이터가 아니라 서버가 발급하는 카운터고, gameId는 경로가 정본이라 불일치는 보정 대신
`MALFORMED_PROJECT`로 거부한다. JSONB 정규화(키 순서·공백)로 저장 문자열의 바이트 동일성은
보장하지 않는다 — 계약이 요구하는 것은 의미 동일과 메타 일치다(005의 무손실 원칙은 값이 불투명
문자열일 때의 것이고, 019는 서버가 구조를 아는 문서다).

## 11. 패키지 구조 — 플랫

리드 초안의 `api/ application/ domain/ persistence/` 계층은 채택하지 않는다. booth(46파일)·user·
wallet·auth 전부 플랫 패키지고, 019 하나만 계층을 갖는 것이 코드베이스 안에서 더 큰 비일관이다.
파일 수(~20)도 계층이 필요한 규모가 아니다. 나중에 파트 전체가 계층화를 결정하면 그때 일괄 이동.

## 12. 동시성 — Draft는 원자 UPDATE, Publish는 revision 선검사 + UNIQUE 최후 방어

Draft: 005의 `insertIfAbsent`(첫 저장 경합 시 패자에게 409) / `updateIfRevisionMatches`(revision
불일치 0-row → 409 + 현재 revision) 패턴 그대로. Publish: 요청 `expectedRevision`을 draft.revision과
먼저 대조해 동시 Publish의 한쪽을 409로 보내고, 남는 좁은 경합은 `UNIQUE(game_id, version_no)`가
막는다(위반 시 트랜잭션 전체 롤백 — 부분 반영 없음 조건과 일치). version_no 채번은 booth처럼
`highestVersionNo + 1`.
