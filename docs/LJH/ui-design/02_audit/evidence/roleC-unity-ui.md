# ASC S-20260831-04 / Role C — festa-unity 사용자-facing UI·Feedback 전수조사

- 감사 기준: **origin/develop (9c0db7a)**. 경로는 `festa-unity/Assets/_Project/` 기준 상대 표기.
- 읽기 전용 조사. 파일 수정 없음.
- **기준선 주의**: 지시받은 8개 상이 파일 외에 실측으로 3개가 더 develop 과 다르다 —
  `Scripts/Content/BoothInteractionInput.cs`(105행), `Scripts/Integration/Bridge/BoothInteractBridge.cs`(11행),
  `Scripts/Network/DevConnectionHud.cs`(4행). 셋 다 **front 가 develop 보다 뒤처진**(develop 에 추가된
  근접 자동조준 S15P21A604-346·송신 토스트/Dev 게이트 S15P21A604-348 이 front 에 없음) 상태다.
  본 보고서 판정은 전부 develop 본(`git show origin/develop:…`) 기준이다.

---

## ① Unity Canvas / UI 인벤토리

씬에 미리 배선된 Canvas 는 사실상 없고(아래 Sign 제외), **전부 코드가 런타임 생성**한다.
UI Toolkit(UIDocument)·TextMeshPro 사용 **0건**. 스택은 uGUI(runtime-built) + IMGUI(OnGUI) + TextMesh + 순수 지오메트리 4종.

| UI | 종류 | render mode | sortingOrder | 생성 방식 | 그리는 것 | 근거 |
|---|---|---|---|---|---|---|
| `@InteractHint` (F 힌트 + 송신 토스트) | uGUI Canvas | Screen Space Overlay | 100 | 코드 런타임 생성 | "F — 상호작용" 하단 중앙 힌트, 브리지 송신 직후 2.5초 토스트 | develop `Content/BoothInteractionInput.cs` EnsureHint(≈L205)·OnBridgeSent(≈L272) |
| `@TimerStopGameHud` | uGUI Canvas | Screen Space Overlay | 500 (최상단) | 코드 런타임 생성 | 타이머 정지 미니게임 전체 화면(암막+패널+버튼, 한글 MalgunGothicLight) | `Minigame/TimerStopGameHud.cs:69-78` |
| `Avatar UI` (캐릭터 로비) | uGUI Canvas | Screen Space Overlay | — | 코드 런타임 생성 | 로비 커스터마이징 전체 UI(탭·그리드·컬러픽커·hex 입력) | `World/Avatar/Lobby/CharacterLobbyController.cs:241-244` |
| `Sign` ×3 (게임 부스 간판) | uGUI Canvas | **World Space** | 0 | 에디터 도구가 씬에 bake | 부스 안내 간판(판 + 한글 텍스트) | `Editor/AdminGameBoothBuilder.cs:119-140` → `Scenes/main.unity` |
| placeholder 라벨 | TextMesh (3D) | world | — | 팩토리 런타임 생성 | 프리팹 없는 부스 오브젝트 타입명 라벨 | `Booth/Factory/BoothObjectFactory.cs:102-121` |
| 층수 표시기 | **순수 지오메트리**(7세그 큐브) | world | — | 코드 런타임 생성 | 입장 게이트 엘리베이터 층수 (폰트 미사용 — "텍스트 UI 는 React 몫" 규칙 명시) | `World/Entry/GateFloorIndicator.cs:8-13` |
| 포털 F 프롬프트 | IMGUI OnGUI | screen | — | 즉시 렌더 | 키캡 스타일 `[F] + 행동 문구`, 화면 중앙 하단 고정 | `World/Festival/PortalInteractor.cs:133-175` |
| 포털 하이라이트 링 | 3D Quad + 절차 텍스처 | world | — | 런타임 생성, **로컬 전용** | 대상 부스 발밑 펄스 링 | `World/Festival/PortalInteractor.cs:78-126` |
| 감정표현 휠 | IMGUI OnGUI | screen | — | 즉시 렌더 | Alt+드래그 8방향 방사형 휠(절차 텍스처, 한글 라벨) | `Network/Player/PlayerEmoteController.cs:154-191` |
| Dev 접속 패널 | IMGUI OnGUI | screen | — | 즉시 렌더 | Host/Server/Client 접속 버튼·주소 입력 | `Network/DevConnectionHud.cs:86-159` (develop 은 `isDebugBuild/isEditor` 게이트 추가, S15P21A604-348) |
| PerfHud | IMGUI OnGUI | screen | — | 즉시 렌더 | FPS·GC·드로우콜·NGO 트래픽 (F3 토글) | `Diagnostics/PerfHud.cs:190-237`, `ToolsEnabled` 게이트 L78 |
| 아바타 커스터마이징 HUD | IMGUI OnGUI | screen | — | 즉시 렌더 | C 키 토글 인게임 커스터마이징 패널 | `World/Avatar/AvatarCustomizationHud.cs:27-40` |
| AvatarStressSpawner 패널 | IMGUI OnGUI | screen | — | 즉시 렌더 | F6/F7/F8 아바타 부하 소환 | `Diagnostics/AvatarStressSpawner.cs:252`, `PerfHud.ToolsEnabled` 게이트 L54 |
| 입장 게이트 화면 | 전용 Camera(depth 100)+Light+문 연출 | world | — | 코드 런타임 생성 | 엘리베이터 내부로 로딩 가림, 문 개폐 연출 | `World/Entry/WorldEntryGate.cs:210-247` |

씬 실측: `main.unity` 에 배선된 Canvas 는 world-space `Sign` 계열뿐. `CharacterLobby.unity`·`Verify_LaptopBridge.unity` 에는 serialized Canvas 0개.
main.unity 상주 컴포넌트: PerfHud·AvatarStressSpawner·LoadTestBot·DevConnectionHud·AvatarCustomizationHud (스크립트 GUID 매칭 실측).

## ② Interaction 파이프라인 (develop 기준)

**계약**: `Content/IBoothInteractable.cs:25-29` — `void Interact()` 단일 메서드. 디스패처가 `GetComponentInParent<IBoothInteractable>()` 로 자동 발견(타입 나열 분기 제거, S15P21A604-303). 한 오브젝트에 2개 이상 금지(먼저 찾은 것만 실행).

**감지 (2단계, develop `Content/BoothInteractionInput.cs` Update)**:
1. **마우스 조준** — 매 프레임 `Physics.Raycast(cam.ScreenPointToRay(pointer))` (MaxRayDistance 5000f). 명중 대상이 `Interactive`(IBoothInteractable 실보유) && 사거리 안이면 타깃. `OnMouseXXX` 는 Unity6 WebGL 에서 불발(T-166)이라 폐기.
2. **근접 자동 조준 (S15P21A604-346)** — 조준이 없으면 `BoothInteractionTarget.Active` 정적 레지스트리(OnEnable 자기등록, develop `Booth/Interaction/BoothInteractionTarget.cs` L25-28)를 선형 탐색해 사거리 안 최근접 대상을 자동 타깃. "가까이 가면 F" 기대 동작 충족.

**거리 판정**: 카메라가 아니라 **플레이어 위치 기준**(`InteractionOrigin()` — NGO LocalClient.PlayerObject; 3인칭 카메라 오프셋 오판 방지). 사거리는 develop `Booth/Factory/BoothObjectFactory.cs` AttachCommonInteraction — interactive 40f / non-interactive 29f (**월드 유닛**, 1m≈13.26unit, S15P21A604-339·350 실측 보정).

**실행 입력**: **F 키 단독** (S15P21A604-323). 클릭은 조준·호버 전용. New Input System 우선, 레거시 폴백. 주의 — 코드 주석 자인: docs/02 RUNTIME-06·spec 016 은 아직 "클릭하면"으로 기술되어 계약 문서와 어긋남(갱신 필요 명시됨).

**F Prompt**: **Unity uGUI 가 그린다** (React 아님) — `@InteractHint` ScreenSpaceOverlay canvas, sortingOrder 100(미니게임 500 아래), 화면 하단 중앙 고정 "F — 상호작용". 표시 조건 = 타깃 존재(조준 or 근접). HUD 보고서 §4 "F Prompt Unity 전담" 원칙과 일치.

**Highlight**: `BoothInteractionTarget.SetHighlighted` — MaterialPropertyBlock 으로 `_EmissionColor` 를 하늘색(0.25,0.7,1)×0.65 로 올리는 **비파괴 emission 하이라이트**. Outline 셰이더 아님. 콜라이더 없으면 렌더러 bounds 로 BoxCollider 자동 생성. 포털 쪽은 별도로 발밑 링(PortalInteractor).

**디스패치 대상 (IBoothInteractable 구현 4종)**:
- `LaptopInteractable` (Factory 가 LAPTOP 타입에 부착) → `BoothInteractBridge.SendLaptopInteract`
- `AiNpcInteractable` (AI_AGENT 타입) → `SendAiAgentInteract` (configId 0 = 미연결 → 브리지가 차단+경고)
- `MinigameInteractable` (**팩토리 아님** — `Editor/AdminGameBoothBuilder.cs:80` 이 에디터에서 씬 bake) → React 이벤트 없이 **Unity 내부에서 `TimerStopGameHud.Open()`**
- `VideoScreenPlaceholder` 는 IBoothInteractable 미구현(로그만 — 상호작용 없음)

포털(부스 이동)은 이 파이프라인 밖의 별도 경로: `PortalInteractor`(NetworkBehaviour, Owner 전용) + `BoothPortal.All` 레지스트리, interactRadius 30f, F 로 텔레포트.

## ③ Unity → React 이벤트 전수

경로: C# → `Assets/Plugins/WebGL/FestaUnityBridge.jslib` → `window.FestaUnity.*` → `festa-frontend/src/unity/bridge/events.ts` → `features/interaction/dispatcher.ts` → `openOverlay()`.

| 이벤트 | 송신부 (Unity) | payload | React 수신 | 판정 |
|---|---|---|---|---|
| `BOOTH_LAPTOP_INTERACT` | develop `Integration/Bridge/BoothInteractBridge.cs` SendLaptopInteract | `{type, boothId, objectId}` — url 필드 **제거됨**(S15P21A604-297, FE 합의 #97) | `events.ts:6-12` + `dispatcher.ts:13-14` → `openOverlay('LAPTOP')` → `LaptopOverlay.tsx` | 양측 구현. 단 FE `events.ts:11` 에 `url?` 선택 필드가 **잔존**(계약상 죽은 필드 — 실전송 JSON 은 동일하므로 동작 문제 없음, 문서 정합성만 낡음) |
| `AI_AGENT_INTERACT` | 동 파일 SendAiAgentInteract (configId 0 차단) | `{type, boothId, objectId, configId}` — configId→agentId 변환은 FE(`toAiChatPayload`) | `events.ts:13-18` + `dispatcher.ts:16-17` → `openOverlay('AI_CHAT')` | 송수신 구현. 단 AI_CHAT UI 는 `OverlayHost.tsx:34-43` "준비 중입니다" 임시 플랫폼 — **오버레이 본체 미구현** |
| `BOOTH_GAME_INTERACT` | **Unity 송신부 없음** (develop 전체 grep 0건) | (계약: `{type, boothId, objectId, configId}`, Issue #20) | `events.ts:19-24` + `dispatcher.ts:19-20` → `GameOverlay.tsx` 구현 완료 | **비대칭**: React 는 수신·GameOverlay 까지 있는데 Unity 는 안 보낸다 — 현재 게임 부스 F 는 Unity 자체 `TimerStopGameHud` 를 연다(⑤ 참조) |
| `onWorldGateReady` | `World/Entry/WorldEntryGate.cs:328-343` NotifyGateReady (게이트 개방 순간 1회) | payload 없음 | `events.ts:58-60` + `UnityHost.tsx:35-41` (60초 타임아웃 폴백) | 양측 구현 일치 (Issue #31, spec 002 FR-013·014) |
| `onAvatarApplied` | `World/Avatar/AvatarBridge.cs` NotifyApplied — **`#if UNITY_WEB`(오타로 추정, `UNITY_WEBGL` 아님) + jslib 함수 미존재** | `{...appearance json}` | FestaUnity 타입 선언에 없음, 수신부 없음 | **STUB/BLOCKED** — jslib 에 `FestaNotifyAvatarApplied` 미정의라 WebGL 링크 시 사용 불가 상태(다만 `UNITY_WEB` 심볼이 어디서도 정의되지 않아 컴파일 자체가 빠져 실해는 없음) |

## ④ React → Unity (역방향)

| 경로 | 구현 | 근거 |
|---|---|---|
| Access Token 주입 | `unityInstance.SendMessage('AuthBridge','SetAccessToken', t)` / `ClearAccessToken` — Refresh Token 은 Unity 로 안 넘김 | `Integration/AuthBridge.cs` (자동 등록 오브젝트) — 단 FE 쪽 실호출부는 미구현(`shared/api/client.ts:47` 에 "언제·어떤 방식으로 전달할지" 미정 주석) |
| 아바타 적용 | `SendMessage('AvatarBridge','ApplyAppearance','sk_02\|c=…')` / `GetAppearance` / 프리셋 JSON | `World/Avatar/AvatarBridge.cs` — FE 측 호출부 미확인(계약 문서만) |
| **입력 잠금/복구 (Overlay open 시)** | **미구현.** FE 전체에 `captureAllKeyboardInput` 0건, Unity 에 input lock 수신 API 0건. `GameOverlay.tsx:35-38` 만 activeElement.blur()+자기 focus+Esc 캡처 — Unity WebGL 은 기본이 전역 키보드 캡처라 blur 로는 WASD/F 가 계속 월드에 들어간다 | HUD 보고서 §9 "Character Input → Overlay 종류에 따라 Lock / Close → Input Context 복원" 규칙의 **양측 모두 부재** |
| 카메라 정지/Dim | 미구현 (Unity 측 대응 API 없음, FE 도 없음) | HUD 보고서 §9 |

## ⑤ 즉각 피드백 (상호작용 성공/실패)

| 피드백 | 구현 | 근거 |
|---|---|---|
| Highlight (호버/근접) | 있음 — emission 하이라이트(부스 오브젝트), 발밑 링+펄스(포털) | ②, `PortalInteractor.cs:92-93` |
| F Prompt | 있음 — uGUI 힌트(부스), IMGUI 키캡(포털) | ② |
| **송신 확인 토스트** | develop 에 있음 — 브리지 `OnSent` 이벤트 → "홈페이지 열기 요청을 보냈습니다 — 웹 화면에서 열립니다" / "AI 직원 호출을 보냈습니다…" 2.5초 표시. **보내지 않은 경우(차단) 미발화** — 거짓 피드백 방지 명시 | develop `BoothInteractBridge.cs` OnSent(S15P21A604-348) + `BoothInteractionInput.cs` OnBridgeSent |
| 사운드 | **없음** — 상호작용 효과음 0건 (WorldBgm·FestivalAmbience 는 환경음) | grep 실측 |
| 애니메이션 | 상호작용 자체 애니메이션 없음(이모트는 별도 기능). 게이트 문 개폐·링 펄스가 유일한 연출 | — |
| 실패 피드백 | configId 0·objectId 누락 시 **로그 경고만** — 사용자 화면 피드백 없음. HUD 보고서 §4 의 "sound/animation/highlight/interaction state" 중 highlight 만 충족 | `BoothInteractBridge.cs:70-81` |

## ⑥ Ownership 충돌 (경계 원칙: world-space·동적=Unity / 화면고정 overlay·복잡정보=React)

1. **TimerStopGameHud — 최대 충돌.** 화면 고정 풀스크린 모달(암막+패널+버튼, sortingOrder 500)을 Unity uGUI 가 그린다. 경계상 React Overlay 성격이며, React 는 이미 `BOOTH_GAME_INTERACT`→`GameOverlay` 경로를 완성해 두었다 — **같은 기능의 이중 구현 + Unity 이벤트 미송신 비대칭**. Unity 쪽 사유는 코드에 명시(FE 의존 제거·T-22 는 IMGUI 한정) — 의도적 결정이나 경계 문서·React 구현과 정면 충돌. 게임 F → 어느 쪽이 열려야 하는지 3파트 재확정 필요.
2. **송신 토스트 (develop `@InteractHint` Toast)** — Toast/Notification 은 HUD 보고서 §5 에서 **React 소관(Post-MVP)** 으로 확정된 항목인데 Unity 가 화면 고정 토스트를 그린다. 단독 실행 검증용이라는 사유는 있으나 릴리즈 빌드에서도 그려진다(게이트 없음).
3. **AvatarCustomizationHud — 게이트 없는 잔존 POC.** 정식 소유권은 React(계약 문서 자인)인데, **릴리즈 WebGL 에서도 C 키로 열리는** IMGUI 패널이 main.unity 에 상주한다. batchMode·IsClient 검사만 있고 `ToolsEnabled`/`isDebugBuild` 게이트가 없다 — S15P21A604-348 이 DevConnectionHud 에만 적용한 게이트의 누락 지점.
4. **DevConnectionHud** — develop 은 그리기만 debug 게이트(진입 소비 로직은 유지). front 는 게이트 미반영 상태.
5. **UnityHost 로딩/실패 UI 는 React**(불러오는 중 %·재시도 버튼), 월드 내부 로딩 연출은 Unity(엘리베이터) — 경계 준수 사례.
6. 경계 **준수** 확인: F Prompt·Highlight·게이트 층수 표시(폰트 회피, 지오메트리)·포털 프롬프트 — 전부 Unity 담당 범위. 이름표(nameplate)류 world-space 추적 UI 는 **존재하지 않음** — `NetworkPlayer.Nickname` NetworkVariable 은 있으나 어떤 코드도 렌더링하지 않는다(React DOM 추적 구현도 없음 — 보고서 §6 "이름표" 후보는 미구현 상태).
7. 이모트 휠 — 화면 고정 IMGUI 지만 프레임 결합 조작 UI 라 Unity 귀속이 자연스러움. 충돌로 보지 않되, IMGUI 한글(MalgunGothicLight 로드)이라 T-22 재발 감시 대상.

## ⑦ 구현 상태 분류

| 항목 | 상태 |
|---|---|
| IBoothInteractable 계약 + 중앙 디스패처(레이캐스트+근접 자동조준) | IMPLEMENTED (develop) |
| F Prompt (uGUI 힌트) + Emission Highlight | IMPLEMENTED |
| BOOTH_LAPTOP_INTERACT 송수신 + LaptopOverlay | IMPLEMENTED (FE `url?` 잔존 필드만 낡음) |
| AI_AGENT_INTERACT 송수신 | IMPLEMENTED / AI_CHAT 오버레이 UI 는 **NOT_IMPLEMENTED**(React "준비 중" 플레이스홀더) |
| BOOTH_GAME_INTERACT | **PARTIAL — 비대칭**: React 수신·GameOverlay IMPLEMENTED, Unity 송신 NOT_IMPLEMENTED(Unity 는 자체 HUD 를 엶) |
| onWorldGateReady + 입장 게이트 연출(층수·문) | IMPLEMENTED |
| onAvatarApplied (Unity→React) | **STUB/BLOCKED** — `UNITY_WEB` 오심볼 + jslib 함수 미정의 |
| React→Unity Token 주입(AuthBridge) | Unity IMPLEMENTED / FE 호출부 NOT_IMPLEMENTED(방식 미정 주석) |
| React→Unity 아바타(AvatarBridge SendMessage) | Unity IMPLEMENTED / FE 호출부 미확인 |
| Overlay open 시 Unity Input Lock / Close 복구 | **NOT_IMPLEMENTED (양측)** — HUD 보고서 §9 규칙 전체가 코드에 없음 |
| 상호작용 사운드/실패 사용자 피드백 | NOT_IMPLEMENTED |
| 이름표 등 world-space 추적 동적 UI | NOT_IMPLEMENTED (Nickname 데이터만 존재) |
| 미니게임 HUD (TimerStopGameHud) | IMPLEMENTED — 단 소유권 충돌(⑥-1) |
| 포털 상호작용(링+키캡+텔레포트) / 이모트 휠 | IMPLEMENTED |
| Dev 도구 게이트 | PARTIAL — PerfHud·StressSpawner 게이트 OK, DevConnectionHud 는 develop 만 게이트, **AvatarCustomizationHud 게이트 없음(릴리즈 노출)** |
