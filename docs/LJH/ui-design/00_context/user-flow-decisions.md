# SSAFY FESTA User Flow Decisions

> **문서 지위:** DECISION  
> **목적:** SSAFY FESTA의 사용자 진입·World 상주·World 상호작용·개인/시스템 메뉴·부스 관리·상담·Creator Workspace 간의 **최종 사용자 흐름 정본**을 정의한다.  
> **적용 범위:** Frontend UI/UX, Unity↔React 상호작용 경계, Booth Management 진입 구조, Overlay/Screen/Workspace 역할 분리  
> **작성 기준:** 2026-09-03 Function Truth / Current UI Gap Audit + 이후 사용자 확정 결정  
> **주의:** 이 문서는 구현 완료 상태를 의미하지 않는다. **UX/Navigation/Interaction 의사결정 정본**이며, 실제 구현 성숙도는 REAL / HYBRID / MOCK / BLOCKED로 별도 구분한다.
>
> **구현 연결(2026-09-03):** 여기 정의한 흐름은 `!240`(merge `e961adda`)로 FE 에 구현돼 develop 에
> 반입됐다. 구현 매핑은 `04_prototypes/ux-architecture-remap.md`, 화면별 상태는
> `04_prototypes/screen-specifications.md` 를 본다. World 진입 계약(Booth Management NPC·Survey·
> Project 송신부)은 Unity 측 계약 대기 상태로 남아 있다(§27).

---

## 0. 핵심 요약

SSAFY FESTA는 일반 웹 대시보드가 아니라 **World를 중심으로 상주하는 브라우저 게임 클라이언트**를 지향한다.

사용자가 익혀야 하는 핵심 규칙은 다음과 같다.

```text
WORLD
= 로그인 후 사용자가 상주하는 기본 상태

F
= 현재 눈앞의 World 대상과 상호작용

ESC
= 플레이어 개인 정보 + 시스템 메뉴

HUD 우상단 상담 아이콘
= 상담 상태에 즉시 접근하는 전역 Quick Access

Booth Management NPC
= 내 부스 제작·콘텐츠·운영의 통합 진입점
```

즉 제품 구조를 다음 네 축으로 분리한다.

1. **Entry** — Landing / Login / Auth / First Setup
2. **World Interaction** — Unity F → React Visitor Overlay
3. **Personal/System** — ESC → Profile Summary / Settings / Logout
4. **Booth Owner Management** — Booth Management NPC F → Booth Management Overlay

---

# 1. Product Mental Model

## 1.1 제품 성격

SSAFY FESTA의 기본 사용자 경험은 다음을 전제로 한다.

```text
일반 웹사이트
→ 여러 메뉴와 대시보드에서 기능을 찾는다

SSAFY FESTA
→ World에 들어간다
→ 공간을 이동한다
→ 대상과 상호작용한다
→ 필요한 복합 UI만 React Overlay/Screen으로 연다
```

따라서 제품의 기본 화면은 `/app/home` 같은 대시보드가 아니라 **World**다.

## 1.2 장기 구조

장기적으로는 다음 구조를 목표로 한다.

```text
Persistent GameShell
├─ Unity World Layer
├─ React Overlay Layer
└─ React Screen / Workspace Layer
```

현재 긴급 Mock 단계에서는 일부 World가 Static Mock Surface일 수 있으나, 사용자 Flow는 장기 구조와 충돌하지 않도록 설계한다.

---

# 2. Global User Flow

```text
                         SSAFY FESTA
                              │
                         ┌────▼────┐
                         │ Landing │
                         └────┬────┘
                              │
                         ┌────▼────┐
                         │  Login  │
                         └────┬────┘
                              │
                    ┌─────────▼─────────┐
                    │  Auth Processing  │
                    └─────────┬─────────┘
                              │
                    NICKNAME_REQUIRED?
                       │             │
                     YES             NO
                       │             │
                ┌──────▼──────┐      │
                │ First Setup │      │
                │  Nickname   │      │
                └──────┬──────┘      │
                       └──────┬───────┘
                              │
                    ┌─────────▼─────────┐
                    │       WORLD       │
                    │   기본 상주 상태   │
                    └─────────┬─────────┘
                              │
        ┌─────────────────────┼──────────────────────────┐
        │                     │                          │
        │ F                   │ F                        │ ESC
        ▼                     ▼                          ▼
 일반 World Object       Booth Management NPC       Game Menu
        │                     │                          │
        ▼                     ▼                          ├─ Profile Summary
 Visitor Overlay        Booth Management Overlay       │    └─ My Info
 ├─ Project             ├─ Booth Mini Preview           ├─ Settings
 ├─ LAPTOP              ├─ Booth Studio                 └─ Logout
 ├─ Survey              ├─ Project Management
 ├─ AI                  ├─ Survey Management
 └─ GAME                ├─ Consultation Management
                         └─ Lease / Booth Status

                     WORLD HUD 우상단
                            │
                           💬
                            │
                     Consultation
                     Quick Access
```

---

# 3. Entry Flow

## 3.1 기본 흐름

```text
Landing
  ↓
Login
  ↓
Auth Processing
  ↓
서버 인증 결과
  ├─ AUTHENTICATED
  │    ↓
  │   World
  │
  └─ NICKNAME_REQUIRED
       ↓
     First Setup
       ↓
     Nickname 저장
       ↓
      World
```

## 3.2 Landing

### 역할
- SSAFY FESTA의 게임 진입 지점
- 시작 제스처 제공
- 인증 상태에 따라 Login 또는 인증 완료 흐름으로 연결

### 하지 않는 역할
- 서비스 Dashboard
- Booth/Profile/Wallet 바로가기 허브
- 기능 목록 제공

## 3.3 Login

실제 인증 옵션:
- SSAFY
- Google
- Kakao
- Guest

Provider availability는 실제 구현 상태를 따른다.

## 3.4 Auth Processing

OAuth handoff와 세션 확정을 사용자에게 기술적인 raw HTML로 노출하지 않는다.

```text
OAuth handoff 소비
→ 세션 확정
→ 서버 결과 수신
→ AUTHENTICATED / NICKNAME_REQUIRED 분기
→ 실패 시 Login 복귀
```

UX는 별도 정보 페이지가 아니라 **게임 진입 Transition / Boot 화면**으로 표현한다.

## 3.5 First Setup

신규 사용자 판단은 FE가 추측하지 않는다.

```text
Server
→ NICKNAME_REQUIRED
```

입력 범위는 `Nickname` 하나뿐이다.

추가하지 않는 것:
- 아바타 선택
- 관심사
- 직업
- 튜토리얼 타입
- 프로필 이미지
- 선호 부스
- 세부 계정 설정

## 3.6 기본 목적지 및 Deep Link

명시적인 deep link가 없다면 로그인 완료 후 기본 목적지는 **World**다.

```text
DEFAULT
Login
→ World
```

기존 `/app/home`은 제품 Dashboard로 사용하지 않는다.

Deep Link는 유지한다.

```text
Protected Deep Link
→ Login
→ Auth
→ First Setup(필요 시)
→ 원래 요청한 목적지
```

즉 **명시적 returnTo는 존중하고, 기본 returnTo만 World로 둔다.**

---

# 4. World

## 4.1 역할

```text
WORLD
├─ 이동
├─ 탐색
├─ NPC / Object / Booth
├─ Unity F Interaction
├─ 최소 React HUD
├─ Consultation Quick Access
├─ Booth Management NPC
└─ ESC Game Menu
```

World 자체가 일반 웹 Navigation Hub가 되어서는 안 된다.

## 4.2 Unity / React 역할 경계

### Unity 담당
- 이동 / 카메라
- 대상 감지
- F Prompt
- Highlight
- World-space 동적 UI
- 캐릭터/NPC/Object를 따라다니는 UI
- 즉각적인 World Feedback
- World Animation / Sound

### React 담당
- 복잡한 정보 Overlay
- 읽기 / 입력
- 계정 관리
- Booth Management
- Creator Workspace
- 고정 HUD 일부
- Game Menu

---

# 5. React HUD

## 5.1 허용되는 HUD

1. 이동·조작 안내
2. 미니게임 진행 중 Score / Progress
3. Toast / Notification
4. **Consultation Quick Access**

## 5.2 추가 금지

- Minimap
- HP
- Quest Tracker
- Hotbar
- Crosshair
- Mission Panel
- 기능 Launch Dock
- Project / LAPTOP / Survey / AI / GAME 바로가기 바

## 5.3 DEV_ONLY Interaction Launcher

긴급 Mock에서 World 하단에 존재했던 기능 버튼들은 최종 제품 HUD가 아니다.

```text
Project
LAPTOP
Survey
Consultation
AI
GAME
```

등을 직접 여는 바는 Unity가 없는 환경에서 dispatcher 하류를 검증하기 위한 **DEV_ONLY 대역**으로만 유지 가능하다.

최종 사용자 UI에서는 제거한다.

---

# 6. General World Interaction

```text
대상에 접근
  ↓
Unity Target Detection
  ↓
Unity Highlight / F Prompt
  ↓
F
  ↓
Unity Event
  ↓
React Overlay
  ↓
Close / ESC
  ↓
같은 World 위치로 복귀
```

> **F는 지금 내 앞에 있는 World 대상과 상호작용하는 키다.**

React가 일반 방문 기능을 상시 버튼으로 띄우지 않는다.

---

# 7. Visitor Overlay Family

```text
Visitor Overlay
├─ Project
├─ LAPTOP
├─ Survey
├─ AI
└─ GAME
```

Consultation은 시간 지속성과 즉시 접근 필요성이 있으므로 별도 HUD Quick Access 구조를 사용한다.

---

# 8. Project Overlay

## 진입

```text
World Project Object
→ F
→ Project Overlay
```

## 표시 요소
- Project Media
- Project Name
- Description
- Like
- Deploy Link
- Git Link
- Portfolio Link
- Loading / Empty / Error
- Close

역할 분리:

```text
Visitor
→ Project Overlay

Owner
→ Project Management
```

Project 편집 기능을 Visitor Overlay에 섞지 않는다.

---

# 9. LAPTOP Overlay

## 진입

```text
World Laptop Object
→ F
→ LAPTOP Overlay
```

## 표시 요소
- Homepage iframe
- Hostname
- 새 탭에서 열기
- Loading
- No URL
- Invalid URL
- Error
- Close

iframe 차단을 브라우저에서 완전히 판정하기 어렵기 때문에 **새 탭에서 열기**는 1급 fallback action으로 유지한다.

---

# 10. Survey Visitor Flow

## 목표 진입

```text
World Survey Object / Kiosk
→ F
→ Survey Run Overlay
```

## 표시 요소
- Survey Title
- Question
- Question Type
- Required
- Answer
- Progress
- Submit
- Closed
- Empty
- Error
- Completion State

### Owner 기능과 분리

```text
Visitor
→ Survey Run

Owner
→ Survey Management
   ├─ Builder
   └─ Result
```

### 현재 기술 공백

Survey Overlay/모델은 존재하지만 **Unity Interaction Event 계약은 아직 미정의**다.

따라서 User Flow의 위치는 정본으로 유지하되 실제 Unity 연결은 별도 계약 작업으로 처리한다.

---

# 11. Consultation — Global Quick Access

## 11.1 기본 원칙

Consultation은 다음처럼 시간 지속성이 있는 Communication 기능이다.

- 요청
- 대기
- 수락
- 진행
- 만료
- 종료

따라서 World HUD 우상단에 Consultation 전용 Quick Access를 둔다.

### 권장 아이콘

`Speech Bubble`을 기본으로 한다.

```text
💬 = Consultation / Communication
🔔 = 향후 일반 Notification 용도
```

## 11.2 HUD 상태 예시

### Idle
```text
💬
```

### Waiting / 상태 존재
```text
💬 ●
```

### Active
```text
💬 ●
```

실제 데이터에 없는 unread count나 숫자를 임의 생성하지 않는다.

## 11.3 Visitor Consultation State

```text
IDLE
  ↓
Request
  ↓
WAITING
  ├─ Cancel
  ├─ Accepted → ACTIVE
  └─ Expired → EXPIRED
                  ↓
              Re-request

ACTIVE
  ↓
END
  ↓
ENDED
```

## 11.4 Overlay Close 정책

권장 정책:

```text
Overlay Close
≠ Consultation Cancel
```

즉 World로 돌아가더라도 상담 상태는 유지한다.

```text
상담 요청
→ WAITING
→ Overlay Close
→ World 플레이
→ HUD 💬를 다시 누름
→ WAITING 상태 복원
```

상담 취소는 명시적인 `[상담 요청 취소]` Action으로 수행한다.

## 11.5 새로운 상담 대상 결정

HUD는 상담 상태에 즉시 접근하는 전역 진입점이다.

다만 새 상담 시작 시 어느 Booth가 대상인지 결정하는 세부 정책은 Unity 이벤트 계약과 함께 확정해야 한다.

```text
상담 상태 접근
= HUD 전역

상담 대상 Booth Context
= 실제 Booth/World 계약을 통해 결정
```

HUD에서 근거 없는 Booth Directory UI를 임의 생성하지 않는다.

---

# 12. ESC Game Menu

## 12.1 역할

ESC Game Menu는 Navigation Hub가 아니다.

> 플레이어 자신과 시스템에 관한 최소 메뉴

## 12.2 최종 구조

```text
ESC
┌──────────────────────────────┐
│ [Avatar]  MockUser           │
│           Google             │
│           200 Coin           │
│                   내 정보 >  │
├──────────────────────────────┤
│                              │
│                              │
│                              │
├──────────────────────────────┤
│ 설정                         │
│ 로그아웃                     │
└──────────────────────────────┘
```

## 12.3 Profile Summary

상단 Profile Summary는 별도 상위 Context Box다.

표시 후보:
- Avatar
- Nickname
- Login Provider
- Coin Balance
- My Info 진입

넣지 않는 것:
- 전체 거래내역
- 닉네임 수정 Form
- 회원 탈퇴
- Booth 관리
- Booth Studio
- Consultation 관리

## 12.4 하단 시스템 메뉴

```text
Settings
Logout
```

## 12.5 World 복귀

별도의 `계속하기` 메뉴를 두지 않는다.

```text
ESC 재입력
X
바깥 클릭
```

등 Game Menu Close가 곧 World 복귀다.

## 12.6 ESC에서 제거하는 것

- Booth Studio
- Booth Management
- Consultation Management
- Project
- Survey
- GAME
- 기타 서비스 Navigation

---

# 13. My Info

Profile과 Wallet을 하나의 개인 관리 Context로 통합한다.

```text
My Info
├─ Profile
│  ├─ Nickname
│  ├─ Provider
│  ├─ Avatar State
│  └─ Account Withdrawal
│
└─ Wallet
   ├─ Coin Balance
   └─ Transaction History
```

Wallet은 독립 Top-level Game Menu 항목으로 두지 않는다.

---

# 14. Booth Management NPC

## 14.1 목적

Booth 관련 소유자 기능을 ESC에서 분리하고 **World 안의 명확한 관리 진입점**으로 만든다.

추천 개념:
- Booth Management NPC
- Festival Operation Desk
- Booth Support Staff
- 운영 센터 NPC

사용자가 자신의 Booth까지 직접 이동해야만 관리할 필요는 없다.

공용 운영 NPC는 Spawn 근처, 중앙 광장, 운영 데스크 등 접근성이 높은 위치에 둘 수 있다.

정확한 World 위치는 Unity/World Design 단계에서 확정한다.

## 14.2 진입 Flow

```text
World
  ↓
Booth Management NPC 접근
  ↓
Unity F Prompt
"F  내 부스 관리"
  ↓
F
  ↓
React Booth Management Overlay
```

Close 시:

```text
Booth Management Overlay
→ Close / ESC
→ 동일 NPC 앞 World
```

---

# 15. Booth Management Overlay

## 15.1 역할

Booth Management는 단순 Dashboard가 아니다.

> **내가 운영 중인 하나의 Booth를 중심으로 제작·콘텐츠·운영 상태를 관리하는 통합 인터페이스**

```text
Booth Management
├─ Booth Identity / Status
├─ Booth Studio Entry
├─ Project Management
├─ Survey Management
├─ Consultation Staff
└─ Lease / Booth Information
```

## 15.2 구조 원칙 — A + D 혼합

### D 방식
- My Booth 자체를 화면의 주인공으로 둔다.
- 게임 세계관과 Booth Identity를 강하게 보여준다.

### A 방식
- 기능별 상세 화면으로 Drill-down한다.
- Project/Survey 같은 복잡한 Editor를 한 화면에 욱여넣지 않는다.

---

# 16. Booth Management 상단 Hero / Identity

추천 구조:

```text
┌───────────────────────────────────────────────┐
│ 내 부스 관리                              X  │
├───────────────────────────────────────────────┤
│                                               │
│ ┌────────────────┐  Example Booth             │
│ │                │  A-12                      │
│ │   BOOTH MINI   │                            │
│ │    PREVIEW     │  ● 운영 중                 │
│ │                │  임대 02일 14시간 남음      │
│ └────────────────┘                            │
│                                               │
│                    [ 부스 스튜디오 열기 ]       │
│                                               │
├───────────────────────────────────────────────┤
│ PROJECT                                       │
│ Example Project                   [관리 >]    │
│                                               │
├───────────────────────────────────────────────┤
│ SURVEY                                        │
│ 방문자 설문 · 6문항                [관리 >]    │
│                                               │
├───────────────────────────────────────────────┤
│ CONSULTATION                                  │
│ 상담 요청 운영                     [관리 >]    │
│                                               │
├───────────────────────────────────────────────┤
│ 부스 정보                                      │
│ A-12 · 임대 중 · 종료 시각                     │
└───────────────────────────────────────────────┘
```

---

# 17. Booth Mini Preview

## 17.1 우선순위

```text
1. Booth Facade 기반 Mini Preview
2. Booth Exterior Thumbnail
3. Booth Logo
4. Project Logo / favicon
```

Project Logo를 Booth 대표 이미지로 사용하지 않는다.

```text
Booth ≠ Project
```

## 17.2 구현 방향

새 Screenshot API보다 현재 Booth Facade 데이터로 작은 Preview를 생성하는 방식을 우선한다.

활용 후보:
- themeCode
- primaryColor
- signText
- logoUrl

```text
Facade Data
→ BoothMiniPreview
```

장점:
- 새 BE 계약 불필요
- Booth Studio 외관 변경을 자동 반영 가능
- 실제 데이터 기반
- Project에 종속되지 않음
- “내 Booth를 관리한다”는 Context 강화

Project Logo/favicon은 Project Management 섹션의 Secondary Identity로 사용한다.

---

# 18. Booth Studio

## 18.1 진입

```text
World
→ Booth Management NPC
→ Booth Management Overlay
→ [부스 스튜디오 열기]
→ Booth Studio
```

Booth Studio는 Booth Management Overlay 내부 Tab으로 넣지 않는다.

독립 Creator Workspace를 유지한다.

## 18.2 역할

```text
Booth Studio
├─ Layout
├─ Facade
└─ Template
```

실제 기능:
- Asset 추가
- Selection
- Drag
- Rotate
- Snap
- Bounds
- Inspector
- Facade Editing
- Template
- Save
- Publish

Booth Studio는 **공간을 디자인하는 곳**이다.

Project/Survey/Consultation 운영 기능을 Studio Mode로 추가하지 않는다.

## 18.3 종료 후 복귀

권장:

```text
Booth Management
→ Booth Studio
→ Close / Exit
→ Booth Management
```

---

# 19. Project Management

## 진입

```text
Booth Management
→ Project
→ [관리]
→ Project Management
```

## 역할

Project 소유자의 콘텐츠 편집.

Visitor Project Overlay와 분리한다.

### 실제 필드 중심
- Project Name
- Description
- Media
- Deploy URL
- Git URL
- Portfolio URL
- Save
- Loading / Error

실제 모델에 없는 임의 Analytics/Metadata를 추가하지 않는다.

---

# 20. Survey Management

## 진입

```text
Booth Management
→ Survey
→ [관리]
→ Survey Management
```

```text
Survey Management
├─ Survey Builder
└─ Survey Result
```

## 20.1 Survey Builder

기능:
- Title
- Question
- Question Type
- Required
- Options
- Add
- Remove
- Reorder
- Validation
- Save

실제 질문 유형 모델을 따른다.

## 20.2 Survey Result

실제 모델에 존재하는 결과만 표현한다.

- Choice Counts
- Rating Average
- Rating Distribution
- Text Answer Pagination

임의의 분석 지표를 추가하지 않는다.

---

# 21. Consultation Staff / Booth Owner

Booth Owner의 상담 운영 기능은 Booth Management 안에 존재한다.

```text
Booth Management
→ Consultation
→ Consultation Staff
```

기본 기능:

```text
대기 요청
→ Accept

현재 상담
→ End

오류
→ Action Error
```

단, 실시간 상담은 즉시성이 높기 때문에 **World HUD의 Consultation Quick Access에서도 현재 상태/요청으로 즉시 접근할 수 있다.**

```text
Booth Management
= 상담 운영/관리 Context

HUD 💬
= 현재 발생 중인 상담에 대한 즉시 접근
```

---

# 22. Lease / Booth Status

Lease는 Booth Management의 주요 Editor가 아니라 Booth 상태 정보로 취급한다.

부스 보유 시 표시 후보:
- Slot Code
- Lease Status
- Lease End
- Remaining Time
- Booth Name

---

# 23. 부스가 없는 사용자

Booth Management NPC는 부스 보유 여부와 관계없이 사용할 수 있다.

```text
World
→ Booth Management NPC
→ F
```

### No Booth

```text
┌──────────────────────────────┐
│ 내 부스 관리                 │
│                              │
│ 아직 운영 중인 부스가 없습니다│
│                              │
│ 부스를 임대하면              │
│ 전시 공간을 꾸미고           │
│ 콘텐츠를 운영할 수 있습니다. │
│                              │
│              [부스 임대하기] │
└──────────────────────────────┘
```

```text
No Booth
→ Lease Flow

Has Booth
→ Booth Management
```

---

# 24. 역할별 전체 기능 분류

## Entry
```text
Landing
Login
Auth Processing
First Setup
```

## World / Visitor
```text
World F
├─ Project
├─ LAPTOP
├─ Survey
├─ AI
└─ GAME
```

## Global Consultation
```text
World HUD 💬
└─ Consultation Quick Access
```

## Personal / System
```text
ESC
├─ Profile Summary
│  └─ My Info
├─ Settings
└─ Logout
```

## Booth Owner
```text
Booth Management NPC F
└─ Booth Management
   ├─ Booth Studio
   ├─ Project Management
   ├─ Survey Management
   ├─ Consultation Staff
   └─ Lease / Booth Status
```

---

# 25. 제거되는 기존 UI 구조

## 제거
- Home Dashboard
- World 상단 Auth Chip
- World 상단 Logout
- Booth 화면 전체 Transaction History 중복
- ESC의 Booth Studio
- ESC의 Booth Management
- ESC의 Consultation Management
- ESC의 서비스 Navigation

## DEV_ONLY
- World 하단 Mock Interaction Launcher

---

# 26. 현재 구현 성숙도

## REAL / 거의 REAL
- Auth
- Landing/Login
- Booth/Lease
- Wallet
- Booth Studio
- Profile
- LAPTOP
- Project 데이터/좋아요

## HYBRID
- World Surface
- Project Unity 송신
- AI
- 일부 Overlay integration

## MOCK
- Survey Adapter
- Consultation Adapter

## BLOCKED
- GAME real portal
- 일부 Unity 송신부
- Survey / Consultation Unity Interaction 계약

---

# 27. Open Contract Gaps

## 27.1 Survey Unity Event

Survey의 목표 UX는 World F → Overlay지만, Unity Event Contract가 아직 없다.

추후 정의 필요:
- event name
- boothId
- objectId
- 필요한 config identifier
- dispatcher mapping

## 27.2 Consultation Target Context

Consultation은 HUD Quick Access로 전역 접근 가능하지만:

> 새 상담 시작 시 어느 Booth를 대상으로 하는가

에 대한 실제 계약은 아직 확정 필요하다.

HUD에서 임의 Booth 목록을 발명하지 않는다.

## 27.3 Project Unity Sender

Project React Overlay/VM은 존재하지만 Unity 송신부는 미완이다.

## 27.4 GAME

- BE game-portals
- Unity 송신
- ownership boundary

결정/구현 필요.

---

# 28. Deferred Game Client Features

- Persistent GameShell
- Overlay Stack
- Input Router
- Unity Input Lock
- Focus Restore
- Cursor Manager
- UI Audio
- Settings 상세 기능
- Fullscreen
- Pointer Lock
- Text/Voice Chat 후보
- Lifecycle Recovery
- Responsive/Mobile
- Foundation Extraction

---

# 29. Overlay / Input 장기 규칙

```text
Overlay Open
→ Unity Input Lock
→ React Interaction

Overlay Close
→ Focus Restore
→ Unity Input Resume
```

현재 단일 Overlay Slot 구현은 향후 Overlay Stack으로 확장할 수 있다.

---

# 30. Navigation / Return Rules

## Visitor Overlay
```text
World
→ F
→ Overlay
→ Close
→ 같은 World
```

## Consultation
```text
World
→ HUD 💬
→ Consultation
→ Close
→ World
```

Close가 상담 요청 Cancel을 의미하지 않는다.

## Booth Management
```text
World
→ Booth Management NPC
→ Booth Management
→ Close
→ NPC 앞 World
```

## Booth Studio
```text
Booth Management
→ Booth Studio
→ Exit
→ Booth Management
```

## My Info
```text
World
→ ESC
→ Profile Summary / My Info
→ Close/Back
→ Game Menu 또는 World
```

세부 Screen lifecycle은 Persistent GameShell 구현 시 최종 고정한다.

---

# 31. UX Container 정의

## Entry / Transition
- Landing
- Login
- Auth Processing
- First Setup

## World
- Unity World
- Minimal React HUD
- Consultation Quick Access
- Toast
- Optional Control Guidance

## Visitor Overlay
- Project
- LAPTOP
- Survey
- AI
- GAME

## Personal/System Menu
- Profile Summary
- My Info
- Settings
- Logout

## Booth Management Overlay
- Booth Identity
- Booth Status
- Booth Studio Entry
- Project
- Survey
- Consultation
- Lease

## Creator Workspace
- Booth Studio

## Management Detail Screens/Workspaces
- Project Management
- Survey Builder
- Survey Result
- Consultation Staff

---

# 32. Product UX 완료 기준

```text
신규 사용자
→ World 도착 가능

World
→ 현장 기능 사용 가능

World
→ 상담 상태 즉시 접근 가능

World
→ Booth Management NPC 접근 가능

Booth Owner
→ 임대 / Studio / Project / Survey / Consultation 관리 가능

사용자
→ My Info / Wallet 접근 가능

ESC
→ 개인/시스템 기능만 존재

기획 근거 없는 기능 Launch UI 없음
```

---

# 33. Product Rules — 최종 요약

```text
RULE 1
World는 로그인 후 기본 상주 상태다.

RULE 2
일반 현장 기능은 Unity F 상호작용으로 진입한다.

RULE 3
React는 복잡한 Overlay/Screen/Workspace를 담당한다.

RULE 4
ESC는 플레이어 개인 정보 + 시스템 메뉴로 제한한다.

RULE 5
ESC에는 Booth 관리 기능을 넣지 않는다.

RULE 6
상담은 시간 지속성이 있으므로 HUD 우상단 Quick Access 예외를 허용한다.

RULE 7
Booth 관련 Owner 기능은 Booth Management NPC를 통해 진입한다.

RULE 8
Booth Management는 하나의 Booth를 중심으로 보여주는 통합 관리 Overlay다.

RULE 9
Booth Studio는 공간 디자인 전용 Creator Workspace다.

RULE 10
Project / Survey / Consultation Owner 기능은 Booth Studio Mode가 아니라 별도 Management 기능이다.

RULE 11
Booth Management 상단 Identity는 Booth 자체를 우선한다.
Project Logo를 Booth 대표 Identity로 사용하지 않는다.

RULE 12
Booth Mini Preview는 가능하면 실제 Facade Data 기반으로 생성한다.

RULE 13
Visitor 기능과 Owner 관리 기능을 같은 UI에 섞지 않는다.

RULE 14
현재 UI에 존재한다는 이유만으로 제품 요구사항으로 승격하지 않는다.

RULE 15
Mock/Dev trigger는 최종 제품 UI와 명확히 분리한다.
```

---

# 34. 최종 구조도

```text
                           SSAFY FESTA
                                │
                           ┌────▼────┐
                           │ Landing │
                           └────┬────┘
                                │
                           ┌────▼────┐
                           │  Login  │
                           └────┬────┘
                                │
                      ┌─────────▼─────────┐
                      │  Auth Processing  │
                      └─────────┬─────────┘
                                │
                      NICKNAME_REQUIRED?
                         │             │
                       YES             NO
                         │             │
                  ┌──────▼──────┐      │
                  │ First Setup │      │
                  │  Nickname   │      │
                  └──────┬──────┘      │
                         └──────┬───────┘
                                │
                      ┌─────────▼─────────┐
                      │       WORLD       │
                      │   기본 상주 상태   │
                      └─────────┬─────────┘
                                │
       ┌────────────────────────┼───────────────────────────┐
       │                        │                           │
       │ F                      │ F                         │ ESC
       ▼                        ▼                           ▼
 일반 World Object       Booth Management NPC          Game Menu
       │                        │                           │
       ▼                        ▼                           ├─ Profile Summary
 Visitor Overlay         Booth Management Overlay          │    └─ My Info
 ├─ Project              │                                  ├─ Settings
 ├─ LAPTOP               ├─ Booth Mini Preview              └─ Logout
 ├─ Survey               ├─ Booth Status
 ├─ AI                   │
 └─ GAME                 ├─ Booth Studio
                          │    ├─ Layout
                          │    ├─ Facade
                          │    └─ Template
                          │
                          ├─ Project Management
                          │    └─ Project Editor
                          │
                          ├─ Survey Management
                          │    ├─ Builder
                          │    └─ Result
                          │
                          ├─ Consultation Staff
                          │
                          └─ Lease Information

                     WORLD HUD 우상단
                            │
                           💬
                            │
                   Consultation Quick Access
                            │
            ┌───────────────┼────────────────┐
            │               │                │
          Waiting          Active          Staff
            │               │              Request
            └───────────────┴────────────────┘
```

---

# 35. 다음 단계

이 문서 승인 후:

```text
1. user-flow-decisions.md → DECISION 정본 반영
2. hud-decisions.md 갱신
   - Consultation Quick Access 추가
3. implementation-decisions.md에 User Flow 결정 요약 + pointer 추가
4. README 정본 우선순위 갱신
5. 기존 target-user-flow-draft.md는 Proposal 이력으로 보존
6. 현재 !240 Presentation을 위 Flow 기준으로 재분배
7. UI Family별 Visual Anchor 재설계
8. 기능층은 유지하고 Presentation 재구현
```

---

## 문서 해석 원칙

이 문서는:
- **무엇이 어디에 있어야 하는가**
- **사용자가 어떻게 이동하는가**
- **Unity와 React가 무엇을 담당하는가**

를 정의한다.

반면:
- 현재 무엇이 실제 구현됐는가 → 최신 AUDIT / 코드
- 왜 이런 결정을 내렸는가 → Proposal / Audit
- 어떤 Visual Token을 쓸 것인가 → Foundation / Visual Design
- 언제 구현할 것인가 → Execution Plan

에서 별도로 관리한다.
