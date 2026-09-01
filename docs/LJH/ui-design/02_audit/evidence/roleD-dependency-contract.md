# Role D — 의존성·계약·Blocker 전수조사 (ASC S-20260831-05)

- 조사일: 2026-08-31 | 기준: 로컬 `front` 브랜치 working tree + `origin/develop` 대조 (read-only)
- 원칙: Jira Done ≠ 구현 완료 — 모든 판정은 코드 실물(파일:라인) 근거. 실측 예: S15P21A604-137은 Jira '완료'이나 커밋 실물은 명세 문서 확정뿐, BE 상담 코드 0줄.
- FE↔develop 차이: `git diff HEAD origin/develop -- festa-frontend` = 0. BE는 develop이 앞섬(-177 방문자 Project API, -347 shared-config) — **front 로컬 backend 스냅샷에는 `GET /booths/{boothId}/projects/published`가 없고 origin/develop에는 있다** (commit 7d7f1c4).

---

## ① FE API consumer ↔ BE 실물 대조

FE 호출은 전부 `shared/api/client.ts`(단일 창구, base URL = `shared/config/runtime.ts` — 런타임 `window.__FESTA_CONFIG__.apiBaseUrl` → 빌드타임 `VITE_API_BASE_URL` → 상대경로) 경유. mock 분기는 `*.select.ts`의 `VITE_USE_MOCK === 'true'` 3항 (auth·wallet·layout·facade·lease 5종 + `unity/host/loader.select.ts`). game-studio는 별도 플래그 `VITE_GAME_STUDIO_API_ENABLED`(EditGamePage.tsx:8, PlayGamePage.tsx:15)로 real API를 게이트.

### FE가 부르고 BE에 있는 것 (정합)

| Endpoint | FE 근거 | BE 근거 | 비고 |
|---|---|---|---|
| POST /api/v1/auth/oauth/complete | entities/auth/api.ts:13 | auth/OAuthCompletionController.java:34 | |
| GET /api/v1/auth/oauth/{provider} (리다이렉트) | pages/login/LoginPage.tsx:30 | auth/OAuthAuthorizationController.java:22 | |
| POST /api/v1/auth/guest | entities/auth/api.ts:23 | auth/GuestAuthController.java:32 | |
| POST /api/v1/auth/refresh | entities/auth/api.ts:29 | auth/GuestAuthController.java:40 | 401 인터셉트 재시도(client.ts:55-59) |
| POST /api/v1/auth/logout | entities/auth/api.ts:38 | auth/GuestAuthController.java:55 | |
| GET /api/v1/wallets/me | entities/wallet/api.ts:7 | wallet/WalletController.java:42 | |
| GET /api/v1/wallets/me/transactions | entities/wallet/api.ts:12 | wallet/WalletController.java:49 | offset page |
| GET/PUT /api/v1/booths/{id}/layouts/draft | entities/layout/api.ts:16,21 | booth/BoothLayoutController.java:33,49 | |
| POST /api/v1/booths/{id}/layouts/publish | entities/layout/api.ts:28 | booth/BoothLayoutController.java:59 | |
| GET /api/v1/booths/{id}/layouts/published | entities/layout/api.ts:32 | booth/BoothLayoutController.java:69 | |
| GET /api/v1/booth-layout-templates | entities/layout/api.ts:36 | booth/BoothLayoutTemplateController.java:24 | |
| GET /api/v1/booths/{id} | entities/booth/facadeApi.ts:8 | booth/BoothController.java:37 | 016 확장으로 `homepageUrl` 포함 — **FE는 이 필드를 아직 소비하지 않음**(② 참조) |
| PUT /api/v1/booths/{id}/facade | entities/booth/facadeApi.ts:12 | booth/BoothFacadeController.java:30 | |
| GET /api/v1/booth-slots | entities/booth/leaseApi.ts:7 | booth/BoothSlotController.java:37 | |
| POST /api/v1/booth-slots/{slotId}/leases | entities/booth/leaseApi.ts:13 | booth/BoothSlotController.java:61 | |
| GET /api/v1/booths/mine | entities/booth/leaseApi.ts:22 | booth/BoothController.java:24 | |
| GET/PUT /api/v1/games/{id}/draft | game-studio/studio/ports/gameAuthoringApi.ts:120,129 | game/GameController.java:105,122 | 플래그 게이트 |
| POST /api/v1/games/{id}/publish | gameAuthoringApi.ts:146 | GameController.java:133 | |
| GET /api/v1/games/{id}/published | game-studio/runtime/ports/publishedGameRepository.ts:143 | GameController.java:153 | |

### FE가 부르는데 BE에 없는 것 (Functional Blocker)

| Endpoint | FE 근거 | 계약 근거 | 판정 |
|---|---|---|---|
| GET /api/v1/game-portals/{configId} | game-studio/runtime/ports/gamePortalRepository.ts:116 | specs/019-game-studio/contracts/game-api.md:526, docs/08:1008 | **BE 미구현** — backend에 `game-portals` 문자열 0건. GAME 오버레이 real 경로 차단 |
| POST /api/v1/games/{id}/assets | game-studio/studio/assets/remoteAssetRepository.ts:133 | specs/019 contracts/game-asset-upload.md:30 | **BE 미구현** — GameController에 asset 엔드포인트 0개 |
| POST /api/v1/games/{id}/assets/{assetId}/complete | remoteAssetRepository.ts:139 | game-asset-upload.md:31 | 동상 |
| GET /api/v1/games/{id}/assets/{assetId}/content | remoteAssetRepository.ts:36 | game-asset-upload.md:35 (302 redirect) | 동상 — 에셋 쓰는 published 게임 재생 불가 |

### BE에 있는데 FE가 안 부르는 것

| Endpoint | BE 근거 | 판정 |
|---|---|---|
| POST /api/v1/world-sessions | world/WorldSessionController.java:39 | 정상 — 소비자는 Unity (festa-unity .../Integration/Spring/HttpUserApiClient.cs, ConnectionManager.cs) |
| GET /api/v1/booth-slots/{slotId}/layouts/published | BoothSlotController.java:50 | 정상 — Unity 소비 (festa-unity/Tools/mock-api에 픽스처 존재) |
| GET/PATCH/DELETE /api/v1/users/me, PUT /avatar | user/MyAccountController.java:39,46,68,91 | **FE Profile/Avatar UI 미구현** — FE에 `users/me` 호출 0건. FE 자체 작업이며 외부 blocker 아님 |
| POST/GET /booths/{id}/agents, GET/PATCH/DELETE /agents/{id} | ai/AiAgentController.java:38-76 | **FE AI 에이전트 관리 UI 미구현** (spec 007/008 FE분) |
| PUT /api/v1/booths/{id}/homepage | booth/BoothHomepageController.java:31 | **FE 홈페이지 등록 폼 미구현** (016 소유자 UI) — FE에 `homepage` 호출 0건 |
| POST/GET /booths/{id}/projects, PATCH /projects/{id} | project/ProjectController.java:43,55,63 | **FE Project 편집 UI 미구현** (S15P21A604-134 해야 할 일) |
| GET /booths/{id}/projects/published | origin/develop만 (commit 7d7f1c4) | FE 방문자 표시 UI 미구현 + **front 로컬 BE 스냅샷에 없음 → 착수 전 fetch/merge 필요** |
| POST /games, GET /games/mine, PATCH/DELETE /games/{id}, POST restore | GameController.java:46,63,69,84,92 | **FE 게임 생성·목록 UI 미구현** — FE 라우트는 /app/games/:gameId/edit·play 뿐 (app/router/index.tsx:72,85) |
| GET /internal/ai/booth-access | internal/ai/AiBoothAccessController.java:32 | 정상 — 소비자는 AI 서버 (specs/008 contracts/spring-booth-access-api.yaml) |

### AI 서버(festa-ai) 실물

- 구현된 것: `POST /ai/v1/documents`(app/api/v1/documents.py:34) + health(app/api/v1/router.py:9). prefix `/ai/v1` (app/main.py:29).
- **미구현**: specs/008 contracts/conversation-api.yaml의 `POST /conversations`, `GET /conversations/{id}`, `POST /conversations/{id}/messages`(SSE) 전부 없음. FE는 SSE 파서·타입만 선구현(entities/conversation/stream.types.ts — Issue #32 계약 전사, stream.parser.ts — POST라 EventSource 불가·fetch 스트림 파싱).

---

## ② Unity 이벤트 계약 대조

FE 수신부: unity/bridge/events.ts (window.FestaUnity.onBoothInteract / onWorldGateReady) → features/interaction/dispatcher.ts → shared/types/overlay.ts → features/overlay/OverlayHost.tsx.
Unity 송신부: Assets/Plugins/WebGL/FestaUnityBridge.jslib + Assets/_Project/Scripts/Integration/Bridge/BoothInteractBridge.cs.

| 이벤트 | 계약 | Unity 송신 | FE 수신·처리 | 판정 |
|---|---|---|---|---|
| BOOTH_LAPTOP_INTERACT | 016 spec + events.ts:8 | ✅ BoothInteractBridge.cs:29 — payload `{type, boothId, objectId}` (url 없음) | ✅ events.ts:8→dispatcher.ts:13→LaptopOverlay | ⚠️ **계약-구현 불일치**: FE LaptopOverlay.tsx:36은 이벤트 payload의 `url?`(events.ts:11)에 의존하나 Unity는 url을 보내지 않음. 016 확정 계약(contracts/homepage-api.md — URL은 `GET /booths/{id}`의 `homepageUrl`, C-01/C-02 #97 확정)대로면 **FE가 상호작용 시 booth 조회로 URL을 가져와야 하는데 그 배선이 없음** → 현 상태에서 노트북은 항상 "홈페이지 미준비" 안내. FE 수정 필요(Unity 무관, CLAUDE.local.md의 'url 필드 제거는 Unity 합의' 건과 별개로 FE 소비 전환은 자체 처리 가능) |
| AI_AGENT_INTERACT | Issue #2 (3파트 확정), events.ts:14 | ✅ BoothInteractBridge.cs:32, AiNpcInteractable.cs | ✅ dispatcher.ts:16 → AI_CHAT (configId→agentId 변환 events.ts:80) | 이벤트 왕복은 완결. **AI_CHAT 오버레이 UI가 placeholder** (OverlayHost.tsx:34 "준비 중") |
| BOOTH_GAME_INTERACT | specs/019 contracts/game-portal-bridge.md (Draft v0.3, #20·#34) | ❌ **Unity 송신부 없음** — BoothInteractBridge.cs 상수는 LaptopInteract·AiAgentInteract 2종뿐, festa-unity 전체에 BOOTH_GAME_INTERACT 문자열 0건 | ✅ events.ts:20→dispatcher.ts:19→GameOverlay(lazy) | Unity 송신부 + BE game-portals 둘 다 미구현 — 월드 내 게임 진입 경로 전체 차단 |
| BOOTH_PROJECT_INTERACT | **레포 어디에도 없음** (grep 0건) — Jira S15P21A604-343에만 존재 | ❌ 미구현 ('해야 할 일', 강형순) | ❌ dispatcher에 핸들러 없음, OverlayType에 PROJECT 없음(overlay.ts:4) | **양단 미구현 + 계약 문서도 미작성**. PROJECT_PANEL은 Layout 오브젝트 타입으로만 존재(BE LayoutObjectType.java:25) |
| onWorldGateReady | Issue #31, spec 002 FR-013·FR-014 | ✅ WorldEntryGate.cs:338, jslib:20 | ✅ events.ts:58, WORLD_GATE_TIMEOUT_MS=60s(shared/config/unity.ts:6) | 완결 |
| SURVEY / CONSULTATION 상호작용 | 이벤트 타입 미정의 (overlay.ts:4에 OverlayType만 선언) | ❌ | ❌ (OverlayHost placeholder) | 계약·양단 모두 부재 (P1) |
| React→Unity 자격증명 전달 (013a-AT) | **미결** — shared/api/client.ts:40-48 TODO: token 종류·시점·수신자·갱신 전부 미합의 | — | getAccessToken은 FE 내부 경계일 뿐 | Unity가 인증된 world-session을 FE 토큰으로 얻는 경로 미확정 — 통합 월드 접속의 계약 공백 |

---

## ③ 도메인별 Blocker 판정

Design Blocker = 디자인(화면 설계) 선행조차 막는 것. 전 도메인에서 **Design Blocker는 없음** — spec 초안·docs/05·06(디자인 가이드·와이어프레임)이 전 도메인 커버.

| 도메인 | Functional Blocker (외부 의존) | FE 자체 잔여 작업 (blocker 아님) |
|---|---|---|
| Auth | 없음 — FE·BE 완결 | — |
| Booth·Lease | 없음 — FE·BE 완결 | — |
| Studio(005 Layout·Facade) | 없음 — FE·BE 완결 | — |
| Publish | 없음 (Studio에 포함, 완결) | — |
| Wallet | 없음 — FE·BE 완결 | — |
| Profile | 없음 — BE(users/me 4종) 존재 | FE Profile·닉네임 수정·탈퇴·아바타 UI 전부 미구현 |
| Project(009) | ① Unity BOOTH_PROJECT_INTERACT 송신부 부재(-343, 계약 문서도 미작성) ② front 로컬 BE에 방문자 API 없음(develop fetch로 해소 가능) | FE 편집 UI(-134)·방문자 패널·dispatcher 핸들러·OverlayType 추가 |
| Game(019) | ① BE `GET /game-portals/{configId}` 부재 ② BE 게임 에셋 API 3종 부재 ③ Unity BOOTH_GAME_INTERACT 송신부 부재 | 게임 생성·목록 UI. draft/publish/published는 BE 존재 — 플래그 열면 단독 라우트는 에셋 없는 게임에 한해 동작 가능 |
| AI(007·008) | ① AI 서버 conversation API(SSE) 미구현(계약 conversation-api.yaml만 존재) | AI_CHAT 오버레이 UI, 에이전트 관리 UI(BE agents API는 존재), 문서 업로드 UI(AI 서버 documents API는 존재) |
| Survey(010) | ① BE Survey API 전무(-130·-131·-132·-190·-192·-193 전부 '해야 할 일') ② Unity SURVEY_KIOSK 이벤트 미정의 | SURVEY 오버레이·집계 화면(-133·-194, 이정헌 배정) — **디자인 선행은 지금 가능** |
| Consultation(011) | ① BE WS 채널·토큰 미구현 — Jira -137 '완료'는 **명세 확정 커밋(ee1c906)일 뿐 코드 0줄** (backend에 consultation·WebSocket 구현 없음) ② Unity CONSULTATION_DESK 이벤트 미정의 | CONSULTATION 오버레이·Staff 화면(-138) — 디자인 선행 가능 |
| Dashboard·Staff·Admin(015) | ① BE 코드 전무 (spec 015 초안만, plan/tasks 미생성) | 디자인 선행 가능 |
| World(Unity Host) | ① Unity WebGL 빌드 배포 경로·생성 위치 미결(unity/host/resolver.ts:1-4 주석, #30 후속 — FE nginx.conf에 unity location 없음, infra-003은 게임 '서버'(WSS) 전용이라 WebGL 정적 서빙 미커버) ② VITE_UNITY_BUILD_BASE 런타임 주입 없음(-341) ③ 013a-AT 자격증명 handoff 미결 | mock 로더로 개발은 가능(loader.select.ts) |

---

## ④ 계약만 있고 구현이 없는 것 (전체 목록)

1. `GET /api/v1/game-portals/{configId}` — 계약: specs/019/contracts/game-api.md:526 · FE 호출 존재 · BE 없음
2. 게임 에셋 6종 중 FE가 쓰는 3종 포함 전부 — 계약: specs/019/contracts/game-asset-upload.md:30-35 · BE 없음
3. AI conversation 3종(POST /conversations, GET 단건, POST messages SSE) — 계약: specs/008/contracts/conversation-api.yaml · festa-ai 없음 (FE는 파서만 선구현)
4. BOOTH_PROJECT_INTERACT — Jira -343에만 존재. Unity 송신·FE 수신·계약 문서 3종 모두 부재
5. BOOTH_GAME_INTERACT Unity 송신부 — 계약(game-portal-bridge.md)은 있으나 festa-unity에 0건
6. SURVEY·CONSULTATION 오버레이 — OverlayType 선언(overlay.ts:4)만 있고 이벤트 계약·UI·BE 전부 부재
7. 016 소유자 홈페이지 등록 — BE PUT /homepage 존재·계약 확정(#97)이나 FE 폼 0건 + **FE LAPTOP 오버레이가 계약과 달리 이벤트 url에 의존**(②)
8. Unity 자격증명 handoff(013a-AT) — client.ts:40 TODO, 계약 자체가 미합의
9. spec 011 상담 Realtime — 명세(-137 확정)만 있고 BE·FE 코드 0
10. Survey·Dashboard(010·015) — spec 초안만, plan/tasks·코드 전무

## ⑤ 환경변수·런타임 설정 전수 (FE)

| 키 | 읽는 곳 | .env.example | 런타임 주입 |
|---|---|---|---|
| VITE_API_BASE_URL | shared/config/runtime.ts:20 | ✅ | ✅ PUBLIC_API_BASE_URL → docker/40-runtime-config.sh → /runtime-config.js (no-store, nginx.conf:9) |
| VITE_USE_MOCK | *.select.ts 5종 + unity/host/loader.select.ts:7 + LoginPage.tsx:14 + Edit/PlayGamePage | ✅ (있음 — -341 제목과 달리 현재 파일엔 존재) | ❌ 빌드타임 전용 |
| VITE_UNITY_BUILD_BASE | unity/host/resolver.ts:8 (`${BASE}/manifest.json`, 키 4종 #60 확정) | ❌ **누락** (-341) | ❌ — 배포 이미지에서 Unity 빌드 위치 지정 수단 없음 |
| VITE_GAME_STUDIO_API_ENABLED | EditGamePage.tsx:8, PlayGamePage.tsx:15 | ✅ | ❌ |
| VITE_USE_POLLING | vite.config.ts:10 (dev watch 전용) | — (불필요) | — |

Unity WebGL 서빙: FE nginx.conf에 unity 정적 경로 없음, manifest.json 생성 위치·배포 경로·캐시 헤더 미결(resolver.ts 주석, #30 후속). infra/unity-server는 멀티플레이 게임 서버(WSS) 스펙이라 별건.

## ⑥ 참조한 Jira 키 요지 (읽기 전용, jira_context/search 실측)

| 키 | 상태 | 요지 |
|---|---|---|
| S15P21A604-341 | 해야 할 일 (Low) | .env.example에 VITE_UNITY_BUILD_BASE·VITE_USE_MOCK 누락 — 실측: UNITY_BUILD_BASE만 누락 확인, USE_MOCK은 현재 존재 |
| S15P21A604-343 | 해야 할 일 (강형순) | [UNITY] PROJECT_PANEL 상호작용 송신부 BOOTH_PROJECT_INTERACT — 레포에 계약·코드 0건, FE 수신부도 없음 |
| S15P21A604-110 | 완료 | BE Project 등록·수정 API — 코드 실물 확인(ProjectController 3종). -134(FE 전시 UI, 해야 할 일)를 blocks |
| S15P21A604-177 | 완료 | BE 방문자 Project 조회 — **origin/develop에만 존재**(7d7f1c4), front 로컬 스냅샷엔 없음. -134·-135(좋아요, 해야 할 일)를 blocks |
| S15P21A604-155 | 진행 중 (박준우) | FE GameProject v1.1 스키마·에디터 반영 — game-studio 영역 동시 작업 중, UI 통합 시 충돌 주의 |
| S15P21A604-137 | 완료 | 상담 WS 채널·토큰 — **커밋 실물은 명세 확정 문서뿐(ee1c906), BE 구현 0** (Jira Done ≠ 구현의 실례) |
| S15P21A604-130~133, 190~194 | 해야 할 일 | Survey BE 6건(황덕)·FE 2건(이정헌) 전부 미착수 |
| S15P21A604-138 | 해야 할 일 | FE Staff 상담 화면 (cutline-review 라벨 — 컷라인 검토 대상) |
| S15P21A604-134·135 | 해야 할 일 | FE Project 전시 UI · BE 좋아요 API |
