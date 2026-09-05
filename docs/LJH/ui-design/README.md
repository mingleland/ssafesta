# SSAFY FESTA UI Design — 인덱스

UI 통합 작업의 정본 구조. 원본(SOURCE)·확정(DECISION)·실측(AUDIT)·제안(PROPOSAL)·검증(SPIKE RESULT)을 구분해 관리한다.

## 문서 우선순위 (충돌 시 위가 이긴다)

```text
DECISION  — 확정. 아래 셋이 서로 충돌하면 위가 이긴다
  1. 00_context/implementation-decisions.md   D-01~D-08 구현 결정
  2. 00_context/user-flow-decisions.md        사용자 흐름·진입 구조 정본 (D-08 의 상세)
  3. 00_context/hud-decisions.md              Unity/React HUD 허용 범위

AUDIT     — 관측 기록. 의사결정 정본이 아니다
  4. 02_audit/function-truth-inventory.md     최신 구현 상태 (2026-09-03, develop 0bf878c6)
  5. 02_audit/current-ui-gap-matrix.md        !240 화면 판정 (2026-09-03)
  6. 02_audit/* (2026-08-31 baseline)         HISTORICAL — 상단 stale 표시 참조

PROPOSAL  — 제안·초안. 확정 아님
  7. 04_prototypes/ux-architecture-remap.md   새 Flow 기준 재배치·재구현 계획
  8. 04_prototypes/screen-specifications.md   화면별 Target Structure
  9. 04_prototypes/game-client-experience-draft.md  장기 Game Client 설계
 10. 04_prototypes/world-reference-brief.md   World 시각 사양
 11. 03_foundation/visual-dna.md              Visual 토큰 초안 (Freeze 아님)
 12. 05_technical-spikes/booth-studio-r3f-asset-pipeline-plan.md
                                             Booth Studio 실제 에셋 렌더링 — DEFERRED / Spike 미착수
 13. 04_prototypes/target-user-flow-draft.md  HISTORICAL — user-flow-decisions.md 로 대체됨

SOURCE    — 사람이 제공한 입력 원본. 수정 금지
 14. 00_context/source-docs/                  최종 UI 구조 종합정리 · HUD 확인 필요 보고서
 15. 00_context/sources/                      시각 reference 이미지
 16. EXECUTION_PLAN.md                        역할 분리·실행 절차 (디렉터리 번호는 이 README 가 정본)
```

**Audit 는 관측 기록이지 최신 의사결정 정본이 아니다.** 사용자 결정이 Audit 과 충돌하면 DECISION 3종이 우선한다. `source-docs/` 의 두 기획 문서도 후속 확정 전 입력 자료이며, 흐름은 `user-flow-decisions.md`·HUD 는 `hud-decisions.md` 가 이긴다.

## 디렉터리 역할

| 경로 | 종류 | 내용 |
|---|---|---|
| `EXECUTION_PLAN.md` | SOURCE | 사람/에이전트 역할 분리·전체 실행 절차 |
| `00_context/source-docs/` | SOURCE | 사람이 제공한 기획 문서 원본 (수정 금지). 2026-09-03 에 `references/` 에서 이름을 바꿨다 — `.gitignore` 의 `references/` 규칙에 걸려 git 밖에 있었기 때문 |
| `00_context/sources/` | SOURCE | 시각 reference 원본 — landing.png·login.png·booth-2_5d-reference.png(Booth Studio 시안)·world-ingame-reference.png(2026-09-03 Unity 인게임 캡처) |
| `00_context/implementation-decisions.md` | DECISION | D-01~D-08 (Game Studio 제외·Desktop-first·모바일 확장·Booth 2.5D·R3F 후보·Layout 계약 보존·SSAFY OAuth·World-centered User Flow) |
| `00_context/user-flow-decisions.md` | DECISION | 사용자 흐름 정본 — Entry / World / Visitor Overlay / ESC / Booth Management / Consultation |
| `00_context/hud-decisions.md` | DECISION | Unity/React HUD 경계 + 허용 HUD 4종 |
| `01_tooling/` | 기록 | 도구 운용 (미생성) |
| `02_audit/` | AUDIT | 2026-09-03 실측(function-truth-inventory·current-ui-gap-matrix, baseline develop=0bf878c6) + 2026-08-31 HISTORICAL 6종. `evidence/` 는 역할 A~D 원 보고서 |
| `03_foundation/` | PROPOSAL→DECISION | visual-dna 초안(토큰 미확정) → Visual Direction 선택 후 Freeze |
| `04_prototypes/` | PROPOSAL | UX 재배치·화면 사양·장기 Game Client 설계·World 시각 사양 |
| `05_technical-spikes/` | SPIKE PLAN | `booth-studio-r3f-asset-pipeline-plan.md` — Booth Studio 실제 Unity 에셋 근사 렌더링(R3F + GLB + FE Asset Pipeline). **상태: DEFERRED / Spike 미착수.** R3F 는 여전히 D-05 의 후보이며 채택 결정이 아니다 |
| `06_visual-review/` | 기록 | Taste·Playwright·consistency (미생성) |
| `07_handoff/decision-queue.md` | DECISION 대기 | Unity 합의 4건 |

## 현재 단계

```text
UI 재설계 완료 · develop 반입 (2026-09-03) → Unity 진입 계약 3건 develop 반입 (2026-09-05, !250)
User Flow 정본화 → 문서 정합 → !240 재분류 → 화면별 사양 → R1~R9 구현 →
회귀 검증 → merge → World 상호작용 배선(-343·-414·-415·-416). front = develop 39641e0b.

기능층은 24종 전부 보존했고 제거는 0이다. Presentation 과 진입 구조만 바뀌었다.
Presentation 추가 개발은 중단 상태다 — 다음은 계약 대기 항목이 풀리는 순서로 진행한다.
```

### 후속 우선순위 (착수는 별도 승인)

```text
P1  Unity 진입 런타임 검증      G-1·G-2·G-4 계약·구현 도달(!250) — Editor 컴파일·F 3종·NPC 프리팹 부착·BridgeTests 잔여
P2  Consultation Target Context G-3 — 새 상담의 대상 부스 결정(제품·계약 결정)
P3  Survey / Consultation 실 BE·Transport   G-6 · G-7
P4  Game Client Input Lock      G-8 (Overlay Stack·Input Router 와 묶어 설계)
P5  GAME                        G-5 (ownership·BE·Unity 이벤트)
P6  Booth Studio 실에셋 fidelity  R3F P0 Spike — 계속 DEFERRED
```

Gap 별 현황은 `04_prototypes/ux-architecture-remap.md` §10 을 본다.

### 개발용 상호작용 트리거 (DEV_ONLY)

Unity 송신부가 없는 구간을 FE 단독으로 검증할 때만 쓰는 도구다. **제품 HUD 가 아니다.**

```bash
# festa-frontend/.env.local — 기본값은 .env.example 의 false 다. 필요한 사람만 로컬에서 켠다
VITE_DEV_INTERACTION_BAR=true
```

dev 빌드에서만 동작하고 프로덕션 번들에는 포함되지 않는다.

## `/design` 대표 4축 (최신)

```text
1. Landing / Login
2. World + Minimal HUD (Consultation Quick Access 포함)
3. World Overlay (Visitor Overlay Family + Booth Management Overlay)
4. Booth Studio 2.5D Creator Workspace   ← booth-2_5d-reference.png 이 구현 목표(Visual/Layout Target)
```

## 도구 (전부 검증 완료 — 재설치 금지)

Claude Design READY · Frontend Design LOADABLE(READY smoke 는 구현 직전) · Taste v2 LOADABLE(critique 전용) · Playwright MCP READY.
우선순위: FESTA 정본 > 사용자 선택 Visual Direction > Taste critique.
