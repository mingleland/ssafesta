-- spec 005 / #19 ④ (2026-08-21 확정): 셸 프리팹이 1종이라 layout template도 1종으로 정리한다.
-- DEFAULT를 화이트리스트에서 빼면서 기존 저장분도 함께 옮긴다 — 남겨 두면 "DEFAULT로 저장된
-- 부스는 어느 셸인가"라는 답 없는 질문이 생기고, 옛 Draft가 다음 저장에서 이유 없이 거부된다.
--
-- 공개본(published_versions)은 불변이 원칙이지만 이것은 배포 전 스키마 정합 정정이다:
-- 값의 의미(셸 1종)는 그대로고 이름만 바뀐다. facade의 themeCode 'DEFAULT'는 다른 도메인이므로
-- 건드리지 않는다.

UPDATE booth_layout_drafts
   SET layout_json = jsonb_set(layout_json, '{template}', '"PROJECT_EXHIBITION"')
 WHERE layout_json->>'template' = 'DEFAULT';

UPDATE booth_layout_published_versions
   SET layout_json = jsonb_set(layout_json, '{template}', '"PROJECT_EXHIBITION"')
 WHERE layout_json->>'template' = 'DEFAULT';
