# Real / Hybrid / Mock 분류

> **STATUS: HISTORICAL AUDIT — BASELINE origin/develop 9c0db7a (2026-08-31)**
> 이 관측은 -405·-406 이전 상태다. 현재 구현 상태는 `02_audit/function-truth-inventory.md`
> (baseline develop=0bf878c6, 2026-09-03)가, 흐름 결정은 `00_context/user-flow-decisions.md` 가 정본이다.
> 당시 기록이므로 최신 내용으로 덮어쓰지 않는다.

- 문서 종류: AUDIT(baseline 9c0db7a, 2026-08-31) + DECISION 갱신(2026-09-01, D-01·D-04·D-05 반영)
- 원칙: NOT_IMPLEMENTED ≠ 디자인 제외. 전 화면이 셋 중 하나로 디자인 통합에 존재한다.
- Design Blocker 는 **전 도메인 0** — 어떤 화면도 디자인 선행이 막혀 있지 않다 (roleD ③).
- **Game Studio / Game Play / 게임 생성·목록은 이번 UI 통합 범위 밖**(D-01, PROTECTED TEAM VERTICAL) — 아래 표의 해당 행은 관측 기록으로만 유지한다.

| UI | Functional State | Design Mode | Real Dependency | Mock Needed | Functional Blocker | Design Blocker |
|---|---|---|---|---|---|---|
| Login / Callback / First Setup | IMPLEMENTED | **REAL** | BE auth (완결) + VITE_USE_MOCK | 불요 | 없음 | 없음 |
| Booth 슬롯/Lease | IMPLEMENTED | **REAL** | BE booth-slots (완결) | 불요 | 없음 | 없음 |
| Facade / Publish | IMPLEMENTED | **REAL** | BE layouts (완결) | 불요 | 없음 | 없음 |
| Booth Studio | IMPLEMENTED | **REAL + NEW 2.5D PRESENTATION** (D-04) | BE layouts (완결) — 기존 Save/Publish/validation/conflict 계약 최대 보존(D-06 adapter) | 불요(데이터) / 2.5D 렌더러는 신규 presentation layer | Technical Gate = R3F feasibility spike (D-05, 05_technical-spikes/) | 없음 |
| Wallet | PARTIAL | **REAL** | BE wallets (완결) | 불요 | 없음 | 없음 |
| LAPTOP overlay | IMPLEMENTED | **REAL** | BE homepageUrl + Unity 이벤트 | 콘솔 이벤트 주입 | FE 배선 결함(자체 수정 가능) | 없음 |
| Profile | NOT_IMPLEMENTED | **REAL** (BE 존재) | BE users/me 4종 | 불요 | 없음 | 없음 |
| 게임 생성·목록 | NOT_IMPLEMENTED | (관측: REAL 가능 — BE 존재) — **이번 통합 범위 밖(D-01)** | BE /games CRUD | — | 없음 (타 팀원 트랙) | — |
| World Host | IMPLEMENTED | **HYBRID** | Unity WebGL 빌드 | loader.mock (기존재) | WebGL 서빙 경로·VITE_UNITY_BUILD_BASE·013a-AT | 없음 |
| GAME overlay | IMPLEMENTED | **HYBRID** | BE game-portals + Unity 송신 | portal fixture (기존재) | BE 4종 + Unity 송신부 | 없음 |
| Game Studio / Play | IMPLEMENTED | (관측: HYBRID) — **이번 통합 범위 밖(D-01)** | BE draft/publish(존재)·에셋 API(부재) | — | BE 에셋 3종 (타 팀원 트랙) | — |
| AI overlay | PARTIAL | **HYBRID** | AI 서버 SSE (미구현) | stream.mock (기존재) | AI conversation API | 없음 |
| Project overlay | NOT_IMPLEMENTED | **HYBRID** | BE projects/published (develop 완비) + Unity 이벤트(부재) | Mock project + mock interaction | Unity BOOTH_PROJECT_INTERACT(-343) | 없음 |
| Landing / Title | STUB | **MOCK** | 없음 | 전체 | 없음 | 없음 |
| GameShell / ReactScreenLayer | NOT_IMPLEMENTED | **MOCK** (MockUnitySurface) | Unity 상주 계약 | MockUnitySurface (seam 기존재) | Unity pause/input-lock 계약 | 없음 |
| Survey overlay + Builder | NOT_IMPLEMENTED | **MOCK** | BE 전무 | 전체 (질문·응답 fixture) | BE Survey 6건 + Unity 이벤트 | 없음 |
| Consultation overlay + Staff | NOT_IMPLEMENTED | **MOCK** | BE WS 전무 (-137 은 명세뿐) | 전체 (대화 fixture) | BE WS 채널·토큰 | 없음 |
| Dashboard / Staff / Admin | NOT_IMPLEMENTED | **MOCK** | BE 전무 | 전체 (KPI·테이블 fixture) | BE spec 015 전체 | 없음 |
| Post-MVP React HUD 3종 (조작 안내·점수·Toast) | NOT_IMPLEMENTED | **MOCK** (component preview 만) | — | pattern preview | 없음 (Post-MVP Optional) | 없음 |

## Mock 전략 요지

- 실행 기반: `VITE_USE_MOCK=true` → auth·wallet·layout·facade·lease 5종 + unity loader 전부 mock. 콘솔 `window.FestaUnity.onBoothInteract(JSON.stringify({...}))` 로 임의 overlay open — 실 Unity·실 BE 0 으로 전 화면 구동.
- mock 시나리오 스위치: `window.__unityMock = { forceLoadFail, suppressGateReady }` 로 실패·타임아웃 상태 디자인도 재현 가능.
- 신규 필요 mock: Survey/Consultation/Dashboard fixture, Project mock data, MockUnitySurface 시각 배경(월드 스크린샷/플레이스홀더 — Unity 소관 UI 는 시각 reference 로만, production component 화 금지).
