# UI Ownership Matrix (Unity / React / Discard)

경계 원칙(HUD 보고서 확정): world-space·동적·프레임 결합 = UNITY / 화면 고정 overlay·상시·복잡 정보 = REACT / 필요성 없으면 DISCARD.

| UI Element | Current Owner | Target Owner | MVP/Post-MVP | Evidence | Notes |
|---|---|---|---|---|---|
| F Prompt (@InteractHint) | Unity uGUI | **UNITY** | MVP | BoothInteractionInput.cs (develop) | 경계 준수. 원칙 일치 |
| Highlight (emission) / 포털 링 | Unity | **UNITY** | MVP | BoothInteractionTarget.cs, PortalInteractor.cs | 준수 |
| 근접 자동조준·F 입력 | Unity | **UNITY** | MVP | -346, -323 | 준수 |
| 입장 게이트 연출·층수 표시 | Unity | **UNITY** | MVP | WorldEntryGate.cs, GateFloorIndicator.cs | 층수는 폰트 회피 지오메트리 — "텍스트는 React" 규칙 명시 준수 |
| 이모트 휠 | Unity IMGUI | **UNITY** | MVP | PlayerEmoteController.cs:154 | 프레임 결합 조작 UI. T-22(IMGUI 한글) 감시 |
| **TimerStopGameHud (미니게임 풀스크린)** | Unity uGUI (so=500) | **REACT (GameOverlay)** — 3파트 재확정 필요 | MVP | TimerStopGameHud.cs:69 vs GameOverlay.tsx | **최대 충돌.** 같은 기능 이중 구현 + Unity 가 BOOTH_GAME_INTERACT 미송신. Unity 코드 수정 유발 → 합의 사안 |
| **송신 확인 토스트** (@InteractHint Toast) | Unity (develop, 게이트 없음) | **REACT** (Toast — Post-MVP) 또는 dev 게이트 | Post-MVP | BoothInteractBridge.cs OnSent (-348) | Toast 는 React 소관 확정 항목. 릴리즈 노출 중 — 합의 사안 |
| **AvatarCustomizationHud (C 키)** | Unity IMGUI (게이트 없음) | **DISCARD**(게이트) → React Profile | MVP 정리 | AvatarCustomizationHud.cs:27, main.unity 상주 | 릴리즈 WebGL 노출 잔존 POC. -348 게이트 누락 지점. Unity 수정 유발 → 합의 사안 |
| DevConnectionHud / PerfHud / StressSpawner | Unity IMGUI (debug 게이트) | UNITY (dev 전용) | — | DevConnectionHud.cs (develop 게이트) | front 는 게이트 미반영 — develop sync 대상 |
| 이름표(nameplate)류 | 없음 (Nickname 데이터만) | UNITY (만들 경우) 또는 DISCARD | Post-MVP | NetworkPlayer.Nickname | 미구현. 관성 도입 금지 원칙 |
| Minimap/Quest/HP/Crosshair | 없음 | **DISCARD** | — | HUD 보고서 §6 | 신규 요구로 추가하지 않음 |
| LAPTOP/AI/GAME/SURVEY/CONSULTATION overlay | React | **REACT** | MVP(LAPTOP·AI·GAME) / 이후(나머지) | OverlayHost.tsx | 준수 |
| UnityHost 로딩·실패·재시도 UI | React | **REACT** | MVP | UnityHost.tsx:60-89 | 준수 사례 |
| 이동·조작 안내 / 미니게임 점수 / Toast | 없음 | **REACT** | **Post-MVP Optional** | HUD 보고서 §5 | 3종 한정. Core 디자인을 막지 않음 |
| Overlay open 시 Input Lock/복구 | **없음 (양측)** | UNITY(수신 API)+REACT(호출) 공동 | MVP | captureAllKeyboardInput 0건 (roleC ④) | HUD §9 전체 공백. WASD/F 가 overlay 열림 중에도 월드로 들어감 — 계약 신설 필요(합의 사안) |
| 카메라 정지/Dim | 없음 (React Dim 은 CSS 로 가능) | REACT(Dim) + UNITY(camera pause 계약) | MVP~ | roleC ④ | Dim 은 React 단독 선행 가능 |

## Ownership 충돌 요약 (합의 필요 — 전부 Unity 코드 수정 유발)
1. TimerStopGameHud vs React GameOverlay — 게임 F 가 어느 쪽을 여는가.
2. Unity 송신 토스트 — React Toast 소관 확정과 충돌(릴리즈 게이트 없음).
3. AvatarCustomizationHud 릴리즈 노출 — 게이트 추가 필요.
4. Input Lock/복구 계약 신설 — bridge API 없음.
(+ FE 단독 처리 가능: LaptopOverlay url 의존 → homepageUrl 조회 전환, events.ts url? 잔존 필드 정리)
