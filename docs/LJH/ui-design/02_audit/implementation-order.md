# UI 통합 구현 순서 (최신 확정본)

- 문서 종류: DECISION 반영본 (2026-09-01, `00_context/implementation-decisions.md` D-01~D-06 반영)
- 이전 초안(2026-08-31, baseline 9c0db7a 실측 기반)을 대체한다. Game Studio Creator 작업·gss/grp 토큰 원천 항목은 D-01 로 제거됐다.
- 전제: 디자인 blocker 전 도메인 0. game-studio 밖 전 화면 무스타일 → Foundation 신규 적용에 스타일 충돌 없음.

```text
0. 최신 origin/develop 재확인 (fetch 후 baseline 재기록)
1. ASC 이전 결과 collect / 작업 경계 정리          ← 완료 (2026-09-01, S-20260831-01~05 collect)
2. Frontend Design READY smoke
3. Foundation Draft
   - Visual DNA / Typography / Color direction
   - Semantic state / Spacing / Radius / Elevation / Z-index
   - Desktop-first responsive policy (D-02)
4. Primitive
   - Button / Modal(native dialog 정본) / Field / ScreenNotice / Toast / Badge / IconButton
5. GameShell / MockUnitySurface 구조
6. /design 대표 4축 (Landing·Login / World+Minimal / World+Overlay / Booth Studio 2.5D)
7. 사용자 Visual Direction 선택
8. Foundation Freeze (token·component theme 확정)
9. Landing / Login
10. OverlayFrame
11. 기존 REAL 화면 RESTYLE (Booth 슬롯·Lease / Facade / Wallet / Profile 신규 / LAPTOP url 배선 수정)
12. HYBRID 화면 (AI overlay UI / GAME overlay real 잔여 / Project overlay)
13. MOCK 화면 (Survey 응답 / Consultation / Landing 잔여)
14. Booth Studio 2.5D feasibility spike (D-05, 05_technical-spikes/booth-studio-2_5d/ — Gate)
15. Booth Studio 2.5D Creator Workspace (D-04, Spike PASS 시 R3F 정식 채택)
16. Survey Builder
17. Operations (Dashboard / Staff / Admin)
18. Post-MVP Optional React HUD preview (조작 안내·점수·Toast)
19. Actual API / Unity integration (decision-queue 4건 해소 후)
```

## 범위 제외

- **Game Studio 전체** — D-01: 타 팀원 수직 개발 영역. 수정·RESTYLE·구조 재편·Creator 통합 대상 아님. read-only 참고만.
- **full mobile responsive** — D-02/D-03: MVP 는 Desktop-first + DesktopRequiredGate. 모바일은 확장 기능(Landscape 중심).

## Foundation 원천 (D-01 중요 수정)

기존 초안의 "gss/grp 팔레트 = 토큰 원천" 판정은 **폐기**. 새 원천:
최종 UI 구조 문서 + Landing/Login reference + Booth 2.5D reference + 사용자 선택 Visual Direction.
