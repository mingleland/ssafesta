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

## React 소관 HUD — 4종

```text
1. 이동·조작 안내
2. 미니게임 점수 / 진행도        (해당 콘텐츠 진행 중에만)
3. Toast / Notification
4. Consultation Quick Access    (우상단, 상시)
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
