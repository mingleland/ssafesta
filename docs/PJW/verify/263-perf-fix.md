# S15P21A604-263 — 최대 fixture 성능 병목 원인 규명·수정

> 작업일: 2026-08-27 | 작업자: 박준우 | 대상: `fix/S15P21A604-263-perf-fixture-bottleneck` 브랜치

## 배경

[S15P21A604-156](https://ssafy.atlassian.net/browse/S15P21A604-156) 검증 중 발견한 후속 이슈.
`?fixture=max`(100×100, Object 500/500, Tile 10,000/10,000)에서 FPS는 목표(55 이상)를 넘지만
p95 frame time·느린 frame 비율이 오버레이 자체 설계 목표(18.2ms·5%)를 3회 모두 2.3~2.7배
초과했다.

## 원인 규명

`ReferenceGamePlayer.tsx`의 타일맵 렌더링(`scene.tileLayers.map(layer => layer.data.map(...))`)이
**타일마다** `resolveTilesetVisual(project.assets.find(...), assetUrls)`를 호출하고 있었다.
타일셋은 레이어당 하나로 고정인데, 그 lookup을 타일 개수(레이어당 최대 10,000개)만큼
반복 실행한 것 — 명백한 비효율이다. 게다가 이 전체 렌더 블록이 메모이제이션 없이
컴포넌트 최상위에 있어서, 120ms 주기의 world tick이나 플레이어 이동 등 **런타임 상태가
바뀔 때마다** 무관한 정적 타일맵까지 매번 다시 계산됐다.

새로 작성한 회귀 테스트([referenceGamePlayerTileMemo.test.tsx](../../../festa-frontend/src/game-studio/__tests__/unit/referenceGamePlayerTileMemo.test.tsx))로
직접 계측한 수치:

| 시점 | `resolveTilesetVisual` 호출 횟수 (레이어 1개 fixture) |
|---|---|
| 수정 전 — 초기 렌더 1회만 | **10,000회** |
| 수정 후 — 초기 렌더 1회 | **1회** |
| 수정 후 — 이동 3회로 인한 추가 리렌더 3회 포함 누적 | **1회** (그대로) |

DOM 노드 개수·참조 동일성으로는 이 차이가 드러나지 않는다 — React가 `key`로 리스트 재조정을
하기 때문에 memo 유무와 무관하게 같은 DOM 노드가 재사용된다. 실제 병목은 그 노드를 만들기
위해 매 렌더마다 다시 실행되는 JS 계산(타일마다의 배열 `find`)이었다.

## 수정

- `ReferenceGamePlayer.tsx`에 `TileLayers`라는 `memo` 컴포넌트를 분리했다. `layers`·`sceneWidth`·
  `assets`·`assetUrls`만 props로 받아, `project`가 안 바뀌는 한(즉 런타임 상태만 바뀌는 한)
  리렌더를 스킵한다.
- 타일셋 lookup(`resolveTilesetVisual`)을 타일 루프 밖, 레이어 루프 안으로 옮겨 레이어당
  한 번만 계산한다.
- 렌더링 결과(HTML 구조·CSS)는 그대로다 — 순수 위치 이동 + 메모이제이션이라 시각적 회귀 없음.

## 검증

| 항목 | 결과 |
|---|---|
| `npx tsc -b` | 통과 |
| `npm run lint` (oxlint) | 통과 (경고 0) |
| `npx vitest run src/game-studio` | 27 files / **118 tests** 전부 통과 (신규 회귀 테스트 1건 포함, 기존 117건 회귀 없음) |
| Claude in Chrome 시각 확인 | `/app/games/9302/edit?fixture=max` → 플레이 테스트 진입, 500 오브젝트·10,000 타일 정상 렌더, 콘솔 에러 0건 |
| 신규 회귀 테스트 (`referenceGamePlayerTileMemo.test.tsx`) | 수정 전 코드로 되돌리면 실패(10,000회 호출)하는 것을 직접 확인 — 유의미한 회귀 방어임을 검증 |

## 실측 재검증 — 완료 ✅

성능 오버레이가 `document.visibilityState==='visible'`일 때만 측정되는 제약(156 때와 동일)이
있어 박준우가 직접 탭을 최전면에 두고 측정했다. 절차는 156과 동일: `/app/games/9302/edit?fixture=max`
→ 성능 점검 활성화 → 플레이 테스트(`?perf=1`) 진입 → 탭 최전면 두고 5~10초 대기 → 오버레이 값
3회(시간 간격을 두고) 기록.

| 회차 | FPS | p95 frame time | 느린 frame 비율 |
|---|---|---|---|
| 1 | 119 | 8.6ms | 0% |
| 2 | 119 | 8.6ms | 0% |
| 3 | 119 | 8.5ms | 0% |

| 완료 조건 | 목표 | 실측 | 판정 |
|---|---|---|---|
| FPS | 55 이상 | 119 (3회 동일) | ✅ 통과 |
| p95 frame time | 18.2ms 이하 | 8.5~8.6ms | ✅ 통과 (목표의 절반 이하) |
| 느린 frame 비율 | 5% 이하 | 0% (3회 동일) | ✅ 통과 |

156 실측치(FPS 75~80, p95 41.7~50.1ms, 끊김 11.1~12%) 대비 p95는 약 5~6배 감소, 끊김은
완전히 해소됐다. 타일마다 10,000회씩 반복되던 tileset lookup이 실제 병목의 핵심이었다는
원인 규명이 실측으로 확정됐다.

## 결론

**완료 조건 3건(원인 규명·재측정 통과·Regression 없음) 전량 충족.** 목표치 재조정 논의는
불필요.
