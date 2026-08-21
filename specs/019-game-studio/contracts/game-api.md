# Game Studio API 계약 초안

> 상태: Draft / BE Issue #21 검토 대기
>
> 이 문서는 endpoint 책임과 데이터 흐름만 선행 고정한다. HTTP 상태코드, ETag/If-Match, 보존·캐시 정책은 #21 답변 후 확정한다.

## Authoring

```text
POST /api/v1/games
GET  /api/v1/games/{gameId}/draft
PUT  /api/v1/games/{gameId}/draft
POST /api/v1/games/{gameId}/validate
POST /api/v1/games/{gameId}/publish
GET  /api/v1/games/{gameId}/versions
```

원칙:

- Owner/Editor만 Draft를 조회·저장·검증·Publish한다.
- Guest는 Authoring API를 사용할 수 없다.
- Draft 저장은 `revision` 충돌을 감지하고 조용히 덮어쓰지 않는다.
- Publish는 JSON Schema, ID 참조, Dialogue presentation/복귀, Asset source 정책을 서버에서 재검증한다.
- Publish 성공 시 새 불변 Published Version을 만든다.

## Runtime

```text
GET /api/v1/games/{gameId}/published
```

원칙:

- Draft는 Runtime 공개 endpoint로 노출하지 않는다.
- Published가 없거나 비공개·삭제된 게임은 실행할 수 없다.
- 응답은 `schemaVersion`과 Published Version 식별자를 포함한다.
- Runtime은 지원하지 않는 MAJOR를 거부한다.
- Published GameProject는 Asset binary나 만료 주소를 포함하지 않고 안정적인 Asset reference만 반환한다.

## Asset Boundary

- v1 MVP는 versioned builtin Asset catalog를 사용한다.
- `asset://` resolver와 사용자 upload endpoint, 공개 범위, 삭제·보존 정책은 #21 답변 전 구현하지 않는다.
- 향후 upload를 추가해도 GameVersion JSONB와 Asset binary 저장소를 분리한다.
- Preview/Runtime이 실제 전달 주소를 얻더라도 그 임시 주소를 Draft나 Published JSON에 다시 저장하지 않는다.

## Booth Portal Resolution

```text
GET /api/v1/game-portals/{configId}
```

응답 후보:

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

원칙:

- 요청자의 접근 권한, Booth 상태, Game 공개 상태와 Binding 활성 상태를 서버가 판정한다.
- Unity가 보낸 `boothId`, `objectId`, `configId`는 조회 힌트이며 최종 권한 근거가 아니다.

## MVP 제외

- Coin/Reward 지급
- 경쟁 Ranking
- 클라이언트 점수 기반 서버 정산
- AI 생성 요청
- 사용자 Asset upload·가공
