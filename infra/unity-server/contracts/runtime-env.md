# World runtime environment mapping

값의 Source는 서버 Secret 저장소 또는 배포 manifest다. 아래 이름과 고정값만 저장소에 둔다.

| Meaning | Backend issuer | Game verifier | Infra source |
|---|---|---|---|
| HS256 key | `CONNECTION_TOKEN_SECRET` | `CONNECTION_TOKEN_SECRET_FILE`이 가리키는 파일 | 동일 Secret reference |
| issuer | `app.world.issuer` | `WORLD_ENTRY_TOKEN_ISSUER` | `ssafesta-backend` |
| audience | `app.world.audience` | `WORLD_ENTRY_TOKEN_AUDIENCE` | `ssafesta-world` |
| world | request/default 및 token claim | `WORLD_ID` | `11F` |
| channel | response 및 token claim | `WORLD_CHANNEL_ID` | `11F-01` |
| TTL | `app.world.token-ttl` | claim 검증 | `120s` |
| replay ledger | 해당 없음 | `WORLD_LEDGER_PATH` | game 전용 volume |

Backend와 game은 같은 Base64 Secret bytes를 소비해야 한다. Backend가 환경 변수만 지원하는 동안 배포기는 Secret 저장소에서 값을 프로세스 환경으로 주입하고, game에는 같은 참조를 read-only 파일로 mount한다. 값을 Compose YAML, `.env.example`, 로그 또는 evidence에 복사하지 않는다.

회전 시에는 새 발급을 잠시 차단하고 Backend issuer와 game verifier를 같은 maintenance window에 함께 교체한다. HS256에서 서로 다른 키가 활성화되면 그 사이에 발급한 모든 grant가 거부되므로 한쪽만 선배포하지 않는다.
