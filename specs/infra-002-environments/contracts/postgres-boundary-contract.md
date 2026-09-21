# PostgreSQL Isolation and Backup Contract v1

## Database/role matrix

| Environment | Purpose | Database | Runtime role | Extension |
|---|---|---|---|---|
| dev | Spring business | `festa_dev_business` | `festa_dev_back_app` | application-owned |
| dev | AI RAG/vector | `festa_dev_ai` | `festa_dev_ai_app` | `vector` |
| demo | Spring business | `festa_demo_business` | `festa_demo_back_app` | application-owned |
| demo | AI RAG/vector | `festa_demo_ai` | `festa_demo_ai_app` | `vector` |

하나의 PostgreSQL 17 + pgvector instance를 공유하지만 database와 login role은 분리한다.

## Read-only operator role

| Role | Database | Privilege | Credential |
|---|---|---|---|
| `festa_demo_readonly` | `festa_demo_business`, `festa_demo_ai` | `CONNECT`, public schema `USAGE`, table `SELECT` (default privileges 포함) | 없음 (비밀번호 미설정) |

demo 데이터 확인 요청을 EC2 계정·sudo·docker 그룹 없이 처리하기 위한 운영자 창구다. runtime service는 이 role을 쓰지 않는다.

- 비밀번호를 설정하지 않는다. host 경로가 `scram-sha-256`이라 네트워크로는 인증이 성립하지 않고, 컨테이너 내부 unix socket으로만 붙는다.
- 쓰기 권한을 주지 않는다. demo 데이터 수정은 Flyway migration으로만 수행한다.
- dev database에는 어떤 권한도 주지 않는다. dev/demo 경계는 그대로다.
- 네트워크 접속이 필요해지면 Secret Reference를 추가하고 `ALTER ROLE`로 비밀번호를 건다. 그 변경은 5432 publish 정책 변경과 함께 검토한다.

## Privilege invariants

- 각 runtime role은 `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`다.
- 각 database의 `PUBLIC CONNECT`와 public schema의 불필요한 CREATE 권한을 회수한다.
- runtime role은 자기 database CONNECT와 자기 schema의 최소 DML/sequence 권한만 가진다.
- Spring role은 AI DB에, AI role은 business DB에, dev role은 demo DB에 접근할 수 없다.
- 조회 전용 role은 runtime role이 아니며 demo 두 database의 읽기 권한만 가진다. 쓰기·DDL·dev 접근은 금지한다.
- `vector` extension 설치·upgrade와 database/role 생성은 bootstrap admin만 수행한다.
- credential은 환경별 Secret Reference로 주입하고 example에는 key name만 둔다.
- PostgreSQL 5432는 Docker internal network에만 존재하고 host/public port로 publish하지 않는다.

## Migration ownership

- Spring business database migration은 Spring/Flyway가 소유한다.
- AI database migration과 embedding/vector schema는 AI가 소유한다.
- migration 전에 해당 database의 `pre-migration` backup을 만들고 infra-001 release ID와 연결한다.
- 한 환경 migration이 다른 환경 database를 대상으로 삼으면 즉시 실패한다.

## Backup policy

- destination: R2 private PostgreSQL backup bucket.
- retention: daily 7, weekly 4, pre-migration manual.
- dump마다 database, environment, release, schema version, PostgreSQL/pgvector version, size, SHA-256, document inventory reference를 manifest에 기록한다.
- local dump는 R2 upload와 checksum 확인 후 임시 staging으로만 취급한다.
- backup success는 upload 성공만으로 판정하지 않는다. 폐기 가능한 database에 `pg_restore`하고 schema/row/vector/document metadata 관계 검증을 통과해야 한다.
- AI original document는 이 backup에 복제하지 않는다. R2 document bucket/account 유실 시 원본 복구가 불가능함을 runbook과 restore report에 표시한다.

## Acceptance probes

- 4개 runtime role × 4개 database CONNECT matrix에서 diagonal만 성공.
- `festa_demo_readonly`는 demo 두 database에서 `SELECT`만 성공하고 `INSERT`/`UPDATE`와 dev database CONNECT는 실패한다.
- AI database에만 `vector` extension이 있고 application role이 extension을 변경하지 못함.
- app container 재생성 뒤 volume data 유지.
- R2 backup만으로 빈 PostgreSQL instance에 복구.
- 복구한 metadata의 object key가 document bucket inventory와 일치.

