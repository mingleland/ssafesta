# Implementation Plan: 로그인 · 회원가입 · 회원관리

**Branch**: `feature/auth-user` | **Date**: 2026-08-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `$speckit-plan` command; its definition describes the execution workflow.

## Summary

Google/Kakao/SSAFY 소셜 로그인, 비영속 게스트, 단일 브라우저 세션, 탈퇴 확정 즉시 관련 데이터 전체 hard delete Workflow, 닉네임 정책을 구현한다. OAuth callback은 기존·신규 사용자를 단일 React `/auth/callback`으로 보내며, 1회성 HttpOnly handoff cookie와 단일 완료 API로 토큰 발급·닉네임 보완을 처리한다. PostgreSQL·Redis·파일·Vector·OAuth 삭제를 재실행 가능하게 조율하고 User row는 마지막에 삭제한다.

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1, Spring Security OAuth2 Client, JPA, Redis, Flyway

**Storage**: PostgreSQL 17, Redis 7.2

**Testing**: JUnit 5, Spring Security Test, Testcontainers

**Target Platform**: Docker Spring API, React WebGL client, Unity world consumer

**Project Type**: Web API

**Performance Goals**: 채널당 최대 40명

**Constraints**: Refresh Token은 Unity·게임 서버에 전달 금지, 게스트 비영속, secret 커밋 금지

**Scale/Scope**: Google/Kakao/SSAFY/Guest, 단일 활성 브라우저 세션, 계정·관리자 API

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

PASS — 영속 상태는 Spring이 소유하고, Unity에는 짧은 1회용 접속 권한만 전달한다. 자체 비밀번호 인증과 게스트 영속화를 만들지 않는다.

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file ($speckit-plan command output)
├── research.md          # Phase 0 output ($speckit-plan command)
├── data-model.md        # Phase 1 output ($speckit-plan command)
├── quickstart.md        # Phase 1 output ($speckit-plan command)
├── contracts/           # Phase 1 output ($speckit-plan command)
└── tasks.md             # Phase 2 output ($speckit-tasks command - NOT created by $speckit-plan)
```

OAuth consumer contract: `contracts/oauth-completion.md`.

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
# [REMOVE IF UNUSED] Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# [REMOVE IF UNUSED] Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# [REMOVE IF UNUSED] Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure: feature modules, UI flows, platform tests]
```

**Structure Decision**: [Document the selected structure and reference the real
directories captured above]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
