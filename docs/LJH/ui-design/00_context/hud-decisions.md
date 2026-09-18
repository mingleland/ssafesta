# HUD Decisions — 최신 역할 경계 정본

- 문서 종류: **DECISION** (팀 후속 합의 정본화 2026-09-01, User Flow 확정 반영 2026-09-03)
- `source-docs/SSAFY_FESTA_HUD_확인_필요_보고서.md`(SOURCE, 후속 확정 전 조사 문서)보다 **이 문서가 우선**한다.
- 사용자 흐름·진입 구조의 정본은 `user-flow-decisions.md`다. 이 문서는 그 흐름 안에서 **HUD 허용 범위**만 정한다.

## 최종 역할 경계

```text
캐릭터/NPC/오브젝트를 따라다니는 World-space · 동적 · frame-coupled UI  → UNITY
화면 고정 Overlay · Menu · 복잡한 정보 · 상시/일시 안내               → REACT
```

F 상호작용(감지·Prompt·입력·Highlight·즉시 Feedback)은 Unity 완결 — React 렌더링을 기다리는 구조 금지.

## React 소관 HUD — 6종

```text
1. 이동·조작 안내
2. 미니게임 점수 / 진행도        (해당 콘텐츠 진행 중에만)
3. Toast / Notification
4. Consultation Quick Access    (우상단, 상시)
5. 월드 공용 텍스트 채팅        (좌하단, 회원 입력)
6. 브라우저 전체화면 토글       (우상단, 상담 아래)
```

1~3 은 Post-MVP Optional 이다 — component/pattern preview·mock 가능하나 MVP 필수는 아니며 Core 디자인을 지연시키지 않는다.

**4. Consultation Quick Access 는 예외적으로 상시 HUD 다** (`user-flow-decisions.md` §11 · RULE 6). 근거: 상담은 요청→대기→수락→진행→만료→종료의 **시간 지속성**을 갖는 유일한 방문자 기능이라, 오버레이를 닫고 월드로 돌아간 뒤에도 상태에 즉시 닿을 경로가 필요하다.

```text
아이콘   💬 (speech bubble)   — 🔔 는 향후 일반 Notification 용도로 남긴다
위치     World HUD 우상단
상태     Idle / Waiting / Active / Staff Request
표기     상태 유무를 점(●)으로만 나타낸다 — 실제 데이터에 없는 unread count·숫자를 발명하지 않는다
정책     Overlay Close ≠ Consultation Cancel. 취소는 명시적 액션으로만 한다
```

이 예외는 상담 하나에만 적용된다. 다른 기능이 "자주 쓴다"는 이유로 HUD 자리를 얻지 않는다.

## 컨텍스트 액션 — 상시 HUD 가 아니다

위 4종과 **다른 범주**를 하나 둔다. Unity 가 보낸 월드 컨텍스트가 참일 때만 뜨고, 그 조건이 풀리면 사라지는 액션이다.

```text
조건      Unity → FE 로 온 월드 상태가 참일 때만
수명      그 상태가 풀리면 사라진다 — 사용자가 닫는 것이 아니다
개수      조건당 하나. 여러 액션을 모아 바·독을 만들지 않는다
```

**기능 Launcher 금지와 구분되는 이유**: Launcher 는 *기능을 여는* 바로가기라 Unity `F` 와 진입 경로가 겹친다. 컨텍스트 액션은 여는 것이 아니라 **지금 상태에서 할 수 있는 일 하나를 내놓는다** — 그 일이 이미 Unity 에 경로가 있더라도, 그 경로를 찾지 못하는 사용자가 있다는 것이 근거다.

**기존 경로를 대체하지 않는다.** 추가 경로이고, Unity 쪽 조작(`F`·포털)은 그대로 남으며 조작 안내에서도 빠지지 않는다.

| 액션 | 조건 | 위치 | 근거 |
|---|---|---|---|
| 부스 나가기 | `insideBooth === true` (`WORLD_BOOTH_CONTEXT`) | 하단 중앙 | GitLab #174 · S15P21A604-627 |

위치가 하단 중앙인 이유: 네 모서리가 이미 차 있다(좌하단 조작 안내 · 우하단 이용 안내 · 우상단 Consultation Quick Access). 하단 중앙은 제품에서 비어 있는 유일한 자리이고, 일시 요소라 상시 자리를 뺏지 않는다. DEV_ONLY Mock Interaction Bar 와는 dev 환경에서만 겹치므로 그보다 위에 둔다.

**이 범주를 늘릴 때의 기준**: 조건이 Unity 가 보내는 월드 상태여야 하고(FE 추정 금지), 그 일을 할 다른 경로가 이미 있어야 하며(대체가 아니라 보완), 조건이 풀리면 스스로 사라져야 한다. 셋 중 하나라도 어긋나면 상시 HUD 요청이므로 위 4종 규칙으로 돌아간다.

## 허용 5 — 월드 공용 텍스트 채팅 (2026-09-14 추가, S15P21A604-706 · GitLab #187)

좌하단에 최근 몇 줄이 옅게 보이고, `Enter` 또는 버튼으로 입력창이 열린다. 회원 전용이다.

**기능 Launcher 금지와 충돌하지 않는 이유**: 금지가 막는 것은 *Unity `F` 로 이미 열리는 기능을 React 가 또 여는 것*이다. 채팅은 Unity 에 진입 경로가 없다 — Unity Web 은 한글 IME 를 다룰 수 없고(헌법 25조) 서버와 직접 통신하지도 않는다. React 가 유일한 경로라 대체가 아니라 **유일 수단**이다.

**상시 표시가 아닌 이유**: 입력창을 항상 띄우면 월드 조작 키와 상시 경쟁한다. 평소에는 읽기만 보이고 쓰기는 `Enter` 로 연다.

**`.world-hud` 안에 두지 않는다.** HUD 는 `onMouseDown` 을 막아 캔버스 focus 를 지키는데(S15P21A604-648) 그 안의 입력창은 클릭해도 focus 가 잡히지 않는다. 채팅은 `.world-scene` 의 형제 레이어다.

**저장이 없다.** 서버에 표가 없어 재접속하면 이전 대화가 사라진다 — 스크롤백을 그리지 않고 버퍼는 최근 100줄만 남긴다.

## 허용 6 — 브라우저 전체화면 토글 (2026-09-14 추가, S15P21A604-733)

우상단에 상시 버튼 하나. 상담 퀵액세스와 같은 열에 한 칸 아래로 쌓는다.

**기능 Launcher 금지와 충돌하지 않는 이유**: 이것은 *기능을 여는* 바로가기가 아니라 **화면을 보는 방식**을 바꾼다. 여는 콘텐츠가 없으므로 Unity `F` 와 진입 경로가 겹치지 않는다.

**React 가 유일 수단인 이유**: Fullscreen API 는 브라우저 권한이고 사용자 제스처 안에서만 허용된다. Unity WebGL 안에서는 부를 수 없다 — 캔버스 클릭은 Unity 자기 루프에서 처리되지 DOM 이벤트 핸들러 안이 아니다.

**상시 표시인 이유**: 들어가는 것보다 **나오는 것**이 중요하다. 전체화면에서 나갈 방법이 화면에 없으면 사용자는 `F11`·`ESC` 를 알아야 한다. 조건부로 숨기면 그 순간이 사라진다.

**상태를 FE 가 들고 있지 않는다.** 정본은 브라우저이고 `fullscreenchange` 로 아이콘을 맞춘다 — `F11` 이나 `ESC` 로 빠져나가면 FE state 는 곧바로 어긋난다.

**자동 진입은 보조다.** 로그인 클릭에서 의도만 남기고 '월드 입장' 클릭에서 시도한다(OAuth 가 document 를 갈아 전체화면을 푼다). 거부돼도 월드 진입을 막지 않고 이 버튼이 정본 경로로 남는다.

## World HUD layout 계약 (S15P21A604-740)

HUD는 새 공통 컴포넌트로 감싸지 않는다. 기존 요소가 아래 토큰·영역을 직접 소비한다.

```css
--festa-hud-margin-x: max(16px, 2vw);
--festa-hud-margin-y: max(16px, 3vh);
```

| 영역 | 역할 | 우선순위 |
|---|---|---|
| 좌하단 | 채팅 → 조작 안내 | 화면 하단 → 안전 여백 → 채팅 실제 높이 → 공통 간격 → 안내 순서. 채팅은 `.world-hud` 밖 형제이며 `WorldPage`가 높이 변수 하나로 연결한다 |
| 우상단 | 상담 → 전체화면 | 상담이 첫 칸, 전체화면이 공통 간격 아래 칸 |
| 우하단 | 이용 안내 | 좌하단·우상단과 독립 |
| 하단 중앙 | Context action / Booth Exit | `insideBooth`일 때만. 클릭형이며 F 키 표기를 복제하지 않는다 |
| 상단 중앙 | Toast | 월드에서만 예약. Overlay·system보다 낮지 않다 |

낮은 높이에서는 채팅 로그와 조작 안내가 각자 가용 높이 안에서 스크롤한다. 720px 이하에서는
`채팅 → Booth Exit → 조작 안내`로 세로 전환해 가로 충돌을 없앤다. 채팅이 닫히거나 안내가 접히면
높이 변수가 즉시 다시 계산되어 빈 자리를 남기지 않는다. World HUD 소유 CSS의 z-index는 토큰만
쓰며, 외부 dialog·라이브러리 계층은 이 규칙의 범위 밖이다.

## 그 외 HUD — Unity 귀속 또는 폐기

다음을 "게임이면 보통 필요하다"는 이유로 React 요구사항에 추가하지 않는다:

```text
Minimap / Quest Tracker / HP Bar / Crosshair / Nameplate / Mission Panel
```

**기능 Launcher 도 금지한다.** Project·LAPTOP·Survey·AI·GAME 을 여는 hotbar·dock·바로가기 바를 만들지 않는다 — 그 기능들의 진입은 Unity F 하나다(`user-flow-decisions.md` RULE 2). 현재 World 하단에 있는 Mock Interaction Bar 는 Unity 부재 환경에서 dispatcher 하류를 검증하기 위한 **DEV_ONLY 대역**이며 최종 제품 HUD 가 아니다.

판단 기준: 월드 객체를 따라가는가 → Unity / 프레임 결합인가 → Unity / 화면 고정 정보인가 → React / 실질 필요 없는가 → 폐기.

## Overlay open 기본 동작 (설계 원칙)

```text
Unity World 유지 → Input Lock(종류별) → Camera 필요 시 정지 → Dim → React overlay → Close → Input 복원 → 즉시 재개
```

Input Lock/복구 계약은 현재 양측 미구현(2026-08-31 실측) — `07_handoff/decision-queue.md` 4번 항목. 실제 Unity integration 전 해결.
