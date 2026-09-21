-- 오락실 자리 배정 (S15P21A604-942, GitLab #256).
--
-- V27 의 바인딩은 운영자가 직접 넣는 시드였다. 이제 사용자가 게시하면서 자리를 잡으므로
-- 잡은 사람을 남긴다.
--
-- owner_user_id 는 nullable 이고, 그 NULL 이 "운영자가 걸어 둔 기계" 를 뜻한다. 광장 오락기처럼
-- 사람의 것이 아닌 고정물이 이미 있고, 그 행들에 누군가를 억지로 적으면 1인 2대 한도가 엉뚱한
-- 사람 앞으로 세어진다. 그래서 한도 계산과 해제는 owner_user_id IS NOT NULL 인 행만 본다 —
-- 사용자는 운영자 기계를 회수할 수 없다.
--
-- 게임 소유자를 타고 세지 않은 이유는 셈이 흔들리기 때문이다. 게임이 양도되면 이전 주인의 한도가
-- 조용히 풀리고 새 주인은 세 대를 들 수 있다. 자리는 게임이 아니라 잡은 사람에게 묶인다.
--
-- ON DELETE 를 붙이지 않는 것은 game_id 와 같은 판단이다 (V27). 지우는 쪽이 이 표를 먼저
-- 치우게 두면 어디서 지워지는지가 코드에 남는다.

ALTER TABLE arcade_machine_bindings ADD COLUMN owner_user_id BIGINT REFERENCES users(id);

-- 한도 계산이 이 컬럼으로 센다. 부분 인덱스인 것은 운영자 시드 행이 셈에 들어오지 않기 때문이다.
CREATE INDEX ix_arcade_machine_bindings_owner
  ON arcade_machine_bindings (owner_user_id)
  WHERE owner_user_id IS NOT NULL;
