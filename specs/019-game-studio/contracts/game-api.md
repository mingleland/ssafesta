# Game Studio API 계약

> 상태: Draft v0.3 — BE Issue #21과 제품·Portal 계약 #33·#34 반영. 구현 오류 코드명은 #48에서 고정한다.

## 공통 오류 봉투

기존 FESTA 오류 봉투를 재사용한다. 예시는 다음과 같다.

```json
{
  "code": "GAME_REVISION_CONFLICT",
  "message": "다른 편집 내용이 먼저 저장되었습니다.",
  "details": {
    "currentRevision": 8
  }
}
```

서버 예외 클래스명, stack trace, Provider 원문을 응답하지 않는다.

## Authoring

```text
POST /api/v1/games
GET  /api/v1/games/{gameId}/draft
PUT  /api/v1/games/{gameId}/draft
POST /api/v1/games/{gameId}/publish
GET  /api/v1/games/{gameId}/versions
```

Owner/Editor만 호출할 수 있고 Guest는 Authoring API를 사용할 수 없다.

### Draft 저장

요청은 `expectedRevision`과 전체 GameProject snapshot을 포함한다. 서버는 구조·schema·상한을 검증하되
제작 중 존재하지 않는 참조의 소유권·완성도를 이유로 Draft 저장을 막지 않는다.

```json
{
  "expectedRevision": 7,
  "project": "<GameProject v1 object>"
}
```

- 성공: revision 8의 새 Draft snapshot 반환
- 충돌: HTTP 409 + `GAME_REVISION_CONFLICT` + 현재 revision
- 좌표 clamp, 알 수 없는 필드 삭제, 참조 치환 같은 자동 보정 금지
- 별도 `/validate`가 후속으로 생겨도 Draft 저장과 Publish에서 각각 다시 검증

### Publish

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
- Published 내용은 불변이므로 version별 ETag와 긴 `max-age`를 사용할 수 있다.
- Runtime은 지원하지 않는 MAJOR를 거부한다.
- Asset binary나 만료 주소가 아니라 안정적인 Asset reference만 반환한다.
- route 진입 시 한 번 조회해 공개 상태를 판정한다. Game Studio 전용 socket은 사용하지 않는다.
- 비공개 전환 뒤 이미 GameProject를 로드한 무보상 로컬 세션은 완료까지 허용하되 새 진입은 거부한다.

## Asset Boundary

- MVP는 Game Studio가 소유하는 versioned `builtin://` Asset catalog를 사용한다.
- 서버가 관리하는 `asset://` reference를 추가하더라도 metadata/resolver와 binary 저장소는
  Draft/Published JSONB와 분리한다.
- MVP에 사용자 upload endpoint를 추가하지 않는다. 업로드·용량·검사·보존·탈퇴 삭제는 별도 spec이다.
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

## MVP 제외

- Coin/Reward 지급
- 경쟁 Ranking과 MVP score endpoint. 표시 전용 Ranking은 P1 별도 범위
- 클라이언트 점수 기반 서버 정산
- AI 생성 요청
- 사용자 Asset upload·가공
