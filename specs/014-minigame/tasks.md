# Tasks: 관리자 미니게임

**Spec**: `specs/014-minigame/spec.md` | **Plan**: `plan.md`
**상태**: 게임 종류 확정 — 착수 가능 (단, 2차 MVP 항목)

> **2026-09-06 대조** — 아래 체크는 develop `0418cf0c` 코드 기준으로 맞췄다(작성 당시 이름과 실제 구현 이름이 다른 항목은 괄호에 구현체를 적었다). 미체크는 정말 안 된 것 또는 타 파트·인프라 의존이다.

---

## Phase 0: 결정 (이것 없이는 아무것도 못 만든다)

- [x] ~~T001 게임 종류 결정~~ → ✅ **타이머 정지 게임 (목표 2~4초 무작위, 2026-09-18 개정)**
- [x] ~~T002 단독 플레이~~ → ✅ 확정
- [x] **T003** C-03 보상 정책 + C-04 일일 한도 수치 결정 → ✅ **2026-08-12 리드 확정** (`spec.md` 리드 확정표, `docs/26`). 체크만 따라오지 않았던 항목이다. C-03 이 "수치는 데이터로 둔다" 이므로 실수치는 서버 설정(`app.minigame.*`) — 기본값 `±0.1s→5` · `±0.5s→2`, 한도 `50 Coin/일`
- [x] **T004** C-05 미완료 게임 처리 결정 → ✅ **2026-08-12 무보상 확정.** BE 는 미완료 세션에 아무것도 하지 않는다(정리 배치 없음)
- [x] **T005** C-06 점수 검증 수준 결정 (BE와) → ✅ **양방향 경과시간 검증으로 확정** (계약 §3). 상한만으로는 지연 제출을 못 막아 하한을 같이 걸었다

## Phase 1: 경계 구현 (게임 종류와 무관 — 지금도 가능)

- [x] **T006** `IGameResultClient` 인터페이스 + Mock 구현 — 결과 보고 경계 (Unity 구현체는 FE 이관 후 호출부가 없어져 `S15P21A604-624` 로 제거됨. 같은 경계를 지금은 FE `entities/minigame/api.ts` 가 맡는다)
- [x] **T007** 게임 세션 식별자 발급 (멱등성 키) → FR-004
- [x] **T008** BE와 결과 보고 계약 합의 → ✅ **GitLab #134 에서 합의 (2026-09-10)**, 정본은 `contracts/minigame-api.yaml`. 합의 형태는 이 항목이 적었던 `{sessionId, gameId, score, startedAt, endedAt}` 이 **아니다** — 클라이언트는 `stoppedSeconds` 하나만 보내고 오차·구간·보상은 서버가 계산한다(C-06). `gameId` 는 FR-009 가 1종으로 제한하므로 없앴다
- [x] **T009** 게임 실패·중단이 월드에 영향 없도록 분리 → FR-007

## Phase 2: 게임 구현

- [x] **T010** 게임 부스 진입·시작 처리 — 서버에서 목표 시간(2~4초) 발급받기
- [x] **T011** 타이머 UI + 정지 입력 처리
- [x] **T012** 오차 산출·표시 + 결과 보고 — 실서버 계약은 T008, 구현은 FE `S15P21A604-601` (`-294` 는 그것으로 대체되어 닫힘). 오차는 서버 값을 그대로 그린다 (C-06)
- [x] **T012a** 목표 시간 크게 초과 시 실패 종료 → FR-001d
- [x] **T013** 게임 UI — ✅ **React 오버레이로 확정 (2026-09-10, GitLab #166 · `S15P21A604-601`).** 화면이 FE 로 이관되면서 WebGL IMGUI 한글 미표시(T-22) 제약 자체가 없어졌고, 문구는 `festa-frontend/src/features/minigame/ui/resultCopy.ts` 가 서버 판정 필드로 만든다 — 영문/숫자 라벨 안은 폐기

## Phase 3: 검증

- [x] **T014** 같은 결과 2회 전송 → 1회만 지급되는지 → SC-002 — BE `MinigameRewardIntegrationTest.resubmittingReturnsTheFirstVerdictAndPaysNothingMore` + `MinigameRewardConcurrencyIntegrationTest.twoSimultaneousSubmissionsOfOneSessionPayOnce`
- [x] **T015** 일일 한도 초과 시 게임은 가능하되 미지급 + 안내 → SC-003 — BE `MinigameRewardIntegrationTest` 의 한도 4건(20·45·48·50) + `...ConcurrencyIntegrationTest.twoSessionsFinishingAtOnceCannotBetweenThemCrossTheDailyCap`
- [x] **T016** 조작된 점수 전송 시 서버가 거부하는지 → FR-008 — BE `MinigameTimerStopApiIntegrationTest.reportingTheTargetTimeTheMomentTheSessionIsIssuedIsRefused` (배포 설정값 그대로). **한계는 계약 §3 에 적었다** — `stoppedSeconds` 위변조 자체를 막는 anti-cheat 는 범위 밖이다
- [x] **T017** 게임 중단 후 월드 정상 이용 → SC-004

## Phase 4: 마무리

- [ ] **T018** [P] `architecture.md` 갱신
- [x] **T019** 작업일지·트러블슈팅 기록

---

## 의존 관계

```text
T001 ⛔ ─ T002~T005 ─────────────┐
                                 ├─ Phase 2 (게임 본체)
T006 ─ T007 ─ T008 ─ T009 ───────┘   ← 경계는 C-01 없이도 가능
                                     ← spec 003(wallet) 완료 필요
```

## 병렬 실행 예

- **Phase 1은 게임 종류가 정해지기 전에도 진행 가능** — 결과 보고 경계는 게임과 무관하다
- 다만 이것만 미리 만들 실익은 크지 않으므로, **1차 MVP를 먼저 하는 것이 맞다**

## 차단 요인

| 차단 | 대상 | 해소 조건 |
|---|---|---|
| spec 003 wallet-coin | Phase 3 검증 | BE 완료 |
| 1차 MVP 안정화 | 전체 | 헌법 19조 — 2차 항목 |

## 범위 경고

이 spec은 **재미있어서 범위가 커지기 쉬운** 영역이다.
헌법 19조에 따라 **1종·단순**을 유지한다. 게임을 늘리고 싶으면 P0/P1이 전부 끝난 뒤에 판단한다.
마피아 게임(GAME-05)은 별도 시스템 규모이므로 spec 014에 끼워넣지 않는다.
