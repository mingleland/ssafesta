# 데이터 모델 — 일일 미션

신규 테이블은 없다. 아래 기존 사실 기록을 KST 일자 범위로 조회한다.

| 미션 | 사실 기록 | 판정 |
|---|---|---|
| AI_CONSULT | `consultations.requested_at` | 오늘 1회 이상 |
| SURVEY_ANSWER | `survey_responses.submitted_at` | 오늘 1회 이상 |
| STRIKER_PLAY_3 | `minigame_sessions.started_at` | 오늘 `game_type=HIGH_STRIKER` 3회 이상 |
| STRIKER_SCORE | `minigame_sessions` | 오늘 `HIGH_STRIKER`, COMPLETED, `result_value >= 400` 1회 이상 |
| SLOT_PLAY_3 | `coin_ledger_entries` | `SLOT_BET` 3회 이상 |
| SLOT_WIN | `coin_ledger_entries` | `SLOT_PAYOUT` 1회 이상 |
| BOOTH_VISIT_3 | `booth_visits` | 서로 다른 booth 3곳 이상 |
| BOOTH_VISIT_6 | `booth_visits` | 서로 다른 booth 6곳 이상 |
| WORLD_ENTER | Redis | `mission:world-enter:{user}:{kstDate}` 존재 |

수령 사실은 `coin_ledger_entries`의 `reason_type=DAILY_MISSION`, 참조 키 `DAILY_MISSION:{userId}:{missionId}:{date}`로 보존한다.
