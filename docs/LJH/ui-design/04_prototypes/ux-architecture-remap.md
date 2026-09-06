# UX Architecture Remap — 새 User Flow 기준 재배치

> **STATUS: IMPLEMENTED / BASELINE** — 이 계획의 R1~R9 는 `!240` 로 구현돼 develop 에 반입됐다
> (merge `e961adda`, squash `4c843928`, 2026-09-03). 이후 이 문서는 "무엇을 왜 그 자리에 뒀는가"의
> 근거 기록으로 읽는다. 구현 상태의 정본은 코드이며, 화면별 상태는 `screen-specifications.md` 다.
> §10 Contract Gap 은 여전히 열려 있다 — 후속 작업군으로 분리됐다.

- 문서 종류: **PROPOSAL → IMPLEMENTED** (계획대로 구현 완료)
- 작성일: 2026-09-03
- 흐름 정본: `00_context/user-flow-decisions.md` (D-08) · HUD 정본: `00_context/hud-decisions.md`
- 실측 근거: `02_audit/function-truth-inventory.md` · `02_audit/current-ui-gap-matrix.md`
- 대상: `!240` — **merged** (squash `4c843928` → develop `e961adda`). 계획 시점 baseline 은 `07bf7612` / develop `0bf878c6` 였다
- 원칙: **기능층(VM·Adapter·상태 기계)은 보존하고 Presentation 과 진입 구조를 재배치한다.** !240 은 개선 대상이 아니라 **기능 wiring 검증 자산**이다 — 거기서 잘못 붙은 Presentation 만 떼어낸다.

---

## 1. !240 재분류 Matrix

판정 6종: `KEEP`(그대로 둔다) / `MOVE`(자리만 옮긴다) / `REDESIGN`(같은 목적, 표현 재작성) / `REMOVE`(폐기) / `DEV_ONLY`(개발 대역으로만 유지) / `NEW`(신규).

**기능 wiring 과 Presentation 을 분리해 각각 판정한다.** 같은 항목이 두 축에서 다른 판정을 받을 수 있다 — 그것이 이 표의 요점이다.

| # | 항목 | 기능 wiring | Presentation | 진입 위치 | 근거 |
|---|---|---|---|---|---|
| 1 | **WorldSurface** | **KEEP** — `WorldSurface.select` seam(mock↔UnityHost) | **KEEP** — 정지 캡처는 Unity 도달까지 유효 | 유지 | 교체 계약이 이미 seam 으로 서 있다 |
| 2 | **World HUD** (조작 안내) | **KEEP** | **KEEP** | 좌상 유지 | 허용 HUD 1번 |
| 3 | **MockInteractionBar** | KEEP(dispatcher 와 동일 payload) | **DEV_ONLY** | 제품 UI 에서 미노출 | `hud-decisions` 기능 Launcher 금지. Unity 부재 검증 대역 |
| 4 | **AuthHeader** (`RequireAuth` 내부) | KEEP(logout 호출) | **REMOVE** (World 에서) / **MOVE** (다른 화면) | ESC Game Menu 로 | World 는 상주 상태 — 계정 칩이 상단에 박히지 않는다 |
| 5 | **Home** (`HomePage` 허브) | KEEP(내 부스 쿼리) | **REMOVE** | `/app/home` 호환 route 로 축소 | D-08 — Home 대시보드는 제품 구조가 아니다 |
| 6 | **Auth Processing** | **KEEP** (상태 기계 완성) | **REDESIGN** | 유지 | raw HTML 노출 금지 — 게임 부팅 표현 |
| 7 | **First Setup** | **KEEP** | **REDESIGN** | 유지 | 닉네임 1개. 필드 추가 금지 |
| 8 | **Profile** | **KEEP** (`profile.ts`) | **REDESIGN** → My Info | ESC Profile Summary 경유로 **MOVE** | Wallet 과 한 Context |
| 9 | **Wallet / Transaction** | **KEEP** (`walletApi`·`WalletBadge`·`TransactionsSection`) | **MOVE** — 거래내역은 My Info 한 곳 | Booth 화면에서 **REMOVE** | 두 화면 중복 노출 실측 |
| 10 | **Booth / Lease** | **KEEP** (`useLeaseSlot`·`leaseConfirm`·`remaining`) | **REDESIGN** — 목록 화면 → Management 안의 상태 표시 + No Booth 임대 흐름 | 독립 route → **MOVE** to Booth Management | Lease 는 Editor 가 아니라 상태 정보 |
| 11 | **Booth Studio** | **KEEP** (전 기능층) | **KEEP** (-405 Shell) | **MOVE** — 진입: Booth Management, 복귀: Booth Management | 독립 Creator Workspace 유지 |
| 12 | **Project Overlay** | **KEEP** (`exhibition.ts`) | **KEEP** | 유지 (Unity F) | Visitor 전용. 편집 혼입 금지 |
| 13 | **Project Edit** | **KEEP** (`edit.ts` 완성) | **NEW** — 소비 UI 가 없다 | Booth Management → Project Management | MISSING UX |
| 14 | **LAPTOP** | **KEEP** (`laptopHomepage.ts`) | **KEEP** | 유지 (Unity F) | 새 탭 1급 유지 |
| 15 | **Survey Run** | **KEEP** (`run.ts`) | **KEEP** | 유지 (Unity 계약 대기 G-2) | Visitor 전용 |
| 16 | **Survey Builder** | **KEEP** (`builder.ts` 완성) | **NEW** | Booth Management → Survey Management | MISSING UX |
| 17 | **Survey Result** | **KEEP** (`result.ts` 완성) | **NEW** | 동일 Management Context 안 탭 | MISSING UX. 최상위 카드로 분리하지 않는다 |
| 18 | **Consultation Visitor** | **KEEP** (`visitor.ts`) | **REDESIGN** — close 정책 1건(§2) | 진입이 Overlay → **HUD 우상단 Quick Access** 로 **MOVE** | 시간 지속성 예외 |
| 19 | **Consultation Staff** | **KEEP** (`staff.ts` 완성) | **NEW** | Booth Management → Consultation | MISSING UX |
| 20 | **AI** | **KEEP** (파서·mock) | **KEEP** | 유지 (Unity F) | AI 로직 미접촉 |
| 21 | **GAME** | **KEEP** (PROTECTED) | **KEEP** (PROTECTED) | 유지 | D-01. BLOCKED 상태 그대로 |
| 22 | **OverlayFrame** | — | **KEEP** — Visitor Overlay·Booth Management·ESC Menu 공유 골격 | 유지 | `size` 확장만 필요할 수 있다 |
| 23 | **PageShell** | — | **KEEP (역할 축소)** | World 밖 전체 화면 = Booth Studio + Management Detail 뿐 | Screen Family 가 줄어든다 |
| 24 | ESC Game Menu | — | **NEW** | World ESC | Personal/System |
| 25 | Consultation Quick Access 💬 | KEEP(`visitor.ts` 소비) | **NEW** | World 우상단 | 허용 HUD 4번 |
| 26 | Booth Management Overlay | KEEP(기존 API 조합) | **NEW** | Booth Management NPC F (계약 G-1) | Owner 관리 통합 진입 |
| 27 | My Info | KEEP(profile+wallet) | **NEW 구조 / 기존 화면 재배치** | ESC Profile Summary | Personal Context |
| 28 | Login 푸터 dead text | — | **REMOVE** | — | 링크 대상이 없다 |

집계 — 기능 wiring: **KEEP 24 / REMOVE 0** · Presentation: KEEP 9 / REDESIGN 5 / MOVE 2 / REMOVE 3 / DEV_ONLY 1 / NEW 6.

**기능층에서 버리는 것은 하나도 없다.** 재배치와 표현 재작성만 있다.

### 1-1. 기능층 자산 목록 (전량 KEEP — 재사용 대상)

| 자산 | 위치 | 소비할 새 화면 |
|---|---|---|
| Overlay Bus (단일 슬롯) | `shared/types/overlay.ts` | Visitor Overlay 전체 |
| Interaction Dispatcher | `features/interaction/dispatcher.ts` | 동일 |
| Unity Bridge 이벤트 계약 | `unity/bridge/events.ts` | 동일 (+ 신규 계약 3건 대기) |
| `WorldSurface.select` | `features/world/ui/` | World |
| `exhibition.ts` / `edit.ts` | `features/project/model/` | Project Overlay / Project Management |
| `laptopHomepage.ts` | `features/overlay/model/` | LAPTOP |
| `run.ts` / `result.ts` / `builder.ts` | `features/survey/model/` | Survey Run / Survey Management |
| `visitor.ts` / `staff.ts` | `features/consultation/model/` | Consultation HUD·Overlay / Consultation Staff |
| `profile.ts` | `features/profile/model/` | My Info · ESC Profile Summary |
| `walletApi`·`WalletBadge`·`TransactionsSection` | `features/wallet/` | My Info · ESC Profile Summary |
| `useLeaseSlot`·`leaseConfirm`·`remaining` | `features/booth/`·`entities/booth/` | Booth Management (Lease·No Booth) |
| `facadeApi.getBooth` · `leaseApi.getMyBooth` | `entities/booth/` | Booth Management Identity·Mini Preview |
| Booth Studio 기능층 | `features/studio/` | Booth Studio |
| `entities/conversation` | — | AI Overlay |

## 2. 새 Flow 와 충돌하는 현재 동작 — 1건

`user-flow-decisions.md` §11.4 는 `Overlay Close ≠ Consultation Cancel` 을 정한다. 현재 코드는 반대다.

```tsx
// festa-frontend/src/features/consultation/ui/ConsultationOverlay.tsx
return () => {
  // 오버레이를 닫으면 대기 중이던 요청도 정리한다
  if (state.phase === 'waiting' || state.phase === 'requesting') void cancelConsultation();
};
```

**구현 가능성 판정: 가능하다.** 근거 —

- `features/consultation/model/visitor.ts` 의 state·`setInterval` 카운트다운·채널 구독은 전부 **module-level** 이다. React 컴포넌트가 unmount 돼도 살아 있다.
- 즉 위 cleanup 을 제거하면 `waiting` 상태가 World 로 돌아간 뒤에도 유지되고, HUD 💬 를 다시 눌러 오버레이를 열면 같은 상태가 그대로 보인다. **새 API·새 계약이 필요 없다.**
- 취소는 이미 명시적 액션(`cancelConsultation`)으로 분리돼 있다.
- mock 채널 기준의 판정이다. 실 STOMP transport 는 미확정이라 **transport 도달 시 재확인 대상**으로 남긴다.

## 3. 남는 구조적 질문 2건 (구현 설계에서 푼다)

**3-1. Booth Management·ESC Menu 는 Overlay 인가 Route 인가.** 새 Flow 는 둘 다 World 위에 뜨는 Overlay 로 그린다. 현재 Overlay Bus 는 Unity 이벤트에서 온 요청만 담는 단일 슬롯이고 `WorldPage` 생명주기에 묶여 있다. ESC Menu 는 Unity 이벤트가 아니라 키 입력에서 열리므로 **Overlay Bus 를 쓸지 별도 UI 상태로 둘지**를 정해야 한다. Booth Studio 는 전체 화면 Workspace 라 route 유지가 자연스럽다.

**3-2. World 밖 화면에서의 auth bar.** `RequireAuth` 는 가드가 통과한 **모든** 화면에 `AuthHeader` 를 그린다. World 에서 제거하려면 가드 자체를 손대야 하고, 그때 Booth Studio·Management Detail 화면의 상단도 함께 바뀐다. 최소 변경안: `AuthHeader` 렌더를 `RequireAuth` 에서 떼어내고 각 컨테이너가 필요할 때만 그린다.

---

## 4. Booth Management — 데이터 실현 가능성

`user-flow-decisions.md` §16·§17 의 상단 Identity 와 Mini Preview 를 **현재 API 만으로 채울 수 있다.**

| 표시 항목 | 출처 | 상태 |
|---|---|---|
| Booth Name | `GET /booths/mine` → `MyBooth.name` | 존재 |
| Slot Code | `MyBooth.lease.slotCode` | 존재 (미연결 시 null) |
| Lease Status | `MyBooth.lease` 유무 + `BoothDetail.leaseStatus` | 존재 |
| Remaining Time | `MyBooth.lease.endsAt` → `remaining.ts` 의 기존 카운트다운 | 존재 (SlotListPage 와 같은 로직 재사용) |
| **Booth Mini Preview** | `GET /booths/{boothId}` → `BoothDetail.facade{themeCode, primaryColor, signText, logoUrl}` | **존재 — 새 API 불필요** |
| Project 요약 | `projectApi.getMyProjects(boothId)` | 존재 |
| Survey 요약 | `surveyApi.getDraft()` (mock) | mock adapter |
| Consultation 요약 | `consultationStaff.getQueue()` (mock) | mock adapter |

Mini Preview 는 facade 4필드로 그리는 **순수 React 시각물**이다: `themeCode` 가 테마 골격, `primaryColor` 가 강조색(12색 팔레트 중 하나), `signText` 가 간판 문구, `logoUrl` 이 로고. Booth Studio 에서 외관을 바꾸면 자동으로 반영된다. Project 로고를 부스 대표 이미지로 쓰지 않는다(RULE 11).

부스 없음 상태는 `GET /booths/mine` 이 204 를 주는 것으로 판정된다 — `myBoothQuery.data == null` 이면 Empty State + `[부스 임대하기]`.

---

## 5. Feature → Target Container Map

```text
ENTRY
├─ Landing              /
├─ Login                /login
├─ Auth Processing      /auth/callback         (게임 부팅 표현으로 재작성)
└─ First Setup          /auth/callback 분기     (닉네임 1개)
                            ↓ 기본 목적지 = World

WORLD                   /app/world
├─ Unity World Layer    (mock: StaticMockWorldSurface)
├─ Control Guidance HUD (좌상)
├─ Toast / Notification (미구현 — Post-MVP)
├─ Consultation Quick Access 💬 (우상)          ← NEW
├─ Booth Management NPC (Unity 오브젝트 — 계약 대기)
└─ [DEV_ONLY] Mock Interaction Bar

VISITOR OVERLAY         (Unity F 로만)
├─ Project              KEEP
├─ LAPTOP               KEEP
├─ Survey Run           KEEP  (Unity 이벤트 계약 대기)
├─ AI                   KEEP
└─ GAME                 KEEP (PROTECTED, BLOCKED)

PERSONAL / SYSTEM       (ESC)
├─ Game Menu            NEW
├─ Profile Summary      NEW  (avatar·nickname·provider·coin)
├─ My Info              REPLACE (기존 ProfilePage)
│   ├─ Profile          nickname·provider·avatar state·withdrawal
│   └─ Wallet           balance·transaction history
├─ Settings             NEW (껍데기만 — 기능은 Deferred)
└─ Logout               MOVE (World 상단 → 여기)

BOOTH MANAGEMENT        (Booth Management NPC F)
├─ Booth Identity + Mini Preview   NEW
├─ [부스 스튜디오 열기]              NEW (진입 버튼)
├─ Project      → 관리 >            NEW
├─ Survey       → 관리 >            NEW
├─ Consultation → 관리 >            NEW
├─ Lease / Booth Info               MOVE (SlotListPage 의 상태 표시분)
└─ No Booth → [부스 임대하기]        MOVE (SlotListPage 의 임대 흐름)

CREATOR WORKSPACE
└─ Booth Studio         /app/studio/:boothId — KEEP, 진입·복귀만 변경

MANAGEMENT DETAIL
├─ Project Editor       NEW  (edit.ts 소비)
├─ Survey Builder       NEW  (builder.ts 소비)
├─ Survey Result        NEW  (result.ts 소비)
└─ Consultation Staff   NEW  (staff.ts 소비)
```

---

## 6. Route / Entry / Exit Map

새 Flow 의 이동 경로 전부. **닫기가 어디로 되돌리는지**까지 명시한다 — 그것이 재구현에서 가장 자주 틀리는 지점이다.

### 6-1. Entry

```text
/  Landing
└─ 시작 제스처
     └─ 인증 없음 → /login (returnTo 저장)
        인증 있음 → World

/login
├─ OAuth  → 전체 페이지 이동 → /auth/callback
└─ Guest  → 즉시 세션

/auth/callback  Auth Processing
├─ AUTHENTICATED     → consumeReturnTo()   기본값 = World
└─ NICKNAME_REQUIRED → First Setup(닉네임 1개) → consumeReturnTo()
   실패(400/410)     → Login 복귀

explicit deep link 는 그대로 존중한다. 기본값만 World 로 바뀐다.
```

현재 코드 대비 변경점: `returnTo.ts` 의 `DEFAULT_RETURN_TO` (`/app/home` → World), Landing 클릭 목적지, `/app/home` 축소.

### 6-2. World ↔ Visitor Overlay

```text
World (상주)
└─ 대상 근접 → Unity Highlight·F Prompt
     └─ F → Unity 이벤트 → Dispatcher → openOverlay
          └─ Visitor Overlay (Project / LAPTOP / Survey / AI / GAME)
               └─ Esc · 닫기 · 바깥 클릭
                    └─ 같은 World, 같은 위치
```

World 를 "나가지" 않는다. Overlay 를 열고 닫을 뿐이다.

### 6-3. World ↔ Booth Management

```text
World
└─ Booth Management NPC 접근 → Unity F Prompt "F  내 부스 관리"
     └─ F → Booth Management Overlay
          ├─ 부스 있음 → Identity + Preview + 4섹션
          └─ 부스 없음 → Empty State → [부스 임대하기] → Lease → 같은 Overlay 가 Has Booth 로 전환
               └─ Close · Esc
                    └─ 같은 NPC 앞 World
```

진입 계약 G-1 대기 — 그 전에는 dev trigger 로만 연다.

### 6-4. Booth Management ↔ Booth Studio

```text
Booth Management
└─ [부스 스튜디오 열기]
     └─ Booth Studio (전체 화면 Creator Workspace)
          └─ Exit
               └─ Booth Management  (World 로 바로 튕기지 않는다)
```

### 6-5. Booth Management ↔ Management Detail

```text
Booth Management
├─ PROJECT      [관리 >] → Project Management  → 뒤로 → Booth Management
├─ SURVEY       [관리 >] → Survey Management   → 뒤로 → Booth Management
│                              ├─ [설문 편집] Builder
│                              └─ [결과]     Result      (같은 Context 안 전환)
└─ CONSULTATION [관리 >] → Consultation Staff  → 뒤로 → Booth Management
```

### 6-6. Consultation Quick Access

```text
World
└─ HUD 우상단 💬 (상태: Idle / Waiting ● / Active ● / Staff Request ●)
     └─ Consultation Overlay
          └─ Close
               └─ World  — **상담은 취소되지 않는다**
                          취소는 [상담 요청 취소] 명시적 액션으로만
```

`waiting` 상태로 World 를 돌아다니다 💬 를 다시 누르면 같은 상태가 복원된다(§2 근거).

### 6-7. ESC Game Menu

```text
World
└─ ESC → Game Menu
     ├─ Profile Summary → 내 정보 > → My Info
     │                                   └─ 닫기·뒤로 → Game Menu 또는 World
     ├─ 설정  (Deferred — 껍데기)
     └─ 로그아웃 → /login
     
     ESC 재입력 · X · 바깥 클릭 → World 복귀
```

`계속하기` 항목을 만들지 않는다 — 메뉴를 닫는 것이 곧 복귀다.

---

## 7. 제거할 기존 UI

| 대상 | 이유 |
|---|---|
| Home Dashboard (`HomePage`) | 제품 구조가 아니다(D-08). `/app/home` 은 호환 route 로 축소 |
| World 상단 auth chip | ESC Profile Summary 로 대체 |
| World 상단 로그아웃 | ESC Game Menu 로 대체 |
| Booth 화면의 전체 거래내역 | My Info 로 단일화 |
| Login 푸터 dead text | 링크 대상이 없다 |
| (해당 없음) ESC 안의 Booth 기능 | 애초에 만들지 않았다 — 새로 넣지 않는 것으로 충족 |

## 8. 새로 구현할 UI

```text
World       Consultation Quick Access (HUD 💬)
            ESC Game Menu + Profile Summary
Overlay     Booth Management Overlay (Identity·Mini Preview·4섹션)
Screen      My Info (Profile + Wallet)
Detail      Project Editor / Survey Builder / Survey Result / Consultation Staff
재작성       Auth Processing · First Setup (게임 부팅 표현)
```

## 9. 유지할 기능/VM/Adapter

§1-1 전량. **재구현 과정에서 새 DTO·Port·API 를 만들지 않는다.** 화면이 필요로 하는 데이터가 없으면 그것은 Contract Gap 으로 올린다(§10).

## 10. 열린 Contract Gap

| # | Gap | 영향 | 소관 |
|---|---|---|---|
| G-1 | Booth Management NPC 상호작용 — `WORLD_MANAGEMENT_INTERACT` 계약·양측 구현 develop 도달(-414·-415, !250 2026-09-05) | 잔여: Unity Editor 런타임 검증, `ManagementDeskInteractable` 월드 NPC 프리팹 부착 | Unity 검증 |
| G-2 | Survey Unity 이벤트 — 계약·양측 구현 develop 도달(-415, !250) | 잔여: 런타임 검증, 다중 설문 `objectId → surveyId` binding(BE) | Unity 검증 + BE |
| G-3 | Consultation Target Context — 새 상담의 대상 Booth 결정 방식 미정 | HUD 에서 새 상담 시작 경로 미정. **Booth Directory UI 를 발명하지 않는다** | Unity + FE + BE |
| G-4 | Project Unity 송신부 `ProjectPanelInteractable` develop 도달(-343, !250) | 잔여: 런타임 검증, `BoothInteractBridgeTests` 보강 | Unity 검증 |
| G-5 | GAME — BE game-portals + Unity 송신 + 소유권 | GAME BLOCKED 유지 | BE + Unity + 박준우 |
| G-6 | Survey·Consultation BE endpoint·DTO 미확정 | mock adapter 유지 | BE |
| G-7 | Consultation 실 transport(STOMP) 미확정 | §2 의 close 정책은 mock 기준 판정 — transport 도달 시 재확인 | BE |
| G-8 | Unity Input Lock 계약 (decision-queue #4) | Overlay 중 월드 입력 차단 불가 | Unity + FE |

G-1·G-2·G-4 는 `!250`(2026-09-05) 으로 계약·구현이 닫혔고 Unity 런타임 검증만 남았다. G-1 은 Flow 확정으로 새로 생겼던 항목이고 나머지는 기존 항목.

### 후속 작업군 분류 (2026-09-03, `!240` merge 후)

이 Gap 들은 `!240` 의 품질 문제가 아니다. FE 는 소비 seam 을 준비해 뒀고, 계약 없이 제품 동작을
거짓으로 구현하지 않았다. 아래 분류로 후속 작업에 넘긴다.

| 작업군 | 항목 | FE 준비 상태 | 필요한 것 |
|---|---|---|---|
| **Unity Integration** | G-1 · G-2 · G-4 | dispatcher case·consumer 완비, Unity producer 도달(!250) | Unity Editor 런타임 검증(F 3종·NPC 프리팹 부착·BridgeTests) |
| **Product / Contract Decision** | G-3 | HUD idle 비활성 — 임의 Booth picker 없음 | "새 상담의 대상 부스를 어떤 World context 로 정하는가"의 제품 결정 |
| **Backend / Transport** | G-6 · G-7 | Port 경계 유지, mock adapter | 실 endpoint·DTO / STOMP transport. 도착 시 adapter 교체만 |
| **Blocked** | G-5 | GameOverlay 오류·재시도 표시 | BE game-portals + Unity 송신 + ownership 결정 |
| **Game Client Foundation** | G-8 | 없음(양측 미구현) | Overlay Stack·Input Router 와 함께 설계 |

착수 순서 제안은 `README.md` 의 후속 우선순위(P1~P6)에 있다.

---

## 11. 권장 Presentation 재구현 순서

10단계. 각 단계는 앞 단계의 산출에 의존하므로 순서가 있다. **어느 단계에서도 기능층(VM·Adapter·상태 기계)을 고치지 않는다.**

```text
R1  잘못된 구조 제거
    - HomePage 허브 삭제 · /app/home 호환 route 로 축소
    - RequireAuth 의 AuthHeader 를 World 에서 분리 (§3-2)
    - MockInteractionBar 를 제품 UI 에서 분리 (dev 플래그로만)
    - default returnTo = World 전환 · Landing 클릭 목적지 변경
    변경: HomePage · router · RequireAuth · returnTo.ts 상수
    검증: 로그인 후 World 도착 · 딥링크 returnTo 유지

R2  World 최소 Shell
    - ESC Game Menu (Profile Summary 요약 + 설정 자리 + 로그아웃)
    - Consultation Quick Access 💬 + close 정책 변경 (§2 — cleanup 1곳 제거)
    변경: WorldHud 확장 · 신규 GameMenu · ConsultationOverlay cleanup
    검증: World 이탈 경로 존재 · 대기 상태가 Close 후에도 유지

R3  Booth Management Overlay
    - Identity + Booth Mini Preview(facade 4필드) + 4섹션 + No Booth Empty State
    - SlotListPage 의 Lease 표시·임대 흐름 편입, 거래내역 제거
    변경: 신규 Overlay · SlotListPage 축소 (진입은 G-1 전까지 dev trigger)
    검증: 부스 있음/없음 두 상태 · 임대 성공 후 같은 Overlay 전환

R4  My Info
    - Profile + Wallet 한 Context. ESC Profile Summary 에서 진입 연결
    변경: ProfilePage → My Info 재배치 · 거래내역 단일화 완료
    검증: 거래내역이 한 곳에만 존재

R5  Owner Missing UX 4종
    - Project Editor(edit.ts) · Survey Builder(builder.ts) ·
      Survey Result(result.ts) · Consultation Staff(staff.ts)
    변경: 신규 화면 4개. 모델 무변경
    검증: 각 모델의 상태 전부가 화면에 드러나는가(로딩·빈·오류·저장 중)

R6  Visitor Overlay Presentation
    - Project · LAPTOP · Survey Run · AI 표현 정리 (GAME 은 PROTECTED, 미접촉)
    변경: 표현만
    검증: F→Overlay→Close→같은 World 왕복

R7  Auth Processing / First Setup
    - 게임 부팅 표현으로 재작성. 필드 추가 없음(닉네임 1개)
    변경: 표현만. 상태 기계 무변경
    검증: AUTHENTICATED·NICKNAME_REQUIRED·만료 재시작 3분기

R8  전체 State / Visual Consistency
    - Overlay / Screen / Studio 3 Local Baseline 수치 정렬
    - 로딩·빈·오류·pending 표현 통일

R9  회귀 검증
    - vitest 기준선 대조(현재 462 passed / mock 환경 game-studio 3 failed)
    - Booth Studio 저장·게시·드래그·회전·스냅·경계 회귀
    - Playwright 전 경로 순회

R10 !240 merge 판단
    - 재구현 결과를 !240 에 반영할지, 새 브랜치로 낼지 결정
```

### 요구 순서에서 조정한 2건 (근거)

1. **`default returnTo = World` 전환을 R1 에 넣었다.** Home 제거의 선행 조건이다 — returnTo 기본값이 `/app/home` 인 채로 HomePage 만 지우면 로그인 완료가 빈 route 로 떨어진다. 두 변경은 같은 커밋에 있어야 한다.
2. **Consultation close 정책 변경을 R2 에 넣었다.** 💬 Quick Access 를 만들면서 close 정책이 그대로면 "닫으면 취소"라 HUD 로 상태를 되찾을 수 없다 — HUD 와 같은 단계에서 바꿔야 의미가 성립한다.

Auth Processing(R7)을 뒤에 둔 것은 요구 순서를 그대로 따랐다. 의존이 없어 어느 시점에 해도 무해하고, 앞 단계들이 구조를 바꾸는 동안 표현 작업으로 순서를 소모하지 않는 편이 낫다.

## 12. 이번 블록에서 구현하지 않은 것

Persistent GameShell · Overlay Stack · Input Router · Unity Input Lock · UI Audio · Settings 실제 기능 · Fullscreen · Pointer Lock · Mobile · Foundation Freeze · R3F Spike · GAME blocked 해결 · Unity 이벤트 신규 구현 · BE endpoint 신규 구현.
