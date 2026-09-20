-- V27__arcade_machine_bindings.sql 되돌리기 (S15P21A604-602).
--
-- 표 하나와 그 인덱스만 만들었으므로 표를 지우면 끝이다. 인덱스는 표와 함께 사라진다.
-- 운영자가 넣은 바인딩 행도 같이 사라진다 — 되돌린 뒤 다시 적용하면 행을 다시 넣어야 한다.
DROP TABLE IF EXISTS arcade_machine_bindings;
