# 검증 가이드 — 일일 미션

1. 회원 지갑을 준비하고 `POST /api/v1/world-sessions`를 호출한다.
2. `GET /api/v1/missions/daily`에서 `WORLD_ENTER`의 progress=1, status=CLAIMABLE을 확인한다.
3. `POST /api/v1/missions/daily/WORLD_ENTER/claims`를 호출해 15 Coin과 `DAILY_MISSION` 원장을 확인한다.
4. 같은 요청을 다시 보내 409 `ALREADY_CLAIMED`인지 확인한다.
5. `backend`에서 `./mvnw.cmd -B "-Dtest=DailyMissionServiceTest,WorldSessionApiIntegrationTest" test`를 실행한다.
