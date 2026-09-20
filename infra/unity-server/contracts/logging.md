# Unity world logging contract

## 허용 필드

- `timestamp`, `level`, `event`, `environment`, `releaseId`
- `worldId`, `channelId`, `clientId`
- `result`, `failureLayer`, 제한된 `reasonCode`
- 배포 candidate/current/known-good reference와 비대상 restart delta

## 금지 필드와 값

- connection/access/refresh token 원문 또는 일부
- `CONNECTION_TOKEN_SECRET`, Secret 파일 내용, authorization header
- JTI 원문과 presigned URL
- nickname, avatarCode 및 사용자를 직접 식별하는 payload 원문
- TLS private key, 전체 container environment, `docker compose config`의 민감 값

JTI가 운영 상관관계에 필요하면 lowercase `SHA-256(jti)`만 제한된 저장소에 기록한다. 사용자 응답에는 내부 예외나 stack trace를 노출하지 않는다. 거부 사유는 `INVALID_SIGNATURE`, `EXPIRED`, `WRONG_TARGET`, `REPLAYED`, `LEDGER_UNAVAILABLE`, `SERVER_FULL`처럼 안정적인 범주만 사용한다.
