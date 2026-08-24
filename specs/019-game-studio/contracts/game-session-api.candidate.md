# Published GameSession·Coin API 후보

> 상태: **합의 전 Candidate v0.1** — [Issue #81](https://github.com/kanghyunsoon/ssafesta/issues/81)에서
> FE·BE 합의하기 전에는 운영 API 정본이나 구현 완료 근거로 사용하지 않는다.

## 목적과 권한 경계

다른 사용자가 Published 게임에 진입할 때 선택적으로 Coin을 차감하고, 재시도에도 중복 차감되지 않는
서버 권위 세션 경계를 정의하기 위한 후보안이다.

- 입장 가격, 잔액, 차감 결과, idempotency와 세션 상태는 Backend가 소유한다.
- 가격·보상·랭킹 규칙을 GameProject JSON에 넣지 않는다.
- Runtime의 `COMPLETE_GAME`, 점수, 생존 시간, 적 처치 수는 연출·로컬 진행 값이며 Coin·Reward 지급
  근거가 아니다.
- Unity는 기존처럼 웹 게임 진입 요청만 전달하고 Coin 또는 세션을 해석하지 않는다.

## 제안 흐름

```text
GET  /api/v1/games/{gameId}/entry-policy
→ FE가 가격·로그인 필요 여부 표시
→ 사용자가 입장 확인
POST /api/v1/games/{gameId}/sessions  + Idempotency-Key
→ 서버가 playable·Published version·잔액 재검증
→ 차감 + session 생성 단일 transaction
→ FE가 sessionToken으로 Published Runtime 시작
POST /api/v1/game-sessions/{sessionId}/complete 또는 /exit
```

`entry-policy` 응답은 안내용이다. 실제 가격과 실행 가능 여부는 세션 생성 transaction에서 다시
검증하며, FE가 이전 조회값을 차감 근거로 보내지 않는다.

### 입장 정책 조회 후보

```json
{
  "gameId": 123,
  "publishedVersion": 5,
  "playable": true,
  "chargeMode": "FREE_OR_FIXED_COIN",
  "entryCost": 10,
  "currency": "COIN",
  "authenticationRequired": true
}
```

### 세션 생성 후보

```http
POST /api/v1/games/123/sessions
Idempotency-Key: 7e16c024-...
```

요청 body에는 가격·잔액·`coinCharged`를 넣지 않는다. 서버 성공 응답 후보는 다음과 같다.

```json
{
  "sessionId": 901,
  "sessionToken": "opaque-short-lived-token",
  "gameId": 123,
  "publishedVersion": 5,
  "entryCost": 10,
  "chargedCoin": 10,
  "balanceAfter": 90,
  "expiresAt": "2026-08-24T02:00:00Z"
}
```

- `(userId, gameId, idempotencyKey)`는 unique여야 한다.
- 같은 key와 같은 요청의 재시도는 같은 세션 결과를 반환하고 다시 차감하지 않는다.
- playable 판정, Published Version 고정, 잔액 확인, 차감, 세션 생성은 단일 DB transaction이다.
- `sessionToken`은 URL·GameProject·로그에 저장하지 않는 짧은 수명의 opaque 값이다.

### 완료·종료 후보

```text
POST /api/v1/game-sessions/{sessionId}/complete
POST /api/v1/game-sessions/{sessionId}/exit
```

두 요청은 상태 전이를 idempotent하게 기록한다. MVP에서는 완료 payload의 점수나 게임 결과로 Coin,
Reward, Inventory, Ranking을 변경하지 않는다.

## 오류 후보

기존 5필드 오류 봉투를 사용한다.

| HTTP | code | 의미 |
|---:|---|---|
| 401 | `AUTH_REQUIRED` | 로그인 필요 |
| 402 또는 409 | `INSUFFICIENT_COIN` | 잔액 부족. 상태 코드는 #81에서 확정 |
| 404 | `GAME_NOT_FOUND` | 게임 없음·삭제 |
| 409 | `GAME_NOT_PUBLISHED` | 공개본 없음 |
| 403 | `GAME_NOT_PUBLIC` | 비공개 |
| 409 | `GAME_SESSION_CONFLICT` | 같은 key의 요청 내용 불일치 |
| 410 | `GAME_SESSION_EXPIRED` | 만료된 세션 |

내부 지갑 ID, 결제 Provider 원문, stack trace는 응답하지 않는다.

## #81에서 반드시 확정할 결정

1. v1이 무료와 고정 Coin 가격을 모두 지원하는지, `entryCost`를 어느 Game metadata가 소유하는지
2. 제작자 Preview·본인 게임 플레이의 차감 면제 여부
3. Guest가 무료 게임만 플레이 가능한지 또는 모든 Published 게임에 로그인이 필요한지
4. 새 idempotency key의 재도전마다 다시 차감하는지, 활성 세션 재개를 먼저 제공하는지
5. 세션 생성 뒤 Runtime 로드 실패·브라우저 종료·timeout의 환불 여부와 기준 시점
6. 402와 409 중 잔액 부족 상태 코드, 세션 만료 시간, 동시 활성 세션 상한
7. 완료·종료 감사 필드와 보존 기간

## 제안하는 좁은 MVP 기본값

아래는 구현 확정이 아니라 논의를 줄이기 위한 권고안이다.

- `FREE`와 `FIXED_COIN`만 지원하고 동적 가격·쿠폰·보상은 제외한다.
- 제작자 Preview는 세션 API와 Coin을 사용하지 않는다.
- 동일 idempotency key 재시도는 무차감, 새 플레이 세션은 정책에 따라 다시 차감한다.
- Runtime 결과는 세션 종료 상태만 기록하고 경제 상태를 변경하지 않는다.
- 환불 자동화는 제외하고, 서버가 차감 transaction을 commit하기 전에 실패하면 세션과 차감을 모두
  rollback한다.

## 승인 전 구현 금지선

- FE는 현재 `GameSessionPort`를 유지하되 운영 Coin adapter를 임의 구현하지 않는다.
- BE는 GameProject에 가격 필드를 추가하거나 Runtime 완료값을 지갑 변경 근거로 쓰지 않는다.
- Mock의 `coinCharged=false`를 운영 무료 정책으로 해석하지 않는다.
- 이 문서의 endpoint·필드명은 #81 합의 댓글과 정본 문서 반영 뒤에만 고정한다.
