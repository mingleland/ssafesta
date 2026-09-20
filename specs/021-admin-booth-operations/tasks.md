# Tasks: 관리자 부스 운영 권한

**Input**: [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [contract](contracts/admin-booth-access.md)

## Dependencies

```text
US1 관리자 타 부스 운영 ──┬── US2 마스터 부스 보호
                          └── US3 기존 역할 회귀
```

## Phase 1: Setup

- [x] T001 Verify the active feature path in `.specify/feature.json` points to `specs/021-admin-booth-operations`

## Phase 2: Foundational Authorization Boundary

- [x] T002 Add read and modification authorization boundaries plus Admin dependency to `backend/src/main/java/com/example/ssafesta/booth/BoothAccessGuard.java`
- [x] T003 Add `BOOTH_EDIT` and `BOOTH` audit vocabulary to `backend/src/main/java/com/example/ssafesta/user/AdminActionRecorder.java`

## Phase 3: User Story 1 — 관리자가 일반 부스를 운영한다 (P1)

**Goal**: 전역 관리자가 기존 부스 운영 경로로 타 부스의 콘텐츠를 안전하게 수정한다.

**Independent Test**: 전역 관리자가 타 회원 부스의 홈페이지·레이아웃 변경을 수행하고 `BOOTH_EDIT` 감사 행을 남긴다.

- [x] T004 [US1] Route layout save and publish through modification authorization in `backend/src/main/java/com/example/ssafesta/booth/BoothLayoutService.java`
- [x] T005 [US1] Ensure active editor changes use modification authorization in `backend/src/main/java/com/example/ssafesta/booth/BoothAccessGuard.java`
- [x] T006 [US1] Add administrator cross-booth homepage and layout integration coverage in `backend/src/test/java/com/example/ssafesta/booth/AdminBoothAccessIntegrationTest.java`

## Phase 4: User Story 2 — 마스터 계정의 부스는 보호한다 (P1)

**Goal**: 관리자는 마스터 소유 부스를 읽을 수 있어도 변경하거나 게시할 수 없다.

**Independent Test**: 관리자의 마스터 부스 홈페이지 변경·레이아웃 게시가 `MASTER_PROTECTED`로 거부되고 마스터 본인은 성공한다.

- [x] T007 [US2] Add master-owned booth change and self-owner regression coverage in `backend/src/test/java/com/example/ssafesta/booth/AdminBoothAccessIntegrationTest.java`

## Phase 5: User Story 3 — 기존 부스 권한이 바뀌지 않는다 (P2)

**Goal**: 기존 Owner·직원 역할·일반 회원 판정이 전역 Admin 예외로 넓어지지 않는다.

**Independent Test**: Owner·편집 직원은 통과하고 CONSULTANT·일반 회원·강등된 관리자는 기존과 동일하게 거부된다.

- [x] T008 [US3] Verify existing role regression coverage and add post-demotion coverage in `backend/src/test/java/com/example/ssafesta/booth/AdminBoothAccessIntegrationTest.java`

## Phase 6: Verification and Documentation

- [x] T009 Run targeted Maven tests from `backend/` and record their result in `specs/021-admin-booth-operations/quickstart.md`
- [x] T010 Update `docs/24_작업일지.md` with the completed 742 first-slice record and link any troubleshooting entry if one occurs

## Implementation Strategy

1. Complete T002–T006 for the first demonstrable administrator cross-booth operation.
2. Complete T007 before treating the slice as safe; master protection cannot be deferred once admin access exists.
3. Complete T008–T010, then split 742’s force-reclaim, reports/moderation, events, and observability blocks into follow-up Jira work.
