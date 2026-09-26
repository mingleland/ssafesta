-- 오락기별 전 사용자 TOP 5 랭킹 (S15P21A604-963, GitLab #264).
--
-- 표시 전용이다. spec 019 FR-021 은 클라이언트가 주장한 점수를 검증 없이 쓰는 것을 금지하지만,
-- 2026-09-22 결정으로 보상과 연결되지 않는 랭킹은 그 금지에서 빠졌다(docs/26 결정 기록). 대가로
-- 이 표는 Coin·Reward·Inventory 어디와도 FK 로 연결되지 않는다 — 외래키는 users 하나다.
--
-- 키가 (machine_id, user_id) 이고 game_id 가 없다. 랭킹은 게임기의 것이라는 결정이다. 오락실
-- 캐비닛은 사용자가 게시할 때마다 주인이 바뀌므로(V45, ArcadeSeatService) 게임이 교체돼도 이전
-- 게임에서 딴 점수가 같은 순위표에 남는다. 그것이 의도된 동작이고, 초기화가 필요해지면 지점은
-- ArcadeSeatService.claimOnPublish 하나다.
--
-- machine_id 에 외래키가 없는 것도 같은 이유다. arcade_machine_bindings 는 "지금 무슨 게임이
-- 걸렸나" 를 담는 별개 축이고, 게임이 걸리지 않은 빈 자리도 과거 기록을 들고 있어야 한다.
-- 유효한 machine_id 판정은 app.arcade.machine-ids 화이트리스트가 한다(자리 배정과 같은 목록).
--
-- 복합 기본키가 "사용자당 오락기별 1건" 을 지킨다. 더 높은 점수만 갱신하는 판정은 애플리케이션이
-- 읽고 쓰는 대신 ON CONFLICT ... WHERE 한 문장이 한다(ArcadeScoreRecordRepository).
--
-- user_id 는 ON DELETE CASCADE 다 — V27·V45 가 ON DELETE 를 붙이지 않은 판단에서 의도적으로
-- 벗어난다. 그 판단은 "지우는 쪽이 이 표를 먼저 치우게 두면 어디서 지워지는지가 코드에 남는다"
-- 였는데, 실제로는 GameLifecycleService.hardDelete 가 목록에서 빠뜨려 요청 전체가 롤백된 사고를
-- 낳았다(S15P21A604-681). 랭킹 행은 그 사용자 없이 의미가 없고, 탈퇴가 Score 를 지우는 것은
-- FR-040 이 이미 요구한다 — DB 가 지키게 한다.
CREATE TABLE arcade_score_records (
  machine_id  VARCHAR(64) NOT NULL,
  user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  best_score  INTEGER     NOT NULL,
  achieved_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (machine_id, user_id)
);

-- 열 순서가 정렬 규칙 그대로다. 세 번째 키가 있어야 하는 이유는 동시 등록의 achieved_at 이 같은
-- 시각으로 저장될 수 있기 때문이다 — 그러면 TOP 목록의 행 번호와 /me 의 순위 계산이 서로 다른
-- 답을 낸다. 조회·순위 계산·이 인덱스 세 곳이 같은 순서를 쓴다.
CREATE INDEX ix_arcade_score_records_rank
  ON arcade_score_records (machine_id, best_score DESC, achieved_at ASC, user_id ASC);
