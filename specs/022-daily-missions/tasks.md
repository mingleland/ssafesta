# 작업 목록 — 일일 미션

## 의존성

US1(조회)과 US2(수령)는 활동 집계와 월드 입장 마커를 공통으로 사용한다. US2는 US1의 진행도 판정 뒤 수행한다.

## Phase 1 — 기반

- [X] T001 `specs/022-daily-missions/`에 API 계약·데이터 모델·검증 가이드를 작성한다.
- [X] T002 `backend/src/main/java/com/example/ssafesta/mission/DailyMission.java`에 9개 미션과 보상·목표를 정의한다.
- [X] T003 `backend/src/main/java/com/example/ssafesta/mission/WorldMissionProgressService.java`에 KST Redis 월드 입장 마커를 구현한다.

## Phase 2 — US1 조회

- [X] T004 [US1] 기존 repository에 활동별 KST 일자 집계 쿼리를 추가한다.
- [X] T005 [US1] `backend/src/main/java/com/example/ssafesta/mission/DailyMissionService.java`에 9종 진행도와 조회 응답을 구현한다.
- [X] T006 [US1] `backend/src/main/java/com/example/ssafesta/mission/DailyMissionController.java`에 GET 엔드포인트를 구현한다.

## Phase 3 — US2 수령

- [X] T007 [US2] `backend/src/main/java/com/example/ssafesta/wallet/CoinReason.java`에 `DAILY_MISSION` 원장 사유를 추가한다.
- [X] T008 [US2] `backend/src/main/java/com/example/ssafesta/mission/DailyMissionService.java`에 잠금·멱등 키·상한 검사를 구현한다.
- [X] T009 [US2] `backend/src/main/java/com/example/ssafesta/mission/DailyMissionController.java`에 POST 엔드포인트와 계약 오류를 구현한다.

## Phase 4 — 통합 및 검증

- [X] T010 `backend/src/test/java/com/example/ssafesta/world/WorldSessionApiIntegrationTest.java`에 회원 월드 입장 마커 검증을 추가한다.
- [X] T011 `backend/src/test/java/com/example/ssafesta/mission/DailyMissionServiceTest.java`와 `DailyMissionApiIntegrationTest.java`에서 지급·중복·한도·API 계약을 검증한다.
- [X] T012 `docs/24_작업일지.md`에 완료 기록을 남기고 관련 Maven 테스트를 실행한다.
- [X] T013 [US1] Unity 하이스트라이커의 서버 승인 스윙을 `HIGH_STRIKER` 세션으로 기록하는 API와 3.2초 재전송 방어를 추가하고, 3회·400점 진행도 근거를 그 기록으로 전환한다.
