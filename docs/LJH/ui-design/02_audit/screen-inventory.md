# Screen Inventory — SSAFY FESTA UI 전수조사

- Audit baseline: origin/develop = 9c0db7a (festa-frontend 는 front 와 완전 동일)
- ASC: S-20260831-01 (통합) / -02(A React) -03(B Style) -04(C Unity) -05(D 의존·계약)
- 상세 근거: roleA-react-surface.md / roleB-style-foundation.md / roleC-unity-ui.md / roleD-dependency-contract.md

| Domain | Screen/UI | Current Route/Entry | Implementation | Style State | Data | Runtime Consumer | Evidence |
|---|---|---|---|---|---|---|---|
| Shell | Application Shell (Persistent GameShell) | 없음 (flat 9 route + RequireAuth 별 AuthHeader) | NOT_IMPLEMENTED | — | — | — | app/router/index.tsx, RequireAuth.tsx:15-38 |
| Entry | Landing / Title | `/`→`/app/home` (`<div>home</div>`) | STUB | 무스타일 | MOCK | — | router/index.tsx:34-40 |
| Auth | Login | `/login` | IMPLEMENTED (SSAFY provider 없음 — Google/Kakao/게스트) | 무스타일 | REAL | BE auth 완결 | pages/login/LoginPage.tsx |
| Auth | OAuth Callback | `/auth/callback` | IMPLEMENTED | 무스타일 | REAL | BE 완결 | pages/auth/CallbackPage.tsx:38-110 |
| Auth | First Setup(닉네임) | CallbackPage 내부 분기 | IMPLEMENTED | 무스타일 | REAL | BE 완결 | features/auth/ui/NicknameForm.tsx |
| World | World (Unity Host) | `/app/world` (lazy) | IMPLEMENTED — HUD 전무 | 무스타일 | HYBRID (loader.mock seam) | Unity WebGL 서빙 경로 미결(#30) | pages/world/WorldPage.tsx, unity/host/UnityHost.tsx |
| Booth | 슬롯 목록 / Lease | `/app/booths` | IMPLEMENTED | 무스타일 | REAL | BE 완결 | pages/booth/SlotListPage.tsx |
| Booth | Facade 편집 | StudioPage 내 FacadePanel | IMPLEMENTED | 무스타일 | REAL | BE 완결 | features/studio/ui/FacadePanel.tsx |
| Booth | Homepage(iframe) | LAPTOP overlay 내부 | IMPLEMENTED — 단 URL 소스가 계약(016 homepageUrl)과 불일치 → 항상 "미준비" | 무스타일 | REAL(배선 수정 필요) | BE GET /booths/{id} homepageUrl 존재 | LaptopOverlay.tsx:36, roleD ② |
| Wallet | 잔액·거래내역 | 전용 화면 없음 — SlotListPage 삽입 | PARTIAL | 무스타일 | REAL | BE 완결 | features/wallet/ui/* |
| Profile | Profile/계정/아바타 | 없음 | NOT_IMPLEMENTED | — | REAL 가능 (BE users/me 4종 존재) | MyAccountController | roleD ① |
| Overlay | LAPTOP | Unity BOOTH_LAPTOP_INTERACT | IMPLEMENTED (positioning CSS 없음 — floating panel 아님) | 무스타일 | REAL | Unity 송신 develop 구현 | OverlayHost.tsx:21, roleC ③ |
| Overlay | GAME | Unity BOOTH_GAME_INTERACT | IMPLEMENTED(React) / Unity 송신부 없음 | ggo CSS (z-10000) | HYBRID | BE game-portals 미구현 + Unity 미송신 | GameOverlay.tsx, roleD ② |
| Overlay | AI_CHAT | Unity AI_AGENT_INTERACT | PARTIAL — 배선·SSE 데이터층 완성, UI "준비 중" placeholder | 무스타일 | HYBRID (stream.mock) | AI 서버 conversation API 미구현 | OverlayHost.tsx:34-44, entities/conversation/* |
| Overlay | SURVEY | OverlayType 리터럴만 | NOT_IMPLEMENTED | — | MOCK | BE 전무, Unity 이벤트 미정의 | overlay.ts:4 |
| Overlay | CONSULTATION | OverlayType 리터럴만 | NOT_IMPLEMENTED | — | MOCK | BE 전무(-137 은 명세 커밋뿐), WS 미구현 | overlay.ts:4, roleD ③ |
| Content | Project 전시 | 없음 | NOT_IMPLEMENTED | — | MOCK→HYBRID (BE API 는 develop 에 완비) | Unity BOOTH_PROJECT_INTERACT 미구현(-343) | roleD ②④ |
| Creator | Booth Studio | `/app/studio/:boothId` | IMPLEMENTED (2D — 문서의 3D Canvas 아님) | 무스타일 | REAL | BE 완결 | pages/studio/StudioPage.tsx |
| Creator | Game Studio | `/app/games/:id/edit` | IMPLEMENTED — 앱 최완성 UI | gss CSS 781줄 | HYBRID (API 플래그) | BE draft/publish 존재, 에셋 API 3종 미구현 | game-studio/studio/ui/GameStudioShell.tsx |
| Creator | Game Play | `/app/games/:id/play` | IMPLEMENTED | grp CSS | HYBRID | 동상 | PlayGamePage.tsx |
| Creator | 게임 생성·목록 | 없음 | NOT_IMPLEMENTED | — | REAL 가능 (BE POST /games·/games/mine 존재) | GameController | roleD ① |
| Creator | Survey Builder | 없음 | NOT_IMPLEMENTED | — | MOCK | BE 전무 | roleD ③ |
| Ops | Dashboard / Staff / Admin | 없음 | NOT_IMPLEMENTED | — | MOCK | BE 전무(spec 015 초안만) | roleD ③ |
| Unity | F Prompt·Highlight·근접조준 | Unity 내부 | IMPLEMENTED (develop) | uGUI/emission | — | — | roleC ②, BoothInteractionInput.cs |
| Unity | TimerStopGameHud (미니게임) | Unity 내부 (게임부스 F) | IMPLEMENTED — React GameOverlay 와 소유권 충돌 | uGUI 풀스크린 | — | — | roleC ⑥-1 |
| Unity | 송신 토스트·포털 링·이모트 휠·입장 게이트 | Unity 내부 | IMPLEMENTED | uGUI/IMGUI/지오메트리 | — | — | roleC ① |
| Unity | Input Lock/복구 (overlay open 시) | — | NOT_IMPLEMENTED (양측) | — | — | HUD 보고서 §9 전체 공백 | roleC ④ |
| 상태 공통 | Toast/Modal/OverlayFrame/Loading/Error/Empty | 공통 구현 없음 (feature-local 3계보 혼재) | NOT_IMPLEMENTED (공통층) | — | — | — | roleA ⑤, roleB §3 |

집계: IMPLEMENTED 11 / PARTIAL 2 / STUB 1 / NOT_IMPLEMENTED 10(공통층 포함 시 12) / BLOCKED 0 (화면 단위 원천 불가 없음 — blocker 는 기능 경로에만 존재).
