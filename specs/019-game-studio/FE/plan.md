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
2. **완료 — Authoring shell**: Scene list, Tile/Object canvas, typed inspector, Dialogue editor, 한국어 guide와 6종 템플릿을 하나의 store에 연결했다.
3. **완료 — Reference Renderer/Preview**: TOP_DOWN/PLATFORMER renderer, builtin/local Asset resolver, same-origin local Preview route를 같은 Runtime core에 연결했다.
4. **Backend integration**: [BE plan](../BE/plan.md)의 Draft/Publish API가 준비되면 revision conflict,
   validation error, Published loader를 연결한다.
5. **Portal integration**: 독립 URL 수직 흐름을 먼저 통과한 뒤 `BOOTH_GAME_INTERACT`와 overlay lifecycle을 연결한다.
6. **확장**: Backend Asset upload를 안정 reference adapter로 연결하고, AI 제작 보조는 사용자 승인 patch로만 추가한다.

## Gates

- #33은 합의 완료: 새 진입은 REST 조회에서 차단하고 이미 로드된 무보상 로컬 세션은 종료까지 허용한다.
- #34는 wire/DB 계약 합의 완료: `configId`는 signed Int32 `1..2147483647`, `0` 금지다.
- #35의 로컬 수직 구현안은 반영했다. Production Published parity와 embedded Preview CSP가 필요해질 때만 후속 결정한다.

## Verification

- 계약 fixture와 FE unit test가 같은 오류 코드·상태 전이를 검증한다.
- Edit/Play route가 별도 lazy chunk로 빌드되는지 확인한다.
- Preview와 Published Runtime에 같은 GameProject를 넣어 최종 상태가 같은지 E2E로 확인한다.
- Unity와 Backend가 없어도 최소 key→door→dialogue 게임을 제작·완료할 수 있어야 한다.
