# Implementation Plan: FESTA Game Studio

**Branch**: `019-game-studio` *(feature context; current integration branch is `game`)* | **Date**: 2026-08-20 | **Spec**: [spec.md](./spec.md)

**Input**: Unity와 분리된 웹 2D Game Studio/Runtime, Spring Draft·Publish, 선택적 Booth Portal 연동.

> 이 계획은 GitHub #20~#22 답변을 선점하지 않는다. 계약·상태 머신·fixture 작업은 즉시 가능하고,
> 앱 생성·DB migration·Unity Bridge 변경은 각 이슈 결정 뒤 시작한다.

## Summary

Game Studio를 기존 Unity WebGL의 하위 게임으로 만들지 않고 웹 제작기와 웹 2D Runtime으로 구현한다.
Studio와 Runtime은 같은 versioned GameProject 계약과 순수 TypeScript 상태 전이 코어를 공유한다.
Spring은 Draft, 불변 Published Version, Portal Binding의 Source of Truth다. Unity는 기존 부스 상호작용을
React Host에 전달하는 선택적 진입점만 맡는다. 첫 수직 범위는 TOP_DOWN 탐색과 DIALOGUE 그래프이며,
PLATFORMER는 공통 상태·Event 계약을 검증한 뒤 별도 renderer/physics adapter로 추가한다.

## Technical Context

**Language/Version**: Frontend TypeScript `~6.0.2`; Backend Java 21; 계약 JSON Schema draft 2020-12

**Primary Dependencies**: 현재 `origin/front` 기준 React 19.2, Vite 8.2, React Router 7.18,
TanStack Query 5.101; 현재 `origin/back` 기준 Spring Boot 4.1, Spring MVC/JPA/Security,
Flyway, PostgreSQL. 2D renderer/physics library는 공통 코어의 선행 의존성이 아니며 #20에서 선택한다.

**Storage**: Spring/PostgreSQL. Game root와 Portal Binding은 관계형 컬럼, GameProject Version은 JSONB 후보.
정확한 Draft 행·버전 보존·cache 정책은 #21 결정 게이트다.

**Testing**: 현재 계약은 Node 무의존 reference validator와 fixtures. Frontend는 #20에서 기존 앱과
테스트 도구를 맞춘다. Backend는 JUnit/Spring integration test/Testcontainers 패턴을 재사용한다.

**Target Platform**: 데스크톱 최신 브라우저 제작, 데스크톱·모바일 브라우저 플레이. Unity WebGL은
선택적 Host sibling이며 Runtime target이 아니다.

**Project Type**: 독립 웹 앱 + Spring API + versioned cross-part contract

**Performance Goals**: TOP_DOWN Runtime 60fps 목표, 일반 편집 동작 100ms 이내 반응,
최대 100×100 tile·500 object·300 event 프로젝트가 Preview에서 중단 없이 열린다.

**Constraints**: 사용자 임의 코드 실행 금지, AI 비의존, Published 불변, Draft 충돌 무음 덮어쓰기 금지,
Unity/Game Runtime 장애 격리, Booth Layout과 GameProject 분리.

**Scale/Scope**: MVP Scene 유형 2개(TOP_DOWN, DIALOGUE), 프로젝트당 Scene 50개 이하,
변수·아이템 각 100개 이하, Asset reference 300개 이하. PLATFORMER는 후속 P2.

## Constitution Check

*GATE: Phase 0 전 및 Phase 1 후 재검토 — 통과.*

| 조항 | 판정 | 계획 반영 |
|---|---|---|
| 1 Source of Truth | PASS | Draft/Publish/Portal 영구 상태는 Spring만 소유 |
| 2 실시간/영구 분리 | PASS | Unity Dedicated Server는 GameProject 실행·저장을 하지 않음 |
| 3 AI 장애 격리 | PASS | MVP Runtime/Studio는 FastAPI 호출 없음 |
| 4 데이터 기반 콘텐츠 | PASS | GameProject JSON으로 제작·실행, 콘텐츠 추가에 Unity 빌드 불필요 |
| 11~16 인증·불신 | PASS | Guest는 play only, client 완료 주장은 보상 근거가 아님 |
| 24 계약 변경 | PASS | Schema version + fixture 소비자 검증 |
| 25 React UI | PASS | 제작·대화·외부 화면은 Web UI 소유 |
| 27 기준선 동결 | PASS | 현재 Unity Booth Runtime/014 Minigame 코드를 변경하지 않음 |
| 28 범위 통제 | PASS | 사용자 지시로 추가된 P2 UGC이며 기존 Unity 미니게임 종류를 늘리지 않음 |
| 30 미정 임의 확정 금지 | PASS | #20~#22 결정 게이트를 tasks에 명시 |

## Project Structure

### Documentation

```text
specs/019-game-studio/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── game-project-v1.schema.json
│   ├── game-api.md
│   ├── game-portal-bridge.md
│   ├── part-boundaries.md
│   └── fixtures/
└── tasks.md
```

### Source Code

```text
# #20 승인 후 생성할 독립 Frontend 경계(제안)
festa-game-studio/
├── src/
│   ├── app/                 # routes, providers, auth handoff
│   ├── contracts/           # generated/handwritten TS contract adapter
│   ├── core/                # pure state, event interpreter, validation
│   ├── studio/              # map/object/dialogue/event editors
│   ├── runtime/             # scene lifecycle + renderer adapters
│   ├── preview/             # Studio ↔ iframe Runtime protocol
│   └── host/                # standalone/FESTA overlay entry adapters
└── tests/
    ├── contract/
    ├── unit/
    └── integration/

# origin/back의 기존 Spring 앱에 #21 승인 후 추가
backend/src/main/java/com/example/ssafesta/game/
├── api/
├── application/
├── domain/
└── persistence/

backend/src/test/java/com/example/ssafesta/game/
backend/src/main/resources/db/migration/

# 기존 festa-frontend에는 #20 승인 후 Host adapter만 추가
festa-frontend/src/game-studio/
└── gameStudioHost.ts
```

**Structure Decision**: Game Studio 본체는 기존 FESTA 화면과 분리하고 Host adapter만 기존 Frontend에 둔다.
루트 앱 이름과 배포 단위는 #20 승인 대상이므로, 승인 전에는 `specs/019-game-studio/contracts/`의
framework-independent 코드와 fixture만 변경한다.

## Design Phases

### Phase A — 즉시 가능: 계약·순수 코어

1. Schema positive/negative fixture를 확장한다.
2. ID 참조, Component 중복, Scene 전환, Event budget을 검증하는 reference validator를 완성한다.
3. Runtime state와 Trigger→Condition→Action 순서를 데이터 모델로 고정한다.
4. Preview/Portal 메시지의 보안 불변식과 오류 격리 시나리오를 문서화한다.

### Phase B — #20 이후: Web Studio/Runtime

1. 승인된 앱 경계와 테스트 도구로 프로젝트를 생성한다.
2. 계약 adapter와 순수 state/event core를 이식한다.
3. TOP_DOWN renderer, DIALOGUE runner, Preview를 차례로 구현한다.
4. 독립 URL을 먼저 검증한 뒤 FESTA Host/Unity Portal을 연결한다.

### Phase C — #21 이후: Spring Draft/Publish

1. 확정 Aggregate/migration을 추가한다.
2. Draft revision과 server validation을 구현한다.
3. immutable Publish/runtime query/Portal resolution을 구현한다.
4. 권한·충돌·불변성 integration test를 추가한다.

### Phase D — 후속 P2

PLATFORMER adapter, 사용자 Asset upload, AI 제작 보조, 결과·보상·랭킹은 각각 별도 결정과 spec을 거친다.

## Post-Design Constitution Check

Phase 1 산출물은 Spring 영구 상태 소유, Unity 비실행, AI 비의존, 제한형 Event/Component,
Published 불변 원칙을 모두 유지한다. 즉시 가능한 계약 작업은 파트 결정을 선점하지 않는다. **PASS**.

## Complexity Tracking

위반 없음. 별도 웹 앱은 Unity와 한 Runtime으로 합치는 것보다 실행·장애·배포 경계를 단순화하며,
공통 계약과 순수 코어를 Studio/Preview/Published Runtime이 공유해 중복을 제한한다.
