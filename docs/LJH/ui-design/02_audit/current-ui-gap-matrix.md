# Current UI Gap Matrix — 현재 화면 판정

- 문서 종류: **AUDIT** (관측·판정 기록 — 구현 지시 아님)
- 조사일: 2026-09-03
- 대상: `!240`(`origin/feat/S15P21A604-406-world-project-overlay = 07bf7612`)의 현재 Presentation + `origin/develop = 0bf878c6` 의 잔존 화면
- 근거: `function-truth-inventory.md`(같은 폴더) + `00_context/implementation-decisions.md` + `00_context/hud-decisions.md` + `04_prototypes/game-client-experience-draft.md` + `04_prototypes/world-reference-brief.md`
- **이 문서는 UI 를 고치지 않는다.** 판정만 기록하고, 실행은 사용자 Flow 확정 이후 별도 블록에서 한다.

---

## 판정 규칙

```text
기능 있음 + 기획 필요 + 위치 맞음      → KEEP
기능 있음 + 위치가 틀림                → MOVE
기능 있음 + 표현이 기획 수준에 못 미침 → REDESIGN
개발·Mock 진입을 위한 UI              → DEV_ONLY
기획상 필요한데 UI 없음                → MISSING
기능도 기획도 근거 없음                → REMOVE
현재 UI 만 있고 기능·기획 근거 없음    → REMOVE 우선
```

---

## 1. Entry / Auth

| UI 요소 | 위치 | 판정 | 근거 |
|---|---|---|---|
| Landing 타이틀 화면 | `/` | **KEEP** | landing.png reference 정본 구현. 시작 제스처는 game-client-experience-draft §10 의 audio unlock/fullscreen 후보 지점이기도 하다 |
| Login provider 4종 + 게스트 | `/login` | **KEEP** | D-07 확정 4종 그대로. SSAFY `not_configured` 는 계약에 맞는 상태 표현 |
| Login 하단 푸터(축제 안내·이벤트·고객센터·약관) | `/login` | **REMOVE 후보** | 링크 대상이 없는 장식 텍스트. 기능·기획 근거 없음 — reference 재현이면 KEEP 로 되돌릴 수 있으나 현재는 dead text |
| `로그인 처리 중입니다...` | `/auth/callback` | **REDESIGN** | 상태는 실재(REAL). 게임 클라이언트 부팅 UX(draft §12 Loading)로 다시 그린다 |
| `로그인 정보가 만료되었습니다` 재시작 | `/auth/callback` | **REDESIGN** | 동일. 오류 표현이 raw |
| `처음 오셨네요 + 닉네임 폼` | CallbackPage 분기 | **REDESIGN** | 상태·필드는 REAL 이고 **닉네임 1개가 전부**다. 필드를 추가하지 않고 표현만 다시 그린다 |
| `세션 확인 중...` (bootstrap) | RequireAuth | **REDESIGN** | 전 화면 공통 첫 프레임. Loading 표준으로 흡수 |
| `소셜 로그인이 필요한 기능입니다` | RequireAuth member-only 차단 | **REDESIGN** | 기능 정당, 표현 raw |

## 2. World

| UI 요소 | 위치 | 판정 | 근거 |
|---|---|---|---|
| `StaticMockWorldSurface` (정지 캡처) | World Layer | **KEEP (임시 구현으로서)** | `WorldSurface.select` seam 이 교체 계약이다. Persistent GameShell 단계에서 UnityHost 로 치환 |
| 조작 안내 카드(WASD/F/Esc, 닫기) | 좌상 | **KEEP** | `hud-decisions.md` 허용 3종 중 1번 |
| 미니게임 score/progress | — | **MISSING (Post-MVP Optional)** | 허용 3종 중 2번. 미니게임 진행 중에만 존재해야 하므로 지금 필요 없음 |
| Toast / Notification | — | **MISSING (Post-MVP Optional)** | 허용 3종 중 3번. decision-queue #2(Unity 토스트 이관)와 연결 |
| **하단 목업 상호작용 바 6버튼** | 하단 중앙 | **DEV_ONLY** | 코드 자체가 `IS_MOCK_WORLD` 게이트 안에 있고 주석이 "Unity F 상호작용의 임시 대역"이라 선언한다. `hud-decisions.md` 는 hotbar·기능 launch dock 을 명시적으로 금지한다. **최종 UX 요구사항이 아니다** — 실제 Unity 연결 시 사라진다 |
| **상단 auth bar(`회원으로 이용 중` + 로그아웃)** | World 상단 | **MOVE** | `RequireAuth` 가 모든 가드 화면에 무조건 그린다. World 는 "상주 상태"이므로 화면 고정 계정 칩이 상단에 박히는 것은 game-client-experience-draft §3 의 `ESC → Game Menu → 로그아웃` 구조와 어긋난다. Game Menu 로 이동이 목표 |
| World → 다른 화면 나가기 | 없음 | **MISSING** | 런타임 실측: World 에서 브라우저 뒤로가기 외에 나갈 방법이 없다. Game Menu 또는 명시적 진입점 필요 |
| F 프롬프트·하이라이트·이름표 | — | **REMOVE (React 에서)** | Unity 소관 확정(`hud-decisions.md`). React 가 만들지 않는다 — 현재도 만들지 않았다 |
| minimap·HP·quest·crosshair·mission panel | — | **REMOVE (추가 금지)** | 동 문서 명시 금지 |

## 3. Overlay Family

| UI 요소 | 판정 | 근거 |
|---|---|---|
| `OverlayFrame` 공통 골격(title·icon·body·footer·Esc·dim 클릭) | **KEEP** | Overlay Local Baseline v0. 6개 오버레이가 같은 골격을 쓴다 |
| Project Overlay(카드·미디어·좋아요·링크) | **KEEP** | REAL VM(-134·-135) 소비. 좋아요 pending/rollback/게스트 게이트까지 계약대로 |
| LAPTOP Overlay(iframe + 새 탭 + hostname) | **KEEP** | 016 계약 그대로. 새 탭이 1급 기능인 설계 유지 |
| Survey Overlay(Run) | **KEEP** | 6유형·required·progress·제출 상태 모두 모델 소비 |
| Consultation Overlay(Visitor) | **KEEP** | C-01 카운트다운·취소·재요청 모델 소비 |
| AI Chat Overlay | **KEEP (표현) / 데이터는 MOCK 명시 유지** | 실제 응답이 아님을 화면이 밝히고 있다(`준비 중인 기능입니다`) |
| GAME Overlay 오류·재시도 | **KEEP** | 소유 컴포넌트(박준우)이며 blocked 를 정직하게 표현. 손대지 않는다 |
| Overlay **Stack** | **MISSING (DEFERRED)** | 현재는 단일 슬롯. draft §4 의 Overlay Stack·Input Router 는 Game Client Experience 단계 |
| Overlay 열림 중 Unity Input Lock | **MISSING (BLOCKED)** | 양측 미구현, decision-queue #4 |

## 4. Screen Family

| UI 요소 | 판정 | 근거 |
|---|---|---|
| `PageShell` 공통 껍데기(배경·제목·backTo·actions) | **KEEP** | Screen Local Baseline v0 |
| **Home 허브(월드 hero + 카드 3종)** | **REDESIGN / route-role 결정 대상** | 기능은 실재(월드 링크·내 부스 쿼리·프로필 링크)하지만 **"Home 대시보드"를 요구한 기획 문서가 없다.** `/app/home` 은 returnTo 기본값 route 였다. 존치 여부 자체가 제품 결정(§M-1) |
| Booth 슬롯 목록·임대 카드·확인 다이얼로그 | **KEEP** | spec 004 계약 그대로(확인·환불 불가 고지·오류 매핑·카운트다운) |
| Booth 화면의 **코인 사용 내역** | **MOVE** | Wallet 성격의 정보가 Profile 과 중복 노출. 한 곳으로 모은다 |
| Profile(닉네임·provider·코인·탈퇴) | **KEEP** | REAL 계약 그대로 |
| Profile 안의 거래내역 | **KEEP (여기가 유력)** | 두 곳 중 하나를 남긴다면 계정 화면 쪽 — 단 Profile/Wallet 분리 여부는 제품 결정(§M-5) |
| Booth Studio Shell(모드 레일·팔레트·인스펙터·상태바) | **KEEP** | -405 검증분. 기능 계약 무변경 |
| Booth Studio 임시 SVG 아이소메트릭 렌더러 | **KEEP (임시)** | Presentation 교체 가능. D-04/D-05 2.5D 는 spike 통과 후 |
| Game Studio / Game Play 화면 | **판정 제외 (PROTECTED)** | D-01 |

## 5. 없는 것 — MISSING

| 항목 | 데이터층 | 왜 MISSING 인가 | 진입점 |
|---|---|---|---|
| Survey Result(집계·주관식 페이지네이션) | **있음**(`result.ts`) | UI 미구현 | 미정 — 부스 소유자 관리 맥락 |
| Survey Builder(문항 편집) | **있음**(`builder.ts`) | UI 미구현 | 미정 — 부스 소유자 관리 맥락 |
| Consultation Staff(대기열·수락·종료) | **있음**(`staff.ts`) | UI 미구현 | 미정 — World 오버레이가 아니라 별도 관리 화면 |
| **Project 소유자 편집** | **있음**(`edit.ts`) | UI 미구현 — 이번 조사에서 새로 발견 | 미정 — Booth Studio 안이 유력하나 결정 안 됨 |
| Game Menu / Settings | 없음 | draft §3 의 Target UX | ESC |
| Toast / Notification 공통 | 없음 | 허용 HUD 3종 중 하나 | — |
| 아바타 편집 | 액션만 있음(`saveAvatar`) | UI 미구현 + Unity POC 잔존(decision-queue #3) | 미정 |

## 6. 집계

```text
KEEP        16
MOVE         2   (World auth bar / Booth 거래내역)
REDESIGN     7   (Auth 4종 + bootstrap + member 차단 + Home)
DEV_ONLY     1   (World 하단 목업 상호작용 바)
REMOVE 후보  1   (Login 푸터 dead text)
MISSING     10   (Survey Result·Builder / Consultation Staff / Project 편집 /
                  Game Menu / Toast / 미니게임 HUD / Overlay Stack / Input Lock /
                  World 이탈 경로)
PROTECTED    2   (Game Studio / Game Play — 판정 제외)
```

## 7. 판정에서 유의한 점

1. **DEV_ONLY 는 결함이 아니다.** 목업 상호작용 바는 Unity 없이 dispatcher 하류 전체를 검증하기 위한 정당한 임시 장치다. 다만 **제품 HUD 로 승격하지 않는다** — 이것이 이 문서의 핵심 판정이다.
2. **REDESIGN 이 붙은 Auth 화면들은 기능 결함이 아니다.** 상태 기계는 전부 REAL 이고, 표현만 목업 라운드에서 손대지 않은 채 남았다.
3. **MISSING 4건(Survey Result·Builder·Consultation Staff·Project 편집)은 전부 "데이터층 있음 + UI 없음"** 이다. 기능 scope 문제도 blocked 도 아니다. 다만 넷 다 **진입점이 정해져 있지 않다** — 화면을 그리기 전에 어디서 들어가는지가 결정돼야 한다(§M-4).
