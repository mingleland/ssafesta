# Research: ERD 초기 스키마 적용

## Decisions

- **Flyway V1 단일 초기 스키마**: 아직 운영 데이터가 없는 초기 단계이므로 모든 확정 엔티티를 하나의 baseline migration으로 만든다.
- **pgvector 이미지 사용**: `VECTOR(1536)`과 `CREATE EXTENSION vector`는 일반 PostgreSQL 이미지에서 제공되지 않으므로 로컬 Compose와 Testcontainers를 pgvector 이미지로 맞춘다.
- **Refresh Token 비영속화**: Redis TTL 키로 관리하며 영구 테이블을 만들지 않는다.
- **Avatar 파츠 비정규화 금지**: Unity 외형 문자열은 `users.avatar_code VARCHAR(3800)` 하나로 저장한다.
- **층 테이블 보류**: 현 시점은 `booth_slots.floor_no`만 사용한다.
