# 문서 파이프라인 장애 주입·복구 결과 (S15P21A604-162)

복구율: 6/6 (100%)

| 시나리오 | 주입 지점 | 결과 | 비고 |
|---|---|---|---|
| parsing_embedding_kill | compute_embedded_chunks 진행 중 | PASS | attempt 취소, 전송·finalize·failed 호출 없음 — Spring lease sweeper가 회수 |
| chunk_send_kill | chunk_batch 전송 중 | PASS | batch 미완료 상태로 취소 — 부분 전송이 finalize로 이어지지 않음 |
| pre_finalize_kill | finalize 직전(lease 재확인 후) | PASS | chunk는 전달됐지만 finalize 미완료 — READY 오상태 전이 없음 |
| heartbeat_undelivered | heartbeat 전송(30초 주기, 테스트는 0.01초) | PASS | heartbeat 유실이 처리 자체를 막지 않고 finalize까지 정상 도달 |
| finalize_callback_lost | finalize 응답 및 뒤이은 failed 재보고 모두 실패 | PASS | 예외 없이 UNDELIVERED로 종료 — Spring lease 만료로 재시도 위임 |
| job_gone_at_finalize | finalize 호출이 410(Job 소멸)로 거부됨 | PASS | failed 재보고 없이 SUPERSEDED로 조용히 종료 — 중복 보고 0건 |
