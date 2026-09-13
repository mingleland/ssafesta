# Phase 0 Research: 직원 / 사람 상담

**Date**: 2026-09-13 | **Plan**: [plan.md](./plan.md)

spec의 Clarification 14건이 전부 닫혀 있어 `NEEDS CLARIFICATION`은 없다. 여기서 푸는 것은 **문서 사이의 드리프트**와 **구현 형태 선택** 다섯 건이다.

---

## R-01. REST 경로 정본 — `docs/08` §12 와 #133 확정본이 다르다

**두 문서가 서로 다른 경로를 적고 있다.**

| 출처 | 요청 생성 | 수락 | 종료 |
|---|---|---|---|
| `docs/08` §12 | `POST /booths/{boothId}/consultations` | `POST /consultations/{consultationId}/accept` | `POST /consultations/{consultationId}/end` |
| #133 BE 회신 (2026-09-07 17:22) | `POST /api/v1/consultation/requests` | `POST /api/v1/consultation/requests/{requestId}/accept` | `POST /api/v1/consultation/sessions/{sessionId}/end` |

**Decision**: **#133 확정본을 정본으로 삼고 `docs/08` §12를 그에 맞춘다.**

**Rationale**: FE가 이미 그 이름·경로로 구현해 develop에 넣었다 — `S15P21A604-519`(완료)가 `channel.port.ts`·mock·`AiChatOverlay` handoff를 확정본에 맞췄고, BE가 #133에서 "일정과 무관하게 지금 확정이므로 이 이름·경로는 바뀌지 않는다"고 명시했다. 지금 `docs/08` 형태로 되돌리면 **소비자가 이미 쓰는 계약을 깨는 Breaking Change**가 되어 헌법 24조에 걸린다. `docs/08` §12는 spec 011 C-01 확정(2026-08-31) 시점의 서술이고 그 뒤 갱신되지 않았다.

**Alternatives considered**:
- `docs/08` 경로 채택 → FE 어댑터·mock·테스트를 다시 고쳐야 하고, 확정 통보를 스스로 뒤집는 셈이다. 기각.
- 둘 다 열어 두기 → 같은 자원에 경로가 둘이면 만료·수락 동시성 판정이 두 곳에 생긴다. 기각.

**따라오는 작업**: `docs/08` §12 갱신, §10에 FR-016(`GET /staff-invitations/mine`)·FR-017(Owner 취소) 추가. 헌법 24조 통보는 **경로를 바꾸는 것이 아니라 문서를 실제 계약에 맞추는 것**이므로 FE·AI에 정정 통보로 족하다.

---

## R-02. AI 요약 조달 — `-139` 를 기다리지 않는다

**Decision**: **요약 없는 인계를 먼저 연다.** `HandoffSummaryClient`를 인터페이스로 두고 P1 1차 구현은 항상 `null`을 반환한다. `S15P21A604-139` 도착 후 FastAPI 호출 구현으로 교체한다.

**Rationale**: 확정 계약이 `handoffSummary: string | null`이고 "요약 생성 실패도 `null`일 뿐 요청은 진행된다"가 이미 계약에 박혀 있다. 헌법 3조(AI 장애 격리)가 요구하는 동작과 같다 — FastAPI가 없을 때의 동작이 이미 정의돼 있으므로, 없는 동안이 곧 그 경로다. `-139`가 진행 중(2026-09-13 기준)이라 기다리면 US1 전체가 막힌다.

**주의**: 요약은 **요청 생성 시점의 스냅샷**이다(#133 1항). 이후 대화가 이어져도 갱신하지 않는다 — 직원이 본 요약이 나중에 달라지면 안 된다. `consultations.summary` 컬럼이 V1부터 있어 저장 자리는 이미 있다.

**Alternatives considered**: `-139` 완료까지 대기 → SC-004(직원이 요약만 보고 문맥 파악)는 못 채우지만 SC-001·002·003·005·006은 전부 요약과 무관하다. 기각.

---

## R-03. 동시성 — DB가 강제하게 만든다

**Decision**: 애플리케이션 판정이 아니라 **DB 제약**으로 막는다.

- **SC-001 (한 요청은 한 명만 수락)** → 조건부 갱신. `UPDATE consultations SET status='ACCEPTED', staff_user_id=?, accepted_at=now() WHERE id=? AND status='REQUESTED'` 의 영향 행이 0이면 이미 누가 가져갔거나 만료된 것이다. 읽고-판단하고-쓰는 경로를 만들지 않는다.
- **SC-006 (직원당 활성 상담 1건)** → **부분 유니크 인덱스** `ux_consultations_active_staff ON consultations(staff_user_id) WHERE status='ACCEPTED'`. 위반은 제약 위반으로 올라오고 `409 CONSULTATION_ALREADY_ACTIVE`로 번역한다.

**Rationale**: 저장소에 이미 같은 관례가 있다 — `ux_booth_leases_active_slot`(슬롯당 `ACTIVE` 1건), `ux_staff_invitations_pending`(부스·사용자당 `PENDING` 1건). 같은 모양을 쓰면 읽는 사람이 새로 배울 것이 없고, 동시 요청에서도 **DB가 마지막 방어선**이 된다(FR-021이 "동시 요청에서도 거부"를 명시한다).

**Alternatives considered**: 비관적 잠금(`SELECT … FOR UPDATE`) → 대기열 조회까지 같은 행을 잡아 경합이 커진다. 조건부 갱신이 같은 보장을 더 싸게 준다. 기각.

---

## R-04. Presence 저장 위치 — Redis가 아니라 DB 컬럼

**Decision**: `booth_staffs`에 `consultation_status` 컬럼을 더한다. 기본값 `OFFLINE`. 변경은 명시적 REST(`PUT /booths/{boothId}/staff/me/presence`)로만 한다.

**Rationale**: `booth_staffs` 행이 이미 있고 조회 경로가 전부 REST다(직원 목록·대기열). Redis에 따로 두면 재시작·정합·삭제 시점을 새로 관리해야 하는데 P1이 얻는 것이 없다. US2가 "**오프라인이 기본 상태**"라고 못박았으므로 기본값이 곧 정상 경로다.

**범위 밖**: STOMP 연결이 끊길 때 자동으로 `OFFLINE`으로 내리는 동작. 연결 수명과 상담 가능 여부는 별개다(직원이 창을 닫아도 자리에 있을 수 있다). P1은 명시적 전환만 제공하고, 필요해지면 P2에서 연결 이벤트와 묶는다.

---

## R-05. STOMP 구성 — simple broker, SockJS 없음

**Decision**: `spring-boot-starter-websocket`을 더하고 native WebSocket 엔드포인트 `/ws/consultation`에 STOMP를 얹는다. **in-memory simple broker**를 쓰고 SockJS fallback은 등록하지 않는다(C-05).

인증은 `ChannelInterceptor`가 `CONNECT` 프레임의 `Authorization: Bearer <wsToken>` 헤더를 검증한다. URL query 토큰 전달은 받지 않는다(FR-019·헌법 13조).

**WS Token**은 Redis에 5분 TTL로 둔다 — Refresh Token이 이미 Redis를 쓰고 있어 새 저장소가 늘지 않는다. 연결 성립 후에는 토큰 만료로 끊지 않으며(FR-020), 재연결 시 새로 발급받는다.

**한계를 함께 적는다**: simple broker는 **단일 인스턴스 전제**다. Spring을 두 대 이상 띄우면 A 인스턴스에 붙은 직원이 B 인스턴스가 발행한 이벤트를 받지 못한다. P1 배포 형상이 단일 인스턴스라 지금은 충분하지만, 스케일아웃이 결정되면 외부 브로커 릴레이가 필요하다. 이것은 **배포 형상 결정이 정해진 뒤의 후속**이며 구현자가 지금 고를 문제가 아니다.

**이벤트 유실은 계약이 이미 인정한다** — #133 6항이 "재연결 직후 `getQueue`와 요청 상태를 REST로 다시 읽는다, 이벤트 재전송은 P1에 없다"로 닫아 뒀다. 따라서 STOMP는 **알림**이고 **정본은 REST**다.

---

## R-06. `requestId` 와 `sessionId` — 같은 행, 경로만 둘

**Decision**: `consultations` 행 하나가 요청과 세션을 겸한다. `sessionId`는 그 행 id의 문자열 표현이며 `requestId`와 **같은 값**이다. `accept` 응답에 `sessionId`를 함께 실어 FE 어댑터가 둘 중 무엇을 들고 있어도 `end`를 부를 수 있게 한다.

**Rationale**: 확정 계약의 경로가 `requests/{requestId}/accept`와 `sessions/{sessionId}/end`로 갈려 있는데, FE Port는 "어댑터가 REST 응답에서 받아 들고 있다가 cancel·end 경로에 쓴다"고만 적는다. 행을 둘로 나누면 상태 전이가 두 테이블에 걸치고 SC-001의 조건부 갱신이 복잡해진다. 한 행으로 두면 `REQUESTED → ACCEPTED → ENDED`가 한 자리에서 끝난다.

**주의**: 계약의 id 타입은 **문자열**이다(`requestId: string`). DB는 `BIGINT`이므로 응답 직렬화에서 문자열로 내보낸다.

---

## R-07. 만료 두 건 — 기존 sweeper 관례를 따른다

**Decision**: `@Scheduled` 스위퍼 둘을 더한다.

| 대상 | 조건 | 전이 |
|---|---|---|
| 상담 요청 | `status='REQUESTED'` 이고 `requested_at + 10분 < now()` (C-01) | `EXPIRED` + 방문자·직원 토픽에 `expired` 발행 |
| 직원 초대 | `status='PENDING'` 이고 `expires_at < now()` (C-07, 48시간) | `EXPIRED` |

**Rationale**: develop에 이미 `@Scheduled` 스위퍼가 7개 돈다(`BoothLeaseExpirySweeper`·`AiDocumentExpirySweeper` 등). 같은 모양이면 배치 주기·로그·테스트 관례를 그대로 쓴다. `staff_invitations.expires_at`은 V1부터 있는 컬럼이라 초대 쪽은 조건만 쓰면 된다.

**SC-003(무한 대기 0건)은 스위퍼가 아니라 계약이 먼저 막는다** — 요청 응답에 `expiresInSeconds: 600`이 실려 FE가 잔여 시간을 보여 주고 재요청 버튼을 낸다. 스위퍼는 서버 쪽 정본을 맞추는 장치다.
