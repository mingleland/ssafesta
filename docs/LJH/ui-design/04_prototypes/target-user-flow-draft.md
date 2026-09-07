# Target User Flow Draft — 기능을 사용자 목적으로 재배치

> **STATUS: HISTORICAL DRAFT — SUPERSEDED FOR CURRENT FLOW BY `00_context/user-flow-decisions.md`**
> 이 문서가 §5 에 올린 7개 결정은 2026-09-03 에 전부 확정됐다. 후보 검토 단계의 기록으로 보존하며,
> 현재 흐름 정본으로 읽지 않는다. 내용은 최신 결정으로 덮어쓰지 않는다.

- 문서 종류: **PROPOSAL / DRAFT** — 확정 Decision 아니다. 사용자가 고르기 전까지 어떤 항목도 `implementation-decisions.md` 로 승격하지 않는다.
- 작성일: 2026-09-03
- 근거: `02_audit/function-truth-inventory.md`(같은 조사 회차) · `02_audit/current-ui-gap-matrix.md` · `00_context/implementation-decisions.md`(D-01~D-07) · `00_context/hud-decisions.md` · `04_prototypes/game-client-experience-draft.md` · `04_prototypes/world-reference-brief.md`
- 전제: **SSAFY FESTA 는 브라우저 게임 클라이언트이지 대시보드 웹사이트가 아니다.** World 는 페이지 하나가 아니라 로그인 후 사용자가 상주하는 기본 상태다.

---

## 1. Feature → User Goal

기능이 존재한다는 사실은 "World 에 상시 버튼이 필요하다"는 뜻이 아니다. 각 기능을 사용자 목적으로 먼저 환산한다.

| 기능 | 사용자가 왜 쓰는가 | 그 목적이 발생하는 순간 |
|---|---|---|
| Project 전시 | 방문 중인 부스의 프로젝트를 본다 | **부스 앞에 서 있을 때** |
| LAPTOP | 그 부스가 제공하는 홈페이지를 연다 | 부스 앞, 노트북 오브젝트 앞 |
| Survey | 그 부스의 설문에 참여한다 | 부스 앞, 키오스크 앞 |
| Consultation | 그 부스 담당자와 상담한다 | 부스 앞, 상담 데스크 앞 |
| AI 직원 | 부스 자료를 근거로 물어본다 | 부스 앞, AI 직원 앞 |
| 미니게임 | 부스가 만든 게임을 한다 | 부스 앞, 포털 앞 |
| Booth Lease | 내 전시 공간을 확보한다 | **월드 밖 — 준비 단계** |
| Booth Studio | 확보한 부스를 꾸미고 게시한다 | 월드 밖 — 준비 단계 |
| Project 편집 | 내 부스에 전시할 프로젝트를 등록·수정한다 | 월드 밖 — 준비 단계 |
| Survey Builder | 내 부스 설문을 만든다 | 월드 밖 — 준비 단계 |
| Survey Result | 내 부스 설문 응답을 확인한다 | 월드 밖 — 운영 단계 |
| Consultation Staff | 내 부스에 온 상담 요청을 처리한다 | **운영 중 — 즉시성 있음** |
| Profile | 내 계정 정보를 관리한다 | 아무 때나 — 드물게 |
| Wallet | 보유 코인·거래를 확인한다 | 임대 직전, 그리고 가끔 |

여기서 갈리는 선이 하나 보인다. **부스 앞에서 발생하는 목적(왼쪽 6개)은 World 상호작용이고, 준비·운영 목적(오른쪽)은 World 밖 화면이다.** 현재 World 하단 목업 바가 두 무리를 같은 자리에 섞어 놓고 있지만 그것은 Unity 대역일 뿐이다.

---

## 2. Feature → UX Container Mapping (제안)

```text
Entry / Transition
├─ Landing            게임 타이틀·시작 제스처
├─ Login              provider 4종
├─ Auth Processing    handoff 소비 (게임 부팅 표현)
└─ First Setup        닉네임 1개 — 그 이상 만들지 않는다

World  (상주 상태)
├─ Unity World Layer  이동·카메라·F 상호작용·하이라이트·이름표
├─ React HUD (최소)   조작 안내 / 미니게임 score·progress / Toast
└─ (없음)             hotbar·minimap·HP·quest·기능 launch dock

World Interaction Overlay  (Unity F 로만 열린다)
├─ PROJECT
├─ LAPTOP
├─ SURVEY
├─ CONSULTATION
├─ AI
└─ GAME

Management  (World 밖 — 준비·운영)
├─ Booth / Lease
├─ Booth Studio        구조·외관·템플릿
│   └─ (후보) Project 편집 · Survey Builder · Survey Result
├─ Consultation Staff  (후보 위치 미정 — §4-C)
├─ Profile
└─ Wallet

Future Game Client  (Target UX — 이번에 구현하지 않는다)
├─ Game Menu (ESC)
├─ Settings
└─ Logout
```

**DEV_ONLY 로 분류된 World 하단 목업 바는 이 지도에 없다.** Unity 연결 시 사라지는 대역이다.

---

## 3. Entry / Exit 매핑

| 기능 | 어디서 진입 | trigger | 어디에 표시 | 어떻게 닫음 | 닫으면 어디로 | 다음 단계 |
|---|---|---|---|---|---|---|
| Project | World, 부스 전시물 앞 | Unity F | Overlay | Esc / 닫기 / dim | **같은 World, 같은 위치** | 계속 관람 |
| LAPTOP | World, 노트북 앞 | Unity F | Overlay(iframe + 새 탭) | 동일 | 동일 | — |
| Survey | World, 키오스크 앞 | Unity F **(계약 미정 — F-2)** | Overlay | 동일 | 동일 | 제출 완료 표시 |
| Consultation | World, 상담 데스크 앞 | Unity F **(계약 미정 — F-2)** | Overlay | 동일(요청도 취소됨) | 동일 | 수락 시 대화 |
| AI | World, AI 직원 앞 | Unity F | Overlay | 동일 | 동일 | — |
| GAME | World, 포털 앞 | Unity F **(소유권 미정 — decision-queue #1)** | Overlay | 동일 | 동일 | — |
| Booth Lease | Management 진입점 | 클릭 | Screen | 뒤로 | 진입점 | 임대 성공 → Booth Studio 유도 |
| Booth Studio | Booth 화면 "스튜디오에서 편집" | 클릭 | Screen | 뒤로 | Booth 화면 | 저장 / 게시 |
| Profile | Management 진입점 | 클릭 | Screen | 뒤로 | 진입점 | — |
| Wallet | Profile 안(현재) | — | Screen 일부 | — | — | — |

---

## 4. Target User Flow 후보

### Flow A — Entry

현재 코드가 실제로 하는 일:

```text
/  Landing
└─ 클릭 → /app/home
      └─ RequireAuth(guest-allowed)
            ├─ 세션 없음 → returnTo 저장 → /login
            └─ 세션 있음 → /app/home 표시

/login
├─ OAuth  → 전체 페이지 이동 → /auth/callback
│            ├─ AUTHENTICATED   → consumeReturnTo()  (기본 /app/home)
│            └─ NICKNAME_REQUIRED → 닉네임 폼 → consumeReturnTo()
└─ 게스트 → 즉시 세션 → consumeReturnTo()
```

즉 **현재 기본 목적지는 `/app/home` 이고, 그 자리는 develop 에서 `<div>home</div>` 이었다.** "Home 을 거친다"는 것은 제품 결정이 아니라 returnTo 기본값의 부수 효과다.

제안(선택 대상 — §5-1):

```text
A-1  Landing → Login → Auth → (신규면 First Setup) → World          ← 게임 클라이언트 원칙에 가장 부합
A-2  Landing → Login → Auth → (신규면 First Setup) → 짧은 Entry/Loading → World
A-3  현행 유지: … → Home 허브 → 사용자가 World 를 선택
```

세 후보 모두 **신규 사용자 판정은 서버의 `NICKNAME_REQUIRED` 하나**이며, First Setup 은 **닉네임 1개**다(코드·계약 근거). 관심사·아바타·튜토리얼 단계를 추가하지 않는다.

A-1/A-2 를 고를 경우 필요한 변경은 작다: `returnTo.ts` 의 `DEFAULT_RETURN_TO` 와 Landing 클릭 목적지. 다만 **Management(부스·프로필)로 가는 진입점이 World 안에 생겨야 한다**(§4-C·§5-4와 묶인 결정).

### Flow B — World Interaction

```text
World (상주)
└─ 이동·탐색 (Unity)
     └─ 대상 근접 → Unity 하이라이트·F 프롬프트
          └─ F
               └─ Unity 이벤트 → Dispatcher → openOverlay
                    └─ React Overlay
                         └─ Esc / 닫기 / 바깥 클릭
                              └─ 같은 World, 같은 위치
```

이 흐름은 `hud-decisions.md` 확정 그대로이며 현재 코드도 같은 모양이다(mock 바는 dispatcher 와 동일 payload 를 쓰는 대역). 남은 공백 3가지:

- **SURVEY·CONSULTATION 의 Unity 이벤트 계약이 없다**(F-2) — 3파트 합의 필요.
- **Overlay 열림 중 Unity Input Lock 이 없다**(decision-queue #4).
- **Overlay 는 스택이 아니라 단일 슬롯이다**(F-3) — Game Client Experience 단계 과제.

### Flow C — Booth Owner

관리 진입점은 **임의로 확정하지 않는다.** 코드가 지지하는 후보 두 가지:

```text
C-1  Management 화면 경유 (현행 구조에 가깝다)
     진입점 → 부스 슬롯 → (임대) → 내 부스 → Booth Studio → 저장/게시 → Booth 화면

C-2  World 상주 + 메뉴 경유 (게임 클라이언트 원칙에 가깝다)
     World → ESC Game Menu → 내 부스 → Booth Studio → 저장/게시 → World 복귀
```

어느 쪽이든 **Booth Studio 로 가는 경로는 이미 실재**한다(Booth 화면의 "스튜디오에서 편집", Home 카드). 결정해야 하는 것은 그 위에 있는 **Management 진입점의 자리**다.

MISSING 4건의 배치도 여기에 물려 있다:

```text
Project 편집     → Booth Studio 안(4번째 모드) 또는 부스 관리 화면
Survey Builder   → 같음
Survey Result    → 같음 (운영 단계)
Consultation Staff → 즉시성이 있어 별개 — World 안 알림 + 별도 화면 조합이 후보
```

### Flow D — Account

```text
D-1  단일 진입
     진입점 → 내 정보 (프로필 + 코인 + 거래내역)        ← 현재 !240 구조

D-2  분리
     진입점 ├─ 프로필 (닉네임·provider·탈퇴)
            └─ 지갑   (잔액·거래내역)
```

판단 재료: Wallet 의 기능 표면은 **잔액 1값 + 거래 페이지 목록**뿐이고 사용자 액션이 없다(일일 지급은 서버 자동). 반면 Profile 은 닉네임 변경·탈퇴라는 액션을 갖는다. 화면 하나를 더 만들 만큼의 상호작용이 Wallet 에 없으므로 **D-1(통합) 을 권한다.** 다만 임대 직전 잔액 확인은 Booth 화면의 `WalletBadge` 가 이미 담당하므로, **Booth 화면의 거래내역 전체 목록은 Profile 로 모으는 것(MOVE)** 이 함께 따라온다.

장기적으로 Game Menu 가 생기면:

```text
ESC → Game Menu ├─ 게임으로 돌아가기
                ├─ 내 부스
                ├─ 내 정보 (프로필·코인)
                ├─ 설정        (Future)
                └─ 로그아웃
```

이 구조에서 현재 World 상단 auth bar 는 Game Menu 로 흡수된다(Gap Matrix: MOVE).

---

## 5. 사용자 결정이 필요한 항목

| # | 결정 | 후보 | 이 결정이 막고 있는 것 |
|---|---|---|---|
| 1 | **로그인 완료 후 목적지** | A-1 바로 World / A-2 Entry 화면 경유 후 World / A-3 Home 허브 유지 | Home 화면의 존치·재설계 여부, `DEFAULT_RETURN_TO`, Landing 클릭 목적지 |
| 2 | **신규 사용자 흐름 확정** | Auth → 닉네임 First Setup → (1번 결정 목적지) 로 고정 | First Setup 재설계 착수. 근거상 닉네임 외 단계 없음 — 확인만 필요 |
| 3 | **World 기능 진입을 Unity F 로 고정** | 고정(권장 — hud-decisions 확정 유지) / React launcher 를 최종 UX 로 승격 | 목업 상호작용 바의 최종 처리(DEV_ONLY 유지 vs 제품화) |
| 4 | **Management 진입점** | C-1 화면 경유 / C-2 ESC Game Menu / 둘 병행 | Booth·Profile·MISSING 4건의 자리, World 이탈 경로 |
| 5 | **Profile / Wallet 구조** | D-1 통합(권장) / D-2 분리 | 거래내역 중복 해소, Profile 재설계 |
| 6 | **Game Menu 를 이번 목업에 넣을지** | 최소형 포함(ESC → 메뉴 3항목) / Game Client 단계까지 보류 | World 이탈 경로, auth bar MOVE 대상 존재 여부 |
| 7 | **MISSING 4건의 배치** | Booth Studio 확장 / 별도 부스 관리 화면 / Consultation Staff 만 분리 | Survey Result·Builder·Consultation Staff·Project 편집 화면 착수 |

색상·radius·shadow 같은 Visual 결정은 이 목록에 넣지 않았다 — Flow 확정 이후 단계다.

---

## 6. 이 문서가 확정하지 않은 것

- 위 7개 결정 중 어느 것도 확정으로 기록하지 않는다.
- `implementation-decisions.md`·`03_foundation/`·전역 token·장기 GameShell 정본은 이번 회차에 수정하지 않았다.
- Flow A/C/D 의 권장 표시는 근거를 붙인 제안일 뿐 결정이 아니다.
