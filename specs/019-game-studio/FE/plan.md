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
2. **완료 — Authoring shell**: Scene list, Tile/Object canvas, 간단/고급 Inspector, Dialogue editor, 프로젝트별 한국어 guide와 실제 플레이 화면이 보이는 6종 템플릿을 하나의 store에 연결했다. Canvas는 선택 영역·다중 선택·묶음 이동·Event 보존 복제·안전 삭제·화면 이동·격자 전환을 지원하고 Scene 크기를 기존 Tile/Object와 함께 변경한다.
3. **완료 — Reference Renderer/Preview**: TOP_DOWN/PLATFORMER renderer, builtin/local Asset resolver, same-origin local Preview route를 같은 Runtime core에 연결했다.
4. **완료 — Backend adapter**: revision-aware Draft/Publish client, Published loader, schema guard,
   충돌 복구·검증 오류 UI를 구현했다. [BE plan](../BE/plan.md)의 endpoint가 준비되면 환경 플래그로 전환한다.
5. **완료 — Portal FE integration**: `BOOTH_GAME_INTERACT`→Portal resolver→GAME overlay와 close/fail lifecycle을 연결했다. 서버 resolver가 준비되면 브라우저 E2E만 수행한다.
6. **진행 중 — 운영 확장**: 적 처치 수·타이머·점수 임계 승리 조건은 v1.1 FE candidate와 Mock Runtime에 구현했다. [#78](https://github.com/kanghyunsoon/ssafesta/issues/78)에서 BE validator·AI 허용 출력 계약을 확정하고, #69의 Backend Asset upload를 stable reference adapter로 연결한다. AI 제작 보조는 사용자 승인 patch로만 추가하며 #81의 Published Session/Coin은 GameProject 밖 서버 권위 adapter로 연결한다.

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
- Preview의 `?source=local&perf=1` 진단은 활성 PC 탭에서 55~60fps를 확인한다. 백그라운드 탭의 브라우저 throttling 결과는 합격 근거로 사용하지 않는다.
- 프로젝트별 첫 방문 guide, 장르와 무관한 5단계 tutorial, 검색 가능한 시각 재료함, 간단/고급 Inspector, Object Layer 검색·잠금·편집 숨김·z-index 정렬을 PC 제작 UX 기준선으로 유지한다.
- 760~1039px compact 창에서는 문서 전체 가로 스크롤을 만들지 않고, Shift+F 집중 모드로 양쪽 panel을 숨겨 큰 맵을 편집한다. 권장 작업 폭은 1280px 이상이다.
- 템플릿 테스트는 제목 차이가 아니라 Scene type, Object preset, Event/Dialogue Action 프로필이 6종 모두 구분되는지 검증한다.
- 브라우저 제작 QA는 `선택 2개 → 동작 포함 복제 → 묶음 이동`, 선택/화면 이동/격자 상태, 1024px/800px 도구 배치를 포함한다.
