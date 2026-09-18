// STOMP destination 정본 — 이 목록 밖으로 나가면 서버가 세션을 끊는다 (S15P21A604-686·-693).
//
// 한 소켓이 상담 알림과 월드 채팅을 함께 나르므로 **오타 하나에 둘 다 끊긴다.** 그래서 문자열을
// 여기 한 곳에만 두고 transport 가 allowlist 로 강제한다. SEND 만이 아니라 SUBSCRIBE 도 막는다 —
// `-693` 으로 부스 토픽 구독에도 같은 제재가 붙었다.

/** 클라이언트가 보낼 수 있는 유일한 destination. 접두 매칭이 아니라 정확히 일치다 */
export const WORLD_CHAT_SEND = '/app/world/chat';

export const WORLD_CHAT_TOPIC = '/topic/world/chat';
export const WORLD_CHAT_ERRORS = '/user/queue/world/chat/errors';
/** D07 — the tenant only; Spring resolves this through the authenticated STOMP principal. */
export const BOOTH_LEASE_EXPIRY_QUEUE = '/user/queue/booth-lease-expiry';

export const ALLOWED_SEND_DESTINATIONS: readonly string[] = [WORLD_CHAT_SEND];

// 상담 real 어댑터가 도착하면 `/user/queue/consultation` 과 부스 토픽이 여기 붙는다.
export const ALLOWED_SUBSCRIBE_DESTINATIONS: readonly string[] = [
  WORLD_CHAT_TOPIC,
  WORLD_CHAT_ERRORS,
  BOOTH_LEASE_EXPIRY_QUEUE,
];
