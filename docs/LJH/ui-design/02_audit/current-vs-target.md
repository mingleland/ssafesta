# Current → Target 매핑 (Persistent GameShell 재편)

> **STATUS: HISTORICAL AUDIT — BASELINE origin/develop 9c0db7a (2026-08-31)**
> 이 관측은 -405·-406 이전 상태다. 현재 구현 상태는 `02_audit/function-truth-inventory.md`
> (baseline develop=0bf878c6, 2026-09-03)가, 흐름 결정은 `00_context/user-flow-decisions.md` 가 정본이다.
> 당시 기록이므로 최신 내용으로 덮어쓰지 않는다.

- 문서 종류: AUDIT(실측 baseline origin/develop 9c0db7a, 2026-08-31) + DECISION 갱신(2026-09-01, implementation-decisions.md D-01·D-04 반영)
- 목표 구조 = SSAFY_FESTA_최종_UI_구조_종합정리.md. 결정 충돌 시 00_context/implementation-decisions.md 가 우선.

## 핵심 실측 (Persistent GameShell 가능성)

- **현재는 route 순회형 웹앱** — Unity 는 `/app/world` 소속. route 이탈 시 `releaseUnitySession()`→`instance.Quit()` 로 반드시 재생성 (sessionManager.ts:96-101).
- **재편 자산**: sessionManager·Overlay Bus·bridge 전부 module-scope singleton — 컴포넌트 밖에 이미 있어 셸 승격 비용 낮음.
- **재편 공백**: ① UnityHost 의 router 밖 승격 ② dispatcher 구독의 셸 수준 이동(현재 월드 밖 오버레이 차단 설계) ③ ReactScreenLayer 계층 신규 ④ Unity pause/input-lock 계약 부재(bridge 이벤트 2종뿐) ⑤ 셸 상주화 시 Quit 경로 소멸(명시 필요) ⑥ WORLD_GATE 60s 실패 UI 의 셸 분리 ⑦ canvas 소유권 분해.
- **MockUnitySurface**: seam 기존재 — `VITE_USE_MOCK` + `loader.mock.ts` + 콘솔 `window.FestaUnity.onBoothInteract(...)` 주입으로 실 Unity·실 BE 없이 전 overlay 구동. preview 전용 route 불요(디자인 갤러리 원하면 dev route 1개가 최소 작업).

## 매핑 표

| Current | Target 위치 | Decision | Reason |
|---|---|---|---|
| flat router 9 route (layout 없음) | Persistent GameShell + layout route | **REPLACE**(구조) | Unity 상주 전제. flat sibling 구조로는 불가 |
| `/app/home` `<div>home</div>` | Landing / Title | **MOCK_NEW** | stub. 게임 타이틀 화면 신규 |
| `/login` LoginPage | GameShell 위 Login 메뉴 overlay | **KEEP(행동)+RESTYLE+MOVE** | OAuth·게스트 흐름 검증됨. 표현만 게임 메뉴화 |
| `/auth/callback`+NicknameForm | First Setup 패널 (World/Login 배경 위) | KEEP+RESTYLE | 상태기계 보존 |
| `/app/world` WorldPage | GameShell 본체 (Unity 상주) | **MOVE**(승격) | UnityHost·OverlayHost·dispatcher 를 셸로 |
| `/app/booths` SlotListPage | React Screen UI (또는 L Panel) | KEEP+RESTYLE | 서버 권위 카운트다운·오류 매핑 보존 |
| Wallet (SlotListPage 삽입) | OverlayRoot > Wallet | **MOVE+RESTYLE** | 전용 overlay 로 분리, queryKey 공유 유지 |
| LAPTOP overlay | OverlayRoot > Booth/Project (M~L) | KEEP+RESTYLE | URL 소스를 016 계약(homepageUrl 조회)로 전환 + OverlayFrame 적용 |
| GAME overlay | OverlayRoot > Game | KEEP+RESTYLE | z-10000 fixed 유일 정상 사례. OverlayFrame 으로 통합 |
| AI_CHAT placeholder | OverlayRoot > AI (L) | **MOCK_NEW**(UI)+KEEP(데이터층) | SSE 타입·파서·mock 완성 — UI 만 신규 |
| SURVEY/CONSULTATION 리터럴 | OverlayRoot > Survey/Consultation (L) | MOCK_NEW | 전면 신규 (BE 전무여도 디자인 선행) |
| Project (없음) | OverlayRoot > Booth/Project | MOCK_NEW→HYBRID | BE API develop 완비, Unity 이벤트만 부재 |
| Profile (없음) | OverlayRoot > Profile (게임 메뉴) | MOCK_NEW→REAL | BE users/me 존재 |
| `/app/studio/:boothId` Booth Studio | **2.5D Creator Workspace** (XL) | **KEEP behavior + REWORK presentation** | D-04 확정: 고정 isometric/orthographic 2.5D 편집기. 게이트·conflict 방어·Save/Publish 계약 보존, 중앙 Canvas 만 2.5D 렌더러(D-06 adapter). R3F 정식 채택은 Spike Gate(D-05) 이후 |
| `/app/games/:id/edit` Game Studio | **OUT_OF_SCOPE / PROTECTED TEAM VERTICAL** | 대상 아님 | D-01 확정: 타 팀원 수직 영역. 수정·RESTYLE·이동 금지. read-only 참고만, 토큰 원천 아님. -155 박준우 진행 중 |
| `/app/games/:id/play` | OUT_OF_SCOPE (Game Studio 소속) | 대상 아님 | D-01 동일 |
| 게임 생성·목록 (없음) | OUT_OF_SCOPE (Game Studio 소속) | 대상 아님 | D-01 동일 — 이번 통합에서 신규 제작하지 않음 |
| Survey Builder (없음) | Creator Workspace | MOCK_NEW | |
| Dashboard/Staff/Admin (없음) | Operations | MOCK_NEW | 절제 톤, 동일 토큰 |
| RequireAuth 별 AuthHeader | GameShell 상단/메뉴로 흡수 | MERGE | 화면별 헤더 삽입 구조 해체 |
| Unity TimerStopGameHud | React GameOverlay 로 단일화 (원칙상) | **팀 재확정 필요** | 소유권 충돌 — Unity 코드 수정 유발 사안이라 3파트 합의 대상 |
| Unity AvatarCustomizationHud (C 키 잔존 POC) | React Profile/아바타 UI | DISCARD(Unity 측 게이트) | 정식 소유권 React — Unity 수정 유발, 합의 대상 |
