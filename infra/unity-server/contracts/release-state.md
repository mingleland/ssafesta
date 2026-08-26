# Game release state mapping

Game 배포는 infra-001의 release manifest, deployment record와 target lock을 재사용한다. 별도 전역 배포 상태 저장소를 만들지 않는다.

| infra-003 state | infra-001 record 의미 |
|---|---|
| `CANDIDATE` | candidate release가 선택됐지만 실행 전 |
| `VERIFYING` | `deploy` 및 `world` stage가 실행 중 |
| `CURRENT/KNOWN_GOOD` | `ACTIVE`, world stage 성공 후 current pointer 갱신 |
| `FAILED` | `FAILED_PENDING_DECISION`, failure evidence 필수 |
| `ROLLED_BACK` | `ROLLED_BACK`, 이전 known-good로 game-only 복구 |

- target ID는 `demo/game`이며 동일 target의 배포는 infra-001 lock으로 직렬화한다.
- image reference는 digest 또는 full commit SHA만 허용한다.
- 배포는 `docker compose up -d --no-deps demo-game`으로 game만 변경한다.
- 프로세스 실행만으로 승격하지 않는다. 내부 listener와 실제 승인된 외부 WSS 입장이 모두 성공해야 한다.
- Backend·AI·web restart delta가 0이 아니면 승격하지 않는다.
