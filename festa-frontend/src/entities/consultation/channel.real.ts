// Consultation real 어댑터 — REST 가 정본이고 STOMP 는 알림 전용이다 (spec 011 §B).
//
// BE 계약(ConsultationController)과 Port(channel.port.ts)의 이름·경로가 일치한다 — 2026-09-07
// GitLab #133 확정을 S15P21A604-519 가 선반영했고, 구현은 이번이 처음이다. 실시간은 상담 전용
// 소켓을 만들지 않고 shared/realtime 공용 transport 를 쓴다(C-05, -687). SEND 는 없다(C-12) —
// 요청·취소·수락·종료는 전부 REST 다.
//
// `requestId`·`sessionId` 는 Port 밖으로 내보내지 않는다 — 어댑터가 REST 응답에서 받아 들고
// 있다가 cancel·end 경로에 쓴다(channel.port.ts 계약). 재연결 시 이벤트 재전송이 없어서
// (P1 에 없음) 상태의 정본은 언제나 REST 재조회다.
import type {
  ConsultationChannelPort,
  ConsultationRequestCard,
  ConsultationStaffPort,
  VisitorChannelEvent,
} from './channel.port';
import { api } from '../../shared/api/client';
import { connectRealtime, subscribeRealtime } from '../../shared/realtime/realtimeClient';
import { CONSULTATION_VISITOR_QUEUE } from '../../shared/realtime/destinations';

/** POST /consultation/requests 201 — `requestId` 는 어댑터가 보관한다 */
interface RequestView {
  requestId: string;
  expiresInSeconds: number;
}

/** GET /booths/{boothId}/consultation/requests 200 */
interface QueueItemView {
  requestId: string;
  visitorNickname: string;
  requestedAt: string;
  handoffSummary: string | null;
}

/** POST /consultation/requests/{requestId}/accept 200 — `sessionId` 는 종료 경로에 쓴다 */
interface AcceptedView {
  requestId: string;
  sessionId: string;
  visitorNickname: string;
  handoffSummary: string | null;
}

/** 내 요청(방문자) — cancel 이 이 id 로 간다. 수락·만료·종료 뒤에는 더 쓸 일이 없다 */
let myRequestId: string | null = null;
/** 내 활성 세션(직원) — end 가 이 id 로 간다. 수락이 앞서지 않으면 null 이다 */
let mySessionId: string | null = null;

/** 방문자 봉투 — {type, requestId, occurredAt, …}. VisitorChannelEvent 로 좁힌다 */
function parseVisitorEvent(raw: string): VisitorChannelEvent {
  const parsed: unknown = JSON.parse(raw);
  if (parsed === null || typeof parsed !== 'object') throw new Error('봉투가 객체가 아니다');
  const event = parsed as { type?: unknown; staffName?: unknown };
  if (event.type === 'accepted') {
    if (typeof event.staffName !== 'string') throw new Error('accepted 에 staffName 이 없다');
    return { type: 'accepted', staffName: event.staffName };
  }
  if (event.type === 'expired' || event.type === 'ended') return { type: event.type };
  throw new Error(`알 수 없는 상담 이벤트: ${String(event.type)}`);
}

export const consultationVisitorReal: ConsultationChannelPort = {
  async requestConsultation(boothId: number, conversationId?: string) {
    const view = await api<RequestView>('/api/v1/consultation/requests', {
      method: 'POST',
      body: JSON.stringify({ boothId, conversationId }),
    });
    myRequestId = view.requestId;
    return { expiresInSeconds: view.expiresInSeconds };
  },

  async cancelRequest() {
    if (myRequestId === null) return;
    await api(`/api/v1/consultation/requests/${myRequestId}`, { method: 'DELETE' });
    myRequestId = null;
  },

  onVisitorEvent(cb: (event: VisitorChannelEvent) => void) {
    const stop = subscribeRealtime(CONSULTATION_VISITOR_QUEUE, (body: string) => {
      try {
        cb(parseVisitorEvent(body));
      } catch (error) {
        // 알림은 정본이 아니다 — 모르는 봉투를 화면에 반영하는 대신 원인을 남긴다(T-24 정신)
        console.error('[consultation] 읽을 수 없는 상담 알림을 버렸습니다', body, error);
      }
    });
    void connectRealtime().catch((error: unknown) => {
      console.error('[consultation] 실시간 연결 실패 —', error);
    });
    return stop;
  },
};

export const consultationStaffReal: ConsultationStaffPort = {
  async getQueue(boothId: number) {
    const items = await api<QueueItemView[]>(`/api/v1/booths/${boothId}/consultation/requests`);
    const cards: ConsultationRequestCard[] = items.map((item) => ({
      requestId: item.requestId,
      visitorNickname: item.visitorNickname,
      requestedAt: item.requestedAt,
      handoffSummary: item.handoffSummary,
    }));
    return cards;
  },

  async accept(requestId: string) {
    const view = await api<AcceptedView>(`/api/v1/consultation/requests/${requestId}/accept`, {
      method: 'POST',
    });
    mySessionId = view.sessionId;
    return { requestId: view.requestId, sessionId: view.sessionId, visitorNickname: view.visitorNickname, handoffSummary: view.handoffSummary };
  },

  async end() {
    if (mySessionId === null) {
      // active 가 없는데 end 를 부른 경로는 모델 게이트 버그다 — 조용히 넘기지 않는다(T-24 정신)
      throw new Error('[consultation] 종료할 세션이 없다 — accept 가 먼저여야 한다');
    }
    await api(`/api/v1/consultation/sessions/${mySessionId}/end`, { method: 'POST' });
    mySessionId = null;
  },
};
