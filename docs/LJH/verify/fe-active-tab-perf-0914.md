# `#73` 활성 탭 성능 재측정 — 2026-09-14 (S15P21A604-518)

2026-09-08 회귀에서 `BLOCKED_AT_TAB_NOT_VISIBLE` 로 남은 것을 다시 시도했다.
**전제는 이번에 섰고, 측정 수단이 없어서 막혔다.** 사유가 09-08 과 다르므로 그대로 적는다.

## 결과 요약

```text
측정 전제(visible)        SATISFIED    ← 09-08 의 막힌 사유가 해소됐다
-524 DPR cap             PASS         ← 09-08 이 못 하던 실물 검증을 했다
런타임 프레임·draws·tris  BLOCKED_AT_NO_TIMING_API   (새 사유)
에디터 interaction p95    BLOCKED_AT_NO_TIMING_API   (같은 사유)
5인 사용성                NOT_EXECUTED (변동 없음 — 사람이 필요하다)
```

## 0. 환경

```text
FE          vite 5176 (worktree festa-fe-world-ui, origin/develop)
Unity WebGL 127.0.0.1:8001 = ssafesta-external/share/festa-webgl-dev
백엔드       Spring 8080 (local) · Postgres 5432
진입         /login → 게스트로 둘러보기 → /app/world
```

⚠️ **인계문이 가리킨 `share/festa-webgl-release-e9f2067e` 는 디스크에 없다.** 남아 있는 것은
`festa-webgl-dev`·`festa-webgl-dev-0907`·`festa-webgl-dev-664bc74e` 셋이고, 09-08 회귀와 같은
`festa-webgl-dev` 를 썼다. 그래서 이 문서의 수치는 **09-08 과 같은 빌드 기준**이고 최신 릴리스
기준이 아니다. 8001 은 죽어 있어 다시 띄웠다(`ThreadingTCPServer` + CORS + `.unityweb` 에
`Content-Encoding: br`).

## 1. 측정 전제 — 이번엔 성립했다

```text
document.visibilityState   "visible"
```

09-08 의 막힌 지점이 *"사람이 Chrome 창을 전면으로 올려야 한다"* 였는데 그 조건은 섰다.
Unity 도 실제로 돌았다 — 캔버스가 생기고 월드가 그려지며 콘솔 `error` 0건이다.

## 2. `-524` DPR cap — PASS, 그리고 이번엔 실물이다

```text
window.devicePixelRatio      1.75            ← 상한 1.5 를 넘는 화면
canvas CSS 크기               1280 × 720      (0.922 MP)
canvas backing buffer        1920 × 1080     (2.074 MP)
effectiveDpr X / Y           1.5000 / 1.5000
판정 (effectiveDpr ≤ 1.5)     PASS
```

**09-08 이 못 했던 것을 했다.** 그때는 화면 DPR 이 정확히 1.5 라 *"상한이 걸렸다"* 와 *"걸릴
필요가 없었다"* 가 구별되지 않아 `resolveDevicePixelRatio()` 에 값을 주입해 갈랐다. 이번 화면은
1.75 라 **상한이 실제로 구속하는 조건**이고, 구속이 관측됐다.

상한이 없었다면 backing buffer 는 2240 × 1260(2.822 MP)이어야 한다. 실제는 1920 × 1080 이라
**픽셀 26.5% 감소**다. 게임 파트 09-08 실측(실효 DPR 2.0 → 1.5, 픽셀 −44%)과 방향이 같다.

## 3. 런타임 성능 — `BLOCKED_AT_NO_TIMING_API`

```text
1. 재현        월드 진입 후 rAF 프레임 수를 3초간 센다
2. 마지막 성공  Unity 구동 · 캔버스 생성 · 월드 렌더 · 콘솔 error 0건
3. 최초 실패    측정 스코프에서 requestAnimationFrame 과 performance 가 undefined 다
4. 원문        { "raf": "undefined", "perf": "undefined",
                 "visibility": "visible", "dpr": 1.75, "now": 1789361397418 }
5. 관련        같은 스코프에서 document.hasFocus 도 함수가 아니다
6. 다음 조건    프레임을 셀 수 있는 실행 경로. 브라우저 개발자 도구를 사람이 직접 열거나,
               계측을 앱에 심어야 한다
```

**09-08 과 다른 사유다.** 그때는 탭이 숨어 있어 프레임이 0 이었고, 이번에는 탭이 보이는데
**프레임을 셀 도구가 없다.** 읽기 전용 평가 스코프가 `Date.now()`·`document.visibilityState`·
`window.devicePixelRatio` 는 주지만 타이밍 API 는 주지 않는다.

판별 축인 **draws·tris 증분은 더더욱 못 읽는다** — Unity Profiler 값이라 브라우저 DOM 에
노출되지 않는다. `-518` 이 이 축을 고른 이유(숨은 탭에서 프레임 시간이라는 값이 성립하지
않는다)는 그대로 유효하고, 그 축을 읽으려면 Unity 쪽 계측이 필요하다.

**앱에 계측 코드를 심지 않았다.** 측정하려고 측정 대상을 바꾸는 것이고, 그렇게 넣은 코드는
회차가 끝나도 남는다.

## 4. 에디터 interaction p95 — 같은 사유로 막혔다

런타임 FPS 와 **다른 지표**라 따로 재야 하는데(09-08 §5), 시간 측정이 `performance` 에 의존해서
같은 벽에 걸린다. 런타임 수치와 한 표에 섞지 않는다는 원칙은 그대로 둔다.

## 5. 5인 사용성 — `NOT_EXECUTED`, 자동 대체하지 않는다

```text
필요 참가자   5명 (첫 사용자, 서비스 사전 지식 없음)
시나리오      Game Studio 첫 진입 → 게임 1개 생성 → 저장 → 게시 → 플레이 (20분)
측정 항목     완료율 · 완료 시간 · 이탈 지점 · 도움 요청 횟수 · SUS
불가 사유     모집·일정이 팀 조율 사안이다
```

09-08 설계 그대로다. 혼자 만들 수 없는 항목이라 숫자를 지어내지 않는다.

## 6. 다음 회차에 필요한 것

| 남은 것 | 막는 것 | 푸는 방법 |
|---|---|---|
| 런타임 프레임·draws·tris | 타이밍 API 부재 | 사람이 개발자 도구로 재거나, Unity 쪽에서 Profiler 값을 노출 |
| 에디터 interaction p95 | 같음 | 같음 |
| 5인 사용성 | 참가자 모집 | 리드 조율 |

`#73` 은 닫지 않는다. `-518` 도 진행 중으로 둔다.
