# FE Implementation Plan: FESTA Game Studio

**Date**: 2026-08-23 | **Shared spec**: [../spec.md](../spec.md) | **Shared contracts**: [../contracts/](../contracts/)

## Summary

Game Studio는 기존 `festa-frontend` 안의 lazy-loaded 독립 모듈로 구현한다. 제작기와 2D Runtime은
동일한 GameProject v1 계약과 순수 TypeScript 상태 전이 코어를 공유한다. 현재 로컬 수직 구현은
`TOP_DOWN + PLATFORMER + DIALOGUE`를 같은 계약 위의 reference renderer/physics adapter로 실행한다.
`PUZZLE`은 새 Runtime 종류가 아니라 제한형 Component/Event 조합으로 먼저 제공한다.

Unity WebGL은 게임을 실행하지 않는다. 독립 URL과 Local Preview가 기본 실행 경로이고, 부스 연동 시에만
Unity가 기존 상호작용을 React Host로 전달한다. React는 Portal Resolver를 조회한 뒤 같은 Web Runtime
overlay를 연다.

## Technical Context

- TypeScript `~6.0.2`, React 19.2, Vite 8.2, React Router 7.18, TanStack Query 5.101
- Game Studio 전용 Vitest unit/contract/integration test
- `/app/games/:gameId/edit`, `/app/games/:gameId/play` lazy route
- same-origin local Preview route; 인증 토큰을 GameProject나 Preview payload에 포함하지 않음
- 사용자 JavaScript·표현식 실행 금지
- JSON 2,000,000 bytes, Scene 50, Scene당 Object 500/Event 300, Asset 300; Runtime 60fps 목표

## Source Boundary

```text
festa-frontend/src/game-studio/
├── app/          # edit/play route entry와 API adapter
├── contracts/    # GameProject TypeScript type/guard
├── core/         # renderer-independent state/event interpreter
├── studio/       # Scene/Object/Map/Dialogue/Event editor
├── runtime/      # scene lifecycle와 renderer adapters
├── assets/       # versioned builtin image/tile/portrait resources
├── host/         # FESTA overlay·Portal adapter
└── __tests__/
```

기존 인증, `shared/api/client.ts`, 전역 오류 봉투를 재사용한다. 일반 FESTA route는 Game Studio chunk를
로드하지 않아야 한다. `festa-unity/`와 `backend/`는 FE 구현 브랜치에서 수정하지 않는다.

## Implementation Phases

1. **완료 — 순수 코어**: GameProject type/guard, Runtime state, Condition/Action/Event, Dialogue,
   undo/redo, reversible preset recipe, lazy edit/play entry를 PR #47에 구현했다.
2. **완료 — Authoring shell**: Scene list, Tile/Object canvas, 간단/고급 Inspector, Dialogue editor, 프로젝트별 한국어 guide와 실제 플레이 화면이 보이는 6종 템플릿을 하나의 store에 연결했다. Canvas는 선택 영역·다중 선택·묶음 이동·Event 보존 복제·안전 삭제·화면 이동·격자 전환을 지원한다. Scene은 내부 참조를 보존해 전체 복제·정렬할 수 있고 Object/Event 묶음은 Scene 사이에 복사·붙여넣을 수 있다. Tile은 브러시·사각형·연결 영역 채우기·스포이드를 제공하며 Collider 가이드를 선택적으로 표시한다. Scene 크기는 기존 Tile/Object와 함께 변경한다. World Canvas는 32px cell 기준 실제 작업 공간, 미니맵, 전체 맞춤, 1:1, 선택 위치 이동을 함께 제공한다. 25% 이하 전체 보기에서는 최대 10,000 Tile을 단일 Canvas로 합성하고 편집 배율에서는 scroll viewport 주변 Object/Tile만 overscan 렌더링한다. 레이어 검색에서 고른 원거리 Object는 캔버스가 자동으로 중앙 탐색하며, 500개 레이어 목록은 80개 단위로 점진 표시한다. Dialogue 흐름은 도달 불가·결과 미설정 Node를 파생 분석하고, Project 데이터는 시작·완료·상호작용·대화·Scene·Asset 6축 완성도 체크를 제공한다. builtin Sprite Sheet는 clip별 방향·fps 재생을 확인할 수 있다. 이 세 기능은 GameProject/API를 늘리지 않는 Editor 파생 UX다. 저장 전 변경은 GameProject/API 밖의 로컬 복구 저널에 800ms 지연으로 보관하고 명시 저장 성공 때 제거한다.
3. **완료 — Reference Renderer/Preview**: TOP_DOWN/PLATFORMER renderer, builtin/local Asset resolver, same-origin local Preview route를 같은 Runtime core에 연결했다.
4. **완료 — Backend adapter**: revision-aware Draft/Publish client, Published loader, schema guard,
   충돌 복구·검증 오류 UI를 구현했다. [BE plan](../BE/plan.md)의 endpoint가 준비되면 환경 플래그로 전환한다.
5. **완료 — Portal FE integration**: `BOOTH_GAME_INTERACT`→Portal resolver→GAME overlay와 close/fail lifecycle을 연결했다. 서버 resolver가 준비되면 브라우저 E2E만 수행한다.
6. **진행 중 — 운영 확장**: 적 처치 수·타이머·점수 임계 승리 조건은 v1.1 FE candidate와 Mock Runtime에 구현했다. [GitLab #78](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/78)에서 BE validator·AI 허용 출력 계약을 확정하고, [GitLab #69](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/69)의 Backend Asset upload를 stable reference adapter로 연결한다. AI 제작 보조는 사용자 승인 patch로만 추가하며 [GitLab #81](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/81)의 Published Session/Coin은 GameProject 밖 서버 권위 adapter로 연결한다.

## Gates

- #33은 합의 완료: 새 진입은 REST 조회에서 차단하고 이미 로드된 무보상 로컬 세션은 종료까지 허용한다.
- #34는 wire/DB 계약 합의 완료: `configId`는 signed Int32 `1..2147483647`, `0` 금지다.
- #35의 로컬 수직 구현은 PR #63으로 병합·종료됐다. Production Published parity는 #48·#55, Booth overlay는 #56에서 구현하며 embedded Preview CSP는 iframe transport를 실제 채택할 때만 별도 결정한다.

## Verification

- 계약 fixture와 FE unit test가 같은 오류 코드·상태 전이를 검증한다.
- Edit/Play route가 별도 lazy chunk로 빌드되는지 확인한다.
- Preview와 Published Runtime에 같은 GameProject를 넣어 최종 상태가 같은지 E2E로 확인한다.
- Unity와 Backend가 없어도 최소 key→door→dialogue 게임을 제작·완료할 수 있어야 한다.
- 500 Object Scene의 편집 commit은 자동 성능 테스트에서 100ms 미만이어야 한다.
- 큰 맵 Canvas는 Scene 비율을 좁은 카드에 압축하지 않고 cell 크기×zoom으로 계산한다. scroll/resize마다 보이는 grid 범위를 계산해 2칸 overscan만 렌더링하고, 화면 밖 Object와 Tile DOM은 만들지 않는다.
- 전체 맞춤이 25% 이하이면 TileLayer는 단일 Canvas overview로 합성하고 1:1 복귀 시 DOM viewport 가상화로 전환한다. 미니맵·전체·1:1·선택 위치 이동은 작은 맵과 큰 맵에 같은 규칙으로 제공한다.
- Dialogue 흐름과 Project 완성도 점검은 현재 GameProject의 파생 정보여야 하며 Draft/Published JSON·Backend DTO를 변경하지 않는다. Sprite clip 선택도 Editor 미리보기 상태로만 유지한다.
- 현행 shared schema의 TileLayer `maxItems=10,000`이 유지되는 동안 Authoring은 가로×세로 10,000칸을 넘는 크기 적용을 사전에 막는다. PLATFORMER 200×100 지원 여부는 [GitLab #101](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/work_items/101)에서 Backend validator·DB 비용을 합의한 뒤 schema와 함께 변경한다.
- 편집기의 `성능 점검`은 활성 PC 탭에서 FPS 55 이상, p95 frame 18.2ms 이하, 느린 frame 5% 이하를 함께 확인한다. 비활성 탭에서는 측정을 멈추며 브라우저 throttling 결과를 합격 근거로 사용하지 않는다.
- 저장하지 않은 변경 뒤 화면을 다시 열면 로컬 복구본의 시각·복구/폐기/JSON 보관 선택이 나타나고, 명시 저장 뒤에는 같은 안내가 재발하지 않아야 한다.
- 프로젝트별 첫 방문 guide, 장르와 무관한 5단계 tutorial, 검색 가능한 시각 재료함, 간단/고급 Inspector, Object Layer 검색·잠금·편집 숨김·z-index 정렬을 PC 제작 UX 기준선으로 유지한다.
- 760~1039px compact 창에서는 문서 전체 가로 스크롤을 만들지 않고, Shift+F 집중 모드로 양쪽 panel을 숨겨 큰 맵을 편집한다. 권장 작업 폭은 1280px 이상이다.
- 템플릿 테스트는 제목 차이가 아니라 Scene type, Object preset, Event/Dialogue Action 프로필이 6종 모두 구분되는지 검증한다.
- 브라우저 제작 QA는 `Scene 전체 복제·정렬 → Object/Event Scene 간 복사 → Tile 사각형·연결 영역 채우기·스포이드 → Collider 표시`, 선택 2개·묶음 이동, 선택/화면 이동/격자 상태, 1024px/800px 도구 배치를 포함한다.
