# Implementation Plan: ERD 초기 스키마 적용

**Branch**: `feature/erd-schema` | **Date**: 2026-08-18 | **Spec**: [spec.md](spec.md)

## Summary

ERD 29개 영구 엔티티를 Flyway V1 migration으로 생성하고, RAG vector 컬럼을 지원하도록 로컬·테스트 PostgreSQL 이미지를 pgvector 이미지로 통일한다.

## Technical Context

**Language/Version**: Java 21 / Spring Boot 4.1  
**Primary Dependencies**: Flyway, Spring Data JPA, PostgreSQL, Testcontainers  
**Storage**: PostgreSQL + pgvector, Redis(Refresh Token·Presence 등 단기 상태)  
**Testing**: Maven + Testcontainers PostgreSQL/Redis  
**Target Platform**: Docker 기반 Spring 서비스

## Constitution Check

- 영구 상태는 Spring/PostgreSQL에 둔다: 통과.
- Refresh Token과 게스트는 비영속으로 둔다: 통과.
- RAG Chunk는 booth_id + agent_id와 vector(1536)를 보관한다: 통과.
- 코인 변경은 wallet + ledger와 트랜잭션 경계로 관리한다: 통과.
- 미정인 world_floors, P2 Event/Competition 테이블은 생성하지 않는다: 통과.

## Project Structure

```text
backend/
├── compose.yaml
├── src/main/resources/db/migration/V1__initial_schema.sql
└── src/test/java/com/example/ssafesta/TestcontainersConfiguration.java
specs/020-erd-schema/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
└── tasks.md
```

## Complexity Tracking

해당 없음.
