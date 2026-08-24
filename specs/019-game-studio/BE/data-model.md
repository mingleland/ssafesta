# BE Data Model: Game Aggregate

## Aggregate

```text
Game
├── GameDraft 0..1
├── GamePublishedVersion 0..N
└── GamePortalBinding 0..N
```

## Game

| Field | Type | Rule |
|---|---|---|
| id | BIGINT | 내부 PK, 외부 Game API ID |
| owner_user_id | BIGINT | Guest 불가 |
| title | VARCHAR(100) | GameProject title과 Publish 시 검증 |
| visibility | PRIVATE/PUBLIC | 신규 실행 가능 여부 |
| published_version | nullable INTEGER | 현재 공개 versionNo |
| deleted_at | nullable TIMESTAMP | 일반 삭제 soft-delete marker |

`(id, published_version)`은 같은 Game의 Published row만 가리키는 복합 FK다. nullable pointer만 비울 수
있어야 하며 `games.id`까지 `SET NULL`하는 FK를 만들지 않는다.

## GameDraft

| Field | Type | Rule |
|---|---|---|
| game_id | BIGINT | PK/FK, Game당 최대 1개 |
| schema_version | VARCHAR | Project envelope와 일치 |
| revision | INTEGER | non-negative optimistic lock |
| project_json | JSONB | Schema+semantic validation 대상 |
| updated_by_user_id | BIGINT | 인증 사용자 |

## GamePublishedVersion

| Field | Type | Rule |
|---|---|---|
| id | BIGINT | 내부 PK |
| game_id | BIGINT | Game FK |
| version_no | INTEGER | positive, UNIQUE(game_id, version_no) |
| schema_version | VARCHAR | Project envelope와 일치 |
| project_json | JSONB | 생성 후 update 금지 |
| published_by_user_id | BIGINT | 인증 사용자 |

## GamePortalBinding

| Field | Type | Rule |
|---|---|---|
| id | BIGINT | 내부 PK/FK용, 외부 비노출 |
| config_id | INTEGER | UNIQUE NOT NULL CHECK (>0), sequence START 1 |
| booth_id | BIGINT | Booth FK |
| object_id | VARCHAR | Layout의 stable objectId |
| game_id | BIGINT | Game FK |
| enabled | BOOLEAN | 실행 가능성의 한 조건 |

`config_id` wire 범위는 `1..2147483647`; 0은 Unity에서 미연결 sentinel이라 발급하지 않는다.
Unique `(booth_id, object_id)`를 둔다.

## Lifecycle

```text
DRAFT(revision N) → DRAFT(revision N+1)
DRAFT(valid) → PUBLISHED(version M) append + pointer=M
ACTIVE → SOFT_DELETED
MEMBER_WITHDRAWAL → HARD_DELETED
```

soft delete는 새 Published/Portal 진입을 차단하되 Published 이력을 보존한다. hard delete는 관련 데이터와
파일/Asset/표시용 Score를 삭제한다.
