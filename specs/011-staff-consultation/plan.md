# Implementation Plan: 직원 / 사람 상담

**Branch**: `011-staff-consultation` | **Date**: 2026-09-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-staff-consultation/spec.md`

## Summary

부스 Owner가 직원을 초대해 역할을 주고(US3), 방문자가 AI 대화 중 사람 상담을 요청하면 직원 한 명이 이어받는다(US1). 담당자가 없어도 AI 상담은 계속 동작한다(US2).

기술 접근은 **이미 정해진 것을 구현하는 쪽**이다. spec의 Clarification 14건이 전부 닫혀 있고, REST·STOMP 계약은 2026-09-07 BE 회신으로 확정돼 FE가 `S15P21A604-519`로 선반영까지 마쳤다. DB도 `booth_staffs`·`staff_invitations`·`consultations`·`consultation_messages` 네 테이블이 **V1부터 존재한다**. 남은 것은 ⑴ 확정 계약대로 REST·STOMP를 여는 일, ⑵ 동시성 두 건(요청은 한 명만 수락 / 직원당 활성 상담 1건)을 DB가 강제하게 만드는 일, ⑶ 만료 두 건(요청 10분 / 초대 48시간)이다.

**AI 요약은 P1을 막지 않는다.** 확정 계약이 `handoffSummary: string | null`이고 요약 생성 실패도 `null`일 뿐 요청은 진행된다. `S15P21A604-139`(AI Handoff Summary)가 진행 중이라, **요약 없는 인계를 먼저 열고 `-139` 도착 후 조달 경로만 연결한다.**

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.0 (webmvc, data-jpa, security, oauth2), Flyway, **spring-boot-starter-websocket 신규 추가**

**Storage**: PostgreSQL (Flyway, develop 최신 `V30`) + Redis (WS Token — 기존 Refresh Token 경로와 같은 저장소)

**Testing**: JUnit 5 + Spring Boot Test + Testcontainers (기존 스위트 1195건)

**Target Platform**: Linux 서버 (Docker)

**Project Type**: 웹 서비스 백엔드 — 소비자는 React FE

**Performance Goals**: 대기열 조회·수락은 기존 REST 예산과 동일. STOMP는 P1에서 서버→클라이언트 단방향 알림 전용이라 메시지 처리량 목표가 없다

**Constraints**: STOMP 브로커는 **in-memory simple broker** — 단일 인스턴스 전제다. 스케일아웃 시 브로커 릴레이가 필요하며 P1 범위 밖이다

**Scale/Scope**: 부스 12개 상한, 직원 한 명당 활성 상담 1건. 대기열은 부스 단위

## Constitution Check

*GATE: Phase 0 전 통과, Phase 1 설계 후 재확인.*

| 조 | 요구 | 이 설계에서 |
|---|---|---|
| 1조 Source of Truth | Staff는 Spring이 유일 기준 | ✅ 상담방·Presence·연결 전부 Spring. Unity·React 복제 없음 |
| 3조 AI 장애 격리 | FastAPI 장애가 비AI 기능을 막지 않음 | ✅ 요약 실패는 `null`로 흘리고 요청은 진행. Spring이 요약을 동기 중계하지 않는다 |
| 12조 게스트 | 게스트 영속화·특권 금지 | ✅ FR-014 — 게스트는 상담 요청 불가, 소셜 로그인 안내 |
| 13조 토큰 4계층 | WS는 별도 토큰 | ✅ 전용 단기 WS Token(5분). Access Token 재사용·Cookie handshake·URL query 전달 금지 |
| 16조 클라이언트 불신 | 서버가 검증 | ✅ 수락 가능 여부·활성 상담 수를 서버가 판정. FE 게이트는 표시용 |
| 20조 원장 | 경제 상태는 REST/DB 트랜잭션 | N/A — P1 상담에 코인 이동이 없다 |
| 24조 계약 변경 | Consumer 통보 없는 Breaking 금지 | ⚠️ **이 plan의 핵심 쟁점.** research R-01 참조 — `docs/08` §12를 확정 계약에 맞춘다 |
| 25조 UI | 텍스트 입력은 React | ✅ 상담 화면은 FE 오버레이. Unity 영향 없음 |
| 28조 범위 통제 | P1/P2 컷라인 준수 | ✅ 실시간 메시지 송수신·저장은 P2(C-12). 비동기 문의도 P2(C-02) |
| 30조 미정 임의확정 금지 | | ✅ 미정 없음 — Clarification 14건이 전부 닫혀 있다 |

**결과**: 통과. 24조는 위반이 아니라 **드리프트 정정**이다 — 두 문서가 서로 다른 경로를 적고 있고, 소비자가 이미 쓰는 쪽으로 맞춘다(R-01).

**Phase 1 설계 후 재확인 (2026-09-13)**: 통과. 설계가 더한 것은 컬럼 둘·제약 하나·STOMP 구성뿐이라 새 위반이 생기지 않았다. 다만 설계 중에 **기존 코드의 위반 가능성**이 하나 드러났다 — `BoothAccessGuard.requireEditor` 가 역할을 보지 않아, 직원 행이 생기는 순간 `CONSULTANT` 가 FR-002·C-09를 넘는다. 이것은 이 plan이 만드는 위반이 아니라 **이 plan이 먼저 닫아야 하는 선행 작업**이며 `tasks.md` 의 첫 묶음에 둔다([data-model.md](./data-model.md)).

## Project Structure

### Documentation (this feature)

```text
specs/011-staff-consultation/
├── plan.md              # 이 파일
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── staff-consultation-api.md   # Phase 1
└── tasks.md             # /speckit-tasks 산출물
```

### Source Code (repository root)

```text
backend/src/main/java/com/example/ssafesta/
├── booth/                       # 기존 — BoothStaff·BoothStaffRepository·BoothAccessGuard
│   └── (BoothAccessGuard 에 역할 어휘 연결)
├── staff/                       # 신규 — 초대·역할·Presence
│   ├── StaffInvitation.java
│   ├── StaffInvitationRepository.java
│   ├── StaffInvitationService.java
│   ├── StaffInvitationController.java
│   ├── StaffPresenceService.java
│   ├── StaffController.java
│   └── StaffInvitationExpirySweeper.java
└── consultation/                # 신규 — 요청·수락·종료·알림·WS Token
    ├── Consultation.java
    ├── ConsultationRepository.java
    ├── ConsultationService.java
    ├── ConsultationController.java
    ├── ConsultationExpirySweeper.java
    ├── HandoffSummaryClient.java        # FastAPI 호출 — -139 도착 전에는 null 반환 구현
    └── ws/
        ├── WebSocketConfig.java         # STOMP 엔드포인트·브로커 등록
        ├── WsTokenService.java          # Redis 저장 5분 토큰
        ├── WsTokenController.java
        ├── StompAuthChannelInterceptor.java
        └── ConsultationEventPublisher.java

backend/src/main/resources/db/migration/
└── V31__staff_consultation_p1.sql

backend/src/test/java/com/example/ssafesta/
├── staff/
└── consultation/
```

**Structure Decision**: 기존 패키지 관례(도메인별 최상위 패키지, `internal/` 은 내부 API 전용)를 따른다. `staff` 와 `consultation` 을 나누는 이유는 두 축이 서로 다른 티켓(`S15P21A604-136` / `-137`)이고 **직원 초대는 상담 없이도 독립적으로 완결**되기 때문이다 — US3만 먼저 배포할 수 있다. `booth/BoothStaff` 는 spec 005가 읽기 전용으로 쓰고 있어 그 자리에 둔다(엔티티 javadoc이 "011이 행을 만든다"를 이미 적어 뒀다).

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없다.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
