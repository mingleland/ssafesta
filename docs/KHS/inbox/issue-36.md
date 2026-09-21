# #36 [back] spec 005 구현 통보 — Layout 계약·version 분리·오류 봉투 (헌법 24조)
작성: strdeok · reason: mention · 2026-08-21T08:57:01Z
링크: https://github.com/kanghyunsoon/ssafesta/issues/36

## 무슨 일
colosair 가 계약 문서 누락 3건 지적 — ① `rule` 12개 미기재 ② `errors[].objectId` 문서 예시가 `null` 인데 구현은 키 자체가 빠짐 ③ `themeCode` 허용값 미기재.
strdeok "확인했습니다, PR 올리겠습니다" (08:56). PR #46 이 그 뒤 머지됨(제목: 계약 문서 정정 #36).
이슈는 아직 OPEN — 3건이 #46 으로 다 덮였는지가 미확인입니다.

## 내가 답해야 하는 것
- (game 몫이 있다면) 3건 중 Unity 계약에 영향 있는 것 — `rule` 목록·`themeCode` 화이트리스트를 Unity 가 쓰는지
- #46 머지로 3건이 다 닫혔는지 확인해 이슈 종료를 제안할지

## 승인 후 확인할 것
- PR #46 diff 가 `contracts/layout-api.md` 에 rule 12개 표 · objectId 예시 정정 · themeCode 값을 실제로 넣었는지 (3건 전부인지 일부인지)
- #58 이 같은 오류 봉투를 다시 흔들고 있음 — #36 을 닫기 전에 #58 결론과 충돌하지 않는지
- Unity 쪽이 `rule`/`themeCode` 를 파싱하는 코드가 있는지

## 초안 (뼈대만)
- 이 건은 BE↔FE 계약 정정이라 game 이 낼 답은 없을 가능성이 높다 — 먼저 이해관계 확인.
- #46 이 3건을 다 덮었으면 그 사실만 확인해 주고 종료는 strdeok 판단에 맡긴다.
- #58 과 오류 봉투가 겹치므로 닫는 순서에 주의하자는 한 줄만.
