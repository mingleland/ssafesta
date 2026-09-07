# Release Gate — 기술 검증 기록 (성능 · 접근성 · 오류 복구)

> 이 문서는 **기계가 측정할 수 있는 절반**만 담는다. 사람이 필요한 5인 20분 사용성 검증은
> [`usability-test.md`](./usability-test.md) 가 소유하고 **아직 미실시**다. 둘을 한 문서에 섞지 않는다 —
> 합격 기준도 완료 조건도 다르다.
>
> 대응: GitLab [#73](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/73) ·
> Jira `S15P21A604-518` · Published Runtime E2E 는 `S15P21A604-517`([#55](https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604/-/issues/55))

## 측정 환경 (2026-09-07)

| | 값 |
|---|---|
| 브라우저 | Chrome 152.0.0.0 · Windows NT 10.0 Win64 x64 |
| 화면 / DPR | 1646 × 1029 · devicePixelRatio **1.75** |
| 뷰포트 | 1707 × 898 |
| FE | Vite dev `localhost:5173`, develop `3e6310b8` |
| BE | Spring `localhost:8081`(별도 인스턴스, `app.auth.frontend-base-url=http://localhost:5173`) |
| 대상 | `gameId 1` — 계약 픽스처 `minimal-top-down-dialogue`(열쇠와 문), `PUBLIC`, `publishedVersion 1` |

---

## 1. 오류 복구 UX — 4상태 중 **3 확인 · 1 미확인**

`/app/games/{id}/play` 를 실제로 열어 확인했다. 셋 다 **재시도 버튼 없이 `나가기` 하나**이고,
`문의 코드: req_xxxxxxxx`(spec 005 RequestIdFilter)가 함께 나온다 — 사용자가 본 코드로 서버 로그를 찾을 수 있다.

| 상태 | 만든 방법 | 화면 문구 | 판정 |
|---|---|---|---|
| **404** | `/app/games/999999/play` | `게임을 열 수 없습니다` · **`게임을 찾을 수 없습니다.`** | ✅ |
| **비공개** | `PATCH /games/1 {visibility:PRIVATE}` → 무인증 조회 403 | `게임을 열 수 없습니다` · **`현재 비공개 상태인 게임입니다.`** | ✅ |
| **미발행** | `gameId 2` 생성 후 게시하지 않고 `PUBLIC` → 무인증 조회 404 | `게임을 열 수 없습니다` · **`아직 게시되지 않은 게임입니다.`** | ✅ |
| **schema 미지원** | — | — | ⛔ **E2E 미확인** |

**재시도 버튼이 없는 것이 맞다.** 넷 다 다시 물어도 답이 같은 상태다(4xx). `S15P21A604-458` 에서
전역 재시도 정책이 4xx 를 제외한 것과 같은 결이다.

`schema 미지원`은 **게시 자체가 검증을 통과해야 하므로 정상 경로로는 만들 수 없다.** 단위 테스트가
`runtime/ports/publishedGameRepository.ts` 의 `GAME_SCHEMA_UNSUPPORTED → UNSUPPORTED_SCHEMA` 매핑을
덮고 있고, E2E 로 올리려면 **스키마 MAJOR 가 다른 게시본을 서버에 심는 별도 장치**가 필요하다.
그 장치를 만드는 것은 이 회차 범위 밖으로 둔다.

## 2. 접근성 — 플레이 경로는 **키보드만으로 완주**

`gameId 1` 전 구간을 마우스 없이 통과했다.

```
ArrowRight       → 열쇠 자동 획득 (INVENTORY: ◇ 작은 열쇠)
ArrowRight, ArrowDown, e
                 → 잠긴 문 상호작용 → OVERLAY 대화 "열쇠가 맞았습니다. 문이 열렸습니다."
1                → 선택지 "계속하기" (숫자키 선택, S15P21A604-490)
e                → 재상호작용 → FULL_SCREEN 대화 "방을 탈출했습니다!" (Scene: 탈출)
1                → 선택지 "게임 끝내기" → COMPLETE_GAME
                 → "★ 게임 완료! 제작자가 만든 모든 목표를 달성했습니다."
```

- 이동 4방향 · `E` 상호작용 · **대화 선택지 숫자키** 전부 동작
- 화면에 이동/상호작용 버튼이 함께 있어 마우스 사용자도 같은 경로를 쓴다

### 2-2. 편집기 — 요구 6항 **전부 동작**, 다만 캔버스 컨텍스트에 구조적 제약이 있다

측정 환경: `/app/games/1/edit`, 회원 세션(user 1, 실 BE 8081 에서 발급한 Access Token 주입),
Chrome 148.0.7778.280, 뷰포트 1280×800, DPR 1.75, 저장소는 **브라우저**
(`VITE_GAME_STUDIO_API_ENABLED` 미설정 → `EditGamePage.tsx:8` 에서 서버 authoring 비활성).

> **키 전달 경로의 한계를 밝힌다.** 이 회차에는 창이 그려지지 않아(§3 참조) 실제 키 입력이 페이지에
> 도달하지 않았다 — `window` 에 리스너를 걸고 실키를 눌러도 이벤트가 0건이었다. 그래서
> **합성 `KeyboardEvent` 를 `window` 에 디스패치**하되 `event.target` 을 실제 포커스 대상과 같게 두어
> 핸들러의 포커스 판정(`GameStudioShell.tsx:740`)이 실제와 같은 분기를 타도록 했다.
> **검증된 것은 앱의 단축키 처리와 포커스 판정이고, 브라우저의 키 전달 자체는 아니다.**

| `#73` 요구 | 키 | 결과 | 근거 |
|---|---|---|---|
| 저장 | `Ctrl+S` | ✅ | "저장되지 않은 변경" → "저장 완료" · "저장했습니다." |
| undo | `Ctrl+Z` | ✅ | Object left `59.375% → 53.125%` 원복 |
| redo | `Ctrl+Y` | ✅ | `53.125% → 59.375%` 재적용 |
| 레이어 | `Alt+L` | ✅ | 레이어 버튼 `aria-expanded` `false → true → false` |
| 선택 Object 1칸 이동 | `Arrow*` | ✅ | `53.125% → 59.375%` = +6.25% = **1/16**, Scene 폭 16칸 = 정확히 1칸 |
| focus 표시 | — | ✅ | `outline: 2px solid #2563eb`, `:focus-visible` 매칭 확인 |

전체 선택 `Ctrl+A` 도 동작한다(4/4 선택).

#### 발견 1 — 캔버스 컨텍스트 단축키는 `activeElement === body` 에서만 동작하고, Tab 으로는 갈 수 없다

편집기 전체에 **`[tabindex]` 속성이 0개**다. `.gss-map-canvas`·`.gss-map-object` 어느 쪽도 포커스를
받지 못한다(tabbable 63개 = `button` 56 · `input` 5 · `select` 1 · `a` 1).

`GameStudioShell.tsx:740` 의 `keyboardCanvasContext` 는 target 이 `document.body` 이거나
`.gss-map-canvas`/`.gss-map-object` 일 때만 참이다. 즉 **1칸 이동 · `Ctrl+A/D/C/V` · `Delete` ·
`q`/`w`/`g` 툴 전환**은 body 포커스에서만 듣는다. 실측:

```
target = document.body   Ctrl+A preventDefault=true,  선택 4/4,  Arrow → 이동함
target = <button>        Ctrl+A preventDefault=false, Arrow preventDefault=false, 이동 없음
```

페이지 진입 직후에는 body 포커스라 동작하지만, **한 번이라도 Tab 을 눌러 컨트롤로 가면 그 뒤로는
캔버스 단축키가 듣지 않는다.**

#### 발견 2 — 복귀 경로는 있으나 문서화되지 않은 부수효과다

텍스트 입력에 포커스한 뒤 `Enter` 를 누르면 `CommitInput.tsx:53` 이 `blur()` 를 호출해 포커스가
body 로 돌아간다(실측: `INPUT → BODY`). 그러면 캔버스 단축키가 다시 듣는다.

**Tab 만으로 캔버스 컨텍스트에 도달하는 길은 없다.** 이 복귀는 "이름 입력을 확정한다" 는 다른 목적의
동작이 우연히 만든 부수효과이고, 화면 어디에도 안내가 없다.

#### 발견 3 — 단축키 노출이 `title` 속성뿐이고, 저장과 1칸 이동은 그마저 없다

`title` 로 노출되는 것: `실행 취소 (Ctrl+Z)` · `다시 실행 (Ctrl+Y)` · `복제 (Ctrl+D)` ·
`복사 (Ctrl+C)` · `붙여넣기 (Ctrl+V)` · `찾기·잠금·숨김 (Alt+L)` · `화면 넓게 (Shift+F)`.

**`Ctrl+S`(저장)와 `Arrow`(1칸 이동)는 어디에도 없다** — 저장 버튼에는 `title` 자체가 없다.
`title` 은 마우스 hover 로만 뜨므로 키보드 사용자에게는 노출 경로가 아니다.

#### 발견 4 — `a.gss-back` 만 앱의 focus 링 규칙 밖에 있다

`GameStudioShell.css:37` 의 `:focus-visible` 규칙 대상이 `button`·`input`·`select`·`textarea` 4종이라
tabbable 63개 중 62개가 2px 파란 링을 받고, 뒤로가기 링크(`a.gss-back`)만 브라우저 기본 링
(`outline: auto`, 주황)을 받는다. 링이 없는 것은 아니지만 **다른 모양**이다.


## 3. 성능 — **미측정 (환경 불충족)**

`#73` 이 요구하는 조건은 **"외부 Chromium 활성 탭, 1920×1080"** 이다. 이 회차 환경은 둘 다 아니었다.

```
1차 관측   visibilityState = "hidden" · hasFocus() = false · rAF 1.5초 0회 · 1707 × 898
2차 관측   visibilityState = "visible" · hasFocus() = true  · rAF 1.5초 0회 · 1280 × 800
```

**`visibilityState` 로는 이 상태를 판별할 수 없다.** 2차 관측에서 페이지는 스스로를 "visible" ·
"focused" 로 보고했지만 rAF 콜백은 여전히 한 번도 돌지 않았다(45초 대기 후 타임아웃). 창이 OS 수준에서
최소화·비표시면 페이지의 자기 보고와 실제 렌더 여부가 어긋난다. **재측정 판정은 `visibilityState` 가
아니라 rAF 가 실제로 도는지로 해야 한다.**

**탭이 hidden 이면 Chrome 이 rAF 를 정지시킨다** — `S15P21A604-432`·GitLab `#131` 이 Unity 에서 겪은
바로 그 동작이다. 이 상태에서 잰 fps 는 앱의 성능이 아니라 브라우저의 절전 동작이므로 **숫자를 만들지
않았다.** `setTimeout` 도 hidden 탭에서 1초 이상으로 throttle 되어 지연 측정도 오염된다.

### 다시 잴 때의 절차

1. Chrome 창을 **포그라운드**로 두고 대상 탭을 활성 탭으로 만든다. 확인은 `hasFocus()` 가 아니라
   **rAF 가 1초에 30회 이상 도는지**로 한다(위 2차 관측 참조)
2. 창을 1920×1080 로 맞춘다 (`innerWidth/innerHeight` 로 확인, DPR 도 함께 기록)
3. 플레이 fps — rAF 간격 300 프레임 수집 후 avg·p50·p95·p99·max
4. 편집 반응 p95 — `/app/games/{id}/edit?fixture=max`(100×100 · Object 500 · Tile 10,000)에서
   입력→DOM 갱신까지를 `MutationObserver` 로 수집
5. 원자료(샘플 배열)를 이 문서에 그대로 붙인다. 요약 수치만 적지 않는다

### 다만 — `55fps 이상` 기준은 이 런타임에 그대로 적용되지 않는다

Published Web Runtime 은 **연속 rAF 루프가 아니라 이벤트 구동 DOM** 이다(캔버스가 없다 —
`document.querySelector('canvas')` 가 `null`). 입력이 없으면 그릴 것도 없다. 그래서 이 화면의
체감 지표는 fps 가 아니라 **입력→반영 지연**이고, `#73` 의 55fps 기준은 **편집기 캔버스**
(`?fixture=max` 의 Tile Canvas 합성)에 걸어야 맞다. 기준 문구를 그렇게 나누는 것을 제안한다.

---

## 미실시로 남는 것

| | 사유 |
|---|---|
| 5인 20분 사용성 검증 | **사람이 필요하다.** 자동화로 갈음하지 않는다 — `usability-test.md` 가 소유 |
| 활성 탭 성능(fps · 편집 p95) | 위 §3 환경 불충족. 절차를 남겼다 |
| schema 미지원 E2E | 게시 검증을 통과해야 만들 수 있어 별도 장치 필요 |
