# Implementation Plan: 관리자 부스 운영 권한

**Branch**: `feat/S15P21A604-742-admin-operations` | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md)

## Summary

S15P21A604-742의 첫 슬라이스로, 기존 부스 공통 접근 게이트가 전역 Admin을 타 부스 운영자로 허용하도록 확장한다. 조회와 변경 권한을 분리하여 관리자는 마스터 소유 부스를 읽을 수 있지만 변경·게시할 수 없고, 관리자 자격으로 한 타 부스 변경은 트랜잭션 안에서 감사한다.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot, Spring Security OAuth2 Resource Server, Spring Data JPA

**Storage**: PostgreSQL (`users`, `booths`, `booth_staffs`, `admin_actions`)

**Testing**: Maven Wrapper, JUnit 5, Spring Boot integration tests, Testcontainers

**Target Platform**: Spring Boot API server

**Project Type**: Web service

**Performance Goals**: 부스 운영 권한 판정은 기존 부스·직원 조회에 전역 Admin 판정 1회만 추가한다.

**Constraints**: 기존 API URL·payload·오류 계약을 유지하고, Admin 권한은 JWT claim이 아닌 DB의 현재 회원 상태로 판정한다.

**Scale/Scope**: 공통 게이트가 보호하는 기존 부스 운영 경로 10여 개; 742의 강제 회수·신고·이벤트·전체 집계는 제외한다.

## Constitution Check

| 조항 | 판정 | 근거 |
|---|---|---|
| I-1 Source of Truth | PASS | 영구 권한·부스 상태는 Spring과 PostgreSQL만 사용한다. |
| III-12 Guest persistence | PASS | 게스트에 새 영구 데이터·권한을 주지 않는다. |
| III-16 Client distrust | PASS | 부스 ID 외 권한 주장을 받지 않고 서버에서 Owner·직원·Admin을 판정한다. |
| IV-20 Ledger | PASS | 코인 변경을 만들지 않는다. |
| V-24 Contract procedure | PASS | 기존 API 모양은 유지하고 권한 결과 계약을 문서화한다. |
| VII-27 Baseline freeze | PASS | 동결 Unity 코드를 변경하지 않는다. |
| VII-30 Undecided decisions | PASS | 742의 후속 강제 조치·신고·이벤트 범위를 임의 구현하지 않는다. |

## Project Structure

```text
backend/
├── src/main/java/com/example/ssafesta/
│   ├── booth/BoothAccessGuard.java
│   └── user/AdminActionRecorder.java
└── src/test/java/com/example/ssafesta/booth/
    ├── BoothAccessGuardRoleTest.java
    └── AdminBoothAccessIntegrationTest.java

specs/021-admin-booth-operations/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/admin-booth-access.md
├── quickstart.md
└── tasks.md
```

**Structure Decision**: 기존 Spring 부스 접근 게이트와 관리자 감사 컴포넌트를 확장한다. 신규 Controller·DB 마이그레이션·클라이언트 화면은 만들지 않는다.

## Implementation Approach

1. `BoothAccessGuard`에 읽기용 전역 Admin 예외와 변경용 권한 메서드를 추가한다.
2. 활성 임대가 필요한 기존 변경 경로는 변경용 게이트를 사용하도록 내부 연결을 바꾼다. 레이아웃 저장·게시의 직접 호출도 변경용 게이트로 옮긴다.
3. 관리자로서 타 부스를 변경할 때에만 `BOOTH_EDIT` 감사를 기록한다.
4. Admin 타 부스 성공, 마스터 보호, 강등 즉시 반영, 기존 직원 역할 회귀를 통합 테스트로 고정한다.
