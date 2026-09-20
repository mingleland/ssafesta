import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/consultation/channel.select', async () => {
  const mock = await import('../../../../../entities/consultation/channel.mock');
  return { consultationChannel: mock.consultationVisitorMock, consultationStaff: mock.consultationStaffMock };
});

import {
  __resetVisitorConsultationForTests,
  cancelConsultation,
  getVisitorConsultationSnapshot,
  requestConsultation,
  rerequestConsultation,
} from '../../visitor';
import {
  __lastRequestedConversationId,
  __resetConsultationMockForTests,
  __simulateVisitorEvent,
} from '../../../../../entities/consultation/channel.mock';
import { aiHandoffContext } from '../../startContext';

beforeEach(() => {
  vi.useFakeTimers();
  __resetVisitorConsultationForTests();
  __resetConsultationMockForTests();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('방문자 상담 흐름 (C-01)', () => {
  it('요청 → waiting, 만료 10분(600초) 잔여 시간 카운트다운', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'waiting', remainingSeconds: 600 });
    await vi.advanceTimersByTimeAsync(3_000);
    expect(getVisitorConsultationSnapshot().remainingSeconds).toBe(597);
  });

  it('카운트다운 0 도달 시 expired — 재요청 가능', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    await vi.advanceTimersByTimeAsync(600_000);
    expect(getVisitorConsultationSnapshot().phase).toBe('expired');
    await rerequestConsultation();
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'waiting', remainingSeconds: 600 });
  });

  it('서버 expired 이벤트도 같은 전환 (정본은 서버)', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    __simulateVisitorEvent({ type: 'expired' });
    expect(getVisitorConsultationSnapshot().phase).toBe('expired');
  });

  it('수락 이벤트 → active + 담당 직원, 종료 이벤트 → ended', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    __simulateVisitorEvent({ type: 'accepted', staffName: '김직원' });
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'active', staffName: '김직원' });
  });

  it('waiting 에서만 취소 가능 — 취소 후 idle', async () => {
    await cancelConsultation(); // idle — no-op
    expect(getVisitorConsultationSnapshot().phase).toBe('idle');
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    await cancelConsultation();
    expect(getVisitorConsultationSnapshot().phase).toBe('idle');
  });

  it('expired 가 아니면 rerequest 는 no-op', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    await rerequestConsultation();
    expect(getVisitorConsultationSnapshot().phase).toBe('waiting');
  });

  it('accepted 후에도 채널이 살아 있어 ended 가 도달한다 — active 감금 해소 (-377)', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    __simulateVisitorEvent({ type: 'accepted', staffName: '김직원' });
    expect(getVisitorConsultationSnapshot().phase).toBe('active');
    __simulateVisitorEvent({ type: 'ended' });
    expect(getVisitorConsultationSnapshot().phase).toBe('ended');
  });

  it('로컬 만료 표시 후 도착한 서버 accepted 가 이긴다 — 만료 정본은 서버 (-377)', async () => {
    await requestConsultation({ boothId: 1, source: 'AI_HANDOFF' });
    await vi.advanceTimersByTimeAsync(600_000);
    expect(getVisitorConsultationSnapshot().phase).toBe('expired');
    __simulateVisitorEvent({ type: 'accepted', staffName: '김직원' });
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'active', staffName: '김직원' });
  });
});

// 요약 재료를 무엇으로 보내는가 (#133 확정 계약 · S15P21A604-519).
// 계약 전에는 FE 가 마지막 AI 답변 텍스트를 실어 보냈다. 지금은 conversationId 만 보내고
// 요약은 서버가 만든다 — 이 구분이 깨지면 직원이 보는 요약의 정본이 다시 FE 로 넘어온다.
describe('AI 대화 handoff — conversationId 만 보낸다', () => {
  it('요청 body 재료로 conversationId 가 채널까지 간다', async () => {
    await requestConsultation(aiHandoffContext(7, 'conv-abc'));
    expect(__lastRequestedConversationId()).toBe('conv-abc');
  });

  it('대화 없이 시작하면 undefined — 서버가 요약을 null 로 만든다', async () => {
    await requestConsultation({ boothId: 7, source: 'CONSULTATION_DESK' });
    expect(__lastRequestedConversationId()).toBeUndefined();
  });

  it('만료 후 재요청도 같은 대화를 실어 보낸다 — 맥락이 재요청에서 끊기면 안 된다', async () => {
    await requestConsultation(aiHandoffContext(7, 'conv-abc'));
    await vi.advanceTimersByTimeAsync(600_000);
    expect(getVisitorConsultationSnapshot().phase).toBe('expired');

    __resetConsultationMockForTests();
    await rerequestConsultation();
    expect(__lastRequestedConversationId()).toBe('conv-abc');
  });
});
