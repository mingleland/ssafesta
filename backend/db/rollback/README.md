# 되돌리기 스크립트

Flyway Community 에는 undo 가 없다. 되돌려야 하는 마이그레이션은 여기에 짝이 되는 스크립트를 둔다.

| 파일 | 되돌리는 대상 | 주의 |
|---|---|---|
| `V21__rollback.sql` | `V21__ai_document_jobs_and_chunk_staging.sql` | Job·staging 행이 사라진다. 청크의 `job_id` 출처도 함께 없어진다 |

## 실행 절차

```bash
psql "$DATABASE_URL" -f backend/db/rollback/V21__rollback.sql
psql "$DATABASE_URL" -c "DELETE FROM flyway_schema_history WHERE version = '21'"
```

두 번째 줄을 빼먹으면 다음 `migrate` 가 V21 를 이미 적용된 것으로 보고 건너뛴다. 그 상태에서 애플리케이션이 뜨면 없는 테이블을 찾는다.

## cutover 순서 (V21)

1. **선행 확인** — `ai_documents` 에 행이 없어야 한다. `embedding SET NOT NULL` 과 `metadata` 드롭이 빈 테이블 전제다. 행이 있으면 migrate 가 시끄럽게 실패한다(그것이 의도다).
2. **배포** — 애플리케이션 기동 시 Flyway 가 V21 를 적용한다. `hnsw` 인덱스는 빈 테이블이라 즉시 끝난다.
3. **AI 파트 전환** — Spring 이 뜬 뒤에 FastAPI 에서 DB 자격증명과 `models.py`·`migrations/0001_*` 을 걷어낸다. 순서가 바뀌면 처리 중 문서가 갈 곳을 잃는다.
4. **되돌리는 경우** — 위 실행 절차. AI 파트가 이미 자기 DB 코드를 걷어냈다면 되돌려도 FastAPI 는 그 DB 를 다시 쓰지 않는다. 되돌리기는 스키마 복구이지 경계 복구가 아니다.
