# Redis Ephemeral Cache Boundary v1

Redis는 큰 문서 저장소가 아니라 낮은 지연의 임시 상태/cache다. 큰 RAG 원본은 R2, metadata·chunk·vector와 survey/question/response는 PostgreSQL이 Source of Truth다.

## ACL users and key ranges

| Runtime user | Allowed key patterns |
|---|---|
| `dev-back` | `dev:auth:*`, `dev:presence:*`, `dev:lock:*`, `dev:wallet:*`, `dev:survey:*` |
| `dev-ai` | `dev:rag:*`, 필요한 `dev:lock:*` subset |
| `demo-back` | `demo:auth:*`, `demo:presence:*`, `demo:lock:*`, `demo:wallet:*`, `demo:survey:*` |
| `demo-ai` | `demo:rag:*`, 필요한 `demo:lock:*` subset |

- `default` user는 off이며 username/password 없는 접속은 실패한다.
- runtime user는 필요한 read/write/TTL/transaction command만 허용한다.
- `FLUSHALL`, `FLUSHDB`, `CONFIG`, `ACL`, `KEYS`, module/admin command를 금지한다.
- Pub/Sub을 쓰는 서비스는 environment/service channel pattern만 별도로 허용한다.
- 6379는 public/host port로 publish하지 않는다.

## Key contract

```text
<env>:auth:refresh:<tokenHash>
<env>:auth:session:<userId>
<env>:auth:oauth-handoff:<opaqueId>
<env>:presence:<scope>:<memberId>
<env>:lock:<resource>:<id>
<env>:wallet:daily:<userId>:<date>
<env>:rag:<boothId>:<agentId>:<sourceRevision>:<queryHash>
<env>:survey:<surveyId>:<sourceRevision>:aggregate
```

RAG key에는 `boothId + agentId`를 모두 포함한다. prefix만으로 헌법 17조의 query filter를 대체하지 않으며 cache hit payload도 scope를 재검증한다.

## Data class behavior

| Class | TTL/invalidation | Redis loss behavior |
|---|---|---|
| refresh/session/OAuth handoff | auth 정책 TTL, logout/consume | 사용자는 재로그인; 영구 회원 데이터 유지 |
| presence | heartbeat TTL | offline/unknown으로 재수렴 |
| lock | lease TTL + owner token | lease 만료 후 재획득; 영구 상태 transaction 재검증 |
| daily wallet cache | KST day boundary | PostgreSQL transaction/ledger로 fallback |
| RAG result cache | bounded TTL + document revision | PostgreSQL/R2에서 source 조회·재계산 |
| survey aggregate | 기본 비활성; 활성 시 bounded TTL + response commit revision | PostgreSQL에서 재집계 |

survey MVP는 원본 실시간 집계를 기본으로 하고 성능 실측 후에만 cache를 활성화한다.

## Invalidation order

- source transaction 또는 document state 변경이 먼저 commit된다.
- revision이 증가하므로 새 request는 이전 key를 더 이상 선택하지 않는다.
- 이후 이전 revision key를 best-effort delete한다.
- invalidation 실패를 source transaction rollback 사유로 만들지 않되 metric/alert를 남긴다.
- cache write/read 장애는 empty success로 삼키지 않고 source-read/recompute 또는 재로그인으로 명시적으로 저하한다.

## Memory and persistence

- 기본 eviction policy는 `noeviction`; cache write 실패는 source fallback으로 처리한다.
- namespace별 entry size/budget과 Redis memory warning을 둔다. 원본 문서·대형 blob을 넣지 않는다.
- AOF/volume은 재시작 편의를 위해 사용할 수 있지만 correctness나 영구 backup 근거가 아니다.
- Redis value와 credential을 log/metric label로 내보내지 않는다.

## Recovery acceptance

운영자 전용 credential로 test instance를 `FLUSHALL`하거나 volume을 교체한 뒤:

- RAG document/metadata/chunk/vector와 survey/question/response 손실 0건.
- 재생성 RAG 결과와 survey aggregate가 현재 PostgreSQL/R2 source와 100% 일치.
- cross-environment ACL 접근 100% 거부.
- runtime user의 forbidden command 100% 거부.
- auth session 유실은 재로그인으로 명시되고 회원/ledger 영구 데이터는 유지.
