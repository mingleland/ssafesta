# Tasks: ERD 초기 스키마 적용

## Phase 1: Schema Foundation

- [x] T001 Create `backend/src/main/resources/db/migration/V1__initial_schema.sql` with the approved persistent ERD.
- [x] T002 Configure `backend/compose.yaml` to run a pgvector-capable PostgreSQL image.
- [x] T003 Configure `backend/src/test/java/com/example/ssafesta/TestcontainersConfiguration.java` with the same pgvector-capable image.

## Phase 2: Validation

- [x] T004 Run `backend/mvnw test` to validate Flyway migration through Testcontainers.
- [x] T005 Verify the migration history, vector extension, and absence of the Refresh Token table.

## Dependencies

T004 depends on T001-T003. T005 depends on T004.
