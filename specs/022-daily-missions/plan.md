# 구현 계획 — 일일 미션

**Spec**: `022-daily-missions` | **Jira**: `S15P21A604-857`

## 기술 맥락

Spring Boot 3 / Java 21 / PostgreSQL 17 / Redis를 사용한다. 기존 원장과 활동 테이블을 읽고, 신규 영속 테이블·마이그레이션은 만들지 않는다.

## 설계 결정

1. 진행도는 조회 시 계산한다. Coin 지급의 권위는 `coin_ledger_entries`이고 진행도는 지급 결과가 아니다.
2. 기존 DB 사실 기록이 없는 두 미션만 Redis KST 마커로 기록한다. `WORLD_ENTER`는 Spring의 성공 세션 발급 시점에, `AI_CONSULT`는 FastAPI가 Conversation 생성에 성공한 직후 서비스 토큰 경로로 통보한 시점에 기록한다. 둘 다 하루 동안의 사실 마커일 뿐 Coin 원장이 아니다. 기록 실패 정책은 갈린다 — `WORLD_ENTER`는 fail-closed, `AI_CONSULT`는 best-effort (spec FR-004a).
3. 수령 트랜잭션은 지갑 행을 잠그고 원장 키 재확인→완료 판정→일일 한도→credit 순서로 수행한다.
4. API는 `missionId`를 안정적인 enum 문자열로 노출한다. 새 상태값을 추가하지 않고 `LOCKED`/`CLAIMABLE`/`CLAIMED`로 표시한다.
5. 하이스트라이커 화면의 점수 권위는 Netcode에 남긴다. Spring은 승인 뒤 전송된 결과만 `HIGH_STRIKER` 세션으로 저장하고, 기계 화이트리스트·1~999 점수 제한·3.2초 쿨다운으로 미션 근거의 과도한 재전송을 막는다.

## 헌법 점검

- Coin 변경은 Spring 트랜잭션과 원장을 통해서만 수행한다 (20조).
- 월드 서버는 접속 토큰 검증과 실시간 상태만 맡으며 Coin 또는 영구 진행도를 바꾸지 않는다 (1·2조).
- 회원 식별자는 인증 주체만 사용하고 요청 본문의 사용자 식별자는 받지 않는다 (16조). `POST /internal/ai/mission/ai-consult`의 `userId`는 FastAPI가 사용자 JWT를 검증해 얻은 식별자를 인증된 서버 간 경로로 전달하는 것이며, 클라이언트가 임의로 제출한 식별자는 어디서도 사용하지 않는다.

## 파일 구조

```text
backend/src/main/java/com/example/ssafesta/mission/
  DailyMission.java
  DailyMissionController.java
  DailyMissionService.java
  DailyMissionMarkerService.java
backend/src/main/java/com/example/ssafesta/internal/ai/
  AiMissionMarkerController.java
backend/src/main/java/com/example/ssafesta/minigame/
  HighStrikerController.java
  HighStrikerService.java
backend/src/test/java/com/example/ssafesta/mission/
  DailyMissionServiceTest.java
specs/022-daily-missions/contracts/daily-mission-api.md
specs/008-ai-conversation-rag/contracts/spring-mission-marker-api.yaml
festa-ai/app/clients/spring_mission.py
festa-ai/app/services/conversation_service.py
```
