# Role A — festa-frontend React UI Surface 전수조사

- 세션: ASC S-20260831-02 / 조사일 2026-08-31 / read-only
- 기준 트리: 현재 체크아웃 front (= origin/develop 9c0db7a 동일 확인됨)
- 루트: `C:\colosair\projects\ssafesta\festa-frontend`
- 목표 구조 문서: `docs/LJH/ui-design/00_context/references/SSAFY_FESTA_최종_UI_구조_종합정리.md`

## ① Route Inventory

라우터는 단 1개 파일: `festa-frontend/src/app/router/index.tsx` (`createBrowserRouter`, `main.tsx:12`에서 `RouterProvider`로 장착). **계층 없음 — 전부 flat sibling route. layout route·shell route·404(`*`)·errorElement 없음.**

| Path | Element | Guard | Lazy | 근거 (src/app/router/index.tsx) |
|---|---|---|---|---|
| `/` | `<Navigate to="/app/home" replace>` | — (가드 위임) | no | :22-24 |
| `/login` | `LoginPage` | 없음 | no | :26-28 |
| `/auth/callback` | `CallbackPage` | 없음 | no | :30-32 |
| `/app/home` | `<div>home</div>` (inline stub) | guest-allowed | no | :34-40 |
| `/app/booths` | `SlotListPage` | guest-allowed | no | :42-49 |
| `/app/studio/:boothId` | `StudioPage` | member-only | no | :51-57 |
| `/app/world` | `WorldPage` | guest-allowed | **yes** (dynamic import) | :59-70 |
| `/app/games/:gameId/edit` | `EditGamePage` | member-only | **yes** | :72-83 |
| `/app/games/:gameId/play` | `PlayGamePage` | member-only | **yes** | :85-96 |

가드: `RequireAuth`(`src/app/router/RequireAuth.tsx`) + 순수 판정 `evaluateGuard`(`src/app/router/guard.ts:16-20`). anonymous→`/login` redirect(returnTo 저장), guest×member-only→차단 안내. RequireAuth가 허용 화면 상단에 `AuthHeader`(로그아웃 버튼)를 함께 렌더한다(RequireAuth.tsx:15-38) — 이것이 현재 유일한 "공통 셸" 요소다.

## ② 화면 전수표

Implementation: IMPLEMENTED / PARTIAL / STUB / NOT_IMPLEMENTED / BLOCKED.
Data: REAL(실 API로 UI 제작 가능) / HYBRID(일부 실제+fixture) / MOCK(UI 선행 필요).

| Domain | Screen | Route/Entry | Implementation | Data | Evidence |
|---|---|---|---|---|---|
| Shell | Application Shell (Persistent GameShell) | 없음 | **NOT_IMPLEMENTED** — flat route + route별 AuthHeader만. Unity를 감싸는 상주 셸 부재 | — | app/router/index.tsx 전체, RequireAuth.tsx:15-38 |
| Entry | Landing / Title | 없음 (`/`→`/app/home`) | **NOT_IMPLEMENTED** — `/app/home`은 `<div>home</div>` 문자 그대로의 stub | MOCK | app/router/index.tsx:38 |
| Auth | Login | `/login` | IMPLEMENTED (기능 완결·무스타일. Google/Kakao/게스트. 문서의 SSAFY provider 없음) | REAL (mock 스위치 있음) | pages/login/LoginPage.tsx:1-70 |
| Auth | OAuth Callback | `/auth/callback` | IMPLEMENTED (completing/nickname-required/restart 상태기계) | REAL | pages/auth/CallbackPage.tsx:38-110 |
| Auth | First Setup (닉네임) | CallbackPage 내부 분기 | IMPLEMENTED | REAL | features/auth/ui/NicknameForm.tsx:1-80 |
| World | World (Unity Host) | `/app/world` | IMPLEMENTED — UnityHost+OverlayHost+dispatcher 조립. loading %/failed/재시도. **HUD·미니맵·퀘스트·메뉴 등 문서 §8 HUD는 전무** | HYBRID (Unity 빌드 or loader.mock) | pages/world/WorldPage.tsx:10-27, unity/host/UnityHost.tsx:60-89 |
| Booth | Booth 슬롯 목록/임대(Lease) | `/app/booths` | IMPLEMENTED — 목록·카운트다운·임대 확인 dialog·게스트 안내·지갑 배지 | REAL | pages/booth/SlotListPage.tsx 전체, features/booth/ui/LeaseConfirmDialog.tsx:32 |
| Booth | Homepage (부스 홈페이지) | LAPTOP overlay 내 iframe | IMPLEMENTED (iframe+새 탭 병행, URL 정규화) | REAL (url은 Unity 이벤트 유래) | features/overlay/LaptopOverlay.tsx:1-80 |
| Booth | Facade 편집 | StudioPage 내 FacadePanel | IMPLEMENTED (4필드 폼, field별 오류 매핑) | REAL | features/studio/ui/FacadePanel.tsx:1-40 |
| Wallet | Wallet | 전용 route 없음 — SlotListPage에 삽입 | **PARTIAL** — WalletBadge+TransactionsSection 존재, 독립 화면/오버레이 없음 | REAL | features/wallet/ui/WalletBadge.tsx:7-20, TransactionsSection.tsx:8-60, SlotListPage.tsx:96·(하단) |
| Profile | Profile | 없음 | **NOT_IMPLEMENTED** (grep 0건) | MOCK | src 전체 grep "profile" — UI 없음 |
| Overlay | LAPTOP overlay | Unity `BOOTH_LAPTOP_INTERACT` | IMPLEMENTED — 단, **positioning CSS 없음(일반 흐름 div, floating panel 아님)** | REAL | OverlayHost.tsx:21-23, LaptopOverlay.tsx (style은 iframe 크기뿐) |
| Overlay | GAME overlay | Unity `BOOTH_GAME_INTERACT` | IMPLEMENTED — fixed+z-index:10000, Esc 닫기, 오류/재시도, lazy 분리 | HYBRID (portal repo 서버 or mock) | game-studio/host/GameOverlay.tsx:29-60, GameOverlay.css:7-8 |
| Overlay | AI overlay (AI_CHAT) | Unity `AI_AGENT_INTERACT` | **PARTIAL** — dispatcher는 openOverlay('AI_CHAT')까지 배선, 렌더는 "준비 중입니다" placeholder. entity 계층(SSE 타입·파서·mock)은 완성 | HYBRID (stream.mock 존재, UI 없음) | features/interaction/dispatcher.ts:17-19, OverlayHost.tsx:36-44, entities/conversation/stream.types.ts·stream.parser.ts·stream.mock.ts |
| Content | Project (프로젝트 정보) | 없음 | **NOT_IMPLEMENTED** | MOCK | grep 0건 |
| Overlay | Survey (응답) | OverlayType 리터럴만 | **NOT_IMPLEMENTED** (placeholder 공용 분기만) | MOCK | shared/types/overlay.ts:4, OverlayHost.tsx:36-44 |
| Overlay | Consultation | OverlayType 리터럴만 | **NOT_IMPLEMENTED** (동일) | MOCK | shared/types/overlay.ts:4, OverlayHost.tsx:36-44 |
| Creator | Booth Studio | `/app/studio/:boothId` | IMPLEMENTED — 2D EditorCanvas+Palette+Properties+Publish+Facade. 문서의 "3D Canvas"는 아님 | REAL | pages/studio/StudioPage.tsx:1-90, features/studio/ui/* |
| Creator | Game Studio | `/app/games/:id/edit` | IMPLEMENTED — GameStudioShell 1,405줄, 좌/중/우 workspace, 자체 CSS. 앱에서 가장 완성된 UI | HYBRID (VITE_GAME_STUDIO_API_ENABLED로 서버/브라우저 저장 전환) | game-studio/app/routes/EditGamePage.tsx:8-38, game-studio/studio/ui/GameStudioShell.tsx·.css |
| Creator | Game Play | `/app/games/:id/play` | IMPLEMENTED (published/local preview 이원) | HYBRID | game-studio/app/routes/PlayGamePage.tsx:1-60 |
| Creator | Survey Builder | 없음 | **NOT_IMPLEMENTED** | MOCK | grep 0건 |
| Ops | Dashboard | 없음 | **NOT_IMPLEMENTED** | MOCK | grep 0건 |
| Ops | Staff | 없음 | **NOT_IMPLEMENTED** | MOCK | grep 0건 |
| Ops | Admin | 없음 | **NOT_IMPLEMENTED** | MOCK | grep 0건 |

## ③ Persistent GameShell 실측

**결론: 현재 구조는 문서의 Persistent GameShell이 아니다. Unity는 route-scoped다.**

1. **Unity mount 지점**: `/app/world` route의 `WorldPage`(pages/world/WorldPage.tsx:22) → `UnityHost`가 자기 canvas ref에 인스턴스를 만든다(unity/host/UnityHost.tsx:17,33-36). 앱 최상위(main.tsx/AppProviders)에는 Unity 관련 코드가 전혀 없다.
2. **route 이동 시 Unity 생존**: **죽는다.** UnityHost 언마운트 cleanup이 `releaseUnitySession()`을 호출하고(UnityHost.tsx:52-56), sessionManager는 setTimeout 0으로 한 틱 미룬 뒤 `instance.Quit()`을 실행한다(unity/host/sessionManager.ts:96-101, 53-58). 지연의 목적은 StrictMode 이중 mount 방어(B-2)일 뿐, **실제 route 이탈(`/app/world`→`/app/booths` 등)에서는 취소자가 없어 반드시 Quit된다.** 재진입 시 loadUnityBuild부터 재생성.
3. **Overlay와 Unity 생존의 독립성**: **독립적이다(월드 내부에서는).** Overlay Bus는 module-level 상태(shared/types/overlay.ts:14-16)이고 OverlayHost는 UnityHost의 sibling으로 렌더될 뿐(WorldPage.tsx:23-25) Unity 인스턴스를 건드리지 않는다. GameOverlay가 열려도 canvas는 그대로다. 단 WorldPage 자체를 벗어나면 cleanup에서 `closeOverlay()`(WorldPage.tsx:14-18)와 Unity Quit이 함께 일어난다 — 즉 오버레이 생명주기 < WorldPage 생명주기 = Unity 생명주기.
4. **`<GameShell><UnityCanvas/><ReactScreenLayer/><OverlayRoot/></GameShell>` 재편 판정**:
   - **재사용 가능**: sessionManager의 single-flight 획득/직렬화 종료(acquire/release/restart)는 이미 컴포넌트 밖 module-scope singleton이라 셸 위치가 어디든 그대로 쓸 수 있다. Overlay Bus·OverlayHost·dispatcher도 위치 독립적(전부 module-level pub/sub) — OverlayHost를 route 밖 셸로 올리는 것은 코드 수정 거의 없이 가능하다.
   - **구조 변경 필요**: ① UnityHost를 router 밖(또는 layout route)으로 승격 — 현재는 WorldPage 소속이라 route 이동=Quit. ② dispatcher 구독을 WorldPage useEffect에서 셸 수준으로 이동(현재 월드 밖 오버레이 오픈을 의도적으로 차단하는 설계 주석 있음, WorldPage.tsx:1-4). ③ RequireAuth가 화면마다 header를 끼워 넣는 구조라 셸과 역할 재배분 필요. ④ ReactScreenLayer에 해당하는 계층 자체가 없음(신규).
   - **route 충돌**: `/app/games/:id/edit·play`와 `/app/studio/:boothId`는 전체 화면 페이지로 설계돼 있다(GameStudioShell 자체 workspace CSS). Unity 상주 셸 아래 두면 Unity가 뒤에서 계속 렌더링(WebGL GPU 소모) — 문서 §10 "Camera 필요 시 정지" 같은 pause 계약이 Unity bridge에 없다(events.ts에는 onBoothInteract·onWorldGateReady 2개뿐). guard 등급도 route별로 달라(guest/member) 셸 하위로 접으려면 가드 재배치 필요.
   - **Unity lifecycle 위험**: (a) StrictMode 방어가 "한 틱 지연 취소" 패턴에 의존 — 셸로 올려도 유지되지만, 셸이 언마운트되지 않게 되면 Quit 경로가 사실상 사라져 탭 종료 외 정리 시점이 없어진다(의도된 결과이나 명시 필요). (b) `WORLD_GATE_TIMEOUT_MS`=60초 게이트(shared/config/unity.ts:8)와 실패/재시도 UI가 UnityHost에 내장 — 셸 승격 시 이 상태 UI가 모든 화면 위에 뜨게 되므로 분리 필요. (c) canvas는 UnityHost 로컬 ref — 셸이 canvas를 소유하고 UnityHost는 상태만 관리하도록 분해해야 한다.

## ④ MockUnitySurface 실측

**결론: seam이 이미 존재한다. 실 Unity 없이 전 overlay를 열 수 있다.**

- **로더 교체 seam**: `unity/host/loader.select.ts` — `VITE_USE_MOCK==='true'`면 `loader.mock.ts`가 실 빌드 대신 가짜 progress + `window.FestaUnity.onWorldGateReady()` 자가 호출로 'ready'까지 도달한다(loader.mock.ts:27-44). UnityHost·sessionManager는 mock/real을 구분하지 않는다.
- **mock 시나리오 스위치**: devtools `window.__unityMock = { forceLoadFail, suppressGateReady }` (loader.mock.ts:8-14) — 로드 실패·60초 타임아웃 재현 가능.
- **fixture로 overlay 열기**: 가능, 2경로. ① 브라우저 콘솔에서 `window.FestaUnity.onBoothInteract(JSON.stringify({type:'BOOTH_LAPTOP_INTERACT', boothId:1, objectId:'x', url:'https://…'}))` — bridge(unity/bridge/events.ts:44-56)가 실 Unity와 동일 경로로 dispatcher→OverlayHost까지 흘린다. ② 코드에서 `openOverlay(type, payload)` 직접 호출(shared/types/overlay.ts:22-25). 테스트도 이 방식(`features/overlay/__tests__/unit/overlayHostGameWiring.test.tsx`).
- **기존 mock 스위치 총람**: `VITE_USE_MOCK`(auth·facade·lease·layout·wallet의 `*.select.ts` 5종 + unity loader + LoginPage SPA 우회 + game-studio 브라우저 발행), `VITE_GAME_STUDIO_API_ENABLED`(game-studio 서버 저장), `VITE_API_BASE_URL`/`window.__FESTA_CONFIG__`(shared/config/runtime.ts), `VITE_UNITY_BUILD_BASE`(unity/host/resolver.ts:8), DEV 한정 `?fixture=max`(EditGamePage.tsx:20).
- **preview 전용 route 필요 여부**: 불필요 — `VITE_USE_MOCK=true`로 `/app/world`에 들어가면 mock 게이트가 열리고 콘솔 이벤트 주입으로 임의 overlay를 띄울 수 있다. 다만 "디자인 갤러리"처럼 특정 overlay를 URL로 바로 여는 기능은 없으므로, 그것이 필요하면 dev 전용 route 1개 추가가 최소 작업이다.

## ⑤ 상태 UI 공통 구현

**공통 컴포넌트 계층이 없다.** `shared/`에는 api/config/types뿐(shared/ 하위에 ui 디렉터리 없음). 문서 §13 OverlayFrame·§20 State Foundation에 해당하는 것 부재.

| 상태 | 현황 | 근거 |
|---|---|---|
| Loading | 화면별 inline `<p>불러오는 중...</p>` 등 각자 구현 | UnityHost.tsx:79, SlotListPage, TransactionsSection.tsx:16, RequireAuth.tsx:49 |
| Error | 화면별 inline `<p role="alert">`/문구. 공통은 API 오류 envelope 파싱(`isApiError`, shared/api/client.ts)뿐 | LoginPage.tsx:63, SlotListPage.tsx:20-35 |
| Empty | 개별 처리(예: LAPTOP NO_URL 안내) | LaptopOverlay.tsx:38-46 |
| Disabled | 버튼 `disabled` 속성 개별 사용 | NicknameForm.tsx:70, LoginPage.tsx:58 |
| Success | 공통 없음(요청별 invalidate 후 재렌더) | features/studio/model/useLayoutMutations.ts |
| Confirm | 공통 없음 — LeaseConfirmDialog는 native `<dialog>`(Esc·backdrop), PublishDialog는 `div role="dialog"` — 서로 다른 구현 | LeaseConfirmDialog.tsx:32, PublishDialog.tsx:29 |
| Toast | **없음** | grep 0건 |
| Modal/Dialog 공통 | **없음** (위 2개 feature-local) | 상동 |
| Drawer / Panel | **없음** (GameStudioShell 내부 패널은 자체 CSS 클래스) | GameStudioShell.css |
| 전역 스타일 | index.css 12줄(reset 수준). 앱 전체에서 CSS 파일은 GameOverlay.css·GameStudioShell.css·ReferenceGamePlayer.css 3개뿐 — **game-studio 밖 화면은 전부 무스타일 브라우저 기본 HTML** | src/index.css, glob *.css |
| z-order | 관리 체계 없음. GameOverlay만 z-index:10000 fixed(GameOverlay.css:7-8), LaptopOverlay·placeholder는 z-index/position 미지정(일반 문서 흐름) | GameOverlay.css, LaptopOverlay.tsx |

## ⑥ 요약

**구현 상태 집계** (목표 구조 대비 23개 화면 단위)
- IMPLEMENTED: 11 — Login, OAuth Callback, First Setup, World Host, Booth 슬롯/Lease, Homepage(LAPTOP iframe), Facade, LAPTOP overlay, GAME overlay, Booth Studio, Game Studio(+Play)
- PARTIAL: 2 — Wallet(배지·내역만, 전용 화면 없음), AI overlay(배선·데이터층 완성, UI는 placeholder)
- STUB: 1 — `/app/home`(`<div>home</div>`; Landing 자리)
- NOT_IMPLEMENTED: 9 — Application Shell(GameShell), Landing/Title, Profile, Project, Survey, Consultation, Survey Builder, Dashboard, Staff+Admin(합산 시 10)
- BLOCKED: 0 (외부 의존으로 원천 불가한 화면 없음 — AI overlay도 mock stream 존재)

**디자인 생성 방식 분류**
- REAL: Login·Callback·First Setup·Booth 목록/Lease·Facade·Booth Studio·Wallet 부분·LAPTOP overlay (mock API 포함 실 데이터 경로 완비)
- HYBRID: World Host(Unity 빌드 or loader.mock)·GAME overlay·Game Studio/Play(env 스위치)·AI overlay(SSE mock 有, UI 無)
- MOCK(UI 선행): Landing/Title, Profile, Project, Survey, Consultation, Survey Builder, Dashboard, Staff, Admin, GameShell/HUD 계층

**핵심 소견**
1. 현재 앱은 문서 §0의 "풀스크린 게임 클라이언트"가 아니라 **route 순회형 웹앱**이다 — Unity는 `/app/world` 한 route의 소유물이고 route 이탈 시 Quit된다(③).
2. GameShell 재편의 최대 자산은 module-scope singleton들(sessionManager·Overlay Bus·bridge) — 이미 컴포넌트 생명주기와 분리돼 있어 승격 비용이 낮다. 최대 공백은 ReactScreenLayer와 Unity pause/input-lock 계약(bridge에 이벤트 2종뿐).
3. 디자인 작업 관점: game-studio 밖 전 화면이 무스타일이라 **기존 스타일과의 충돌 없이 Foundation부터 신규 적용 가능**하고, `VITE_USE_MOCK` + `window.FestaUnity.onBoothInteract(...)` 주입으로 실 Unity·실 BE 없이 전 overlay·전 화면을 브라우저에서 띄워 디자인할 수 있다(④).
