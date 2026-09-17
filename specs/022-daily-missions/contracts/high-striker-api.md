# 하이스트라이커 플레이 기록 계약

## POST /api/v1/minigames/high-striker/plays

회원 전용. Unity 월드가 Netcode 서버에서 승인한 스윙 직후 호출한다. 이 API는 점수 연출이나 랜덤 롤을 결정하지 않고, 일일 미션이 읽을 활동 사실만 저장한다.

```json
{ "machineId": "plaza-high-striker-01", "score": 400 }
```

성공은 `201`이고 `playId`, 제한된 `score`, KST 오늘의 `playsToday`, `bestScoreToday`를 반환한다. `score`는 1 이상이어야 하며 999 초과값은 999로 제한한다.

| 조건 | HTTP | error.code |
|---|---:|---|
| machineId 또는 score 누락·0 이하 | 400 | `VALIDATION_FAILED` |
| 게스트 | 403 | `MEMBER_ONLY` |
| 알 수 없는 기계 | 404 | `HIGH_STRIKER_NOT_FOUND` |
| 같은 회원이 직전 기록 뒤 3.2초 안에 재전송 | 429 | `HIGH_STRIKER_TOO_FAST` |

저장된 `game_type=HIGH_STRIKER` 기록은 일일 미션 `STRIKER_PLAY_3`(3회)와 `STRIKER_SCORE`(400점 이상 1회)의 유일한 근거다.
