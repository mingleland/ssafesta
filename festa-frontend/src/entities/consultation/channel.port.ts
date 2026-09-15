// Consultation Channel Port — FE 가 필요로 하는 데이터·이벤트 요구의 표현 (S15P21A604-375).
// spec 011 확정 정책만 소비한다: C-01 만료 10분, C-04 게스트 불가(진입점에서 미노출),
// C-05 실시간은 STOMP over native WS, C-06 직원 동시 1건.
//
// BE 계약이 확정됐다 (#133 2026-09-07 · S15P21A604-519 선반영). 구현 도착은 미정이지만
// (`-136` 5SP · `-137` 8SP 스프린트 미배정) 이름·경로는 바뀌지 않는다고 BE 가 명시했다.
// 아래는 real 어댑터가 구현할 계약이다 — STOMP 는 그 어댑터의 내부 상세고 이 Port 에 새지 않는다.
//
//   WS        /ws   native WebSocket + STOMP (SockJS 없음, C-05)
//             `/api/v1/*` 아래가 아니다 — Spring 의 STOMP 등록이 REST 와 별도다
//   Token     POST /api/v1/realtime/ws-token → { token, expiresInSeconds }
//             STOMP CONNECT 의 `Authorization: Bearer <token>` 헤더로 넘긴다. 재연결 시 새로 발급.
//             **URL query 로 토큰을 넘기지 않는다** (FR-019 · 헌법 13조) — connectHeaders 경로다
//
//   ⚠️ 위 둘은 2026-09-14 에 바뀐 값이다. 원래 이 자리에 `wss://<host>/ws/consultation` 과
//   `POST /api/v1/consultation/ws-token` 이 적혀 있었는데, 월드 공용 텍스트 채팅(`-687`·`-706`,
//   GitLab #187)이 들어오면서 **소켓 하나를 채팅과 상담이 나눠 쓰는 구조**가 되고 이름도
//   `realtime` 으로 일반화됐다. 상담 전용 소켓은 만들지 않는다.
//
//   그래서 real 어댑터는 여기에 STOMP 를 새로 구현하지 않고 `shared/realtime` 의 공용
//   transport 를 쓴다. destination 정본은 `shared/realtime/destinations.ts` 한 곳이고,
//   transport 가 SEND·SUBSCRIBE 양쪽에 allowlist 를 강제한다 — 목록 밖으로 나가면 서버가
//   세션을 끊어서(`-686`·`-693`) 오타 하나에 채팅 알림까지 함께 죽는다. 아래 SUBSCRIBE 두
//   destination 을 그 목록에 더하는 것이 real 어댑터의 첫 걸음이다.
//   SUBSCRIBE /user/queue/consultation             방문자 — 내 요청의 상태 변화
//             /topic/booths/{boothId}/consultation 직원 — 그 부스 대기열 변화
//   SEND      **없다.** P1 STOMP 는 서버→클라이언트 단방향 알림 전용이고(C-12 가 메시지 송수신을
//             P2 로 내렸다) 요청·취소·수락·종료는 전부 아래 REST 다
//   봉투      { type, requestId, occurredAt, … } — `type` 으로 갈라 읽는다
//
//   REST                                                          Port 메서드
//   POST   /api/v1/consultation/requests                          requestConsultation
//          body { boothId, conversationId? } → { requestId, expiresInSeconds: 600 }
//   DELETE /api/v1/consultation/requests/{requestId} → 204        cancelRequest
//   GET    /api/v1/booths/{boothId}/consultation/requests         getQueue
//   POST   /api/v1/consultation/requests/{requestId}/accept       accept
//          C-06 위반은 409 `CONSULTATION_ALREADY_ACTIVE`
//   POST   /api/v1/consultation/sessions/{sessionId}/end          end
//
// `requestId`·`sessionId` 는 Port 밖으로 내보내지 않는다 — 어댑터가 REST 응답에서 받아 들고
// 있다가 cancel·end 경로에 쓴다. FE 상태 기계가 서버 식별자를 운반할 이유가 없다.
//
// **재연결 시 REST 로 다시 읽는다.** 이벤트 재전송이 P1 에 없어서 STOMP 가 끊긴 사이의 변화는
// 유실된다. 어댑터는 재연결 직후 `getQueue()`(직원)·요청 상태(방문자)를 한 번 재조회해야 한다.
// `occurredAt` 은 그때 순서를 가르는 값이다.

/** C-01 — REQUESTED → EXPIRED 만료 초. 정본은 서버지만 FE 잔여 시간 안내의 기준값 */
export const CONSULTATION_EXPIRY_SECONDS = 600;

export type VisitorChannelEvent =
  | { type: 'accepted'; staffName: string }
  | { type: 'expired' } // C-01 서버 기준 만료
  | { type: 'ended' };

/**
 * 직원 토픽(`/topic/booths/{boothId}/consultation`) 이벤트.
 *
 * **아직 구독하는 코드가 없다** — 상담 운영 화면은 `getQueue()` 재조회(새로고침)로 돈다.
 * 그래도 여기 적어 두는 이유는 `taken` 때문이다: 직원이 여럿일 때 남이 먼저 수락한 카드가
 * 대기열에 남아 있다가 `accept` 에서 409 로 터진다. 실시간 구독을 붙일 때 이 봉투를 다시
 * 발명하지 않도록 확정본을 코드에 고정해 둔다(#133).
 *
 * **`ended` 가 빠져 있는 것은 실수가 아니라 계약 그대로다.** 방문자가 먼저 종료하면 직원
 * 토픽으로는 아무 알림도 가지 않아서 직원 화면에 "진행 중" 이 남는다. BE 가 2026-09-14 에
 * 이 모순을 찾아 결정을 요청했고(#133), FE 는 **직원 토픽에 `ended` 를 추가하는 쪽**으로
 * 답했다 — 봉투는 `{ type: 'ended', requestId, occurredAt }`, 추가 필드 없음.
 *
 * 타입을 아직 안 늘린 이유는 서버가 그 이벤트를 아직 보내지 않기 때문이다. 안 오는 이벤트를
 * 먼저 올리면 한 번도 안 타는 분기가 생기고, 그게 `BOOTH_FORBIDDEN` 이 났던 자리다.
 * BE 구현이 develop 에 닿으면 여기 한 줄을 더한다.
 */
export type StaffChannelEvent =
  | { type: 'requested'; visitorNickname: string; requestedAt: string; handoffSummary: string | null }
  | { type: 'cancelled' }
  | { type: 'expired' }
  /** 다른 직원이 먼저 수락 — 대기열에서 카드를 내린다 */
  | { type: 'taken'; staffName: string };

export interface ConsultationRequestCard {
  requestId: string;
  visitorNickname: string;
  requestedAt: string;
  /** AI 대화 요약 (spec 011 Handoff Summary) — 없으면 null */
  handoffSummary: string | null;
}

export interface ConsultationActiveSession {
  requestId: string;
  visitorNickname: string;
  handoffSummary: string | null;
}

export interface ConsultationChannelPort {
  /**
   * 상담 요청 — 반환값은 만료까지 남은 초 (C-01).
   *
   * `conversationId` 만 넘긴다. **요약 텍스트는 FE 가 만들지도 보내지도 않는다** — 서버가 이 id 로
   * FastAPI 에 요약을 요청해 요청 생성 시점의 스냅샷으로 굳힌다(FR-012). 이후 대화가 이어져도
   * 갱신되지 않는다: 직원이 본 요약이 나중에 달라지면 안 된다.
   * 없으면(대화 없이 바로 요청) 요약은 `null` 이고, 요약 생성 실패도 `null` 일 뿐 요청은 진행된다.
   */
  requestConsultation(boothId: number, conversationId?: string): Promise<{ expiresInSeconds: number }>;
  cancelRequest(): Promise<void>;
  onVisitorEvent(cb: (event: VisitorChannelEvent) => void): () => void;
}

export interface ConsultationStaffPort {
  getQueue(): Promise<ConsultationRequestCard[]>;
  /** C-06 — 활성 상담이 있으면 서버도 409 로 거부한다. FE 는 호출 전에 게이트한다 */
  accept(requestId: string): Promise<ConsultationActiveSession>;
  end(): Promise<void>;
}
