# SSAFY FESTA Game Client Experience Draft

> **Status:** PROPOSAL / DRAFT  
> **Purpose:** SSAFY FESTA를 브라우저 기반의 하나의 실행형 게임 클라이언트처럼 동작시키기 위한 UX·입력·오디오·라이프사이클 구조 초안.  
> **Important:** 본 문서는 팀 확정 계약이나 구현 SSOT가 아니다. World/GameShell prototype 검증 결과에 따라 수정하고, 검증된 결정만 `implementation-decisions.md` 및 Foundation 문서로 승격한다.

---

## 1. 목적

SSAFY FESTA의 최종 경험을 단순한 “웹페이지 안의 Unity”가 아니라 다음과 같은 **Persistent Game Client**로 만든다.

```text
Persistent GameShell
├─ Unity World Layer
├─ React Screen Layer
├─ React Overlay Layer
├─ Overlay Stack
├─ Input Router
├─ Cursor Manager
├─ UI Audio Manager
├─ Fullscreen / Pointer Lock Manager
├─ Lifecycle Manager
├─ Client Settings
├─ Session Manager
├─ Network Recovery
├─ Cache / Preload
└─ Update Manager
```

핵심 목표:

- React 화면/Overlay 변화로 Unity를 불필요하게 재생성하지 않는다.
- World → Overlay → World 복귀가 즉각적이어야 한다.
- 입력, 커서, 사운드, 메뉴, 설정을 페이지별 임시 구현이 아닌 GameShell 공통 시스템으로 관리한다.
- 일반 웹사이트가 아니라 실행형 PC 게임에 가까운 조작감과 피드백을 제공한다.

---

## 2. Target Client Architecture

```text
Authenticated FESTA
└─ Persistent GameShell
   ├─ Unity World Layer
   │  ├─ 3D World
   │  ├─ Player / NPC / Booth
   │  └─ Unity-owned immediate interaction UI
   │
   ├─ React Screen Layer
   │  ├─ Home / Profile
   │  ├─ Loading / Fatal / Retry
   │  └─ DesktopRequiredGate
   │
   └─ React Overlay Layer
      ├─ PROJECT
      ├─ LAPTOP
      ├─ SURVEY
      ├─ CONSULTATION
      ├─ GAME
      ├─ AI
      ├─ Game Menu / Settings
      └─ Toast
```

### Unity 소유

- F 상호작용 prompt
- target detection / highlight
- world-following UI
- 상호작용 애니메이션 / 사운드
- World BGM / World SFX
- frame-coupled 즉시 반응 UI

### React 소유

- 화면 고정 Overlay
- 복잡한 정보/입력 UI
- 메뉴 / 설정
- Profile / Project / Survey / Consultation 등
- Toast / Notification
- UI SFX
- React-owned 접근성 및 포커스

---

## 3. Game Menu / Settings

### 기본 ESC 동작 후보

```text
ESC
├─ Overlay 있음
│  └─ 최상위 Overlay 닫기
├─ Overlay 없음 + Game Menu 닫힘
│  └─ Game Menu 열기
└─ Game Menu 열림
   └─ Game Menu 닫기 → World 복귀
```

### Game Menu 후보

```text
게임으로 돌아가기
설정
로그아웃
```

상시 큰 톱니바퀴 버튼보다는 **ESC → Game Menu → 설정** 구조를 우선 후보로 한다.

모바일/터치 환경에서는 후속으로 별도 메뉴 버튼을 제공한다.

---

## 4. Input Router / ESC / Overlay Stack

페이지별 `keydown` 분산 구현 대신 공통 Input Context를 사용한다.

```text
TEXT_INPUT
> OVERLAY
> GAME_MENU
> WORLD
```

### World

- WASD: 이동
- Mouse: 카메라
- F: Unity 상호작용
- ESC: Game Menu

### Overlay

- WASD / F / Space 등 World 입력 차단
- ESC: 최상위 Overlay 닫기
- Enter: 확인 후보
- Tab / Arrow: React UI 탐색

### Text Input

입력 필드가 포커스를 가진 동안 일반 문자키를 World shortcut으로 소비하지 않는다.

---

## 5. Unity Input Lock

React Overlay가 열린 동안 뒤의 Unity 캐릭터가 움직이거나 상호작용하지 않아야 한다.

```text
Overlay OPEN
→ React notifyOverlayState(...)
→ Unity Player Input Lock

Overlay CLOSED
→ React notifyOverlayState(...)
→ Unity Player Input Restore
```

현재 FE seam이 존재하는 경우 이를 유지하고, Unity receiver/object/API가 확정되기 전에는 임의 계약을 만들지 않는다.

**Priority:** P0  
**Status:** FE seam / external receiver 확인 필요

---

## 6. Cursor / Pointer

공통 `CursorManager` 후보:

```text
WORLD
UI_DEFAULT
INTERACTIVE
DRAG
DISABLED
LOCKED/HIDDEN
```

지원 후보:

- FESTA 기본 cursor
- 클릭 가능한 버튼 hover cursor
- disabled 상태
- drag / grabbing
- World 카메라 모드에서 cursor 숨김 또는 Pointer Lock

CSS cursor와 Unity canvas cursor 정책은 동일한 UX를 목표로 정합한다.

---

## 7. Interaction Feedback

모든 interactive element는 최소 다음 상태를 가진다.

```text
Idle
Hover
Focused
Pressed
Disabled
```

### Hover 후보

- 밝기/경계 변화
- 1~2px 상승
- cursor 변화
- hover SFX

### Press 후보

- 약한 scale-down
- click SFX
- 시각적 pressed 상태

Hover는 부가 기능이며, 필수 정보나 조작을 hover에만 의존하지 않는다.

---

## 8. Audio Architecture

### 목표 Audio Bus

```text
Master
├─ Music
├─ World SFX
├─ UI SFX
└─ Voice
```

### 소유권

```text
React
├─ Settings UI
├─ UI SFX
└─ 설정값 관리

Unity
├─ BGM
├─ World SFX
└─ AudioMixer

React ↔ Unity Bridge
└─ volume 설정 전달
```

### React UI Audio 후보

```text
hover
click
confirm
cancel
open
close
success
warning
error
purchase
notification
```

버튼/페이지마다 오디오를 직접 재생하지 않고 semantic event 기반 공통 `UIAudioManager` 사용을 우선한다.

### Unity BGM 연동

Unity가 World 진입 후 BGM을 재생하므로 Settings의 Music/SFX 값은 React → Unity Bridge → Unity AudioMixer 형태로 전달해야 한다.

후보 계약 개념:

```text
SetMasterVolume
SetMusicVolume
SetSfxVolume
```

실제 함수명/receiver는 Unity 파트 계약 전까지 확정하지 않는다.

**Status:** PROPOSAL / CROSS-PART CONTRACT REQUIRED

---

## 9. Social / Text Chat / Voice Chat

팀 피드백을 반영한 신규 제안 영역.

### Social Settings 후보

```text
Text Chat 표시 ON/OFF
Chat Notification ON/OFF
Voice Chat 참여 ON/OFF
Voice Volume
Mic Volume
Push-to-Talk
Input Device
Output Device
```

### 반드시 별도로 결정할 것

- Text Chat OFF가 UI 숨김만 의미하는지 subscription 중단까지 의미하는지
- Voice Chat 실제 제품 범위
- Voice transport
- room/channel
- Push-to-Talk key
- 사용자별 mute
- 마이크 권한 거부 UX
- 연결 끊김/재연결

`AI Chat`과 플레이어 간 Text/Voice Chat은 별도 기능으로 본다.

**Settings UI concept:** PROPOSAL  
**Voice Chat implementation:** PRODUCT / CONTRACT DECISION REQUIRED

---

## 10. Fullscreen / Pointer Lock

### Fullscreen

Landing의 사용자 클릭을 다음 초기화 지점 후보로 사용할 수 있다.

```text
Landing Start Gesture
├─ Audio unlock
├─ Fullscreen request (optional)
└─ Game Client activate
```

브라우저/OS 단축키까지 완전히 제어할 수 없으므로 fallback을 전제로 한다.

### Pointer Lock

3D World 카메라의 게임 감각을 높일 수 있으나 3인칭 구조이므로 필수는 아니다.

후보 흐름:

```text
World
→ Pointer Lock optional

Overlay Open
→ Pointer Lock release
→ Cursor UI mode

Overlay Close
→ World focus / optional reacquire
```

**Priority:** P1  
**Status:** UX validation required

---

## 11. Lifecycle / Pause / Visibility

브라우저 탭 전환, 화면 숨김, 복귀를 게임 클라이언트 상태로 관리한다.

```text
ACTIVE
HIDDEN
PAUSED/DEGRADED
RESUMING
```

후보 동작:

- hidden 시 입력 중지
- BGM/UI Audio 정책 적용
- 필요 시 World pause/request
- 복귀 시 서버 상태 재동기화
- “게임으로 돌아가기” 상태 처리

Quit은 원칙적으로 다음에 한정한다.

```text
logout
fatal reset
explicit app teardown
```

---

## 12. Loading / Error / Network Recovery

일반 웹 spinner보다 게임 boot/recovery UX를 우선한다.

### Loading

```text
SSAFESTA
Preparing Festival...
████████░░
```

가능하면 실제 Unity loader/session/asset readiness와 연결한다.

### Error

```text
월드 입장에 실패했습니다.
[다시 시도]
```

### Network

```text
CONNECTED
DEGRADED
RECONNECTING
OFFLINE
RECOVERED
```

가능한 경우 GameShell을 유지하고 fatal 상황에서만 전체 teardown한다.

---

## 13. Client Settings / Accessibility

### Audio

- Master
- Music
- World SFX
- UI SFX
- Voice

### Controls

- Mouse sensitivity
- Invert Y
- Key bindings
- Controller sensitivity

### Graphics

- Fullscreen
- Quality preset
- Resolution scale
- Effects / Shadow / FPS cap 후보

### Social

- Text Chat
- Chat Notification
- Voice Chat

### Accessibility

- Reduce Motion
- Reduce Transparency
- UI Scale
- Text Scale
- High Contrast 후보
- Mute UI Sounds
- Keyboard navigation
- Focus indicator
- Captions/Subtitles 후보

---

## 14. Cache / Preload / Update

### Preload / Cache

목표:

```text
Landing / Login
→ 필요한 경우 Unity preload

World 진입
→ 준비된 session 재사용

재접속
→ cache hit로 빠른 진입
```

Unity/React asset cache는 각 플랫폼에 맞는 방식으로 관리한다.

### Update / Patch

향후:

```text
새 버전 감지
→ "새로운 FESTA가 준비되었습니다"
→ 안전한 재시작
```

형태의 게임 패치 UX 후보.

---

## 15. Gamepad / PWA / Post-MVP

### Gamepad

후보:

```text
Left Stick  이동
Right Stick 카메라
A/X         확인/상호작용
B/O         취소
Start       Game Menu
```

마지막 입력 장치에 따라 화면의 키 힌트를 자동 전환하는 방식도 검토한다.

### PWA

Post-MVP에서 설치형 Game Mode 후보:

```text
SSAFESTA 아이콘
→ standalone app window
→ Landing
→ Game
```

### 기타 후보

- Wake Lock
- Haptics
- Multi-tab session protection
- Adaptive graphics
- Mobile touch controls
- Orientation handling

---

## 16. Priority

### P0 — 실행형 게임 감각의 핵심

1. Persistent GameShell
2. Overlay Stack
3. ESC Overlay close / Game Menu hierarchy
4. Input Router
5. Unity Input Lock
6. World focus restore
7. Cursor Manager
8. Hover / Press / Focus feedback
9. UI Audio
10. Game-style Loading / Error / Retry
11. World ↔ Overlay 즉시 복귀
12. Game surface의 불필요한 browser artifact 제거

### P1 — PC 게임 클라이언트 고도화

1. Game Menu
2. Settings
3. React ↔ Unity Audio Mixer
4. Fullscreen
5. Pointer Lock 정책
6. Audio Mixer
7. Graphics / Input Settings
8. Gamepad
9. Input modality 자동 전환
10. Network Recovery
11. Wake Lock
12. Preload / Cache
13. Transition / Motion
14. Multi-tab session protection

### P2 / Scope Decision

1. Text Chat preferences
2. Voice Chat
3. Push-to-Talk
4. Haptics
5. PWA standalone
6. Update / Patch UX
7. Mobile touch controls
8. Adaptive graphics

---

## 17. Open Decisions

다음은 아직 확정하지 않는다.

- Game Menu 진입 방식 / 상시 메뉴 hint 필요 여부
- React ↔ Unity AudioMixer 계약
- Master / Music / SFX / UI / Voice volume model
- Text Chat OFF semantics
- Voice Chat 제품 범위
- Voice transport / channel
- Push-to-Talk
- Fullscreen 채택 여부
- Pointer Lock 채택 여부
- Cursor custom asset / state 체계
- Gamepad 우선순위
- PWA 채택 여부

이 항목은 `07_handoff/decision-queue.md`와 연계해 관리한다.

---

## 18. Validation / Promotion Criteria

본 문서의 Proposal은 다음 순서로 검증한다.

```text
World Reference
→ Persistent GameShell Prototype
→ Project Overlay Prototype
→ Game Client Experience P0 검증
→ Complex UI 확보
→ Visual Foundation 추출
→ Remaining UI Straight
→ P1/P2 고도화
```

### DECISION으로 승격 조건

다음이 실제 브라우저에서 검증되면 `00_context/implementation-decisions.md`로 승격한다.

예:

- ESC가 Overlay Stack 최상단을 안정적으로 닫음
- Overlay open 시 Unity Input Lock 동작
- close 후 World focus/Input이 즉시 복구됨
- Unity session이 React 화면 변화로 재생성되지 않음
- Cursor/Audio 공통 manager가 여러 화면에서 재사용 가능
- Settings ↔ Unity Audio 계약이 실제 동작

### Foundation으로 승격 조건

실제 여러 화면에서 공통으로 검증된 다음만 `03_foundation/`으로 승격한다.

- hover / pressed / focus
- cursor visual states
- overlay motion
- surface / radius / spacing / typography
- button / field / card / modal visual language

Landing/Login 단독 값은 전역 Foundation으로 바로 승격하지 않는다.

---

## 19. 기존 UI 전체 작업 계획과의 연결

권장 순서:

```text
1. Game Client Experience Draft 저장        ← 현재
2. world.png 생성/선택
3. world-project-overlay.png 생성/선택
4. Persistent GameShell 구현
5. Project Overlay 구현
6. Game Client Experience P0 검증
   - ESC
   - Overlay Stack
   - Input Router
   - Input Lock
   - Cursor
   - Interaction feedback
   - UI Audio
7. Booth/Lease 등 복합 UI 구현
8. Visual Foundation 추출
9. Primitive 공통화
10. 나머지 페이지 UI Straight
11. Game Client Experience P1
    - Settings
    - Unity BGM/SFX 계약
    - Fullscreen / Pointer Lock 등
12. Social / Voice 범위 확정 시 구현
13. 전체 Visual / Interaction Polish
    - Landing/Login 재방문 포함
```

현재는 Settings/Voice Chat까지 구현 범위를 즉시 확대하지 않는다.  
먼저 World → GameShell → Project Overlay에서 P0 구조를 검증한다.

---

## 20. 권장 문서 배치

```text
docs/LJH/ui-design/
├─ 00_context/
│  ├─ implementation-decisions.md
│  └─ hud-decisions.md
├─ 03_foundation/
├─ 04_prototypes/
│  ├─ world-reference-brief.md
│  └─ game-client-experience-draft.md   ← 본 문서
└─ 07_handoff/
   └─ decision-queue.md
```

연계 갱신 권장:

- `README.md`: 본 문서 포인터 추가, PROPOSAL임을 명시
- `EXECUTION_PLAN.md`: World → GameShell → Project Overlay → Game Client P0 → Complex UI → Foundation 순서 반영
- `07_handoff/decision-queue.md`: Audio/Settings/Text Chat/Voice Chat/Fullscreen/Pointer Lock 미확정 항목 추가

아직 바로 갱신하지 않을 곳:

- `00_context/implementation-decisions.md`
- `03_foundation/*`

검증된 항목만 후속 승격한다.
