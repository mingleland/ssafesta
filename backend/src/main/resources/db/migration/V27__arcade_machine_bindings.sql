-- 오락기 1대가 어떤 Published 게임을 실행하는지 (S15P21A604-602, GitLab #56 안 1).
--
-- Unity 는 machine_id 만 안다 — 씬이 정한 canonical id 이고 gameId 는 모른다 (spec 019 FR-017).
-- 큐레이션이 바뀌어도 Unity 빌드가 바뀌지 않게 하려는 것이 그 분담의 이유이고, 그래서 해석은
-- 서버가 매 요청 한다.
--
-- machine_id 가 그대로 PK 다. 오락기 1대 = 게임 1개 고정 바인딩이라(2026-09-10 결정, docs/26
-- 결정 기록) 대리 키 + UNIQUE 로 두면 같은 사실을 두 곳에서 지키게 된다.
--
-- 부스 내부 GAME_PORTAL 경로와 다른 표다. 그쪽은 임대 부스에 속하고 configId 로 해석되며
-- 지금 보류 상태다(S15P21A604-158·-204). 이 표가 다루는 오락기는 월드 고정물이라 임대·소유권
-- 판정이 없다 — booth_id 칼럼이 없고 BOOTH_LEASE_EXPIRED 를 낼 자리도 없는 이유다.
--
-- v1 은 운영자가 행을 직접 넣는다(관리자 UI 는 범위 밖). machine_id 실값과 대수는 게임 파트가
-- 오락기를 배치한 뒤 통보하므로 이 마이그레이션은 표만 만들고 행은 넣지 않는다. 현재 씬 값은
-- plaza-arcade-01·plaza-arcade-02 이지만 기계가 게임 전용 부스로 이전될 예정이라, 지금 시드하면
-- 이전 뒤 죽은 행이 남는다.
--
-- FK 에 ON DELETE 를 걸지 않은 것은 games 를 참조하는 기존 세 표(V13 game_drafts·
-- game_published_versions, V18 game_assets)와 같은 선택이다. 탈퇴 하드삭제(spec 019 FR-040)는
-- AccountDeletionService 가 자식 표를 순서대로 지우는 방식이고 이 표도 거기 한 줄로 들어간다.
-- CASCADE 를 걸면 그 파일만 읽어서는 무엇이 함께 지워지는지 알 수 없게 된다.
CREATE TABLE arcade_machine_bindings (
  machine_id VARCHAR(64) PRIMARY KEY,
  game_id    BIGINT NOT NULL REFERENCES games(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 탈퇴가 지울 행을 game_id 로 찾는다. PK 가 machine_id 라 이 인덱스가 없으면 순차 탐색이다.
CREATE INDEX ix_arcade_machine_bindings_game_id ON arcade_machine_bindings (game_id);
