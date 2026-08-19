# Tasks: 로그인 · 회원가입 · 회원관리

## Phase 1: Foundation

- [X] T001 Add authentication properties and secret-safe environment bindings in `backend/src/main/resources/application-local.yml`
- [X] T002 Add account lifecycle and audit Flyway migration in `backend/src/main/resources/db/migration/V2__auth_account_lifecycle.sql`
- [ ] T003 Create shared API error response and security configuration in `backend/src/main/java/com/example/ssafesta/common/` and `backend/src/main/java/com/example/ssafesta/auth/`

## Phase 2: User Story 1 — Social sign-up and login

**Goal**: Google/Kakao callback creates or resumes a member after nickname completion.

- [ ] T004 [US1] Create user, OAuth identity and registration-attempt entities/repositories in `backend/src/main/java/com/example/ssafesta/user/`
- [ ] T005 [US1] Implement OAuth state validation, provider identity lookup and nickname policy in `backend/src/main/java/com/example/ssafesta/auth/`
- [ ] T006 [US1] Implement unified OAuth callback handoff cookie and `POST /api/v1/auth/oauth/complete` (existing member token issue / `NICKNAME_REQUIRED` / nickname completion) plus profile endpoints in `backend/src/main/java/com/example/ssafesta/auth/` and `backend/src/main/java/com/example/ssafesta/user/`
- [ ] T007 [US1] Add unit/integration tests for duplicate identity, incomplete signup and nickname blocking in `backend/src/test/java/com/example/ssafesta/`

## Phase 3: User Story 2/3 — Guest and sessions

- [ ] T008 [US2] Implement guest Access Token issuance and member/guest authorization in `backend/src/main/java/com/example/ssafesta/auth/`
- [ ] T009 [US3] Implement JWT Access Token, Redis Refresh Token rotation and one-active-browser-session policy in `backend/src/main/java/com/example/ssafesta/auth/`
- [ ] T010 [US3] Implement logout and revoked-session handling in `backend/src/main/java/com/example/ssafesta/auth/`
- [ ] T011 [US2] [US3] Add authentication and session tests in `backend/src/test/java/com/example/ssafesta/auth/`

## Phase 4: User Story 4/5 — Account lifecycle and administration

- [ ] T012 [US4] Implement immediate withdrawal, Booth/content unpublish, and session/world revocation in `backend/src/main/java/com/example/ssafesta/user/`
- [ ] T012a [US4] Design and implement idempotent immediate hard-delete Workflow for DB dependents, Redis, file storage, Vector data and Google/Kakao unlink/revoke in `backend/src/main/java/com/example/ssafesta/user/`
- [ ] T013 [US5] **Deferred — 관리자 기능 추후 작업**: admin role/권한 모델 확정 뒤 수동 정지·정지 해제·상태 감사 endpoint를 `backend/src/main/java/com/example/ssafesta/admin/`에 구현한다.
- [ ] T014 [US4] [US5] **Deferred — 관리자 기능 추후 작업**: lifecycle 및 관리자 권한·감사 테스트를 `backend/src/test/java/com/example/ssafesta/`에 추가한다.

## Phase 5: World integration and validation

- [ ] T015 [US6] Define world disconnect/channel-capacity contract in `specs/002-world-session/` and implement backend producer in `backend/src/main/java/com/example/ssafesta/world/`
- [ ] T016 Run `backend/mvnw test`, validate OAuth local callback flow, and update `docs/HDD/작업일지.md`

## Dependencies

T001–T003 block all user stories. T004–T007 precede T008–T014. T015 requires the spec 002 consumer contract and is not implemented by changing frozen Unity baseline files.
