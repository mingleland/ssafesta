# S15P21A604-156 — Game Studio 성능 실측 결과

> 검증일: 2026-08-27 | 검증자: 박준우

## 완료 조건 대조

| 완료 조건 | 실측 | 판정 |
|---|---|---|
| 편집 조작 p95 100ms 이하 | `editorPerformance.test.ts` — 500-object Scene에서 오브젝트 이동 반복, 최대값 <100ms 어설션 통과 (3회 실행 모두 pass) | ✅ 통과 |
| 활성 탭 55fps 이상 | `/app/games/9302/edit?fixture=max` → 플레이 테스트(perf=1), Claude in Chrome 실측 3회: **80 / 75 / 77 fps** | ✅ 통과 |

## 측정 방법

1. `npx vitest run src/game-studio/__tests__/unit/editorPerformance.test.ts` — 3회 반복 실행, 매번 2 tests passed
2. 최대 fixture(100×100, Object 500/500, Tile 10,000/10,000)를 로드한 뒤 "성능 점검" 활성화 상태로 플레이 테스트 진입, 탭을 최전면에 두고 5~10초 후 오버레이 값을 3회(시간 간격을 두고) 기록

## 실측 원자료 (3회)

| 회차 | FPS | p95 frame time | 느린 frame 비율 |
|---|---|---|---|
| 1 | 80 | 41.7ms | 11.1% |
| 2 | 75 | 50.1ms | 12% |
| 3 | 77 | 49.9ms | 11.7% |

## 후속 발견 — 별도 이슈로 분리

FPS는 목표(55 이상)를 충분히 넘지만, p95 frame time·느린 frame 비율은 이 성능 오버레이 기능 자체의 설계 목표
(`specs/019-game-studio/FE/quickstart.md` #18 — p95 18.2ms 이하, 느린 frame 5% 이하)를 3회 모두 일관되게
2.3~2.7배 초과함. 156의 완료조건 자체(p95 100ms, 55fps)는 아니지만 재현되는 병목이라 별도 Bug로 분리함.

→ **[S15P21A604-263](https://ssafy.atlassian.net/browse/S15P21A604-263)** [BUG][FE] Game Studio 최대 fixture에서 p95 frame time·끊김 비율 목표 초과

## 결론

**156의 완료조건 2건 모두 실측으로 충족.** 병목 이슈는 263으로 분리 완료.
