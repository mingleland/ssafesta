# SSAFY FESTA UI Design — 인덱스

UI 통합 작업의 정본 구조. 원본(SOURCE)·확정(DECISION)·실측(AUDIT)·제안(PROPOSAL)·검증(SPIKE RESULT)을 구분해 관리한다.

## 문서 우선순위 (충돌 시 위가 이긴다)

```text
1. 00_context/implementation-decisions.md   — 사용자가 후속 확정한 구현 결정
2. 00_context/hud-decisions.md              — 최신 HUD 역할 경계
3. 00_context/references/SSAFY_FESTA_최종_UI_구조_종합정리.md — 전체 UX/Game UI 목표 구조
4. EXECUTION_PLAN.md                        — 실행 절차
5. 02_audit/*                               — 특정 baseline 실측 현황
6. 01_tooling/*                             — 도구 운용 기록
```

**Audit 는 관측 기록이지 최신 의사결정 정본이 아니다.** 이후 사용자 결정이 Audit 과 충돌하면 implementation-decisions.md 가 우선한다. HUD 보고서(references/)도 후속 확정 전 조사 문서 — hud-decisions.md 가 우선.

## 디렉터리 역할

| 경로 | 종류 | 내용 |
|---|---|---|
| `EXECUTION_PLAN.md` | SOURCE | 사람/에이전트 역할 분리·전체 실행 절차 |
| `00_context/references/` | SOURCE | 사람이 제공한 문서 원본 (수정 금지) |
| `00_context/sources/` | SOURCE | 시각 reference 원본 — landing.png·login.png·booth-2_5d-reference.png (3종 확보 완료) |
| `00_context/implementation-decisions.md` | DECISION | D-01~D-07 (Game Studio 제외·Desktop-first·모바일 확장·Booth 2.5D·R3F 후보·Layout 계약 보존·SSAFY OAuth 도입) |
| `00_context/hud-decisions.md` | DECISION | Unity/React HUD 경계 최종 |
| `01_tooling/` | 기록 | 도구 운용 (추후 생성) |
| `02_audit/` | AUDIT | 2026-08-31 실측(baseline origin/develop=9c0db7a) + 후속 결정 갱신 표시. `evidence/` 는 역할 A~D 원 보고서 |
| `03_foundation/` | PROPOSAL→DECISION | visual-dna 초안(토큰 미확정) → Visual Direction 선택 후 Freeze |
| `04_prototypes/` | PROPOSAL | `/design` 대표 4축 (추후 생성) |
| `05_technical-spikes/` | SPIKE | booth-studio-2_5d feasibility (R3F 채택 Gate, 추후 생성) |
| `06_visual-review/` | 기록 | Taste·Playwright·consistency (추후 생성) |
| `07_handoff/decision-queue.md` | DECISION 대기 | Unity 합의 4건 + SSAFY provider 처리 |

## 현재 단계

```text
완료: Gate A(Control Plane)·B(Design Tooling)·C(UI Audit) PASS / 구조 정본화·후속 결정 반영 (2026-09-01)
다음: Frontend Design READY smoke → Foundation Draft → /design 대표 4축 → 사용자 Visual Direction 선택
```

## `/design` 대표 4축 (최신)

```text
1. Landing / Login
2. World + Minimal Screen UI
3. World + React Overlay
4. Booth Studio 2.5D Creator Workspace   ← booth-2_5d-reference.png 을 visual anchor 로 사용 (확보 완료)
```

## 도구 (전부 검증 완료 — 재설치 금지)

Claude Design READY · Frontend Design LOADABLE(READY smoke 는 구현 직전) · Taste v2 LOADABLE(critique 전용) · Playwright MCP READY.
우선순위: FESTA 정본 > 사용자 선택 Visual Direction > Taste critique.
