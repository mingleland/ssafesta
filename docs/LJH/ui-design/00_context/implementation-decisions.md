# Implementation Decisions — 사용자 후속 확정 정본

- 문서 종류: **DECISION** (사용자 확정, 2026-09-01 정본화)
- 이 문서는 02_audit 실측·HUD 보고서·EXECUTION_PLAN 과 충돌할 때 **우선**한다 (우선순위는 README.md).

## D-01 — Game Studio 는 이번 UI 통합 범위 밖

Game Studio(`/app/games/:id/edit`·`/play`·게임 생성/목록 포함)는 타 팀원의 수직 개발 영역이다.

```text
수정 금지 / RESTYLE 금지 / 구조 재편 금지 / Creator 통합 대상 제외 / 진행 작업(-155)과 충돌 금지
```

read-only 참고는 가능하나 디자인 정본으로 사용하지 않는다.

**중요 수정 — 토큰 원천 판정 폐기**: 2026-08-31 Audit 의 "gss/grp 팔레트 = FESTA Foundation token 원천" 판정은 폐기한다.
새 Foundation 원천 = 최종 UI 구조 문서 + Landing reference + Login reference + Booth 2.5D reference + 사용자 선택 Visual Direction.
Game Studio 의 색상/CSS 는 FESTA 디자인 SSOT 가 아니다.

## D-02 — MVP 는 Desktop-first

MVP 에서 전체 모바일 UI 를 지원하지 않는다.

```text
Desktop / Laptop        → 정상 지원
일정 이하 viewport      → DesktopRequiredGate — "PC 브라우저에서 이용해주세요"
```

최소 viewport 수치는 지금 정하지 않는다 — 실제 디자인을 Playwright viewport matrix 로 검증한 뒤 결정한다.

## D-03 — 모바일은 확장 기능 (Post-MVP)

```text
Mobile  → Landscape 중심
React   → Landscape UI / Overlay 대응
Unity   → Touch joystick / touch interaction
portrait → "기기를 가로로 돌려주세요" 안내 우선
```

Orientation Lock API 는 지원 환경에서만 보조 고려. MVP 범위를 full mobile responsive 로 확대하지 않는다.

## D-04 — Booth Studio 는 2.5D

완전 자유 3D Editor 가 아니라 **고정 Isometric/Orthographic 시점에서 2D 편집기처럼 조작하는 2.5D Booth Editor**.

- Camera: Orthographic + 고정 isometric-like angle + Pan/Zoom (+ 필요 시 제한적 90° 회전). 자유 orbit 은 기본 UX 아님.
- Object: X/Z 이동, Y 고정, Y-axis rotation, Grid snap, Booth bounds, overlap/collision validation, selection.
- UI: Left Palette / Center 2.5D Canvas / Right Inspector — 3D 렌더러는 중앙 Canvas 한정, 나머지는 React DOM.
- Visual/Interaction Reference: `00_context/sources/booth-2_5d-reference.png` — **확보 완료** (사람 제공 원본, SOURCE).

## D-05 — 2.5D 1순위 기술 후보 (PROPOSAL — 채택 아님)

```text
1순위: React + @react-three/fiber + three + @react-three/drei
렌더 구조: OrthographicCamera + fixed isometric-like angle
```

**production dependency 확정 아님** — `05_technical-spikes/booth-studio-2_5d/` feasibility spike(완료조건 15항) PASS 후에만 정식 채택.
실패 시 fallback(react-konva + isometric sprites 등 2D 가짜 2.5D)을 그때 재검토. 양쪽 동시 구현 금지.

## D-06 — 기존 Layout 계약 최대 보존

BE 저장 계약을 3D 로 재설계하지 않는다.

```text
기존 Layout canonical data → renderer adapter → R3F 2.5D representation
layout.x → world X / layout.y → world Z / object height → fixed Y / layout rotation → world Y-axis rotation
```

정확한 schema 호환은 Spike 에서 실측. 기존 Save·Publish·Validation·conflict handling 최대 보존.

## D-07 — SSAFY OAuth 도입 확정

**제품 결정: SSAFY OAuth = ADOPTED.** 최종 Login provider 4종:

```text
SSAFY / Google / Kakao / Guest
```

**현재 구현 상태: SSAFY 실제 BE/FE 계약 = 아직 없음 / NO_PROGRESS** — UI 목표에서는 정식 provider 지만 구현 완료로 기술하지 않는다.
UI 디자인에서 SSAFY 버튼은 정식 슬롯으로 표현하되, 실 wiring 전에는 disabled/not-configured 등 계약에 맞는 상태로 처리한다.
실제 OAuth URL·DTO·redirect 계약은 디자인 단계에서 발명하지 않는다.
