// Consultation mock 시뮬레이터 — Port 의 유일한 구현 (real 은 BE -137 구현 후).
// 테스트·개발에서 수락/만료/종료를 트리거할 수 있게 시뮬레이션 함수를 노출한다.
import type {
  ConsultationActiveSession,
  ConsultationChannelPort,
  ConsultationRequestCard,
  ConsultationStaffPort,
  VisitorChannelEvent,
} from './channel.port';
import { CONSULTATION_EXPIRY_SECONDS } from './channel.port';

let visitorListeners = new Set<(event: VisitorChannelEvent) => void>();

const QUEUE_FIXTURE: ConsultationRequestCard[] = [
  {
    requestId: 'req-1',
    visitorNickname: '방문자A',
    requestedAt: '2026-09-01T10:00:00+09:00',
    handoffSummary: 'AI 상담에서 채용 연계 전형 일정을 문의했습니다.',
  },
  { requestId: 'req-2', visitorNickname: '방문자B', requestedAt: '2026-09-01T10:03:00+09:00', handoffSummary: null },
];

let queue: ConsultationRequestCard[] = [...QUEUE_FIXTURE];
let active: ConsultationActiveSession | null = null;

export const consultationVisitorMock: ConsultationChannelPort = {
  async requestConsultation(_boothId: number) {
    return { expiresInSeconds: CONSULTATION_EXPIRY_SECONDS };
  },
  async cancelRequest() {},
  onVisitorEvent(cb) {
    visitorListeners.add(cb);
    return () => visitorListeners.delete(cb);
  },
};

export const consultationStaffMock: ConsultationStaffPort = {
  async getQueue() {
    return [...queue];
  },
  async accept(requestId: string) {
    const card = queue.find((c) => c.requestId === requestId);
    if (!card) throw new Error('요청이 큐에 없습니다.');
    queue = queue.filter((c) => c.requestId !== requestId);
    active = { requestId: card.requestId, visitorNickname: card.visitorNickname, handoffSummary: card.handoffSummary };
    return { ...active };
  },
  async end() {
    active = null;
  },
};

/** 시뮬레이션 트리거 — 개발 콘솔·테스트에서 서버 이벤트를 흉내낸다 */
export function __simulateVisitorEvent(event: VisitorChannelEvent): void {
  for (const cb of visitorListeners) cb(event);
}

export function __resetConsultationMockForTests(): void {
  visitorListeners = new Set();
  queue = [...QUEUE_FIXTURE];
  active = null;
}
