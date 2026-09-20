# 일일 미션 API 계약

## GET /api/v1/missions/daily

회원 전용. 200 응답에는 KST 기준 `date`, `resetAt`, `earnedToday`, `dailyCap`, `missions`를 둔다.

```json
{
  "date": "2026-09-17",
  "resetAt": "2026-09-18T00:00:00+09:00",
  "earnedToday": 15,
  "dailyCap": 135,
  "missions": [{
    "missionId": "WORLD_ENTER",
    "goal": 1,
    "progress": 1,
    "reward": 15,
    "status": "CLAIMABLE"
  }]
}
```

`status`는 `LOCKED`, `CLAIMABLE`, `CLAIMED` 중 하나다. 목록은 enum 선언 순서로 9개를 모두 반환한다.

## POST /api/v1/missions/daily/{missionId}/claims

회원 전용. 본문 없음. 성공 시 200으로 `missionId`, `rewardCoin`, `balanceAfter`, `claimedAt`를 반환한다.

| 조건 | HTTP | error.code |
|---|---:|---|
| 미완료 | 400 | `NOT_COMPLETED` |
| 이미 수령 | 409 | `ALREADY_CLAIMED` |
| 일일 135 Coin 도달 | 409 | `DAILY_CAP_REACHED` |
| 게스트 | 403 | 기존 `MEMBER_ONLY` |

동일 수령 키의 경합은 하나만 성공하며, 나머지는 `ALREADY_CLAIMED`를 반환한다.
