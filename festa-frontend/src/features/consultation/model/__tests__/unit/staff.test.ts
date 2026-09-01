import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/consultation/channel.select', async () => {
  const mock = await import('../../../../../entities/consultation/channel.mock');
  return { consultationChannel: mock.consultationVisitorMock, consultationStaff: mock.consultationStaffMock };
});

import {
  __resetStaffConsultationForTests,
  acceptRequest,
  canAccept,
  endActiveConsultation,
  getStaffConsultationSnapshot,
  loadStaffQueue,
} from '../../staff';
import { __resetConsultationMockForTests } from '../../../../../entities/consultation/channel.mock';

beforeEach(() => {
  __resetStaffConsultationForTests();
  __resetConsultationMockForTests();
});

describe('Staff 상담 (C-06 동시 1건)', () => {
  it('큐 로드 — Handoff 요약 포함', async () => {
    await loadStaffQueue();
    const s = getStaffConsultationSnapshot();
    expect(s.status).toBe('ready');
    expect(s.queue).toHaveLength(2);
    expect(s.queue[0].handoffSummary).toContain('채용 연계');
  });

  it('수락 → active·큐에서 제거. 활성 중에는 추가 수락 게이트', async () => {
    await loadStaffQueue();
    await acceptRequest('req-1');
    const s = getStaffConsultationSnapshot();
    expect(s.active?.requestId).toBe('req-1');
    expect(s.queue.map((c) => c.requestId)).toEqual(['req-2']);
    expect(canAccept()).toBe(false); // C-06
    await acceptRequest('req-2'); // no-op
    expect(getStaffConsultationSnapshot().active?.requestId).toBe('req-1');
  });

  it('종료 후 다시 수락 가능', async () => {
    await loadStaffQueue();
    await acceptRequest('req-1');
    await endActiveConsultation();
    expect(getStaffConsultationSnapshot().active).toBeNull();
    expect(canAccept()).toBe(true);
    await acceptRequest('req-2');
    expect(getStaffConsultationSnapshot().active?.requestId).toBe('req-2');
  });
});
