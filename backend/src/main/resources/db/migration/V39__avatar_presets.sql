-- GitLab #237 / S15P21A604-858 — 회원별 아바타 외형 프리셋 3개.
-- avatar_code 는 users.avatar_code 와 같은 불투명 문자열이다. VARCHAR 길이 제한을 두면
-- Unity 인코더가 항목명을 더하는 순간 T-24 형태의 무반응 저장 실패를 다시 만든다.
CREATE TABLE avatar_presets (
  user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 3),
  avatar_code TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, slot)
);
