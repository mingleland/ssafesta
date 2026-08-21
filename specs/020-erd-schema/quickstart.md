# Quickstart: ERD 초기 스키마 적용

1. Docker Desktop을 실행한다.
2. `backend`에서 `docker compose up -d`를 실행한다.
3. `backend`에서 `./mvnw test`를 실행한다.
4. Flyway history에 V1 성공 기록이 있고 `ai_document_chunks.embedding` 타입이 `vector(1536)`인지 확인한다.
5. `refresh_tokens` 테이블이 생성되지 않았는지 확인한다.
