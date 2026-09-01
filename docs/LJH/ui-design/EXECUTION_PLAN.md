# SSAFY FESTA UI 디자인 통합 실행 계획
## 사람 / 에이전트 역할 분리 및 실행 순서

> 목적: SSAFY FESTA의 최종 UI 구조와 현재 개발 실물을 연결하고, 기능 구현 여부와 무관하게 전 화면을 하나의 Game UI System으로 통합 생성하기 위한 실행 계획.
>
> 원칙:
> - Unity World가 서비스 경험의 중심이며 React는 게임 위의 Screen-space UI / Overlay Layer를 담당한다.
> - 기능이 아직 없더라도 디자인 통합 대상에서는 제외하지 않고 Real / Hybrid / Mock으로 처리한다.
> - 디자인 방향 결정과 최종 승인처럼 사람이 맡아야 할 일과, 조사·생성·구현·검증처럼 에이전트가 맡을 일을 분리한다.
> - 디자인 정본보다 플러그인/스킬의 취향을 우선하지 않는다.
> - Post-MVP Optional HUD는 실제 MVP 구현을 막지 않는다.

---

## 0. 현재 확정 입력

### 디자인 기준
- `SSAFY_FESTA_최종_UI_구조_종합정리.md`
- Landing reference image
- Login reference image

### HUD 후속 확정

React 소관 Post-MVP Optional:
- 이동·조작 안내
- 미니게임 점수 / 진행도
- 알림 / Toast

그 외 HUD 후보:
- Unity 담당 또는 폐기

최종 역할 경계:
- 캐릭터/NPC/오브젝트를 따라다니는 World-space·동적 UI → Unity
- 화면 고정 Overlay, 상시/일시 안내, 복잡한 정보 UI → React

### 현재 디자인 도구 상태

| 도구 | 상태 | 역할 |
|---|---|---|
| Claude Design | READY | `/design` 기반 방향 탐색 / artboard / variant |
| Frontend Design | LOADABLE | 실제 React UI 구현 |
| Taste v2 | LOADABLE | 브리핑 우선 Design Read 후 critique / polish |
| Playwright MCP | READY | 실제 browser screenshot / viewport / 반복 검증 |

Taste:
- `~/.claude/skills/design-taste-frontend/SKILL.md`
- v2 사용
- 디자인 정본 생성기가 아니라 critique 도구로 제한
- FESTA 디자인 문서와 reference가 항상 Taste 규칙보다 상위

도구 운영 주의:
- plugin / skill / MCP 설치·smoke는 SSAFY FESTA repository root 밖에서 수행한다.
- 실행 후 반드시 repository cleanliness를 확인한다.

---

# 1. 전체 실행 순서

```text
사람
1. 디자인 입력 정리
2. Tooling 상태 확인
3. 조사 지시 전달
        ↓
에이전트
4. React + Unity UI read-only 전수 조사
5. 목표 UI 구조와 1:1 매핑
6. Real / Hybrid / Mock 분류
7. Foundation / Component / Mock 전략 수립
        ↓
사람
8. 조사 결과 검토
        ↓
에이전트
9. Foundation + 대표 화면 4축 설계
        ↓
사람
10. Visual Direction 선택
        ↓
에이전트
11. 전 화면 통합 구현
12. Taste critique
13. Playwright visual loop
        ↓
사람
14. 대표 화면 최종 승인
        ↓
에이전트
15. 실제 API / Unity 연결
16. Full visual / E2E audit
```

---

# 2. 사람이 해야 할 일

## 2.1 디자인 입력 준비

담당 Claude Code 세션에 아래를 제공한다.

1. 최종 UI 구조 종합 문서
2. HUD 확인 보고서
3. HUD 후속 팀 합의
4. Landing reference
5. Login reference

중요:
HUD 확인 보고서의 과거 "팀 합의 필요" 항목보다 후속 합의가 우선한다.

---

## 2.2 디자인 작업대 확인

구현 시작 전에 다음만 확인한다.

```text
Claude Design      READY
Frontend Design    LOADABLE 이상
Taste v2           LOADABLE 이상
Playwright MCP     READY
```

Frontend Design은 실제 구현 직전 간단한 smoke로 READY를 최종 확인한다.

현재 단계에서 추가 도구를 무리하게 설치하지 않는다.

후순위 도구:
- Figma
- Chrome DevTools
- shadcn MCP
- Ralph Loop
- 21st.dev

필요가 명확해질 때만 추가한다.

---

## 2.3 에이전트 조사 지시

사람이 직접 코드 구조를 다시 조사하지 않는다.

에이전트에 다음 범위를 read-only로 맡긴다.

- React route / page / overlay / creator / operations
- Unity HUD / interaction / world-space UI
- CSS / tokens / assets / common components
- 실제 API 및 data dependency
- 현재 기능 존재 여부
- Mock으로 대체 가능한 영역
- 현재 웹사이트형 구조와 목표 GameShell 구조의 차이

이 단계에서는 대규모 코드 변경을 허용하지 않는다.

---

## 2.4 조사 결과 인간 검토

에이전트 조사 후 사람이 확인할 것은 네 가지다.

1. 빠진 주요 화면이 없는가
2. Real / Hybrid / Mock 분류가 타당한가
3. 기존 구현을 불필요하게 폐기하려 하지 않는가
4. 최종 구조가 Persistent GameShell 원칙과 맞는가

세부 파일 구현 방식까지 사람이 다시 설계할 필요는 없다.

---

## 2.5 Visual Direction 선택

대표 화면을 본 뒤 사람이 한 번 결정한다.

대표 4축:
1. Landing / Login
2. World + Minimal Screen UI
3. World + React Overlay
4. Creator Workspace

검토 기준:
- Landing/Login reference와 같은 세계인가
- SaaS dashboard처럼 보이지 않는가
- Unity World가 항상 중심인가
- Overlay가 World를 과도하게 덮지 않는가
- Creator가 게임의 제작 모드처럼 보이는가

대표 방향 확정 후에는 불필요한 미학적 방향 변경을 자제한다.

---

## 2.6 최종 시각 승인

전 페이지 구현 후 사람이 대표 화면만 다시 확인한다.

최소:
- Landing
- Login
- World
- Overlay
- Studio
- Operations

이 여섯 축이 하나의 제품처럼 보이면 나머지 정리는 에이전트에 맡긴다.

---

## 2.7 사람이 직접 해야 하는 실행/권한 작업

필요한 경우에만:
- OAuth 실제 계정 로그인
- Secret / `.env` 취급
- 외부 서비스 OAuth 연결
- Figma 등 외부 계정 승인
- 팀 디자인 최종 의사결정

에이전트에게 credential을 직접 입력하거나 저장하도록 요구하지 않는다.

---

# 3. 에이전트가 해야 할 일

## 3.1 React + Unity UI read-only 전수 조사

### React

조사 대상:
- routes
- pages
- GameShell
- Unity Host
- OverlayRoot
- Overlay
- modal / dialog
- auth
- booth / lease
- wallet / profile
- studio
- game studio
- project
- AI
- survey
- consultation
- dashboard / staff / admin
- loading / empty / error / disabled
- CSS / CSS Module / Tailwind / inline style
- common components
- assets / icons / fonts

### Unity

조사 대상:
- Interaction detection
- F Prompt
- Highlight / Outline
- World-space UI
- 캐릭터/NPC/오브젝트 추적 UI
- immediate feedback
- Unity → React Overlay event
- Canvas / UI Toolkit
- 현재 HUD 구현

React가 Unity 소관 World-space UI를 가져오지 않도록 확인한다.

---

## 3.2 현재 화면 상태 분류

각 화면을 먼저 구현 상태로 분류한다.

```text
IMPLEMENTED
PARTIAL
STUB
NOT_IMPLEMENTED
BLOCKED
```

그다음 디자인 통합 기준으로 다시 분류한다.

```text
REAL
HYBRID
MOCK
```

### REAL
실제 기능/API 연결 가능

### HYBRID
일부 실제 코드 + fixture/mock 필요

### MOCK
기능/API가 없어도 UI를 먼저 생성

"미구현 = 화면 생성 제외"로 판단하지 않는다.

---

## 3.3 현재 UI → 목표 UI 매핑

목표 구조:

```text
Persistent GameShell
│
├─ Landing
├─ Login
├─ First Setup
├─ World
│
├─ React Screen-space UI
│
├─ OverlayRoot
│  ├─ Booth / Project
│  ├─ AI
│  ├─ Survey
│  ├─ Consultation
│  ├─ Profile
│  └─ Wallet
│
├─ Creator Workspace
│  ├─ Booth Studio
│  ├─ Survey Builder
│  └─ Game Studio
│
└─ Operations
   ├─ Dashboard
   ├─ Staff
   └─ Admin
```

기존 route/page를 단순히 예쁘게 꾸미는 것이 아니라 최종 Game UI 문법 안에서의 위치를 재판정한다.

---

## 3.4 Mock 전략

실 Unity 및 BE 구현이 없더라도 전체 UX를 연결한다.

가능하면:

```tsx
<GameShell>
  <MockUnitySurface />
  <ReactScreenLayer />
  <OverlayRoot />
</GameShell>
```

형태로 디자인 preview가 가능해야 한다.

예:

### Project
```text
Visitor API 없음
Unity event 없음
videoEmbed 있음

→ Hybrid
→ Mock Project data
→ Mock interaction
→ 실제 videoEmbed 연결
```

### Survey
```text
BE 없음
FE 없음

→ Pure Mock
→ 디자인/flow 먼저 완성
```

---

# 4. HUD 처리 원칙

## 4.1 React가 이번에 가져가는 HUD

전부 Post-MVP Optional:

1. 이동·조작 안내
2. 미니게임 점수 / 진행도
3. Toast / Notification

이번 통합 디자인에서:
- component/pattern preview 가능
- mock 가능
- 실제 MVP 구현 필수 아님

이 세 기능 때문에 Core 디자인 작업을 지연시키지 않는다.

---

## 4.2 Unity 또는 폐기 대상

그 외 HUD 후보는 React에서 새로 정본화하지 않는다.

특히 React에서 임의로 만들지 않는다:
- F Prompt
- Highlight
- 캐릭터/NPC/오브젝트를 따라다니는 World-space UI
- 기타 Unity 소관 동적 UI

MockUnitySurface 안에서 시각 reference로 표현할 수는 있지만 React production component로 구현하지 않는다.

---

# 5. Foundation 구축

전 페이지보다 먼저 공통 디자인 언어를 만든다.

```text
Foundation
├─ Color
├─ Typography
├─ Spacing
├─ Radius
├─ Border
├─ Elevation
├─ Motion
└─ Semantic State
```

그 위에:

```text
Primitive
├─ Button
├─ Input
├─ IconButton
├─ Badge
├─ Divider
└─ ScrollArea

Game UI
├─ GameShell
├─ OverlayFrame
├─ GameMenu
├─ ScreenNotice
└─ Toast

Creator
├─ WorkspaceShell
├─ ToolPanel
├─ Inspector
└─ Toolbar
```

를 정의한다.

Landing/Login 이미지는 Visual Anchor로 사용한다.

목표 정체성:
- Festival
- Playful
- Welcoming
- Game-like
- Social
- Digital
- Bright
- 3D

피해야 할 것:
- Enterprise SaaS
- Generic Dashboard
- 과도한 Glassmorphism
- 지나친 Neon
- Cyberpunk cliché
- Kids UI
- Heavy Fantasy RPG

---

# 6. Claude Design 사용

Foundation 초안 후 `/design`으로 대표 4축을 탐색한다.

1. Landing / Login
2. World + Minimal Screen UI
3. World + React Overlay
4. Creator Workspace

각 축은 필요한 경우 2~3개 variant까지만 만든다.

Claude Design은 방향 탐색용이다.
선택한 방향이 실제 React 구현 정본이 된다.

---

# 7. Frontend Design 사용

사람이 Visual Direction을 선택한 뒤 실제 구현에 사용한다.

역할:
- production-grade React/CSS
- layout
- typography
- motion
- responsive
- reusable components

Frontend Design에게 독자적인 미학을 새로 만들게 하지 않는다.

항상:
- 최종 UI 구조
- reference image
- Foundation
- 선택된 `/design` 방향

을 상위 입력으로 준다.

---

# 8. 전 화면 통합 생성

권장 순서:

```text
Landing
→ Login
→ First Setup
→ GameShell
→ World screen layer
→ OverlayFrame
→ 기존 실제 기능
→ 미구현 기능 Mock
→ Creator Workspace
→ Operations
→ Post-MVP Optional React HUD preview
```

미구현 기능도 빈 페이지로 두지 않는다.

---

# 9. Taste v2 사용

Taste는 구현 결과의 critique 용도로만 사용한다.

v2 특징:
- 먼저 브리핑과 화면의 의도를 읽는 Design Read
- 이후 규칙을 적용

따라서 SSAFY FESTA에서는 다음 질문으로 제한한다.

```text
이 화면은 확정된 FESTA 디자인 의도를 얼마나 잘 지키는가?
```

검사 대상:
- visual hierarchy
- spacing
- density
- typography
- motion
- generic AI UI 여부
- SaaS화 여부
- glass / neon 과잉
- 화면 간 consistency
- World보다 panel이 주인공이 되었는지

금지:
- Taste가 새로운 제품 방향을 결정
- 기존 디자인 정본을 뒤집음
- 전체 UI를 독자적으로 재작성

우선순위:

```text
FESTA 디자인 정본
> 사람이 선택한 Visual Direction
> Taste critique
```

---

# 10. Playwright Visual Loop

구현 결과는 실제 브라우저에서 검증한다.

기본 루프:

```text
구현
↓
dev server
↓
Playwright navigate
↓
viewport 설정
↓
screenshot
↓
reference / target 비교
↓
Taste critique
↓
수정
↓
screenshot 재검증
```

최소 viewport:
- 1920×1080
- 일반 16:9 laptop
- 필요 시 390×844 등 작은 화면

모바일 MVP 여부는 별도 정책에 따른다.

검증:
- overflow
- clipping
- typography
- hierarchy
- World visibility
- Overlay size
- responsive
- interaction state

---

# 11. 실제 기능 / Unity 연결

시각 통합 이후 Mock을 실제 구현으로 교체한다.

```text
Mock API
→ Actual API

MockUnitySurface
→ Actual UnityCanvas

Mock interaction
→ Actual Unity event
```

첫 실제 Full Vertical Slice 후보:

```text
Google OAuth
→ Booth Lease
→ Studio
→ Publish
→ Unity
→ Laptop Interaction
→ React Overlay
```

Project/Game 등 현재 외부 blocker가 있는 기능은 blocker 해소 후 교체한다.

---

# 12. 사람 / 에이전트 책임표

| 단계 | 사람 | 에이전트 |
|---|---|---|
| 디자인 정본 제공 | ✅ | |
| HUD 팀 합의 | ✅ | |
| Plugin/Skill 설치·연동 | ✅ 또는 승인 | 보조 조사 |
| Repo UI 실측 | | ✅ |
| Screen Inventory | | ✅ |
| Real/Hybrid/Mock | | ✅ |
| Mock 전략 | | ✅ |
| Foundation 초안 | | ✅ |
| 대표 화면 variant | | ✅ |
| Visual Direction 선택 | ✅ | |
| React 구현 | | ✅ |
| 전 화면 Mock 통합 | | ✅ |
| Taste critique | | ✅ |
| Playwright visual loop | | ✅ |
| 대표 화면 최종 승인 | ✅ | |
| Secret/OAuth 실제 로그인 | ✅ | |
| 실제 API 연결 | | ✅ |
| Unity 연계 | | ✅ |
| 최종 browser smoke | 확인 | ✅ |

---

# 13. 현재 단계

현재:

```text
Tooling Gate
Claude Design       READY
Frontend Design     LOADABLE
Taste v2            LOADABLE
Playwright MCP      READY

→ PASS
```

다음 작업:

```text
React + Unity UI read-only 전수 조사
```

Frontend Design의 실제 READY smoke는 구현 착수 직전에 수행한다.

추가 플러그인 설치는 중단한다.

---

# 14. 문서 산출물 계획

조사 이후 다음 문서를 확장한다.

```text
00_context/
├─ design-direction.md
└─ hud-decisions.md

01_tooling/
├─ claude-code-design-tooling.md
├─ plugin-status.md
└─ workflow.md

02_audit/
├─ screen-inventory.md
├─ current-vs-target.md
├─ real-hybrid-mock.md
└─ ui-ownership-matrix.md

03_foundation/
├─ visual-dna.md
├─ tokens-draft.md
├─ component-map.md
└─ motion-draft.md

04_prototypes/
├─ landing-login.md
├─ world-screen-ui.md
├─ overlay.md
└─ creator-workspace.md

05_visual-review/
├─ taste-review.md
├─ playwright-review.md
└─ consistency-audit.md

06_handoff/
├─ implementation-plan.md
├─ unresolved-decisions.md
└─ design-repo-migration.md
```

폴더는 실제 산출물이 생길 때 생성한다.
빈 디렉터리를 미리 만들 필요는 없다.

---

# 최종 원칙

> 사람은 입력 정본, 외부 권한, 중요한 디자인 선택과 최종 승인을 담당한다.

> 에이전트는 실태 조사, 구조 매핑, Mock 전략, Foundation, 디자인 생성, React 구현, critique, browser 검증을 담당한다.

> 실제 기능이 없다는 이유로 UI를 빼지 않는다. Real / Hybrid / Mock으로 구분해 전 화면을 먼저 통합한다.

> Unity World가 중심이며 React는 화면 고정 UI와 Overlay를 담당한다. World-space 동적 UI는 React로 가져오지 않는다.

> 도구는 FESTA 디자인을 결정하지 않는다. Claude Design, Frontend Design, Taste, Playwright는 이미 결정된 FESTA 디자인을 탐색·구현·검증하기 위한 수단이다.
