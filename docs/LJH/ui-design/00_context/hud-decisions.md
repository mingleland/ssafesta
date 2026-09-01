# HUD Decisions — 최신 역할 경계 정본

- 문서 종류: **DECISION** (팀 후속 합의 정본화, 2026-09-01)
- `references/SSAFY_FESTA_HUD_확인_필요_보고서.md`(SOURCE, 후속 확정 전 조사 문서)보다 **이 문서가 우선**한다.

## 최종 역할 경계

```text
캐릭터/NPC/오브젝트를 따라다니는 World-space · 동적 · frame-coupled UI  → UNITY
화면 고정 Overlay · Menu · 복잡한 정보 · 상시/일시 안내               → REACT
```

F 상호작용(감지·Prompt·입력·Highlight·즉시 Feedback)은 Unity 완결 — React 렌더링을 기다리는 구조 금지.

## React 소관 HUD — Post-MVP Optional 3종뿐

```text
1. 이동·조작 안내
2. 미니게임 점수 / 진행도
3. Toast / Notification
```

이 3종은 component/pattern preview·mock 가능하나 MVP 필수 아님. Core 디자인을 지연시키지 않는다.

## 그 외 HUD — Unity 귀속 또는 폐기

다음을 "게임이면 보통 필요하다"는 이유로 React 요구사항에 추가하지 않는다:

```text
Minimap / Quest / HP Bar / Crosshair / Nameplate
```

판단 기준: 월드 객체를 따라가는가 → Unity / 프레임 결합인가 → Unity / 화면 고정 정보인가 → React / 실질 필요 없는가 → 폐기.

## Overlay open 기본 동작 (설계 원칙)

```text
Unity World 유지 → Input Lock(종류별) → Camera 필요 시 정지 → Dim → React overlay → Close → Input 복원 → 즉시 재개
```

Input Lock/복구 계약은 현재 양측 미구현(2026-08-31 실측) — `07_handoff/decision-queue.md` 4번 항목. 실제 Unity integration 전 해결.
