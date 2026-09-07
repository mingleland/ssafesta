# SSAFY FESTA 최종 UI 구조 종합 정리본

## 0. 최상위 정의

SSAFY FESTA는 **웹사이트에 Unity 게임을 삽입한 서비스가 아니라, 브라우저에서 실행되는 하나의 풀스크린 게임 클라이언트**로 설계한다.

서비스 경험의 중심은 항상 **Unity World**이며, React는 별도의 웹페이지를 보여주는 역할이 아니라 **게임 위에 뜨는 UI Layer**를 담당한다.

```text
Browser Fullscreen
┌────────────────────────────────────────────────────┐
│                    React UI Layer                  │
│         HUD / Popup / Menu / Creator Panel        │
│                                                    │
│                                                    │
│══════════════════ Unity World ═════════════════════│
│                                                    │
│ Character / Camera / Booth / NPC / Environment    │
│                                                    │
└────────────────────────────────────────────────────┘
```

핵심 UX 원칙은 하나다.

> **사용자가 어떤 기능을 사용하더라도 “게임에서 나와 웹사이트로 이동했다”고 느끼지 않아야 한다.**

---

## 1. 기술·UI 생명주기

전체 애플리케이션은 하나의 **Persistent GameShell**을 중심으로 유지한다.

```text
Persistent GameShell
│
├─ Persistent Unity Host
│   ├─ World
│   ├─ Character
│   ├─ Camera
│   ├─ Interaction
│   └─ Game Runtime
│
└─ Persistent React Overlay Root
    ├─ HUD
    ├─ Popup
    ├─ Menu
    ├─ Auth UI
    ├─ Management Panel
    └─ Creator Workspace
```

개념적으로는 다음 구조다.

```tsx
<GameShell>
  <UnityCanvas />

  <ReactHud />

  <OverlayRoot>
    {/* 필요한 UI만 표시 */}
  </OverlayRoot>
</GameShell>
```

중요한 점은 **React 화면 상태가 바뀌어도 Unity 인스턴스를 재생성하지 않는 것**이다.

```text
Unity lifecycle
    >
React overlay lifecycle
```

즉 Unity는 게임 세션의 기반이며, React UI는 그 위에서 열리고 닫히는 구조다.

---

## 2. Unity와 React의 역할 경계

가장 중요한 역할 분리는 다음과 같다.

| Unity | React |
|---|---|
| World Rendering | 로그인 UI |
| Character | 최초 사용자 설정 |
| Camera | AI Chat |
| Movement | Survey |
| Collision | Consultation |
| Object/NPC Detection | Booth/Project 정보 |
| Interaction Target | Profile |
| **F Prompt** | Wallet |
| **F Input** | Management UI |
| **즉각적인 Interaction Feedback** | Creator Workspace |
| Animation / Sound | 복잡한 Form |
| 실제 게임 플레이 | 텍스트 중심 콘텐츠 |

이를 한 문장으로 정리하면:

> **Unity는 “플레이하고 즉시 반응하는 것”, React는 “읽고 입력하고 관리하는 것”을 담당한다.**

---

## 3. F 상호작용 — Unity 전담

`F 상호작용`은 빠른 반응성과 게임 조작감을 위해 **React를 거치지 않고 Unity에서 완결**한다.

```text
Player 접근
   ↓
Unity가 Interactable 감지
   ↓
Outline / Highlight
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
복잡한 UI가 필요한 경우에만
Unity → React Overlay Open Event
```

예:

```text
부스 접근
→ 부스 Highlight                  Unity
→ [F 상호작용]                    Unity
→ F 입력                          Unity
→ 선택음 / Feedback               Unity
→ BOOTH_INTERACTION 이벤트        Unity → React
→ Booth Overlay 표시              React
```

즉 사용자가 F를 눌렀을 때 **React 렌더링을 기다려야 상호작용 여부를 알 수 있는 구조를 만들지 않는다.**

---

## 4. 전체 사용자 경험 흐름

최상위 사용자 Journey는 다음과 같다.

```text
Game Boot
   ↓
Landing / Title
   ↓
Login
   ↓
First Setup
   ↓
World Entry
   ↓
━━━━━━━━━━━━━━━━━━━━━━━━━━
       UNITY WORLD
     서비스의 중심 상태
━━━━━━━━━━━━━━━━━━━━━━━━━━
   ↓
Booth / NPC / Object 접근
   ↓
Unity F Interaction
   ↓
필요 시 React Overlay
   ↓
Overlay Close
   ↓
즉시 World Control 복구
```

`Home → Booth → Dashboard → ...`처럼 웹사이트 페이지를 순회하는 흐름을 핵심 UX로 삼지 않는다.

---

## 5. Landing — 게임 Title Screen

Landing은 일반 웹 Landing Page가 아니라 **게임 시작 화면**이다.

```text
┌─────────────────────────────────────────────┐
│                                             │
│          FESTA WORLD / CINEMATIC            │
│                                             │
│               SSAFY FESTA                   │
│                                             │
│                                             │
│          화면을 클릭해 시작하기              │
│            opacity breathing                │
│                                             │
└─────────────────────────────────────────────┘
```

방향은 다음과 같다.

- 브라우저 전체화면
- 16:9 기준 + 21:9 대응
- 일반 Header / Footer 없음
- 실제 월드보다 조금 과장된 FESTA 연출 허용
- 밝고 화려한 축제 / 테마파크 / Festival Zone 이미지
- 대형 SSAFY FESTA 로고
- `시작하기`를 일반 버튼처럼 만들지 않음
- `화면을 클릭해 시작하기` 문구를 opacity pulse 형태로 표현
- 화면 어디를 클릭해도 진행
- 첫 사용자 입력을 Fullscreen 진입 트리거로 사용 가능

즉 첫 화면의 목적은 기능 설명이 아니라:

> **“이 세계에 들어가고 싶다”는 감정을 만드는 것**

이다.

---

## 6. Login — 게임 안의 로그인 메뉴

로그인은 별도 SaaS 페이지처럼 만들지 않는다.

Landing/World의 게임 화면 위에 **로그인 메뉴가 열린 것처럼** 보여야 한다.

```text
┌─────────────────────────────────────────────┐
│                                             │
│             FESTA WORLD                     │
│                                             │
│              SSAFY FESTA                    │
│                                             │
│        ┌────────────────────────┐           │
│        │ SSAFY로 계속하기       │           │
│        │ Google로 계속하기      │ React     │
│        │ Kakao로 계속하기       │ Overlay   │
│        │ 게스트로 둘러보기      │           │
│        └────────────────────────┘           │
│                                             │
└─────────────────────────────────────────────┘
```

인증 UX 목표는:

```text
SSAFY
Google
Kakao
Guest
```

이다.

시각적으로는:

- 밝고 열린 축제 광장/거리
- 어두운 복도형 배경 제외
- World를 충분히 보여줌
- 로그인 UI가 화면 전체를 가리지 않음
- 큰 흰색 웹 로그인 카드 지양
- Game Menu 느낌 유지

---

## 7. 최초 사용자 설정

OAuth 최초 로그인 후 닉네임 입력 등은 별도 페이지처럼 만들지 않는다.

```text
World / Login background
        +
small onboarding panel
```

예:

```text
┌──────────────────────────────┐
│ 축제에서 사용할 이름         │
│                              │
│ [ 닉네임 입력              ] │
│                              │
│             [입장하기]       │
└──────────────────────────────┘
```

로그인 흐름과 같은 게임 UI 언어를 그대로 유지한다.

---

## 8. World — 실제 서비스의 중심

인증 이후 사용자는 기본적으로 **Unity World에 존재**한다.

```text
              UNITY WORLD
                   │
      ┌────────────┼────────────┐
      │            │            │
    Booth         NPC         Object
      │            │            │
      └───── Interaction ───────┘
```

웹 UI가 기본 화면을 점유하지 않는다.

게임의 평상시 화면은 예를 들면:

```text
Quest / Guide                              Mini Map


                 UNITY WORLD


              [ F 상호작용 ]


Player Status                               Menu
```

HUD도 최소한으로 유지하여 **World가 항상 화면의 주인공**이 되도록 한다.

---

## 9. In-game React Overlay

React의 가장 중요한 역할이다.

평상시:

```text
┌──────────────────────────────────────────┐
│                                          │
│               UNITY WORLD                │
│                                          │
│              [F 상호작용]                │
│                                          │
└──────────────────────────────────────────┘
```

F 상호작용 후:

```text
┌──────────────────────────────────────────┐
│░░░░░░░░ DIMMED UNITY WORLD ░░░░░░░░░░░│
│                                          │
│       ┌──────────────────────────┐       │
│       │                          │       │
│       │       React Overlay      │       │
│       │                          │       │
│       │ 정보 / 입력 / 상담 / 폼 │       │
│       │                          │       │
│       └──────────────────────────┘       │
│                                          │
└──────────────────────────────────────────┘
```

Overlay 닫기:

```text
React Overlay Close
        ↓
World DIM 해제
        ↓
Unity Input Context 복원
        ↓
게임 조작 즉시 재개
```

---

## 10. Overlay 표시 원칙

Overlay가 열려도 Unity World는 제거하지 않는다.

기본 원칙:

```text
World
→ 계속 렌더링

Camera
→ 필요 시 정지

Character Input
→ 필요 시 Lock

Background
→ Dim

Blur
→ 필요한 경우 최소 적용

React
→ World 위에 Floating Panel 표시
```

팝업을 사용한다고 해서 `/survey`, `/chat` 같은 별도 웹페이지로 이동한 것처럼 보여서는 안 된다.

---

## 11. Overlay 크기 체계

모든 React UI가 동일한 크기의 Modal일 필요는 없다.

### S — Game Interaction

```text
[F] 상호작용
아이템 획득
간단 안내
Tooltip
```

주로 Unity.

### M — Popup

```text
부스 정보
프로젝트 정보
간단 NPC 정보
간단 Confirm
```

World를 많이 노출한다.

### L — Functional Panel

```text
AI Chat
Survey
Consultation
Profile
Wallet
Booth Management
```

게임 화면의 50~75% 정도까지 사용할 수 있다.

### XL — Creator Workspace

```text
Booth Studio
Survey Builder
Game Studio
복잡한 Management
```

필요하면 화면의 85~95%까지 사용할 수 있다.

하지만 여전히:

```text
┌──────────────────────────────────────────┐
│░░░░ World context remains visible ░░░░░│
│ ┌──────────────────────────────────────┐ │
│ │          Creator Workspace           │ │
│ │                                      │ │
│ │ Asset │ Canvas │ Inspector           │ │
│ │                                      │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

처럼 **게임 안에서 큰 도구를 연 것**처럼 보여야 한다.

---

## 12. 주요 Overlay 종류

현재 대표적인 World Interaction 기반 React UI는 다음과 같다.

```text
LAPTOP
→ 부스 홈페이지
→ 프로젝트 콘텐츠
→ 외부/상세 콘텐츠

AI_CHAT
→ AI 직원 대화
→ Streaming
→ Handoff

SURVEY
→ 설문 응답

CONSULTATION
→ 사람 상담

GAME
→ 게임 관련 정보 / 진입 UI
```

공통 `OverlayFrame`을 공유한다.

---

## 13. OverlayFrame 공통 구조

```text
┌────────────────────────────────────┐
│ Icon / Title                  [×]  │
│ Subtitle / Status                  │
├────────────────────────────────────┤
│                                    │
│              Content               │
│                                    │
├────────────────────────────────────┤
│ Status                 Main Action │
└────────────────────────────────────┘
```

공통 요소:

- Title
- Description
- Close
- Loading
- Empty
- Error
- Disabled
- Success
- Streaming
- Connection State
- 주요 Action

기능마다 완전히 다른 디자인을 만들지 않는다.

---

## 14. React 디자인은 “웹사이트 UI”가 아닌 “Game Interface”

피해야 할 방향:

```text
일반 SaaS Dashboard
전체화면 흰색 웹페이지
항상 존재하는 Web Sidebar
일반 웹 Header 중심 navigation
기능마다 다른 route의 독립 페이지
과도한 SaaS 카드 그리드
```

지향할 방향:

```text
Floating Panel
Game Modal
HUD
Context Menu
Side Panel
Drawer
World Overlay
Game-like transition
Contextual UI
```

React가 아무리 복잡한 기능을 담당하더라도 **게임 UI라는 인상을 유지**해야 한다.

---

## 15. Booth Studio

Booth Studio는 복잡한 제작 기능이므로 XL Workspace로 처리한다.

```text
┌──────────────────────────────────────────────────┐
│ Booth Studio                 Save       Publish  │
├────────────┬──────────────────────┬──────────────┤
│ Assets     │                      │ Inspector    │
│ Objects    │      3D Canvas       │              │
│ Layers     │                      │ Position     │
│            │                      │ Rotation     │
│            │                      │ Config       │
└────────────┴──────────────────────┴──────────────┘
```

방향:

- 좌측 Asset/Object
- 중앙 3D Canvas
- 우측 Inspector
- Save / Preview / Publish
- FESTA 기본 테마 공유
- 일반 웹 에디터가 아니라 **게임의 제작 모드**처럼 표현

---

## 16. Survey Builder

Survey Builder도 Creator Workspace 계열이다.

```text
┌──────────────────────────────────────────────────┐
│ Survey Builder                         Publish   │
├────────────┬──────────────────────┬──────────────┤
│ Questions  │                      │ Settings     │
│ Sections   │     Form Preview     │ Property     │
│            │                      │              │
└────────────┴──────────────────────┴──────────────┘
```

Booth Studio와 동일한 Creator 문법을 공유한다.

```text
Left
→ structure

Center
→ preview/canvas

Right
→ property
```

---

## 17. Profile / Wallet / Management

기존 웹페이지가 아니라 **게임 메뉴를 연 것처럼** 처리한다.

예:

```text
World
  ↓
ESC / Profile Icon
  ↓
┌──────────────────────────┐
│ Profile                  │
├──────────────────────────┤
│ Player                   │
│ Wallet                   │
│ Transaction              │
│ Settings                 │
└──────────────────────────┘
```

World를 제거하지 않는다.

---

## 18. Dashboard / Staff / Admin

정보 밀도가 높은 관리 UI도 동일한 세계 안에 존재한다.

단 Brand 강도는 낮춘다.

```text
SSAFY FESTA Game UI
        │
        ├─ Experience → 가장 화려함
        ├─ Overlay    → 중간
        ├─ Creator    → 기능 중심
        └─ Operations → 가장 절제
```

Operations UI의 핵심은:

- Table
- Filter
- Search
- KPI
- Chart
- Drawer
- Confirm

이다.

하지만 색·타이포·상태 표현·motion 등은 같은 Design System을 공유한다.

---

## 19. 디자인 시스템 구조

페이지별로 각자 디자인하지 않는다.

최상단에 하나의 **SSAFY FESTA Game UI System**을 둔다.

```text
SSAFY FESTA GAME UI
│
├─ Brand Foundation
│
├─ HUD
│
├─ Interaction
│
├─ Overlay
│   ├─ Information
│   ├─ Chat
│   ├─ Survey
│   └─ Consultation
│
├─ Management Panel
│   ├─ Profile
│   ├─ Wallet
│   └─ Booth
│
├─ Creator Workspace
│   ├─ Booth Studio
│   ├─ Survey Builder
│   └─ Game Studio
│
└─ Operations
    ├─ Dashboard
    ├─ Staff
    └─ Admin
```

---

## 20. 공통 Foundation

전 화면이 다음 Foundation을 공유한다.

```text
Brand
├─ Logo
├─ Symbol
└─ Graphic Motif

Color
├─ Brand
├─ Surface
├─ Text
└─ Semantic

Typography
├─ Display
├─ Heading
├─ Body
└─ Dense UI

Geometry
├─ Spacing
├─ Radius
├─ Border
└─ Elevation

Motion
├─ Open
├─ Close
├─ Focus
├─ Interaction
└─ Celebration

State
├─ Loading
├─ Empty
├─ Error
├─ Disabled
├─ Success
└─ Realtime
```

---

## 21. 브랜드 강도 계층

모든 화면을 화려하게 만드는 것이 아니다.

### Brand-heavy

- Landing
- Login
- World Entry
- Event
- Celebration

### Game Product UI

- AI Chat
- Survey
- Profile
- Wallet
- Booth Information

### Utility / Creator

- Booth Studio
- Survey Builder
- Game Studio

### Operations

- Dashboard
- Staff
- Admin

뒤로 갈수록 장식을 줄이고 정보 밀도를 높인다.

---

## 22. Visual Identity 방향

현재 탐색된 기본 정체성은:

```text
Festival
Playful
Welcoming
Game-like
Social
Digital
Bright
3D
```

피해야 할 방향:

```text
Enterprise SaaS
Generic Dashboard
Cyberpunk cliché
지나친 Neon
과도한 Glassmorphism
지나치게 유아적인 Kids UI
Heavy Fantasy RPG
```

핵심은:

> **축제처럼 밝고 즐겁지만, 실제 게임 인터페이스로 충분히 사용할 수 있는 디자인**

이다.

---

## 23. 별도 Design Repo

디자인 정본은 별도 저장소로 분리하는 방향을 기본안으로 한다.

```text
ssafesta-design/
│
├─ brand/
│  ├─ identity
│  ├─ logo
│  ├─ color
│  ├─ typography
│  └─ graphic-language
│
├─ references/
│
├─ foundations/
│  ├─ color
│  ├─ typography
│  ├─ spacing
│  ├─ radius
│  └─ motion
│
├─ tokens/
│
├─ components/
│
├─ patterns/
│  ├─ hud
│  ├─ interaction
│  ├─ overlay
│  ├─ creator
│  └─ operations
│
├─ assets/
│
└─ decisions/
```

역할:

```text
ssafesta-design
= Design SSOT

frontend
= React UI 구현체

unity
= World / Gameplay 구현체
```

당장은 React UI를 별도 npm library까지 분리하지 않는다.

실제 구현은 frontend 안에서 유지하고, 디자인 규칙과 자산만 별도 SSOT에서 관리한다.

---

## 24. 디자인 진행 순서

페이지 하나씩 깊게 완성하지 않는다.

```text
1. Brand Identity
        ↓
2. Visual Concept
        ↓
3. Reference Board
        ↓
4. Foundation / Token
        ↓
5. Game UI Component Theme
        ↓
6. HUD / Interaction / Overlay Theme
        ↓
7. Creator / Operations Variant
        ↓
8. 전체 기능에 얕게 1차 적용
        ↓
9. 전체 일관성 검증
        ↓
10. 개별 화면 상세화
```

즉 먼저:

> **“SSAFY FESTA의 게임 UI가 어떻게 생기는가?”**

를 결정한 다음 각 기능으로 내려간다.

---

## 25. 최종 구조 한 장 요약

```text
                    SSAFY FESTA
                         │
                 FULLSCREEN GAME
                         │
┌────────────────────────┴─────────────────────────┐
│                                                  │
│                 REACT UI LAYER                   │
│                                                  │
│   HUD / Login / Menu / Popup / Creator / Admin  │
│                                                  │
│          ┌───────────────────────────┐           │
│          │      React Overlay        │           │
│          │                           │           │
│          │ Chat / Survey / Wallet   │           │
│          │ Studio / Management      │           │
│          └───────────────────────────┘           │
│                                                  │
│══════════════════ UNITY LAYER ═══════════════════│
│                                                  │
│                    WORLD                         │
│                                                  │
│ Character / Camera / NPC / Booth / Objects      │
│                                                  │
│ Interaction Detection                            │
│        ↓                                         │
│ [F 상호작용] ← Unity                             │
│        ↓                                         │
│ Immediate Feedback ← Unity                       │
│        ↓                                         │
│ 필요 시 Overlay Event ──────────────→ React      │
│                                                  │
└──────────────────────────────────────────────────┘
```

## 최종 원칙

> **SSAFY FESTA는 브라우저에서 실행되는 하나의 풀스크린 게임이다. Unity World는 항상 경험의 후단과 중심에 존재하며, React는 게임을 대체하는 웹페이지가 아니라 게임 위에 나타나는 UI Layer다.**

> **F 상호작용의 감지, Prompt, 입력, Highlight, Sound, Animation 등 즉각적인 피드백은 Unity가 전담한다. 복잡한 정보 확인이나 텍스트 입력이 필요할 때만 React Overlay를 호출한다.**

> **AI Chat·설문·상담·Wallet·Profile·Booth 관리와 같은 기능은 World를 유지한 상태의 게임형 Overlay로 제공한다. Booth Studio·Survey Builder·Game Studio 같은 복잡한 제작 기능도 가능한 한 World context를 남긴 대형 Workspace 형태로 제공한다.**

> **React라는 기술을 사용한다는 이유로 웹사이트의 문법을 가져오지 않는다. 모든 UI 결정에서 우선 질문은 ‘웹페이지로서 좋은가?’가 아니라 ‘게임을 플레이하는 흐름 안에서 자연스러운가?’가 되어야 한다.**

> **전체 디자인은 페이지별로 만들지 않고, 먼저 SSAFY FESTA Game UI의 Brand Identity·Foundation·Component Theme을 확정한 뒤 HUD → Interaction → Overlay → Creator → Operations 순으로 동일한 디자인 언어를 확장한다.**
