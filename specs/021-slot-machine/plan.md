# Implementation Plan: 광장 슬롯머신 확률·연속 낙첨 보장

**Branch**: `feat/S15P21A604-635-slot-pity` | **Date**: 2026-09-17 | **Spec**: [spec.md](spec.md)

**Input**: `S15P21A604-635`, GitLab #235 확정값

## Summary

확률표를 tier0 78.1%, tier1 ×2 20%, tier2 ×3 1%, tier3 ×10 0.9%로 바꾼다. 최근 원장에서 지급 없는
슬롯 베팅이 10개 연속이면 다음 스핀을 tier1로 확정한다. 상태는 원장에만 두고, 조회부터 정산까지
회원 지갑 잠금으로 직렬화한다.

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot, Spring Data JPA, PostgreSQL/Flyway

**Storage**: PostgreSQL `coin_ledger_entries`, `wallets` (기존 테이블만 사용)

**Testing**: JUnit 5, Spring Boot integration test, Testcontainers PostgreSQL

**Target Platform**: Dockerized Spring backend

**Project Type**: REST web service

**Performance Goals**: 최근 최대 10개 슬롯 원장만 조회하고 지갑 잠금 범위에서 단일 스핀을 정산한다.

**Constraints**: tier는 0..3 유지, 새 API/DB pity 상태 없음, 결제·지급은 단일 DB 트랜잭션과 기존 원장
멱등키를 사용한다.

**Scale/Scope**: 슬롯 확률 설정, 원장 조회, 서비스 정산, 단위·통합 테스트 및 계약 문서.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Result | Evidence |
|---|---|---|
| 영구 경제 상태는 Spring/DB만 권위 | PASS | 지급·차감과 pity 판정은 Spring 트랜잭션 및 기존 원장 사용 |
| 경제 변동은 원장·멱등성으로 기록 | PASS | `SLOT_BET`/`SLOT_PAYOUT`과 기존 idempotency key를 유지 |
| 클라이언트 주장 불신 | PASS | tier·확률·보장은 서버 설정/원장으로만 판정 |
| 파트 간 API 계약 보존 | PASS | 엔드포인트·응답 tier 범위는 불변, tier 3 배수만 ×10으로 변경 |
| 미정 정책 임의 확정 금지 | PASS | 확률과 10회/×2 보장은 사용자 승인값 |

## Project Structure

### Documentation (this feature)

```text
specs/021-slot-machine/
├── plan.md              # This file ($speckit-plan command output)
├── research.md          # Phase 0 output ($speckit-plan command)
├── data-model.md        # Phase 1 output ($speckit-plan command)
├── quickstart.md        # Phase 1 output ($speckit-plan command)
├── contracts/           # Phase 1 output ($speckit-plan command)
└── tasks.md             # Phase 2 output ($speckit-tasks command - NOT created by $speckit-plan)
```

### Source Code (repository root)

```text
backend/
├── src/
│   ├── main/java/com/example/ssafesta/minigame/
│   ├── main/java/com/example/ssafesta/wallet/
│   └── main/resources/application.yml
└── src/test/java/com/example/ssafesta/minigame/
```

**Structure Decision**: 슬롯 정책과 정산은 `minigame`에, 원장 사실 조회는 `wallet` repository에 둔다.
원장 테이블은 이미 경제 사실의 정본이므로 별도 Pity 엔티티·마이그레이션을 만들지 않는다.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 해당 없음 | — | — |
