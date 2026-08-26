# Data Model: World Session 발급 API (002 BE분)

**Date**: 2026-08-25 | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

## 1. 스키마 변경 — 없음

신규 테이블·컬럼·마이그레이션 **0** (R-06). World Session은 영속 엔티티가 아니라 **발급 시점에 만들어져 120초 뒤 사라지는 값**이다. 재사용 차단 원장은 게임 서버 소유(`/var/lib/festa-world/used-grants.log`)라 BE 스키마에 등장하지 않는다.

읽기만 하는 기존 테이블: `users` (회원 claims 채우기 — `id`, `nickname`, `avatar_code`, `status`).

## 2. 값 객체

### WorldSessionResponse (API 응답 — OpenAPI `WorldSession` 스키마와 1:1)

| 필드 | 타입 | 값 | 비고 |
|---|---|---|---|
| `sessionId` | string | `"ws_" + UUID` | 서버 생성. 영속화하지 않음 |
| `worldId` | string | `"11F"` 고정 | FR-011 |
| `channelId` | string | `"11F-01"` 고정 | 단일 채널 |
| `endpoint.scheme` | string | `ws` \| `wss` | 설정 주입 (R-07) |
| `endpoint.host` | string | 설정 주입 | local `127.0.0.1` / demo `world.${ROOT_DOMAIN}` |
| `endpoint.port` | int | 1~65535 | local `7777` / demo `443` |
| `connectionToken` | string | compact JWS | 아래 §3 |
| `expiresAt` | ISO-8601 | 토큰 `exp`와 동일 시각 | 별도 시계를 두지 않는다 |

### WorldSessionRequest (요청 — 전 필드 optional)

| 필드 | 규칙 | 위반 시 |
|---|---|---|
| `worldId` | 생략 가능. 있으면 `"11F"`만 유효 | 400 `VALIDATION_FAILED` + `FIELD_INVALID`/`field:"worldId"` — 사유 문장 포함 (FR-012, R-03) |
| `preferredPartyId` | 받되 무시 | — |

## 3. Connection Token claims (`world-entry-token.md` 정본)

| claim | 회원 | 게스트 |
|---|---|---|
| `jti` | UUID (매 발급 새 값) | 동일 |
| `sub` | AT subject = userId 문자열 | AT subject = `guest:{uuid}` |
| `role` | `MEMBER` | `GUEST` |
| `playerId` | `users.id` 문자열 | `sub` 그대로 |
| `nickname` | `users.nickname` (DB에서 — 헌법 16조) | `"게스트-" + uuid 앞 4자` (서버 파생, R-05) |
| `avatarCode` | `users.avatar_code` (null 가능) | null |
| `sessionId` | 응답 `sessionId`와 동일 | 동일 |
| `worldId` / `channelId` | `11F` / `11F-01` | 동일 |
| `iat` / `exp` | 발급 시각 / +120초 | 동일 |

서명: HS256, 전용 `CONNECTION_TOKEN_SECRET` (Base64 디코드 ≥32바이트). `iss: ssafesta-backend`, `aud: ssafesta-world`.

## 4. 불변식

1. **`expiresAt` == 토큰 `exp`.** 응답과 토큰이 서로 다른 만료를 말하지 않는다.
2. **claims의 신원은 전부 서버 유래.** 요청 본문·헤더의 어떤 값도 claim에 실리지 않는다 (헌법 16조).
3. **토큰 원문·Secret은 로그 금지.** 발급 로그는 `sessionId`·role·(회원이면 userId)까지만.
4. **같은 사용자가 두 번 호출하면 서로 다른 `jti`·`sessionId`·토큰**이 나온다 — 발급은 멱등이 아니며, 두 탭 동시 접속 정책(001 C-07)은 게임 서버 승인 단계 몫이다.
5. **발급 성공이 접속 성공을 의미하지 않는다.** BE는 정원·서버 생존을 검사하지 않는다 (R-10, 헌법 2조).

## 5. 상태 전이 — 없음

BE 관점에서 World Session에 상태 기계가 없다(발급 즉시 손을 뗀다). 발급→사용→소비→만료의 전이는 게임 서버 원장이 소유한다 (FR-007·013·014).

## 6. 오류 어휘 — 신규 0

| 상황 | HTTP | code | 비고 |
|---|---|---|---|
| 토큰 없음/무효 | 401 | `UNAUTHORIZED` | 기존 체인 그대로 |
| 정지 계정 | 403 | 기존 계정 비활성 코드 재사용 | R-08 |
| `worldId` ≠ `11F` | 400 | `VALIDATION_FAILED` + `FIELD_INVALID` | #58 봉투, `field:"worldId"` |

`GAME_*`·`WORLD_*` 신규 코드를 만들지 않는다 — 분기할 새 상황이 없다.
