# Quickstart: 직원 / 사람 상담 검증

**Spec**: `011-staff-consultation` | **Date**: 2026-09-13

이 기능이 실제로 도는지 확인하는 실행 절차다. 계약 세부는 [contracts/staff-consultation-api.md](./contracts/staff-consultation-api.md), 스키마는 [data-model.md](./data-model.md)를 본다.

## 전제

```bash
cd backend && ./mvnw -B test
```

- Docker 가 떠 있어야 한다 (Testcontainers).
- `backend/.env` 는 필요 없다 — 테스트 리소스가 JWT·OAuth 더미를 공급한다(`S15P21A604-549`).
- 기준선: develop 에서 이 스위트가 실패 0으로 통과한다.

## 시나리오 1 — 직원 초대 왕복 (US3, SC-005)

1. Owner 가 `POST /booths/{boothId}/staff-invitations` 로 다른 회원을 `CONSULTANT` 로 초대한다.
2. 초대받은 회원이 `GET /staff-invitations/mine` 으로 `invitationId` 를 확인한다.
3. `POST /staff-invitations/{invitationId}/accept` 로 수락한다.
4. `GET /booths/{boothId}/staff` 가 `OWNER` 읽기 전용 행과 방금 수락한 `CONSULTANT` 행을 함께 반환한다.

**통과 기준**

- 수락 **전**에는 그 회원의 Layout 편집이 `403` 이다.
- 수락 **후**에도 `CONSULTANT` 의 Layout·Facade 편집은 여전히 `403` 이다 (FR-002, C-09).
- `CONTENT_EDITOR` 로 초대·수락하면 Layout 편집이 `200` 이다.
- 어느 역할이든 임대 결제·취소는 남의 부스에 닿지 않는다 (FR-003).

> ⚠️ **이 시나리오가 P1에서 가장 먼저 깨질 자리다.** 지금 `BoothAccessGuard.requireEditor` 는 역할을 보지 않고 "행이 있으면 통과" 다. 역할 게이트를 붙이기 전에 초대를 열면 2·3번 통과 기준이 바로 실패한다 — 그리고 Layout뿐 아니라 AI 직원·문서·설문·프로젝트까지 함께 열린다([data-model.md](./data-model.md) 권한 매트릭스 참조).

## 시나리오 2 — 만료 (SC-003)

1. 회원이 `POST /consultation/requests` 로 요청한다 — 응답의 `expiresInSeconds` 가 `600` 이다.
2. 아무도 수락하지 않는다.
3. 요청 시각 + 10분이 지나면 스위퍼가 `REQUESTED → EXPIRED` 로 옮긴다.
4. 만료된 요청의 `accept` 는 `409 CONSULTATION_NOT_REQUESTED` 다.

**통과 기준**: fixture 로 `requested_at` 을 과거로 밀어 스위퍼 1회 실행에 전이가 일어난다. 방문자 큐에 `expired` 이벤트가 발행된다.

초대 만료도 같은 방식으로 본다 — `expires_at` 을 과거로 두고 스위퍼 실행 후 `accept` 가 `409` 다 (48시간, C-07).

## 시나리오 3 — 동시 수락 (SC-001)

두 직원이 **같은 요청**에 동시에 `accept` 를 던진다.

**통과 기준**

- 정확히 하나가 `200`, 나머지는 `409 CONSULTATION_NOT_REQUESTED`.
- `consultations` 행의 `staff_user_id` 가 성공한 쪽 한 명이다.
- 진 쪽의 대기열에 `taken` 이벤트가 간다.

## 시나리오 4 — 직원당 활성 상담 1건 (SC-006)

한 직원이 상담 A 를 수락한 뒤 **다른 요청** B 를 수락한다.

**통과 기준**

- B 는 `409 CONSULTATION_ALREADY_ACTIVE` 이고 **A 는 그대로 유지된다**(US2 시나리오 4).
- 동시 요청으로 던져도 같다 — `ux_consultations_active_staff` 가 DB 에서 막는다.
- A 를 `end` 한 뒤 B 를 수락하면 `200` 이다.
- **방문자가 `end` 를 호출하면 방문자 큐와 해당 부스 직원 토픽 양쪽에 `ended` 가 발행된다.**
- 직원이 종료한 경우에도 동일하며, 종료를 호출한 직원 본인도 직원 토픽으로 이벤트를 받는다 (2026-09-14 확정, GitLab #133).

## 시나리오 5 — 담당자가 없어도 막히지 않는다 (SC-002)

부스의 모든 직원이 `OFFLINE` 인 상태에서 방문자가 AI 상담을 연다.

**통과 기준**: AI 대화·문서 검색 경로가 전부 정상 동작한다. 사람 상담 요청 경로의 가부와 무관하다 (FR-011, 헌법 3조).

## 시나리오 6 — 게스트 차단 (FR-014)

게스트 토큰으로 `POST /consultation/requests` 를 호출한다.

**통과 기준**: `403 GUEST_FORBIDDEN`. 소셜 로그인 안내가 응답에 담긴다.

## 시나리오 7 — WS Token 과 STOMP 연결 (FR-019·FR-020)

1. `POST /consultation/ws-token` → `expiresInSeconds: 300`.
2. `wss://<host>/ws/consultation` 에 STOMP `CONNECT`, 헤더 `Authorization: Bearer <token>`.
3. `/user/queue/consultation` 을 구독한 방문자가 자기 요청의 `accepted` 를 받는다.

**통과 기준**

- 헤더 없는 `CONNECT` 는 거부된다.
- **URL query 로 토큰을 실은 연결은 거부된다** (헌법 13조).
- Access Token 을 그대로 실은 `CONNECT` 는 거부된다.
- 토큰 발급 후 5분이 지나면 `CONNECT` 가 거부되고, **이미 맺어진 연결은 끊기지 않는다**(FR-020).

## 시나리오 8 — 요약 없는 인계 (R-02)

`S15P21A604-139` 도착 전 상태에서 시나리오 1~4 를 그대로 돈다.

**통과 기준**: `handoffSummary` 가 `null` 인 것 말고는 모든 경로가 동일하게 동작한다. 요약 조달 실패가 요청·수락·종료 어느 것도 막지 않는다.
