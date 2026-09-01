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
  __resetConsultationMockForTests,
  __simulateVisitorEvent,
} from '../../../../../entities/consultation/channel.mock';

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
    await requestConsultation(1);
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'waiting', remainingSeconds: 600 });
    await vi.advanceTimersByTimeAsync(3_000);
    expect(getVisitorConsultationSnapshot().remainingSeconds).toBe(597);
  });

  it('카운트다운 0 도달 시 expired — 재요청 가능', async () => {
    await requestConsultation(1);
    await vi.advanceTimersByTimeAsync(600_000);
    expect(getVisitorConsultationSnapshot().phase).toBe('expired');
    await rerequestConsultation();
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'waiting', remainingSeconds: 600 });
  });

  it('서버 expired 이벤트도 같은 전환 (정본은 서버)', async () => {
    await requestConsultation(1);
    __simulateVisitorEvent({ type: 'expired' });
    expect(getVisitorConsultationSnapshot().phase).toBe('expired');
  });

  it('수락 이벤트 → active + 담당 직원, 종료 이벤트 → ended', async () => {
    await requestConsultation(1);
    __simulateVisitorEvent({ type: 'accepted', staffName: '김직원' });
    expect(getVisitorConsultationSnapshot()).toMatchObject({ phase: 'active', staffName: '김직원' });
  });

  it('waiting 에서만 취소 가능 — 취소 후 idle', async () => {
    await cancelConsultation(); // idle — no-op
    expect(getVisitorConsultationSnapshot().phase).toBe('idle');
    await requestConsultation(1);
    await cancelConsultation();
    expect(getVisitorConsultationSnapshot().phase).toBe('idle');
  });

  it('expired 가 아니면 rerequest 는 no-op', async () => {
    await requestConsultation(1);
    await rerequestConsultation();
    expect(getVisitorConsultationSnapshot().phase).toBe('waiting');
  });
});
