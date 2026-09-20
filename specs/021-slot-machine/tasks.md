# Tasks: 광장 슬롯머신 확률·연속 낙첨 보장

**Input**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/slot-machine-api.md`

## Phase 1: Setup

- [X] T001 확정 확률·보장 정책을 `specs/021-slot-machine/spec.md`와 `contracts/slot-machine-api.md`에 반영한다.
- [X] T002 구현 선택과 원장 기반 동시성 근거를 `specs/021-slot-machine/{plan,research,data-model,quickstart}.md`에 기록한다.

## Phase 2: Foundational

- [X] T003 최근 슬롯 베팅의 지급 여부를 최대 10건 조회하는 repository 메서드를 `backend/src/main/java/com/example/ssafesta/wallet/CoinLedgerEntryRepository.java`에 추가한다.
- [X] T004 지갑 잠금 뒤 pity 판정을 수행하도록 `backend/src/main/java/com/example/ssafesta/minigame/SlotMachineService.java`의 정산 순서를 고친다.

## Phase 3: User Story 1 — 확정 확률로 스핀한다 (Priority: P1)

**Goal**: tier 0..3 계약은 유지한 채 새 확률과 ×10 지급을 서버가 정산한다.

**Independent Test**: 설정 기반 분포·RTP·tier별 배수가 새 표와 일치하고 기존 API 스핀이 원장 불변식을 지킨다.

- [X] T005 [P] [US1] 새 확률·RTP 0.52·tier3 ×10 단위 테스트를 `backend/src/test/java/com/example/ssafesta/minigame/SlotMachineOddsTest.java`에 먼저 작성한다.
- [X] T006 [US1] 확률 설정과 검증 주석을 `backend/src/main/resources/application.yml` 및 `backend/src/main/java/com/example/ssafesta/minigame/SlotMachineProperties.java`에 반영한다.
- [X] T007 [US1] `backend/src/test/java/com/example/ssafesta/minigame/SlotMachineSpinApiIntegrationTest.java`에서 API payout·원장 불변식을 새 tier 표로 검증한다.

## Phase 4: User Story 2 — 10연속 낙첨 후 보장 당첨을 받는다 (Priority: P1)

**Goal**: 기존 원장만으로 10연속 tier0 뒤 다음 회차를 tier1 ×2로 강제한다.

**Independent Test**: 지급 없는 `SLOT_BET` 10개를 만든 뒤 다음 API 스핀이 tier1/20 Coin이고, 지급 원장이 연속 구간을 끊는다.

- [X] T008 [US2] 10회 임계값과 tier1 강제 결과를 `backend/src/test/java/com/example/ssafesta/minigame/SlotMachineOddsTest.java`에 먼저 작성한다.
- [X] T009 [US2] 원장 조회·지갑 잠금·강제 tier 판정을 `backend/src/main/java/com/example/ssafesta/minigame/SlotMachineService.java`에 구현한다.
- [X] T010 [US2] 실제 원장 10연속 낙첨과 다음 API 보장 지급을 `backend/src/test/java/com/example/ssafesta/minigame/SlotMachineSpinApiIntegrationTest.java`에 검증한다.

## Phase 5: Polish & validation

- [X] T011 `backend/src/test/java/com/example/ssafesta/minigame/SlotMachineOddsTest.java`와 `SlotMachineSpinApiIntegrationTest.java`를 실행한다.
- [X] T012 `backend`의 `mvnw.cmd -B clean test` 전체 회귀를 실행한다.
- [X] T013 작업 내용과 검증 결과를 `docs/24_작업일지.md`에, 발생한 문제는 `docs/25_트러블슈팅.md`에 기록한다.

## Dependencies & Execution Order

`T001–T002 → T003–T004 → T005–T007 → T008–T010 → T011–T013` 순서다. 확률 정책과 pity 판정은 같은
서비스 파일을 수정하므로 순차 처리한다. 문서 작업과 테스트 초안은 코드 변경 전 병렬 준비가 가능하다.

## Implementation Strategy

새 확률의 산술·계약부터 고정하고, 이어 원장 기반 pity를 지갑 잠금 범위에 넣는다. 마지막에 focused test와
전체 Maven 회귀를 통과시킨다.
