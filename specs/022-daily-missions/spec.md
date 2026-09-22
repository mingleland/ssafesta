# 일일 미션

**Spec**: `022-daily-missions`
**Jira**: `S15P21A604-857`
**GitLab**: #233
**담당**: Backend

## 목표

회원이 KST 하루 동안 기존 활동 기록으로 아홉 가지 미션의 진행도를 확인하고, 완료한 보상을 한 번씩 수령한다. 미션 진행도 전용 테이블이나 클라이언트 제출 값은 만들지 않는다.

## 사용자 시나리오

### US1 — 오늘의 미션 조회 (P1)

회원은 `GET /api/v1/missions/daily`로 9개 미션의 목표·현재 진행도·수령 상태와 오늘 수령 보상 합계를 본다. 날짜 경계는 `Asia/Seoul`이며, 각 미션 보상은 15 Coin, 하루 총 상한은 135 Coin이다.

### US2 — 완료 보상 수령 (P1)

회원은 완료 상태의 미션을 수령한다. 동일 회원·미션·KST 날짜의 원장 멱등성 키로 동시 요청에도 한 번만 지급된다.

## 기능 요구사항

- FR-001: 미션은 `AI_CONSULT`, `SURVEY_ANSWER`, `STRIKER_PLAY_3`, `STRIKER_SCORE`, `SLOT_PLAY_3`, `SLOT_WIN`, `BOOTH_VISIT_3`, `BOOTH_VISIT_6`, `WORLD_ENTER` 9종이다.
- FR-002: 설문·하이스트라이커·슬롯·부스 방문은 기존 DB 사실 기록에서 요청 시 계산한다. 하이스트라이커는 Unity가 서버 승인 스윙 뒤 전송한 `HIGH_STRIKER` 기록만 센다.
- FR-003: `WORLD_ENTER`는 회원의 성공한 `POST /api/v1/world-sessions` 뒤 Redis에 KST 일자 마커를 기록해 판정한다. 일일 지급 캐시 키와 공유하지 않는다.
- FR-003a: `AI_CONSULT`는 회원이 AI 직원과 대화를 시작한 사실로 판정한다. 대화는 FastAPI가 처리하고 Spring을 거치지 않으므로, FastAPI가 Conversation 생성에 성공한 직후 서비스 토큰 경로 `POST /internal/ai/mission/ai-consult`로 통보하고 Spring이 `WORLD_ENTER`와 같은 형태의 Redis KST 일자 마커를 기록한다. 사람 상담 요청(`consultations`)은 이 미션의 근거가 아니다.
- FR-003b: FR-003a의 `userId`는 FastAPI가 사용자 JWT를 검증해 얻은 식별자를 인증된 서버 간 경로로 전달하는 것이며, 클라이언트가 임의로 제출한 식별자는 어디서도 사용하지 않는다. 사용자 access token으로는 `/internal/**`이 열리지 않는다.
- FR-004: 마커 조회가 실패하면 해당 미션 조회는 재시도로 회복 가능한 503으로 실패한다. 누락된 활동을 완료로 추정하지 않는다.
- FR-004a: 마커 기록 실패의 정책은 미션마다 다르다. `WORLD_ENTER`는 fail-closed — 기록에 실패하면 월드 세션 발급 자체가 503으로 실패한다. `AI_CONSULT`는 best-effort — 기록에 실패해도 대화는 성공하며, FastAPI가 경고 로그를 남기고 그 회원은 그날 이 미션만 달성하지 못한다. 미션 하나 때문에 대화를 막지 않는다.
- FR-005: 수령은 지갑 잠금과 `DAILY_MISSION:{userId}:{missionId}:{yyyy-MM-dd}` 원장 키로 보호하고, `DAILY_MISSION` 사유로 15 Coin을 지급한다.
- FR-006: 미완료는 `400 NOT_COMPLETED`, 중복 수령 및 하루 상한은 `409 ALREADY_CLAIMED` / `409 DAILY_CAP_REACHED`다. 게스트는 기존 회원 전용 403 규칙을 따른다.
- FR-007: `STRIKER_PLAY_3`은 오늘 `HIGH_STRIKER` 기록 3회를, `STRIKER_SCORE`는 완료된 `HIGH_STRIKER` 기록 중 점수 400 이상 1회를 센다. 기록 API는 `plaza-high-striker-01`만 받고 점수는 1~999로 제한하며 3.2초 쿨다운으로 재전송을 사실로 남기지 않는다.

## 범위 제외

- 별도 진행도 테이블·이벤트 스트림·배치 집계
- 게스트 진행도·보상
- FE/Unity 화면 및 알림

## 리뷰

- 2026-09-17: GitLab #233의 9종, 각 15 Coin, 일일 135 Coin과 `WORLD_ENTER` Redis 사실 마커를 확정했다.
