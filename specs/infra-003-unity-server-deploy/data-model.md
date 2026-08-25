# Data Model: Unity Dedicated Server 외부 배포

## World Server Deployment

| Field | Rule |
|---|---|
| environment | `demo` |
| worldId / channelId | `11F` / `11F-01` |
| imageRef | immutable digest or full commit SHA |
| maxPlayers | `40` |
| internalEndpoint | `ws://demo-game:7777` |
| current / knownGood | infra-001 release reference |
| replayVolume | game 전용 persistent volume |

상태는 `CANDIDATE → VERIFYING → CURRENT/KNOWN_GOOD` 또는 `FAILED → ROLLED_BACK`으로 전이한다.

## World Entry Grant

| Field | Rule |
|---|---|
| jti | 예측 불가능한 UUID, 필수 |
| subject / role | Access Token에서 검증된 회원 또는 게스트 |
| playerId | 회원 ID 또는 게스트 임시 음수 ID |
| nickname / avatarCode | Backend가 확정한 표시 신원 |
| sessionId | `ws_` 접두 UUID |
| worldId / channelId | `11F` / `11F-01` |
| issuedAt / expiresAt | TTL 120초 |

## Used Grant Record

| Field | Rule |
|---|---|
| jtiHash | lowercase SHA-256 hex; token/jti 원문 저장 금지 |
| expiresAt | Unix epoch seconds |

동일 hash는 최초 소비만 성공한다. 만료 전에는 연결 종료와 무관하게 유지하고 만료 뒤에만 정리한다.

## World Connection

검증된 grant, NGO client ID, 연결/종료 시각과 종료 사유를 연결한다. 연결 종료 시 `SessionDataStore`와 player object가 제거된다.

## External Path Evidence / Capacity Observation

릴리스 ID, client 릴리스, DNS/TLS/Upgrade/approval 결과, 10분 idle 결과, 단계별 동시 접속 수, 예상 밖 종료, EC2 자원과 비대상 서비스 재시작 횟수를 기록한다. Secret·token·개인정보 원문은 포함하지 않는다.
