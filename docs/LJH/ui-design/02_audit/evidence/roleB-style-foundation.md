# festa-frontend 스타일·디자인 기반 전수조사 (ASC S-20260831-03 / roleB)

조사일 2026-08-31 · 브랜치 front · 대상 `C:\colosair\projects\ssafesta\festa-frontend` (read-only)

## 0. 요약

프론트는 **두 세계로 갈라져 있다.**
- **Game Studio 계열** (`src/game-studio/**`) — 947줄의 수작업 다크 테마 CSS로 상당히 완성된 게임 툴 UI.
- **플랫폼 화면 전부** (`src/pages/**`, `src/features/**`) — className 0개, CSS 0줄. **브라우저 기본 스타일의 무장식 시맨틱 HTML.** `/app/home`은 문자 그대로 `<div>home</div>`(`src/app/router/index.tsx:36`).

공통 컴포넌트·전역 토큰·shared/ui 디렉터리는 **존재하지 않는다.** 행동(폼·모달·API 배선·게이트 로직)은 검증돼 있고 프레젠테이션만 비어 있다 — RESTYLE 조건이 이상적으로 갖춰진 상태다.

---

## 1. 스타일 방식 실측

| 방식 | 실측 | 근거 |
|---|---|---|
| Plain global CSS | **4개 파일, 총 947줄** — 사실상 유일한 방식 | `src/index.css`(13줄), `src/game-studio/studio/ui/GameStudioShell.css`(781줄), `src/game-studio/runtime/reference/ReferenceGamePlayer.css`(101줄), `src/game-studio/host/GameOverlay.css`(52줄) |
| CSS Module | **0개** | `*.module.css` glob 0건 |
| Tailwind / styled-components / emotion | **없음** | package.json deps에 없음(react·react-dom·react-router-dom·@tanstack/react-query 뿐), `styled\|tailwind\|@emotion` grep 0건 |
| inline style | 17곳 / 8파일 — 캔버스·iframe 크기 지정 등 기능적 용도 | `TopDownCanvas.tsx`(6), `ReferenceGamePlayer.tsx`(6), `LaptopOverlay.tsx:71`, `EditorCanvas.tsx:54` 등 |
| 무스타일 (기본 HTML) | **pages/features/app/unity 전체** — className 사용 0건 | `grep className src/pages src/features src/app src/unity` → 0건 |

클래스 네이밍은 접두사 네임스페이스: `gss-`(스튜디오) / `grp-`(런타임 플레이어) / `ggo-`(게임 오버레이). className을 쓰는 tsx 18개는 전부 `src/game-studio/` 안이다.

## 2. Design Token 실태

| 항목 | 상태 | 근거 |
|---|---|---|
| Color 변수 | **스코프 한정 CSS var만 존재, 전역 없음.** `.gss-root`에 13개(`--gss-bg/panel/panel-2/panel-3/border/border-soft/text/muted/blue/blue-soft/gold/green/red`), `.grp-root`에 3개. 둘의 팔레트는 거의 같은데 **중복 정의**(예: blue `#3388ff` vs `#4d94ff`) | `GameStudioShell.css:1-14`, `ReferenceGamePlayer.css:1-4` |
| 그 외 색상 | 변수 밖 하드코딩 hex/rgba **수백 건** (`#171d26`, `rgba(51,136,255,.13)` 류가 파일 전반) | GameStudioShell.css 전반 |
| Typography scale | **없음.** font-size 6px~30px가 개별 하드코딩(7px·8px·9px 다수). 폰트는 `Inter, Pretendard, 'Segoe UI'` 선언만 있고 **로딩이 없어** 실제로는 Segoe UI 폴백 | `GameStudioShell.css:18`, `index.css:2`, index.html에 font link 없음 |
| Spacing scale | 없음 — gap/padding 전부 개별 px | 전 CSS |
| Radius | 없음 — 3~18px 하드코딩 혼재 | 전 CSS |
| Shadow/Elevation | 없음 — box-shadow 개별 정의 | 전 CSS |
| z-index 관리 | **비관리.** 1·2·3·4·5·8·20·30·32·45·50·70·80·100·130 산발 + `ggo-overlay`가 10000 | z-index grep 결과, `GameOverlay.css:8` |
| Motion/transition | 정의 5건뿐(GameStudioShell 4 + ReferenceGamePlayer 1), duration·easing 개별 지정. 애니메이션 keyframe 0건 | grep transition |
| light/dark | `index.css:3`에 `color-scheme: light dark`만. 게임 계열은 다크 고정, 플랫폼 화면은 OS 기본색 | `index.css` |

**결론: 중앙 토큰 시스템 부재. 유일한 "토큰"인 gss 변수조차 `.gss-root` 스코프라 플랫폼 화면이 쓸 수 없다.**

## 3. 공통 컴포넌트 인벤토리

`src/shared/`는 `api/ config/ types/` 뿐 — **shared/ui 없음. 공통 UI 컴포넌트 0개.** primitive는 game-studio 내부 CSS 클래스로만 존재한다.

| Primitive | 기존 유사물 | 재사용 범위 |
|---|---|---|
| Button | `.gss-primary-actions > button`·`.gss-secondary-wide`·`.gss-danger-button`·`.grp-interact` 등 CSS 클래스 (컴포넌트 아님) | game-studio 내부만 |
| IconButton | `.gss-icon-button` (`GameStudioShell.css:127`) | game-studio 내부만 |
| Input/Field | `.gss-field` label+input 패턴 (`GameStudioShell.css:399-419`) | game-studio 내부만 |
| Badge | `.gss-type-badge`/`.gss-count-badge` (`GameStudioShell.css:284`) | game-studio 내부만 |
| Modal | ① native `<dialog>`+showModal — `features/booth/ui/LeaseConfirmDialog.tsx`(Esc·포커스트랩·backdrop 무료 획득, 무스타일) ② div 기반 `.gss-guide-backdrop/.gss-guide-modal`·`.gss-template-modal`·`.gss-asset-picker-backdrop` ③ `role="dialog"` div — `features/studio/ui/PublishDialog.tsx` | 세 가지 방식이 **비일관** 공존 |
| Toast | `.gss-toast` — `GameStudioShell.tsx:1399`에 인라인 구현(컴포넌트 분리 안 됨) | GameStudioShell 1곳 |
| Divider / ScrollArea | 없음 (border·overflow-y 개별 지정) | — |

## 4. 반응형·모바일

- breakpoint는 **GameStudioShell.css에만** 3개: `1250px / 1039px / 760px`(`:744,750,774`). 그마저 `min-width:760px`(`:22`)라 사실상 데스크톱 전용.
- ReferenceGamePlayer는 `min-width:900px`(`ReferenceGamePlayer.css:13`) — 데스크톱 고정.
- 플랫폼 화면은 스타일이 없으니 반응형 개념 자체가 없음. viewport meta는 있음(index.html).
- **모바일 대응 실태: 없음.**

## 5. Asset 전수

| 위치 | 내용 |
|---|---|
| `public/` | `favicon.svg`, `runtime-config.js` **2개뿐.** 로고 이미지·폰트·배경·비디오 없음 |
| `src/game-studio/assets/` | webp 14개 — 스프라이트/타일셋/오브젝트 아틀라스 5, 템플릿 프리뷰 6, 배경·포트레이트 3 (`adventurer-walk-4x4.webp`, `fantasy-library-tileset-8x8.webp`, `template-*-preview.webp` 등) |
| 폰트 로딩 | **없음.** CSS가 Inter·Pretendard를 참조하지만 `@font-face`도 `<link>`도 없어 Segoe UI/system-ui 폴백으로 렌더 |
| 서비스 로고 | 없음 — `.gss-logo`는 그라데이션 사각형 + 글자(`GameStudioShell.css:84`) |

## 6. 전체 디자인 수준 진단

| 영역 | 수준 |
|---|---|
| Game Studio (`/app/games/:id/edit`) | **높음.** VSCode풍 3열 다크 에디터 — 토큰 유사 변수·focus-visible 처리·상태색 체계(gold=dirty·green=saved·red=error)·모달·토스트·튜토리얼 독까지 갖춤. 게임 툴다운 완성형 |
| Reference Game Player / GameOverlay | **중상.** 다크 게임 HUD·다이얼로그 박스·게임패드 컨트롤 — 게임형 요소 뚜렷 |
| 플랫폼 화면 (login·booths·studio·world·home·callback) | **0.** 스타일 부재. generic 이하 — 브라우저 기본 렌더 |
| 화면 간 consistency | **없음.** 게임 계열 내부는 서로 유사하나(팔레트 중복 정의) 플랫폼 계열과 단절 |

즉 "generic SaaS 형"도 아니고, **게임형 아일랜드 + 무스타일 대륙** 구조다.

## 7. Foundation 후보 매핑

| 후보 | 판정 | 근거 경로 |
|---|---|---|
| **Foundation: Color** | 기존 유사물 있음 — gss/grp 팔레트를 전역 토큰으로 승격·통합 | `GameStudioShell.css:1-14`, `ReferenceGamePlayer.css:1-4` |
| Foundation: Typography | **신규 필요** (scale 부재 + 폰트 로딩 자체가 없음) | §2 |
| Foundation: Spacing / Radius / Border | **신규 필요** | §2 |
| Foundation: Elevation(shadow·z-index) | **신규 필요** (z-index 1~10000 산발) | §2 |
| Foundation: Motion | **신규 필요** (transition 5건, keyframe 0) | §2 |
| Foundation: Semantic State | 기존 유사물 있음 — dirty/saved/error 색 규약(`gss-save-state`), warning/blocker 카드(`gss-health-card`) | `GameStudioShell.css:120-125,530-539` |
| **Primitive: Button** | 기존 유사물 있음(CSS 클래스) → 컴포넌트화 필요 | `GameStudioShell.css:144-164` |
| Primitive: Input | 기존 유사물 있음(`.gss-field`) → 컴포넌트화 필요 | `GameStudioShell.css:399-423` |
| Primitive: IconButton | 기존 유사물 있음 | `GameStudioShell.css:127-140` |
| Primitive: Badge | 기존 유사물 있음 | `GameStudioShell.css:284-294` |
| Primitive: Divider / ScrollArea | **신규 필요** | — |
| **Game UI: GameShell** | 기존 있음 — `GameStudioShell.tsx`+css(편집), `ReferenceGamePlayer`(플레이) | `src/game-studio/studio/ui/`, `src/game-studio/runtime/reference/` |
| Game UI: OverlayFrame | 기존 있음(부분) — `GameOverlay.tsx/.css`(전체화면 z-10000), `OverlayHost.tsx`는 무스타일 | `src/game-studio/host/`, `src/features/overlay/OverlayHost.tsx` |
| Game UI: GameMenu | **신규 필요** (grp-hud·grp-controls가 부분 유사) | `ReferenceGamePlayer.css:62-81` |
| Game UI: ScreenNotice | 기존 유사물 있음 — `.ggo-message`(에러/로딩 풀스크린), `.grp-result`(게임 결과) | `GameOverlay.css:14-53`, `ReferenceGamePlayer.css:83-92` |
| Game UI: Toast | 기존 유사물 있음(`.gss-toast`, 단 인라인·비공유) | `GameStudioShell.tsx:1399`, `GameStudioShell.css:597-601` |
| **Creator: WorkspaceShell** | 기존 있음 — gss 3열 레이아웃(topbar/layout/statusbar) | `GameStudioShell.css:16-24,166-176,381` |
| Creator: ToolPanel | 기존 있음 — `.gss-sidebar-section`·`.gss-panel-tabs`·`.gss-layer-panel` | `GameStudioShell.css:178,385,694` |
| Creator: Inspector | 기존 있음 — `InspectorPanel.tsx`(gss 50회 사용)·`PropertiesPanel.tsx`(무스타일, booth studio) | `src/game-studio/studio/ui/InspectorPanel.tsx`, `src/features/studio/ui/PropertiesPanel.tsx` |
| Creator: Toolbar | 기존 있음 — `.gss-canvas-toolbar`·`.gss-canvas-tools`·`.gss-tool-segment` | `GameStudioShell.css:277,683-693` |

## 8. KEEP / RESTYLE / REPLACE

| 대상 | 판정 | 이유 |
|---|---|---|
| `LeaseConfirmDialog.tsx` — native dialog·showModal·잔액 캐시 공유·affordability 판정 | **KEEP(행동) + RESTYLE(표현)** | Esc/포커스트랩/backdrop 검증됨. 스타일만 0 |
| `SlotListPage.tsx` — 카운트다운(RemainingTime)·에러 코드→문구 매핑·invalidate 흐름 | KEEP + RESTYLE | 서버 권위 카운트다운(C-02)·T026 계열 방어 로직 보존 필수 |
| `StudioPage.tsx` + `editorReducer`·`useStudioGates` — conflict/dirty/게이트 배선 | KEEP + RESTYLE | 실버그(T026) 실측으로 잡은 refetch 방어가 주석과 함께 있음. 마크업만 재구성 |
| `EditorCanvas.tsx` — setPointerCapture·snap·clamp·OOB 표시 | KEEP + RESTYLE | 좌표계·드래그 행동 검증됨. 색만 하드코딩(`#4a90d9`·`#e63946`) |
| `LoginPage.tsx`·`CallbackPage.tsx`·`NicknameForm.tsx` — OAuth 전체 페이지 이동·게스트 흐름 | KEEP + RESTYLE | FR-007 오류 표면화 로직 유지 |
| `WalletBadge`·`TransactionsSection`·`PublishDialog(DetailList)` | KEEP + RESTYLE | queryKey 공유·원자 publish 계약(C-04) 유지 |
| `OverlayHost.tsx`·`LaptopOverlay.tsx` — lazy 분할·URL 정규화(scheme 검증) | KEEP + RESTYLE | 보안 정규화·번들 분리는 보존, 프레임 UI만 입힘 |
| GameStudioShell + gss CSS 전반 | **KEEP** (토큰 추출 원천으로 활용) | 이미 완성형. 색 변수→전역 토큰 승격 시 참조 원본 |
| ReferenceGamePlayer/GameOverlay CSS | KEEP (grp 변수를 공통 토큰으로 통합할 때만 소폭 수정) | gss와 팔레트 중복이 유일한 결함 |
| `role="dialog"` div (PublishDialog) ·준비중 안내 div (OverlayHost fallback) | **REPLACE** → 공통 Modal/ScreenNotice로 | 접근성 반쪽(포커스트랩 없음)·중복 패턴 |
| 폰트 미로딩 상태 | REPLACE → Pretendard 실로딩(self-host) | CSS 선언만 있고 로드 없음 |

## 먼저 만들어야 할 shared component 후보 (우선순위순)

1. **`shared/ui/tokens.css`** — gss/grp 팔레트 통합 전역 CSS 변수(color·radius·shadow·z-index·spacing·type scale) + Pretendard `@font-face`. 모든 후속 작업의 전제.
2. **`Button`** (variant: primary/secondary/ghost/danger, size) — `.gss-primary-actions`·`.gss-danger-button` 승격. 무스타일 버튼 ~20곳이 즉시 수혜.
3. **`Modal`** — native `<dialog>` 기반(LeaseConfirmDialog 방식이 정본), gss-guide-modal 스타일 이식. PublishDialog·LeaseConfirmDialog·게임스튜디오 모달 3계보 통일.
4. **`Field`(Input/Select/Textarea+label)** — `.gss-field` 승격. NicknameForm·FacadePanel·PropertiesPanel 수혜.
5. **`ScreenNotice`** — `.ggo-message`/`.grp-result` 통합(로딩·에러·빈 상태·결과 풀스크린). OverlayHost fallback·CallbackPage·SlotListPage 로딩/에러가 소비.
6. **`Toast`** — GameStudioShell.tsx:1399 인라인 구현을 컴포넌트+전역 버스로 분리.
7. **`Badge`** — `.gss-type-badge`·상태 배지(WalletBadge·슬롯 상태 라벨) 승격.
8. **`IconButton`** — `.gss-icon-button` 승격.
9. **`OverlayFrame`** — GameOverlay의 fixed 전체화면 프레임(z-index 토큰화 포함)을 LAPTOP/AI_CHAT/SURVEY/CONSULTATION 오버레이 공통 껍데기로.
10. **`PageShell`** — 플랫폼 화면(login·booths·home) 공통 레이아웃(헤더+WalletBadge 슬롯). 현재 대응물 전무.
