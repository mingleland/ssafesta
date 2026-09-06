# SSAFY FESTA HUD 관련 확인 필요 보고서 — 갱신본

## 1. 목적

SSAFY FESTA는 **브라우저 전체가 하나의 풀스크린 게임 클라이언트**로 동작하며, **Unity World가 항상 후단에서 살아 있고 React는 게임 위에 Overlay UI를 제공하는 구조**를 기본 방향으로 한다.

HUD는 이 구조에서 게임 플레이와 웹 UI 사이의 경계를 결정하므로, 단순 스타일 요소가 아니라 다음 항목에 직접 영향을 준다.

- 월드 탐색 및 상호작용 방식
- Unity와 React의 역할 경계
- 게임 몰입감
- 화면 정보 밀도
- 즉각적인 입력 반응성
- Overlay 진입/복귀 UX
- MVP / Post-MVP 구현 범위

본 문서는 HUD 관련 후속 합의 내용을 반영해 **담당 범위와 우선순위를 갱신한 정리본**이다.

---

## 2. 최상위 UI 원칙

SSAFY FESTA의 기본 화면은 항상 **Unity World**다.

React는 게임을 대체하는 별도 웹페이지가 아니라, Unity 위에 나타나는 **화면 고정 UI Layer**로 사용한다.

```text
Persistent GameShell
│
├─ Unity
│   ├─ World Rendering
│   ├─ Character / NPC / Object
│   ├─ Camera / Movement / Collision
│   ├─ World-space UI
│   └─ Immediate Interaction
│
└─ React
    ├─ Screen-space Overlay
    ├─ Persistent / Temporary Guide
    ├─ Complex Information UI
    └─ Post-MVP Optional HUD
```

핵심 원칙:

> **게임 플레이와 월드에 종속되는 동적 UI는 Unity, 화면에 고정되어 정보 전달·입력·관리를 담당하는 UI는 React가 담당한다.**

---

## 3. 최종 역할 경계

HUD 후속 합의에 따라 Unity와 React의 경계를 다음과 같이 확정한다.

### Unity 담당

> **캐릭터 / NPC / 오브젝트를 따라다니는 World-space·동적 UI**

대표 범위:

- Character / NPC / Object 기반 UI
- 상호작용 대상 감지
- 대상 Highlight / Outline
- F 상호작용 Prompt
- F 입력 처리
- 상호작용 즉시 Feedback
- 월드 좌표를 따라가는 이름표 / 마커 / 상태 표현
- 게임 플레이와 프레임 단위로 결합되는 UI
- React Overlay Open Trigger

### React 담당

> **화면 고정 Overlay, 상시/일시 안내, 복잡한 정보 UI**

대표 범위:

- Login / Onboarding
- AI Chat
- Survey
- Consultation
- Booth / Project 정보
- Profile / Wallet
- Management Panel
- Creator Workspace
- 화면 고정 안내 UI
- Toast / Notification
- 필요 시 게임 진행 정보

한 문장으로 정리하면:

> **World-space·동적 UI → Unity / Screen-space·정보 UI → React**

---

## 4. F 상호작용 — Unity 전담

`F 상호작용`은 빠른 반응성과 게임 조작감을 위해 **React를 거치지 않고 Unity에서 완결**한다.

```text
Player 접근
   ↓
Unity가 Interactable 감지
   ↓
Highlight / Outline
   ↓
[F 상호작용] 표시
   ↓
사용자가 F 입력
   ↓
Unity 즉시 Feedback
   ├─ sound
   ├─ animation
   ├─ highlight
   └─ interaction state
   ↓
복잡한 정보 UI가 필요한 경우에만
Unity → React Overlay Open Event
```

예:

```text
부스 접근
→ Highlight                  Unity
→ [F 상호작용]               Unity
→ F 입력                     Unity
→ 선택음 / 즉시 Feedback     Unity
→ BOOTH_INTERACTION Event    Unity → React
→ Booth Overlay 표시         React
```

즉 F 입력 이후 React 렌더링을 기다려야 상호작용 성공 여부를 인지하는 구조를 만들지 않는다.

---

## 5. React 소관 HUD — Post-MVP Optional

HUD 후속 합의 결과, 다음 3개 항목은 **React 소관이지만 Post-MVP Optional**로 둔다.

| 항목 | 담당 | 우선순위 | 비고 |
|---|---|---|---|
| 이동·조작 안내 | React | Post-MVP Optional | 최초 진입 또는 특정 상황의 화면 고정 안내 |
| 미니게임 점수 / 진행도 | React | Post-MVP Optional | 필요 콘텐츠에서만 화면 고정 표시 |
| 알림 / Toast | React | Post-MVP Optional | 성공/실패/상태 변화 등 일시 안내 |

즉 해당 기능들은 현재 MVP 핵심 HUD로 간주하지 않는다.

```text
MVP
→ 게임 플레이와 상호작용 핵심 우선

Post-MVP
→ 필요한 경우 React HUD 확장
   ├─ 이동·조작 안내
   ├─ 미니게임 점수 / 진행도
   └─ 알림 / Toast
```

---

## 6. 기존 HUD 후보 정리

기존에 검토했던 HUD 후보 중 위 3개를 제외한 항목은 **Unity 담당으로 귀속하거나 필요성이 낮으면 폐기**한다.

따라서 아래 후보를 React 상시 HUD 설계 대상으로 별도 유지하지 않는다.

- Crosshair
- NPC / 사용자 / 부스 이름표
- 현재 위치 / Zone 표시
- Mini Map
- Quest / Guide
- Player Status
- 기타 World-space 상태 UI

판단 기준:

```text
월드 객체를 따라가는가?
→ Unity

프레임 단위 플레이 상태와 결합되는가?
→ Unity

화면에 고정된 정보/안내인가?
→ React

게임 경험에 실질적으로 필요하지 않은가?
→ 폐기
```

즉 **전형적인 게임 HUD라는 이유만으로 Crosshair, Mini Map, Quest, Player Status 등을 추가하지 않는다.**

---

## 7. HUD와 React Overlay의 구분

React가 담당한다고 해서 모든 것을 HUD로 보지는 않는다.

### React HUD

화면에 고정되어 짧고 즉각적인 정보를 전달하는 UI.

현재 Post-MVP Optional:

- 이동·조작 안내
- 미니게임 점수 / 진행도
- 알림 / Toast

### React Overlay

정보량이 많거나 입력이 필요한 UI.

대표:

- AI Chat
- Survey
- Consultation
- Booth / Project 상세
- Profile
- Wallet
- Booth Management
- Booth Studio
- Survey Builder
- Game Studio

기본 흐름:

```text
Unity World
   ↓
Unity Interaction
   ↓
필요한 경우
React Overlay
   ↓
Close
   ↓
Unity World 즉시 복귀
```

---

## 8. 화면 계층

최종 화면 계층은 다음과 같이 본다.

```text
┌──────────────────────────────────────────┐
│ React Screen-space UI                    │
│ ├─ Overlay                              │
│ ├─ Toast / Guide (Post-MVP Optional)    │
│ └─ Score / Progress (Post-MVP Optional) │
│                                          │
│        ┌────────────────────────┐        │
│        │      React Panel       │        │
│        └────────────────────────┘        │
│                                          │
│══════════════ Unity World ═══════════════│
│                                          │
│ Character / NPC / Booth / Object         │
│ World-space UI / F Interaction           │
│ Highlight / Immediate Feedback           │
│                                          │
└──────────────────────────────────────────┘
```

React UI가 등장해도 Unity World는 제거하지 않는다.

---

## 9. Overlay 오픈 시 기본 동작

React Overlay가 열리는 경우에도 World context를 유지한다.

기본 원칙:

```text
Unity World
→ 계속 유지

Character Input
→ Overlay 종류에 따라 Lock

Camera
→ 필요 시 제한 또는 정지

Background
→ Dim

React
→ Screen-space Overlay 표시

Close
→ React 제거
→ Input Context 복원
→ 게임 즉시 재개
```

React Overlay가 별도 웹페이지로 전환된 것처럼 보이면 안 된다.

---

## 10. MVP 기준 HUD 범위

현재 MVP에서는 HUD 기능을 적극적으로 확장하지 않고 **Unity 중심의 핵심 플레이 경험과 Overlay 연결을 우선한다.**

### MVP 핵심

- Unity World
- Unity 기반 F 상호작용
- Unity 기반 World-space / Dynamic UI
- Unity → React Overlay Trigger
- React Overlay
- Overlay Close → Unity Control 복구

### Post-MVP Optional

- React 이동·조작 안내
- React 미니게임 점수 / 진행도
- React 알림 / Toast

이 구조를 통해 MVP 단계에서 불필요한 HUD 확장을 피하고, 실제 사용성 문제가 확인되는 경우에만 React HUD를 추가한다.

---

## 11. 디자인 원칙

HUD의 기본 철학은 **더 많이 보여주는 것보다 World를 가리지 않는 것**이다.

```text
World first
   ↓
Immediate interaction → Unity
   ↓
Necessary screen information → React
   ↓
Complex interaction → React Overlay
```

피해야 할 방향:

- 전형적인 RPG HUD를 이유 없이 추가
- Mini Map / Quest / Player Status의 관성적 도입
- 월드 객체 UI를 React DOM 위치 추적으로 구현
- F Prompt를 React 렌더링에 의존
- React 상시 UI가 게임 화면을 과도하게 점유
- Overlay가 별도 웹페이지처럼 보이는 구조

지향할 방향:

- 최소 HUD
- Contextual UI
- World-space / Screen-space 역할 분리
- 즉각적인 Unity Feedback
- 게임 인터페이스처럼 보이는 React Overlay
- 필요성이 검증된 HUD만 Post-MVP에서 추가

---

## 12. 디자인 시스템 반영 범위

확정된 역할 경계는 SSAFY FESTA Game UI System에 다음과 같이 반영한다.

```text
SSAFY FESTA GAME UI
│
├─ Unity-side Game UI
│   ├─ World-space UI
│   ├─ Interaction
│   ├─ Highlight
│   └─ Immediate Feedback
│
└─ React-side Game UI
    ├─ Overlay
    ├─ Management Panel
    ├─ Creator Workspace
    └─ Optional HUD
        ├─ Control Guide
        ├─ Score / Progress
        └─ Toast / Notification
```

React HUD가 추가될 경우 공통 Foundation을 공유한다.

- Color
- Typography
- Icon
- Spacing
- Motion
- Semantic State
- Layer / Z-index
- Overlay Dim 규칙

---

## 13. 남은 확인 사항

HUD의 큰 역할 경계는 확정되었으므로, 이후 확인은 **기능 존재 여부와 세부 동작** 중심으로 축소한다.

### Unity 측

- 실제 World-space UI 목록
- F Prompt의 위치 / 거리 / 표시 조건
- Highlight / Outline 표현
- 이름표 등 동적 UI 사용 여부
- Overlay 호출 시 Unity Input Lock 규칙
- Overlay Close 시 Control 복구 규칙

### React 측 — Post-MVP

- 이동·조작 안내가 실제 필요한지
- 미니게임별 Score / Progress 표시 필요 여부
- Toast / Notification 사용 시점
- Overlay와 Optional HUD의 동시 표시 우선순위

---

## 14. 최종 결론

> **SSAFY FESTA의 HUD는 Unity와 React가 기능 종류가 아니라 공간과 반응성 기준으로 역할을 나눈다.**

> **캐릭터·NPC·오브젝트를 따라다니는 World-space UI와 플레이에 즉시 반응해야 하는 동적 UI는 Unity가 담당한다. 특히 F 상호작용의 감지·Prompt·입력·Highlight·즉시 Feedback은 Unity에서 완결한다.**

> **화면에 고정되는 Overlay, 상시/일시 안내, 복잡한 정보 UI는 React가 담당한다. 단, React HUD 중 이동·조작 안내, 미니게임 점수/진행도, 알림/Toast는 MVP 필수 기능이 아니라 Post-MVP Optional로 둔다.**

> **그 외 기존 HUD 후보는 Unity 담당으로 귀속하거나 필요성이 없다면 폐기하며, 전형적인 게임 HUD를 관성적으로 추가하지 않는다.**

> **최종 목표는 HUD 자체를 풍부하게 만드는 것이 아니라 Unity World와 게임 플레이를 항상 화면의 중심으로 유지하는 것이다.**
