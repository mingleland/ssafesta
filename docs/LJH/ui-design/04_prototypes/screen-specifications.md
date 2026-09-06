# Screen Specifications — 화면별 Target Structure

> **STATUS: IMPLEMENTED / CONTRACT PENDING 혼재** — 사양대로 구현돼 develop 에 반입됐다
> (`!240`, merge `e961adda`, 2026-09-03). 아래 표가 화면별 현재 상태의 정본이다.
>
> | 화면 | 상태 | 비고 |
> |---|---|---|
> | Auth Processing · First Setup | **IMPLEMENTED** | 게임 진입 Transition 으로 재작성 |
> | World (HUD 4종 중 2종) | **IMPLEMENTED** | 조작 안내 + Consultation Quick Access. Toast·미니게임 score 는 Post-MVP |
> | ESC Game Menu | **IMPLEMENTED** | 설정은 Deferred 표기 상태 |
> | My Info | **IMPLEMENTED** | Profile + Wallet 단일화, 거래내역 중복 해소 |
> | Booth Management | **IMPLEMENTED / CONTRACT PENDING** | 화면 완성. 제품 진입은 Unity NPC 이벤트 대기(G-1) — 현재 DEV_ONLY 트리거 |
> | Project Overlay | **IMPLEMENTED / CONTRACT PENDING** | Unity 송신부 대기(G-4) |
> | Project Management | **IMPLEMENTED** | `edit.ts` 소비 |
> | LAPTOP | **IMPLEMENTED** | REAL 경로 |
> | Survey Run | **IMPLEMENTED / CONTRACT PENDING** | Unity 이벤트 없음(G-2) + BE 미착수(G-6) — 화면이 mock 임을 표시 |
> | Survey Management | **IMPLEMENTED** | Builder·Result 모두. adapter 는 mock(G-6) |
> | Consultation (Visitor) | **IMPLEMENTED** | Close≠Cancel 적용. 새 상담 대상 지정은 미결(G-3), 실 transport 대기(G-7) |
> | Consultation Staff | **IMPLEMENTED** | adapter 는 mock(G-6·G-7) |
> | AI | **IMPLEMENTED (MOCK 표기)** | 실 응답은 AI 서버 대기 |
> | GAME | **BLOCKED** | 미접촉(G-5) |
> | Booth Studio | **IMPLEMENTED** | 기능층 무변경, 진입·복귀만 변경 |

- 문서 종류: **PROPOSAL → IMPLEMENTED** (사양대로 구현 완료)
- 작성일: 2026-09-03
- 흐름 정본: `00_context/user-flow-decisions.md` · 재배치 계획: `04_prototypes/ux-architecture-remap.md`
- 데이터 근거: `02_audit/function-truth-inventory.md`
- 원칙: **각 화면은 실재하는 모델·계약만 소비한다.** "Forbidden" 항목은 만들지 않기로 명시된 것이며, 근거 없는 지표·메뉴·필드를 추가하지 않는다.

각 화면 공통 서식:

```text
Purpose / Entry / Exit / Container / Data / Actions / States /
Forbidden / Visual / Maturity / Reuse
```

`Reuse` = 재사용할 기존 자산. **비어 있는 화면은 없다** — 전부 !240·develop 의 기존 모델·컴포넌트를 소비한다.

---

## 1. Auth Processing

```text
Purpose      OAuth handoff 를 소비해 세션을 확정하고 분기한다
Entry        /auth/callback — OAuth 왕복 후 자동
Exit         AUTHENTICATED → 기본 목적지(World) 또는 explicit returnTo
             NICKNAME_REQUIRED → First Setup
             실패 → Login 복귀 액션
Data         authApi.complete() 응답 2상태뿐 (accessToken·expiresAt / null)
Actions      없음 (실패 시 "로그인 화면으로" 1개)
States       completing · nickname-required · restart(만료·재사용)
Forbidden    진행률 퍼센트, 단계 목록, 서버 응답 raw 노출, 기술 용어(handoff·token)
Container    Entry / Transition — 전체 화면
Visual       게임 부팅 화면. 로고 + 짧은 상태 문구 + 정적/저속 모션.
             일반 웹 스피너 화면이 아니다
Maturity     REAL (상태 기계 완성, 표현만 재작성)
Reuse        CallbackPage 상태 기계 · authApi.complete · consumeReturnTo
```

## 2. First Setup

```text
Purpose      최초 닉네임을 확정하고 World 로 보낸다
Entry        Auth Processing 의 NICKNAME_REQUIRED 분기
Exit         저장 성공 → 기본 목적지(World) / handoff 만료 → 재시작
Data         없음 (입력만). 판정은 서버
Actions      닉네임 입력 · 확인
States       idle · submitting · rejected(비특정 문구) · handoff expired
Forbidden    아바타 선택 · 관심사 · 직업 · 튜토리얼 타입 · 프로필 이미지 ·
             선호 부스 · 세부 계정 설정 · 클라이언트 길이 규칙 발명
Container    Entry / Transition — 전체 화면
Visual       "축제 입장 전 이름표를 받는" 순간. 필드 1개에 집중
Maturity     REAL (표현만 재작성)
Reuse        NicknameForm · authApi.complete(nickname)
```

## 3. World

```text
Purpose      로그인 후 사용자가 상주하는 기본 상태
Entry        Entry Flow 의 기본 목적지 / explicit deep link
Exit         없음 — World 를 "나가지" 않는다. Overlay 를 열고 닫을 뿐
Data         Unity 세션(실) 또는 정지 캡처(mock) · Consultation 상태
Actions      이동·카메라·F (Unity) / ESC (React Game Menu) / 💬 (React)
States       World 로딩 · 정상 · (mock) 정지 화면 고지
Forbidden    minimap · HP · quest · hotbar · crosshair · mission panel ·
             기능 launcher · 상단 계정 칩 · 로그아웃 버튼
Container    World
Visual       화면의 주인공은 World. HUD 총 점유 15% 미만, 모서리 배치,
             중앙은 비운다 (world-reference-brief)
Maturity     HYBRID (mock surface + 실 dispatcher)
Reuse        WorldSurface.select · WorldHud · OverlayHost · dispatcher · Overlay Bus
```

HUD 구성 4종: 조작 안내(좌상, 닫기 가능) · 미니게임 score(콘텐츠 중에만, 미구현) · Toast(우상, 미구현) · **Consultation Quick Access(우상, 신규)**.

## 4. ESC Game Menu

```text
Purpose      플레이어 자신과 시스템에 관한 최소 메뉴
Entry        World 에서 ESC
Exit         ESC 재입력 · X · 바깥 클릭 → World 복귀 (별도 "계속하기" 없음)
Data         Profile Summary 용 — nickname · provider · coin balance · avatar 상태
Actions      내 정보 진입 · 설정 · 로그아웃
States       열림/닫힘 · profile 로딩 · coin 미조회(게스트)
Forbidden    Booth Studio · Booth Management · Consultation 관리 · Project ·
             Survey · GAME · 서비스 navigation · "계속하기" 항목 ·
             전체 거래내역 · 닉네임 수정 폼 · 회원 탈퇴
Container    Personal / System — World 위 Overlay
Visual       상단 Profile Summary 박스 + 하단 시스템 목록. 게임 일시정지 메뉴 문법
Maturity     NEW (데이터는 REAL — profile.ts · wallet 쿼리)
Reuse        profile.ts(useProfile) · walletApi.getWallet · OverlayFrame · clearSession/logout(AuthHeader 에서 이관)
```

구조:

```text
┌──────────────────────────────┐
│ [Avatar]  Nickname           │
│           Provider           │
│           N Coin             │
│                   내 정보 >  │
├──────────────────────────────┤
│                              │
├──────────────────────────────┤
│ 설정                         │
│ 로그아웃                     │
└──────────────────────────────┘
```

## 5. My Info

```text
Purpose      계정과 보유 자산을 한 곳에서 관리
Entry        ESC Game Menu → Profile Summary → 내 정보
Exit         닫기/뒤로 → Game Menu 또는 World
Data         userApi.getMe() (userId·nickname·providers·avatarCode) ·
             walletApi.getWallet() · walletApi.getTransactions(page)
Actions      닉네임 변경 · (아바타 상태 표시) · 회원 탈퇴 3단 확인 ·
             거래내역 페이지 이동
States       loading · ready · error / nicknameEdit(idle·submitting·success·error+errorKind) /
             withdrawal(idle·confirming·submitting·error)
Forbidden    통계·활동 요약·등급·업적 · 코인 "받기" 버튼(서버 자동 지급) ·
             모르는 reasonType 숨기기
Container    Personal / System
Visual       "게임 속 내 정보". 관리 콘솔 아님
Maturity     REAL (기존 ProfilePage 재배치)
Reuse        ProfilePage 전체 · TransactionsSection · profile.ts · walletApi · PageShell
```

Wallet 은 독립 최상위 항목이 아니라 My Info 의 하위 섹션이다. 잔액의 다른 노출 지점은 ESC Profile Summary 와 임대 맥락의 `WalletBadge` 뿐이다.

## 6. Booth Management

```text
Purpose      내가 운영 중인 하나의 Booth 를 중심으로 제작·콘텐츠·운영을 관리
Entry        World → Booth Management NPC → F   (계약 대기 G-1, 그 전에는 dev trigger)
Exit         닫기/ESC → 같은 NPC 앞 World
Data         leaseApi.getMyBooth()  → name · status · lease{slotCode·endsAt·remainingSeconds}
             facadeApi.getBooth(id) → facade{themeCode·primaryColor·signText·logoUrl}
             projectApi.getMyProjects(boothId) → 요약 1건
             surveyApi.getDraft() → 문항 수 요약 (mock)
             consultationStaff.getQueue() → 대기 건수 요약 (mock)
Actions      부스 스튜디오 열기 · Project 관리 · Survey 관리 · Consultation 관리 ·
             (부스 없음) 부스 임대하기
States       loading · has-booth · no-booth · error / lease active·expired
Forbidden    Dashboard analytics(방문자 수·전환율·차트) · Project/Survey Editor 를
             이 화면에 펼치기 · Booth Studio 를 탭으로 넣기 ·
             Project 로고를 Booth 대표 이미지로 쓰기
Container    Booth Management Overlay — World 위
Visual       A + D 혼합 — Booth 자체가 주인공(D), 기능은 Drill-down(A)
Maturity     NEW (데이터 전량 기존 API)
Reuse        leaseApi.getMyBooth · facadeApi.getBooth · remaining.ts(카운트다운) ·
             useLeaseSlot·LeaseConfirmDialog(No Booth 임대) · OverlayFrame
```

레이아웃(정본 §16 그대로):

```text
┌───────────────────────────────────────────────┐
│ 내 부스 관리                              X  │
├───────────────────────────────────────────────┤
│ ┌────────────────┐  Booth Name                │
│ │  BOOTH MINI    │  Slot Code                 │
│ │   PREVIEW      │  ● 운영 중                 │
│ │                │  임대 N일 N시간 남음        │
│ └────────────────┘                            │
│                    [ 부스 스튜디오 열기 ]       │
├───────────────────────────────────────────────┤
│ PROJECT       <프로젝트명 요약>      [관리 >] │
├───────────────────────────────────────────────┤
│ SURVEY        <문항 수 요약>         [관리 >] │
├───────────────────────────────────────────────┤
│ CONSULTATION  <대기 요약>            [관리 >] │
├───────────────────────────────────────────────┤
│ 부스 정보   Slot · 임대 상태 · 종료 시각        │
└───────────────────────────────────────────────┘
```

**Booth Mini Preview**: facade 4필드로 그리는 React 시각물. `themeCode` = 골격, `primaryColor` = 강조색(12색 팔레트), `signText` = 간판 문구, `logoUrl` = 로고(없으면 이니셜/기본 도형). 스크린샷 API 를 만들지 않는다. Booth Studio 외관 변경이 자동 반영된다.

**No Booth**: 안내 문구 + `[부스 임대하기]` → Lease 흐름. 임대 성공 시 같은 Overlay 가 Has Booth 상태로 전환.

## 7. Project Overlay (Visitor)

```text
Purpose      방문 중인 부스의 전시 프로젝트를 본다
Entry        World Project Object → Unity F  (송신부 미구현 G-4)
Exit         Esc · 닫기 · 바깥 → 같은 World
Data         exhibition.ts — name·description·thumbnail·video(EMBED/LINK_ONLY/INVALID)·
             links(deploy·git·portfolio)·like{count·likedByMe·canToggle·pending·error}
Actions      좋아요 토글(회원만) · 외부 링크 열기 · 닫기
States       idle·loading·ready·empty·error / like pending·rollback
Forbidden    편집 기능 · 조회수·순위 등 없는 지표 · 임의 뱃지·메타데이터
Container    Visitor Overlay
Visual       미디어가 큰 전시 카드. 좋아요는 상태가 분명해야 한다
Maturity     HYBRID (VM REAL, Unity 송신 미구현)
Reuse        exhibition.ts · videoEmbed.ts · ProjectOverlay(!240) · OverlayFrame
```

## 8. Project Management (Owner)

```text
Purpose      내 부스에 전시할 프로젝트를 편집한다
Entry        Booth Management → PROJECT [관리]
Exit         뒤로 → Booth Management
Data         edit.ts — draft(name·description·thumbnailUrl·videoUrl·deployUrl·gitUrl·
             portfolioUrl) · dirty 집합 · projectId(null = 미생성)
Actions      필드 편집 · 저장(dirty 키만 PATCH, 미생성이면 create)
States       idle·loading·ready·error / save(idle·submitting·success·error)
             저장 중 입력 차단
Forbidden    Analytics · 조회수 · 좋아요 관리 · 모델에 없는 필드 ·
             전체 객체 전송(PresenceField 계약 위반)
Container    Management Detail
Visual       폼 중심. Visitor Overlay 와 시각적으로 구분되어야 한다
Maturity     REAL (모델 완성, UI 신규)
Reuse        edit.ts(useProjectEdit·updateField·saveProject) · PageShell
```

## 9. LAPTOP Overlay

```text
Purpose      부스가 제공하는 홈페이지를 연다
Entry        World Laptop Object → Unity F  (송신 구현됨)
Exit         Esc · 닫기 · 바깥 → 같은 World
Data         laptopHomepage.ts — GET /booths/{id}.homepageUrl 이 정본
Actions      새 탭에서 열기(1급) · 닫기
States       idle·loading·no_url·invalid·error·valid{href·hostname}
Forbidden    이벤트 payload 의 url 소비 · iframe 차단 감지 시도 ·
             새 탭을 2차 fallback 으로 격하
Container    Visitor Overlay
Visual       브라우저 창 은유. hostname 을 분명히 보여준다
Maturity     REAL
Reuse        laptopHomepage.ts · LaptopOverlay(!240) · OverlayFrame
```

## 10. Survey Run (Visitor)

```text
Purpose      부스 설문에 응답한다
Entry        World Survey Object → Unity F  (계약 없음 G-2)
Exit         Esc · 닫기 → 같은 World / 제출 완료 표시 후 닫기
Data         run.ts — questions(6유형)·answers·progress·submit.phase
Actions      답변 입력 · 제출(필수 미응답 시 차단)
States       idle·loading·ready·empty·error·closed / submit(idle·submitting·success·error)
Forbidden    6유형 외 질문 유형 발명 · 클라이언트 집계 · 결과 보기 ·
             편집 기능 · 응답 저장 후 재편집 발명
Container    Visitor Overlay
Visual       한 번에 답할 수 있는 길이감. 진행도와 필수 표시가 분명해야 한다
Maturity     MOCK (모델 REAL 구조, adapter mock, Unity 계약 없음)
Reuse        run.ts · SurveyOverlay(!240) · OverlayFrame
```

## 11. Survey Management (Owner)

```text
Purpose      설문을 만들고 응답 결과를 본다
Entry        Booth Management → SURVEY [관리]
Exit         뒤로 → Booth Management
Data         builder.ts(draft·validation) · result.ts(perQuestion·textAnswers 페이지네이션)
Actions      [편집] 문항 추가·삭제·순서 변경·필수 토글·옵션 편집·저장
             [결과] 집계 보기 · 주관식 다음 페이지
States       builder: idle·loading·ready·error / save(idle·submitting·success·error)
             result:  idle·loading·ready·empty·error / textAnswers.loadingNext
Forbidden    임의 분석 지표(응답률·이탈률·시계열) · 6유형 외 유형 ·
             Builder 와 Result 를 Booth Management 최상위 카드 2개로 분리
Container    Management Detail — 한 Context 안에서 [설문 편집] / [결과] 전환
Visual       편집은 리스트 조작, 결과는 읽기 중심
Maturity     MOCK (모델 3종 REAL 구조, adapter mock)
Reuse        builder.ts · result.ts · surveyApi port · PageShell
```

## 12. Consultation (Visitor)

```text
Purpose      부스 담당자와의 상담을 요청하고 상태를 따라간다
Entry        World HUD 💬 (전역) — 새 상담의 대상 Booth 결정은 계약 대기(G-3)
Exit         닫기 → World. **닫아도 상담은 취소되지 않는다**
Data         visitor.ts — phase·boothId·remainingSeconds·staffName
Actions      상담 요청 · 대기 취소(명시적) · 만료 후 재요청
States       idle·requesting·waiting(카운트다운)·expired·active·ended·error
Forbidden    Booth Directory UI 발명 · unread count 발명 ·
             닫기와 취소를 같은 동작으로 묶기
Container    HUD Quick Access + Overlay
Visual       💬 아이콘 + 상태 점(●). 대기 시간이 분명해야 한다
Maturity     MOCK (모델 REAL 구조, transport 미확정)
Reuse        visitor.ts · ConsultationOverlay(!240, cleanup 1곳 제거) · OverlayFrame
```

HUD 상태 표기: `Idle` = 💬 / `Waiting`·`Active`·`Staff Request` = 💬 ●. 숫자를 만들지 않는다.

## 13. Consultation Staff (Owner)

```text
Purpose      내 부스에 온 상담 요청을 처리한다
Entry        Booth Management → CONSULTATION [관리]
             (진행 중 상담은 World HUD 💬 로도 즉시 접근)
Exit         뒤로 → Booth Management
Data         staff.ts — queue(요청 카드)·active(현재 상담)·accepting·actionError
Actions      수락(동시 1건 제한) · 종료
States       idle·loading·ready·error / accepting / actionError(accept·end)
Forbidden    동시 다중 상담 허용 · 오류를 조용히 삼키기 ·
             대화 송수신 UI(P2 범위 밖)
Container    Management Detail
Visual       대기열 + 현재 상담. 즉시 판단할 수 있는 밀도
Maturity     MOCK (모델 REAL 구조)
Reuse        staff.ts · PageShell
```

## 14. AI Chat Overlay

```text
Purpose      부스 자료를 근거로 AI 직원에게 묻는다
Entry        World AI 직원 → Unity F  (송신 구현됨)
Exit         Esc · 닫기 → 같은 World
Data         entities/conversation — SSE 파서·타입 (실 응답은 AI 서버 미구현)
Actions      질문 전송 · 제안 질문 선택
States       idle · streaming · (미구현) error·cancel·retry
Forbidden    실제 답변인 것처럼 보이게 하기 · 근거 문서 목록 발명 ·
             AI 파트 로직 수정
Container    Visitor Overlay
Visual       대화. 현재는 mock 임을 화면이 밝힌다
Maturity     HYBRID (파서 REAL, 응답 mock)
Reuse        entities/conversation(stream.parser·stream.mock) · AiChatOverlay(!240) · OverlayFrame
```

## 15. GAME Overlay

```text
Purpose      부스가 게시한 미니게임을 연다
Entry        World 포털 → Unity F  (Unity 미송신 · 소유권 미정 G-5)
Exit         Esc · 닫기 → 같은 World
Data         gamePortalRepository.resolve() — BE game-portals (부재)
Actions      다시 시도 · 월드로 돌아가기
States       loading · ready · error(retryable)
Forbidden    이 화면을 UI 통합 범위로 끌어오기 (D-01 PROTECTED)
Container    Visitor Overlay
Visual       현 소유 컴포넌트 유지
Maturity     BLOCKED
Reuse        GameOverlay · gamePortalRepository (소유 컴포넌트 그대로)
```

## 16. Booth Studio

```text
Purpose      부스 공간을 디자인한다
Entry        Booth Management → [부스 스튜디오 열기]
Exit         Exit → Booth Management
Data         layoutApi(draft·templates) · facadeApi · catalog · booth 소유자 게이트
Actions      에셋 추가 · 선택 · 드래그 · 회전 · 스냅 · 경계 검증 · Inspector 편집 ·
             외관 편집 · 템플릿 적용 · 저장 · 게시
States       loading · ready · dirty · saving · publishing · conflict · owner-gate 차단
Forbidden    Project·Survey·Consultation 을 Studio Mode 로 추가 ·
             기능층 재구현 · 12개 상한 완화
Container    Creator Workspace — 전체 화면
Visual       -405 Shell 유지. 임시 SVG 아이소메트릭 렌더러는 교체 가능(D-04/D-05)
Maturity     REAL
Reuse        features/studio 전체 · BoothStudioShell · TemporaryIsoRenderer · PageShell
```

---

## 17. Visual Anchor 구조안

각 UI Family 의 대표 화면. **이번 블록에서는 구조·wire 만 확정하고 구현·이미지 생성은 하지 않는다.**

| # | Anchor | 무엇을 고정하는가 | 상태 |
|---|---|---|---|
| A-1 | World Default | World 주인공 원칙, HUD 4종의 자리와 점유율 | 구조안 아래 |
| A-2 | ESC Game Menu | Personal/System 문법 | 구조안 아래 |
| A-3 | Booth Management Overlay | A+D 혼합의 기준 — **이번 라운드 최우선** | 구조안 아래 |
| A-4 | Project Overlay | Visitor Overlay Family 앵커 | 구현 존재(!240) — 재확인 |
| A-5 | My Info | Personal Context 앵커 | 구현 존재(재배치) |
| A-6 | Auth Processing / First Setup | Entry Transition 문법 | 구조안 아래 |

### A-1. World Default

```text
┌─────────────────────────────────────────────────────────┐
│ ┌──────────────┐                              ┌──────┐  │
│ │ 조작 안내     │                              │  💬  │  │  ← 우상: Consultation
│ │ WASD 이동     │                              └──────┘  │     (상태 시 ● 표시)
│ │ F   상호작용  │                              ┌──────┐  │
│ │ Esc 메뉴      │                              │Toast │  │  ← 우상 아래(일시적)
│ └──────────────┘                              └──────┘  │
│                                                         │
│                    [ Unity World ]                      │
│                     중앙은 비운다                        │
│                  (플레이어·상호작용 대상)                 │
│                                                         │
│              (미니게임 중에만) score / progress          │
└─────────────────────────────────────────────────────────┘
```

제거: 기능 hotbar · auth chip · 로그아웃 · minimap · quest · mission.
HUD 총 점유 15% 미만, 모서리 4곳만. F 프롬프트·하이라이트는 Unity 가 그린다.

### A-2. ESC Game Menu

```text
┌──────────────────────────────┐
│ [Avatar]  Nickname        X  │   ← Profile Summary = 상위 Context 박스
│           Provider           │
│           N Coin             │
│                   내 정보 >  │
├──────────────────────────────┤
│                              │   ← 의도적 여백. 기능을 채우지 않는다
├──────────────────────────────┤
│ 설정                         │
│ 로그아웃                     │
└──────────────────────────────┘
```

닫기 = World 복귀. `계속하기` 항목 없음. Booth·서비스 navigation 금지.

### A-3. Booth Management Overlay (최우선)

```text
┌───────────────────────────────────────────────┐
│ 내 부스 관리                              X  │
├───────────────────────────────────────────────┤
│ ┌────────────────┐  Example Booth             │  ← D: Booth 가 주인공
│ │  BOOTH MINI    │  A-12                      │
│ │   PREVIEW      │  ● 운영 중                 │
│ │  (facade 기반) │  임대 02일 14시간 남음      │
│ └────────────────┘                            │
│                    [ 부스 스튜디오 열기 ]       │  ← Primary CTA
├───────────────────────────────────────────────┤
│ PROJECT       Example Project        [관리 >] │  ← A: Drill-down
├───────────────────────────────────────────────┤
│ SURVEY        방문자 설문 · 6문항     [관리 >] │
├───────────────────────────────────────────────┤
│ CONSULTATION  상담 요청 운영          [관리 >] │
├───────────────────────────────────────────────┤
│ 부스 정보    A-12 · 임대 중 · 종료 시각        │
└───────────────────────────────────────────────┘
```

No Booth:

```text
┌──────────────────────────────┐
│ 내 부스 관리              X  │
│                              │
│ 아직 운영 중인 부스가 없습니다 │
│ 부스를 임대하면 전시 공간을    │
│ 꾸미고 콘텐츠를 운영할 수      │
│ 있습니다.                     │
│                              │
│              [부스 임대하기] │
└──────────────────────────────┘
```

**Booth Mini Preview — 현 단계 설계**: facade 4필드로 그리는 **경량 React 시각물**이다.

```text
themeCode    → 프레임/골격 프리셋 (DEFAULT·SSAFY_BLUE·WARM·MONO)
primaryColor → 차양·강조색 (12색 팔레트 중 1)
signText     → 간판 문구 (최대 60자, 잘림 처리)
logoUrl      → 로고 이미지 (없으면 이니셜 또는 기본 도형)
```

**R3F Preview 를 구현하지 않는다.** 3D 렌더링은 `05_technical-spikes/booth-studio-r3f-asset-pipeline-plan.md` 의 DEFERRED 범위다. 여기서는 CSS/SVG 로 충분하다.

미결 시각 변수(사용자 판단 — §Q):

```text
Mini Preview 비율   정사각 / 4:3 / 16:9  — signText 가독성이 갈린다
Overlay size        OverlayFrame 'l' 또는 'xl' — 4섹션이 스크롤 없이 들어가는 높이
상태 표현           ● 운영 중 / 임대 만료 / 부스 없음 3가지의 시각 구분
```

### A-4. Project Overlay (기존 — 재확인)

!240 구현 유지. Visitor Overlay Family 의 기준선이며 OverlayFrame·미디어·좋아요·링크 리듬을 다른 Overlay 가 따른다. 편집 기능을 넣지 않는다.

### A-5. My Info (기존 재배치)

```text
┌───────────────────────────────────────┐
│ ← 내 정보                             │
├───────────────────────────────────────┤
│ [Avatar]  Nickname      [이름 변경]   │
│           Provider 배지                │
├───────────────────────────────────────┤
│ 보유 코인   N 코인                     │
│ 매일 접속하면 자동으로 지급됩니다        │
├───────────────────────────────────────┤
│ 코인 사용 내역                         │
│ (일시 · 내역 · 금액 · 잔액, 페이지 이동) │
├───────────────────────────────────────┤
│ 계정        [탈퇴하기]                 │
└───────────────────────────────────────┘
```

거래내역의 **유일한** 자리. Booth 화면에서는 제거된다.

### A-6. Auth Processing / First Setup

```text
Auth Processing                First Setup
┌────────────────────┐         ┌────────────────────┐
│                    │         │                    │
│      SSAFESTA      │         │      SSAFESTA      │
│                    │         │                    │
│   축제 입장 중...   │         │  사용할 이름을      │
│   ▓▓▓▓▓▓░░░░       │         │  알려주세요         │
│                    │         │  ┌──────────────┐  │
│                    │         │  │ 닉네임        │  │
│                    │         │  └──────────────┘  │
│                    │         │        [ 확인 ]    │
└────────────────────┘         └────────────────────┘
```

진행률 퍼센트·단계 목록·기술 용어를 쓰지 않는다. 필드는 닉네임 1개뿐.

## 18. 이 문서가 정하지 않은 것

색·radius·shadow·타이포 등 Visual token — `03_foundation/visual-dna.md` 는 여전히 PROPOSAL 이고 Freeze 되지 않았다. 화면 구조가 확정된 뒤 Foundation 추출 단계에서 정한다.
