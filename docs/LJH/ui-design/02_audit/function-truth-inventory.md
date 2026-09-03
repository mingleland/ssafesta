# Function Truth Inventory — UI 를 벗긴 기능 전수조사

- 문서 종류: **AUDIT** (관측 기록 — 의사결정 정본 아님)
- 조사일: 2026-09-03
- Plane A baseline (구현 정본): `origin/develop = 0bf878c6`
- Plane B baseline (현재 프로토타입): `origin/feat/S15P21A604-406-world-project-overlay = 07bf7612` (MR !240, open)
- UX 결정 정본: `front = ac8c5ab0` 의 `docs/LJH/ui-design/`
- 조사 방법: 두 baseline 코드 read-only 대조 + `VITE_USE_MOCK=true` 런타임 순회(worktree dev server :5174) + vitest 기준선
- 원칙: **현재 화면에 존재한다는 사실은 기획 근거가 아니다.** 기능(무엇이 동작하는가)과 진입 UI(어디서 여는가)를 분리해 기록한다.

---

## 0. Evidence Priority (이 문서가 따른 순서)

```text
1. 최신 origin/develop 코드·테스트·런타임
2. 최신 contract/spec (shared/contracts, entities/*/types, specs/)
3. 최근 구현 MR과 검증 결과
4. docs/LJH/ui-design 의 UX 결정 (implementation-decisions > hud-decisions)
5. Jira
6. 현재 !240 화면
7. 과거 보고
```

`front` 의 공용 `docs/`·`specs/` 사본은 develop 대비 43파일 차이가 있어 **구현 정본으로 쓰지 않았다.** 계약 확인은 전부 `git show origin/develop:` 로 했다.

---

## 1. Route / Entry Point Inventory

`origin/develop:festa-frontend/src/app/router/index.tsx` 기준 9 route. !240 이 `/app/profile` 1개를 추가하고 `/app/home` 의 element 를 교체했다.

| Route / Trigger | Auth | 진입 조건 | 현재 화면 (develop → !240) | 실제 기능 목적 | 성공 후 이동 | 닫기/복귀 | Owner | 성숙도 |
|---|---|---|---|---|---|---|---|---|
| `/` | 없음 | 항상 | 타이틀 화면(배경·로고·CTA) — 동일 | 게임 진입 게이트 | 클릭 → `/app/home` | — | FE(LJH) | REAL |
| `/login` | 없음 | 항상 | provider 4버튼 + 게스트 — 동일 | 세션 획득 | `consumeReturnTo()` (기본 `/app/home`) | — | FE(LJH)/BE | REAL (SSAFY 만 `not_configured`) |
| `/auth/callback` | 없음 | OAuth 왕복 후 | 무스타일 `로그인 처리 중입니다...` — 동일 | handoff 소비·세션 확정 | AUTHENTICATED → returnTo / NICKNAME_REQUIRED → 폼 | 실패 시 `/login` 버튼 | FE(LJH)/BE | REAL |
| First Setup (닉네임) | 없음(핸드오프) | `complete()` 가 `NICKNAME_REQUIRED` | `CallbackPage` 내부 분기, 무스타일 폼 — 동일 | 최초 닉네임 확정 | returnTo | 만료 시 재시작 | FE(LJH)/BE | REAL |
| `/app/home` | guest-allowed | 세션 있음 | **`<div>home</div>`** → **HomePage 허브** | (develop) 없음 / (!240) 월드·부스·프로필 진입면 | — | — | FE(LJH) | develop: STUB / !240: MOCK-UI + REAL 쿼리 |
| `/app/world` (lazy) | guest-allowed | 세션 있음 | `UnityHost + OverlayHost` → `WorldSurface + WorldHud + MockInteractionBar + OverlayHost` | 월드 상주·상호작용 진입 | — | — | FE(LJH)/Unity | HYBRID |
| `/app/booths` | guest-allowed | 세션 있음 | 무스타일 목록 → PageShell 카드 목록 | 슬롯 조회·임대·잔액·거래내역 | 임대 성공 → 목록 갱신(내 부스 표시) | `backTo="/app/home"` | FE(LJH)/BE | REAL |
| `/app/studio/:boothId` | member-only | 소유자 게이트 통과 | Booth Studio 3모드(-405) — 동일 | 부스 편집·저장·게시 | 저장/게시 후 동일 화면 | 링크로 목록 복귀 | FE(LJH)/BE | REAL |
| `/app/profile` | member-only | !240 에서 신설 | (develop 없음) → 닉네임·provider·코인·거래내역·탈퇴 | 계정 관리 | — | `backTo="/app/home"` | FE(LJH)/BE | REAL |
| `/app/games/:id/edit` | member-only | 직접 URL | Game Studio | 게임 저작 | — | — | **박준우(PROTECTED)** | HYBRID |
| `/app/games/:id/play` | member-only | 직접 URL | Game Play | 게임 플레이 | — | — | **박준우(PROTECTED)** | HYBRID |
| Overlay: PROJECT | 세션 | Unity `BOOTH_PROJECT_INTERACT` (Unity 송신부 -343 미구현) / mock intent | (develop) "준비 중" placeholder → ProjectOverlay | 부스 전시 프로젝트 열람·좋아요 | — | Esc/dim/닫기 → 동일 World | FE(LJH)/BE | HYBRID |
| Overlay: LAPTOP | 세션 | Unity `BOOTH_LAPTOP_INTERACT` (**Unity 송신 구현됨**) | 무스타일 iframe → OverlayFrame iframe | 부스 홈페이지 열람 | — | 동일 | FE(LJH)/BE/Unity | REAL |
| Overlay: AI_CHAT | 세션 | Unity `AI_AGENT_INTERACT` (Unity 송신 구현됨) | "준비 중" → AiChatOverlay(mock 스트림) | AI 직원 문답 | — | 동일 | FE(LJH)/AI | MOCK(표현)/HYBRID(데이터층) |
| Overlay: GAME | 세션 | Unity `BOOTH_GAME_INTERACT` (**Unity 송신부 없음** — decision-queue #1) | GameOverlay(오류·재시도) — 동일 | 부스 미니게임 진입 | — | 동일 | 박준우(PROTECTED) | BLOCKED (BE game-portals 부재) |
| Overlay: SURVEY | 세션 | **Unity 이벤트 계약 없음** — mock intent 뿐 | "준비 중" → SurveyOverlay | 부스 설문 응답 | — | 동일 | FE(LJH) | MOCK |
| Overlay: CONSULTATION | 세션 | **Unity 이벤트 계약 없음** — mock intent 뿐 | "준비 중" → ConsultationOverlay | 부스 상담 요청 | — | 동일 | FE(LJH) | MOCK |

### 1-1. `/app/home` 의 실제 이력 — 판정

세 가지 사실이 이 route 의 성격을 결정한다.

1. **develop 의 `/app/home` 은 화면이 아니다.** `element` 가 `<div>home</div>` 이다 (`app/router/index.tsx`). spec 001 시점부터 지금까지 플레이스홀더였다.
2. **그런데 코드상 기본 목적지다.** `features/auth/model/returnTo.ts` 의 `DEFAULT_RETURN_TO = '/app/home'` 이고, LandingPage 의 시작 클릭도 `/app/home` 으로 간다. 즉 "로그인 완료 후 아무 데도 지정되지 않았을 때 가는 곳"이라는 **기술적 역할**로 먼저 존재했다.
3. **대시보드 구조는 !240 에서 처음 생겼다.** HomePage 는 월드 입장 hero + 부스/스튜디오/내 정보 카드다. 이것은 목업 라운드의 산물이지 spec·decision 문서가 요구한 구조가 아니다 — `hud-decisions.md`·`game-client-experience-draft.md` 어디에도 Home 대시보드 요구가 없다. 반대로 `world-reference-brief.md` 는 World 를 **"로그인 후 사용자가 상주하는 기본 상태"** 로 규정한다.

판정: `/app/home` 은 **제품이 정한 Home 이 아니라 returnTo 기본값이 놓인 기술 route** 이며, 현재의 허브 화면은 목업 과정에서 그 자리에 채워진 것이다. 제품 결정 대상으로 올린다(§M).

---

## 2. Function Truth — 도메인별

UI 형태(카드·색·radius)는 기록하지 않는다. State / Action / Trigger / Output / Data source / 상태 분기 / Contract / Owner / Maturity 만 기록한다.

### 2-A. Auth / Session

| 항목 | 값 |
|---|---|
| Provider | `google`·`kakao`(available) / `ssafy`(not_configured, D-07 도입 확정·BE -357 미병합) / `guest`(available) — `entities/auth/providers.ts` |
| 세션 종류 | `anonymous` / `guest` / `member` — 발급 경로로만 구분(응답에 사용자 식별 정보 없음) |
| OAuth 시작 | real: `window.location.href = {api}/api/v1/auth/oauth/{provider}` 전체 페이지 이동 / mock: SPA 내비게이션 |
| 완료 계약 | `POST /auth/oauth/complete` → `AUTHENTICATED{accessToken,expiresAt}` 또는 `NICKNAME_REQUIRED{null,null}` (2상태뿐) |
| **신규 사용자 판정** | **FE 가 하지 않는다.** 서버가 `NICKNAME_REQUIRED` 를 돌려주는 것이 유일한 판정이다 |
| First Setup 범위 | **닉네임 1개뿐.** `NicknameForm` 이 요구하는 필드는 nickname 하나이며, 관심사·아바타·튜토리얼 같은 단계는 코드·계약 어디에도 없다 |
| 닉네임 검증 | FE 는 공백만 검사, 나머지는 서버 판정(FR-007e). 거절 사유는 비특정 문구로만 노출 |
| 로그인 후 목적지 | `consumeReturnTo()` — 딥링크 있으면 그곳, 없으면 **`/app/home`** |
| 세션 복원 | `bootstrapAuth()` 가 앱 시작 1회 refresh 시도. 게스트는 복원하지 않음. `bootstrapped` 플래그 전에는 가드가 redirect 를 확정하지 않는다 |
| 로그아웃 | `AuthHeader`(RequireAuth 내부) — `authApi.logout()` 실패해도 클라이언트 세션은 정리하고 `/login` |
| 상태 표현 | `session-expired` / `guest-reentry-required` notice 를 LoginPage 가 읽어 안내 |
| Owner / Maturity | FE(LJH) + BE / **REAL** (SSAFY 만 미배선) |

런타임 실측(mock): `/` 클릭 → `/login` → Google → `로그인 처리 중입니다...` → `처음 오셨네요. 사용할 닉네임을 입력해 주세요.` → 닉네임 제출 → `/app/home`. 두 중간 화면은 **무스타일 raw** 지만 상태 자체는 실재한다.

### 2-B. World / Interaction

| 항목 | 값 |
|---|---|
| World Layer | develop: `UnityHost` 고정 / !240: `WorldSurface.select` seam — `VITE_USE_MOCK=true` 면 `StaticMockWorldSurface`(Unity 인게임 캡처 정지 화면), 아니면 `UnityHost` |
| Dispatcher | `features/interaction/dispatcher.ts` — Unity 이벤트를 `openOverlay(type, payload)` 로 바꾸는 **유일한 지점**. WorldPage 생명주기에 종속 구독 |
| Overlay Bus | `shared/types/overlay.ts` — module-level 단일 슬롯(`current`), `openOverlay`/`closeOverlay`/`subscribeOverlay`. **스택 아님 — 동시에 1개만** |
| Unity → React 이벤트 계약 (`unity/bridge/events.ts`) | `BOOTH_LAPTOP_INTERACT{boothId,objectId}` · `BOOTH_PROJECT_INTERACT{boothId,objectId}` · `AI_AGENT_INTERACT{boothId,objectId,configId→agentId}` · `BOOTH_GAME_INTERACT{boothId,objectId,configId}` · 생명주기 신호 `onWorldGateReady()` |
| **계약 공백** | **SURVEY·CONSULTATION 은 Unity 이벤트가 정의돼 있지 않다.** OverlayType 리터럴은 있으나 진입 트리거가 계약에 없다 |
| Unity 송신 실태 | LAPTOP·AI 는 develop Unity 에 구현. PROJECT 는 payload 확정·송신부 미구현(-343). GAME 은 Unity 가 자체 uGUI 로 열고 이벤트를 보내지 않음(decision-queue #1) |
| React HUD (!240) | `WorldHud` — 조작 안내 카드 1개(WASD/F/Esc, 닫기 가능, mock 주석). `hud-decisions.md` 허용 3종 중 1종만 구현 |
| Mock 진입 대역 (!240) | `MockInteractionBar` — `IS_MOCK_WORLD` 일 때만 렌더. 6개 버튼이 dispatcher 와 **같은 모양의 payload** 로 `openOverlay` 직접 호출. 새 계약을 만들지 않는다 |
| Input Lock | **양측 미구현.** overlay 가 열려도 Unity 입력이 차단되지 않는다(decision-queue #4) |
| Owner / Maturity | FE(LJH) + Unity(KHS) / **HYBRID** |

### 2-C. Project (전시)

| 항목 | 값 |
|---|---|
| 상태 기계 | `features/project/model/exhibition.ts` — `idle/loading/ready/empty/error`, boothId 세대 가드(늦은 응답 폐기) |
| VM | `projectId·name·description·thumbnailUrl·video(VideoEmbed: EMBED/LINK_ONLY/INVALID)·links(deploy/git/portfolio)·like` |
| Like | 멱등 PUT/DELETE, 낙관적 반영 + 실패 롤백 + `error` 플래그, `pending` 중 재클릭 무시, `canToggle = 세션이 member` (게스트는 서버 403 MEMBER_ONLY) |
| 소유자 편집 | `features/project/model/edit.ts` — draft + dirty 키 추적, **dirty 키만 PATCH**(BE PresenceField: 키 생략 ≠ null). 저장 중 입력 차단. `projectId===null` 이면 create |
| **편집 UI** | **없다.** develop·!240 모두 `edit.ts` 를 소비하는 화면이 없다 → MISSING UX |
| Data source | BE `projects/published`·`projects/mine` (develop 완비, -134·-135) |
| 진입 | Unity `BOOTH_PROJECT_INTERACT` 계약 확정·송신 미구현 → 현재는 mock intent |
| Owner / Maturity | FE(LJH) + BE / **HYBRID** (열람 UI 는 !240 에서 REAL VM 소비) |

### 2-D. LAPTOP (부스 홈페이지)

| 항목 | 값 |
|---|---|
| 상태 기계 | `features/overlay/model/laptopHomepage.ts` — `idle/loading/no_url/invalid/error/valid{href,hostname}` |
| URL 정본 | **`GET /booths/{id}` 의 `homepageUrl`** (016 C-01 #97). 이벤트 payload 의 url 은 소비하지 않으며 계약에서도 제거됨(-297) |
| 검증 | `new URL()` 파싱 + `http:`/`https:` 만 허용 |
| 표시 | iframe(`sandbox="allow-scripts allow-same-origin allow-forms allow-popups"`) + **새 탭 버튼을 1급으로 동시 제공** — 차단 감지가 불가능해서 내린 설계 결정 |
| 게스트/회원 차이 | 없음(조회 경로) |
| `-403` 영향 | **없음.** BE 가 `HttpUrlValidator.validateHttpsOnly` 를 신설했지만 대상은 **facade `logoUrl`**(우리 페이지에 임베드되는 자산)이고, destination 인 `homepageUrl` 은 http/https 유지다. FE mock(`facadeApi.mock.ts:82`)은 이미 https-only+2048 규칙을 갖고 있어 이번 변경은 BE 를 FE 계약에 맞춘 쪽이다. FE 코드 변경 0건 |
| Owner / Maturity | FE(LJH) + BE + Unity / **REAL** |

### 2-E. Survey

| 항목 | 값 |
|---|---|
| Run 상태 기계 | `features/survey/model/run.ts` — `idle/loading/ready/empty/error/closed`, 답변 맵, progress, `submit.phase`, `missingRequired()`(빈 선택·공백 텍스트를 미응답으로 판정), 제출 중 편집 차단 |
| Result 상태 기계 | `features/survey/model/result.ts` — 집계 `perQuestion`(choice counts / rating average+distribution) + **주관식 페이지네이션**(`loadNextTextPage`, 누적, 다음 페이지 실패가 화면을 무너뜨리지 않음) |
| Builder 상태 기계 | `features/survey/model/builder.ts` — draft(title+questions), add/remove/reorder/update, FE validation(제목·문항 비공백, 선택형 옵션 ≥2, rating min<max), 저장 중 편집 차단, 리로드 후 id seq 충돌 방지 |
| 질문 6유형 | `single·multi·rating·short_text·long_text·application` (spec 010 FR-002 확정) |
| Port | `entities/survey/api.port.ts` — getRun/submitAnswers/getResult/getTextAnswers/getDraft/saveDraft. **BE endpoint·DTO 미확정(UNKNOWN)** — mock adapter + fixtures 만 존재 |
| UI 현황 | **Run 만 !240 에 있다.** Result·Builder 는 UI 없음 |
| 재판정 | 인계의 "Survey Result·Builder MISSING" 은 **정확**하며, 그 성격은 `데이터층 존재 + UI 없음 = MISSING UX`(기능 blocked 아님). 단 실 데이터 연결은 BE 대기 |
| Owner / Maturity | FE(LJH) / **MOCK** (데이터층 REAL 구조, 어댑터만 mock) |

### 2-F. Consultation

| 항목 | 값 |
|---|---|
| Visitor 상태 기계 | `features/consultation/model/visitor.ts` — `idle/requesting/waiting/expired/active/ended/error`. 10분 만료(C-01) 로컬 카운트다운 + **서버 이벤트가 최종 판정**, 채널 구독은 서버가 끝을 말할 때까지 유지, `cancelConsultation`, `rerequestConsultation` |
| Staff 상태 기계 | `features/consultation/model/staff.ts` — queue 로드(세대 토큰), `canAccept()`(C-06 동시 1건), `acceptRequest`, `endActiveConsultation`, `actionError` 노출(조용히 삼키지 않음) |
| Port | `entities/consultation/channel.port.ts` + mock. STOMP destination·payload schema 미확정 |
| 게스트 | 불가(C-04) — 진입점에서 미노출이 정책, 모델은 재검사하지 않음 |
| 메시지 송수신 | **범위 밖(P2, C-02)** — 현재 모델에 대화 송수신이 없다 |
| UI 현황 | **Visitor 만 !240 에 있다.** Staff 화면 없음 |
| 재판정 | 인계의 "Consultation Staff MISSING" 은 **정확**하며 성격은 `데이터층 존재 + UI 없음 = MISSING UX`. 단 Staff 화면은 **진입 경로 자체가 미정** — World 오버레이가 아니라 별도 관리 화면이어야 한다(제품 결정 필요) |
| Owner / Maturity | FE(LJH) / **MOCK** |

### 2-G. AI

| 항목 | 값 |
|---|---|
| 데이터층 | `entities/conversation/` — `stream.parser.ts`(SSE 파서)·`stream.types.ts`·`stream.mock.ts`. 단위 테스트 3파일 존재 |
| 진입 | Unity `AI_AGENT_INTERACT` → `toAiChatPayload`(configId→agentId 이름 경계) |
| !240 UI | `AiChatOverlay` — 제안 질문 3개, 로컬 mock 토큰 스트리밍 표현, 전송·busy 상태. **AI 서버 conversation API 미구현이라 실제 응답 아님** |
| 미구현 | 실제 backend/worker 연결, cancel, retry, 근거 문서(sources) 실데이터 |
| Owner | AI 파트(로직) / FE(표현) — **AI 로직 미접촉** |
| Maturity | **MOCK(표현) / HYBRID(데이터층 seam)** |

### 2-H. GAME

세 가지를 분리한다.

| 구분 | 실태 |
|---|---|
| **GAME Play(오버레이)** | React `GameOverlay` 완성 — portal resolve → `PublishedGameSurface`. Esc 닫기 자체 구현. **BE `game-portals` 부재로 항상 오류·재시도 상태**(런타임 실측: "게임을 열 수 없습니다") |
| **Game Portal(진입)** | Unity 가 `BOOTH_GAME_INTERACT` 를 **보내지 않는다**. Unity 는 자체 `TimerStopGameHud` 풀스크린 uGUI 로 미니게임을 직접 연다 → 같은 기능 이중 구현(decision-queue #1, 3파트 결정 대기) |
| **Game Studio / Play route** | `/app/games/:id/edit`·`/play` — **박준우 vertical, D-01 PROTECTED.** 이번 조사에서 미접촉 |
| Maturity | **BLOCKED** (BE 4종 + Unity 송신 + 소유권 결정) |

### 2-I. Booth / Lease / Wallet

| 항목 | 값 |
|---|---|
| 슬롯 조회 | `leaseApi.getSlots()` — 공개(guest-allowed). `SlotView{slotId,slotCode,status,type,mine,boothName,leaseEndsAt}` |
| 내 부스 | `leaseApi.getMyBooth()` — member 만(게스트는 요청 자체를 만들지 않음) |
| 임대 | 100코인/1일. **확인 다이얼로그 필수**(환불 없음 FR-013) → `useLeaseSlot` mutation → 성공/실패 모두 목록·잔액 invalidate |
| 오류 매핑 | `INSUFFICIENT_COIN`·`ACTIVE_LEASE_LIMIT`·`BOOTH_SLOT_ALREADY_LEASED`·`BOOTH_SLOT_NOT_RENTABLE` → 한국어. 부족액 숫자는 message 에만 있어 **파싱 금지** |
| 카운트다운 | `endsAt` 절대시각 1초 갱신. 0 도달은 **표기만** 만료, 상태 권위는 refetch 된 서버 status(C-02, 시계 skew 안전) |
| Wallet | `balance`(일일 50코인은 서버 인터셉터가 자동 반영 — "받기" 버튼이 없는 이유), `transactions` offset page(서버 고정 정렬), 모르는 `reasonType` 은 원문 코드 그대로 표시(SC-005) |
| Wallet 화면 소속 | 전용 route 없음. develop: SlotListPage 안 `<details>`. !240: SlotListPage(펼침) **와** ProfilePage 양쪽 — **거래내역이 두 화면에 중복 노출**(런타임 실측) |
| `-403` 영향 | 임대만료 검사 8사본을 `BoothAccessGuard` 로 통합 — **BE 내부 정리.** 계약(쓰기는 유효 임대 요구, 읽기는 만료 후에도 허용)은 종전과 같다. FE 변경 0건 |
| Owner / Maturity | FE(LJH) + BE / **REAL** |

### 2-J. Booth Studio

기능층은 -405 에서 검증됐다. 재조사는 목록 확인 수준으로만 한다.

| 항목 | 값 |
|---|---|
| 3모드 | `구조(layout)` / `외관(facade)` / `템플릿(template)` — `studioMode.ts` |
| 편집 | 팔레트에서 추가, 드래그 이동, 회전, 0.25m 스냅, 부스 경계(bounds) 검증, 선택/인스펙터, 최대 12개(헌법 22조) |
| 좌표 | `coords.ts`·`geometry.ts` — 미터/바닥중앙원점/+Z 정면(헌법 21조) |
| 저장·게시 | `useLayoutMutations` — draft 저장 / publish. conflict 처리, `refetchOnWindowFocus:false`(T026 — 미저장 편집을 서버본이 덮던 결함) |
| Facade | themeCode·primaryColor·signText·logoUrl 4필드. logoUrl 은 https-only 2048자 |
| 카탈로그 | `useCatalogItems('BOOTH_DECOR')` — BE 시드는 AVATAR_PART 뿐이라 조회만 걸려 있음 |
| Renderer | `TemporaryIsoRenderer`(SVG 아이소메트릭) — **교체 가능한 Presentation.** D-04/D-05 의 2.5D(R3F)는 spike 미실행 |
| Function Truth 아님 | SVG 아이소메트릭·lime accent·dark chrome·panel radius |
| Owner / Maturity | FE(LJH) + BE / **REAL** |

### 2-K. Profile

| 항목 | 값 |
|---|---|
| 상태 기계 | `features/profile/model/profile.ts` — `loadProfile`·`submitNickname`(errorKind 분류)·`saveAvatar`(서버 echo 반영)·`beginWithdrawal/cancelWithdrawal/confirmWithdrawal` |
| VM | `userId·nickname·providers[]·avatarCode(null=미저장)` |
| 탈퇴 | `idle→confirming→submitting→error` 3단 확인 |
| 아바타 | `saveAvatar` 액션은 존재하나 **아바타 편집 UI 없음**(Unity POC 가 C 키로 여는 잔존 HUD — decision-queue #3) |
| !240 UI | 닉네임 변경 · provider 배지 · 보유 코인 · 거래내역 · 탈퇴 |
| Owner / Maturity | FE(LJH) + BE / **REAL** |

---

## 3. REAL / HYBRID / MOCK / BLOCKED Matrix

| 기능 | 데이터층 | 진입 트리거 | 표현(UI) | 종합 | 막고 있는 것 |
|---|---|---|---|---|---|
| Auth(Login/Callback/First Setup) | REAL | REAL | raw(무스타일 2화면) | **REAL** | — (SSAFY 는 BE !182) |
| Landing | — | REAL | REAL | **REAL** | — |
| Home 허브 | REAL 쿼리 | REAL | !240 신설 | **UI 신설분** | 제품 결정(§M-1) |
| World Surface | HYBRID | REAL | mock 정지화면 | **HYBRID** | Unity WebGL 서빙·Persistent Shell |
| World HUD | — | — | 허용 3종 중 1종 | **부분** | Post-MVP Optional |
| Overlay: PROJECT | REAL | 미송신(-343) | REAL VM 소비 | **HYBRID** | Unity 송신부 |
| Overlay: LAPTOP | REAL | REAL | REAL | **REAL** | — |
| Overlay: AI | HYBRID(파서 REAL) | REAL | mock 스트림 | **HYBRID** | AI conversation API |
| Overlay: SURVEY(Run) | MOCK adapter | **계약 없음** | !240 신설 | **MOCK** | BE Survey 6건 + Unity 이벤트 계약 |
| Overlay: CONSULTATION(Visitor) | MOCK adapter | **계약 없음** | !240 신설 | **MOCK** | BE WS + Unity 이벤트 계약 |
| Overlay: GAME | 파서 REAL | **미송신** | REAL | **BLOCKED** | BE game-portals + Unity 송신 + 소유권 결정 |
| Booth/Lease | REAL | REAL | REAL | **REAL** | — |
| Wallet | REAL | REAL | REAL(중복 배치) | **REAL** | 배치 결정 |
| Booth Studio | REAL | REAL | 임시 렌더러 | **REAL** | 2.5D spike(D-05) |
| Profile | REAL | REAL | REAL | **REAL** | — |
| Survey Result | 모델 REAL·adapter MOCK | — | **없음** | **MISSING UX** | UI 미구현 |
| Survey Builder | 모델 REAL·adapter MOCK | — | **없음** | **MISSING UX** | UI 미구현 + 진입점 미정 |
| Consultation Staff | 모델 REAL·adapter MOCK | — | **없음** | **MISSING UX** | UI 미구현 + 진입점 미정 |
| Project 소유자 편집 | 모델 REAL·API REAL | — | **없음** | **MISSING UX** | UI 미구현 + 진입점 미정 |
| Game Studio / Play | HYBRID | REAL | REAL | **PROTECTED** | 타 owner(D-01) |

---

## 4. Owner Boundary

| 영역 | Owner | 이번 조사에서 |
|---|---|---|
| FE 화면·상태·표현 (`pages/`·`features/` 중 studio·world·overlay·auth·booth·profile·survey·consultation) | **FE(LJH)** | 읽기만 |
| `game-studio/**` (Studio·Play·runtime·GameOverlay) | **박준우** — D-01 PROTECTED | 읽기만, 수정 금지 |
| `unity/**` 브리지 계약 소비부 | FE / 계약은 Unity 공동 | 읽기만 |
| Unity 프로젝트(`festa-unity/`) | KHS 등 Game 파트 | 미접촉 |
| BE API·가드·validator | BE(황덕 등) | 읽기만 |
| AI conversation·RAG | AI 파트 | 읽기만 |

---

## 5. Test Baseline (2026-09-03)

| 환경 | 결과 |
|---|---|
| worktree `ssafesta-ui` (406, `.env.local: VITE_USE_MOCK=true`) | **Test Files 1 failed / 82 passed (83), Tests 3 failed / 462 passed (465)** — 실패는 전부 `game-studio/__tests__/unit/playGamePageAssetWiring.test.tsx` |
| 원 저장소 `front` (`.env.local: VITE_USE_MOCK=false`) — 같은 파일만 | **4 passed** |

판정: 3건 실패는 **`VITE_USE_MOCK=true` 환경에서만** 발생하는 mock 자산 저장소 선택 문제이며, 인계의 주장이 실측으로 확인됐다. 해당 테스트는 **박준우 소유(D-01)** 라 수정하지 않는다. 커밋 전 검증은 `.env.local` 을 잠시 치우고 돌린다.

`typecheck`(`tsc -b`)·`lint`(oxlint)는 이번 조사에서 실행하지 않았다 — 코드를 바꾸지 않았으므로 기준선은 위 vitest 결과로 충분하다고 판단했다. **미실행 사실을 그대로 기록한다.**

---

## 6. Runtime Audit 관측 (mock, :5174)

| 단계 | 관측 |
|---|---|
| `/` | 타이틀 화면 정상. 클릭 → `/login`(가드 경유) |
| `/login` | SSAFY(준비 중 배지·disabled)·Google·Kakao·게스트 4버튼 |
| Google | `로그인 처리 중입니다...` **무스타일** |
| First Setup | `처음 오셨네요...` + 닉네임 1필드 **무스타일** |
| `/app/home` | 허브 카드(월드 입장 hero / 부스 슬롯 / 내 정보) + 상단 `회원으로 이용 중 · 로그아웃` |
| `/app/world` | 정지 배경 + 조작 안내 카드(좌상) + **하단 목업 상호작용 바 6버튼** + 상단 auth bar |
| PROJECT | 실제 VM 소비 — 카드·YouTube 임베드·좋아요 7 |
| LAPTOP | `festa.example.com` iframe + 새 탭 버튼 + Esc 안내 |
| AI | 제안 질문 3개 + `준비 중인 기능입니다` 표시 |
| SURVEY | 6문항 렌더 · `0/6 답변` · `필수 문항 3개가 남았습니다` |
| CONSULTATION | `대기 중 · 9:57 남음 · 대기 취소` (10분 카운트다운 동작) |
| GAME | **`게임을 열 수 없습니다` + 다시 시도** — BE portal 부재의 정직한 표현 |
| `/app/booths` | 슬롯 9개 · 임대 확인 다이얼로그(차감/보유/환불 불가 고지) · 임대 성공 후 "내 부스" 즉시 표시 · **거래내역 상시 노출** |
| `/app/studio/1` | 비소유 시 `이 부스의 소유자만 편집할 수 있습니다`. 임대 후 Booth Studio 3모드 정상 |
| `/app/profile` | 닉네임·Google 배지·200코인·거래내역·탈퇴 |
| Console | 오류 없음 |

Dead end / 이상 관측:
1. **World 에서 다른 화면으로 나가는 UI 가 없다.** 로그아웃 외에는 브라우저 뒤로가기뿐이다.
2. **거래내역이 Booth 와 Profile 두 곳에 중복**된다.
3. **Auth bar(회원 표시·로그아웃)가 World 를 포함한 모든 가드 화면 상단에 붙는다** — `RequireAuth` 가 children 위에 무조건 렌더하는 구조.
4. GAME 오버레이는 정상 동작의 결과로 오류를 보여준다(결함 아님).

---

## 7. 조사에서 나온 Finding (수정하지 않음)

| # | Finding | 성격 |
|---|---|---|
| F-1 | `/app/home` 은 제품 Home 이 아니라 `returnTo` 기본값 route 였다 | 제품 결정 필요(§M-1) |
| F-2 | SURVEY·CONSULTATION 은 Unity 이벤트 계약이 없다 — World 진입 경로가 미정의 | 계약 공백(3파트) |
| F-3 | Overlay Bus 는 스택이 아니라 단일 슬롯이다 — game-client-experience-draft 의 Overlay Stack 은 미구현 | DEFERRED |
| F-4 | Project 소유자 편집(`edit.ts`)은 완성돼 있으나 소비 UI 가 없다 | MISSING UX(신규 발견) |
| F-5 | 거래내역이 두 화면에 중복 노출된다 | UI 배치 결정 |
| F-6 | Auth bar 가 World 위에도 그려진다 | UI 배치 결정(§Gap) |
| F-7 | `-403` 은 FE 무영향 — facade `logoUrl` https-only 강화는 FE mock 규칙과 이미 일치 | 기록만 |
| F-8 | `VITE_USE_MOCK=true` 에서 game-studio 테스트 3건 실패 | 타 owner, 수정 금지 |
