// Consultation Channel Port — FE 가 필요로 하는 데이터·이벤트 요구의 표현 (S15P21A604-375).
// spec 011 확정 정책만 소비한다: C-01 만료 10분, C-04 게스트 불가(진입점에서 미노출),
// C-05 실시간은 STOMP over native WS, C-06 직원 동시 1건.
// WS 엔드포인트·토큰·STOMP destination·payload schema 는 미확정(-137) — 여기 없다.
// BE 구현 도착 시 real 어댑터가 이 Port 를 구현한다(STOMP 는 그 어댑터의 내부 상세).

/** C-01 — REQUESTED → EXPIRED 만료 초. 정본은 서버지만 FE 잔여 시간 안내의 기준값 */
export const CONSULTATION_EXPIRY_SECONDS = 600;

export type VisitorChannelEvent =
  | { type: 'accepted'; staffName: string }
  | { type: 'expired' } // C-01 서버 기준 만료
  | { type: 'ended' };

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
  /** 상담 요청 — 반환값은 만료까지 남은 초 (C-01) */
  requestConsultation(boothId: number): Promise<{ expiresInSeconds: number }>;
  cancelRequest(): Promise<void>;
  onVisitorEvent(cb: (event: VisitorChannelEvent) => void): () => void;
}

export interface ConsultationStaffPort {
  getQueue(): Promise<ConsultationRequestCard[]>;
  /** C-06 — 활성 상담이 있으면 서버도 거부한다. FE 는 호출 전에 게이트한다 */
  accept(requestId: string): Promise<ConsultationActiveSession>;
  end(): Promise<void>;
}
