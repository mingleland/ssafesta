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
