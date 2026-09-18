import { beforeEach, describe, expect, it, vi } from 'vitest';

const realtime = vi.hoisted(() => ({
  connect: vi.fn(),
  subscribe: vi.fn(),
}));
const toasts = vi.hoisted(() => ({ show: vi.fn() }));

vi.mock('../../../../../shared/realtime/realtimeClient', () => ({
  connectRealtime: realtime.connect,
  subscribeRealtime: realtime.subscribe,
}));
vi.mock('../../../../../shared/ui/toast/toastStore', () => ({ showToast: toasts.show }));

import { BOOTH_LEASE_EXPIRY_QUEUE } from '../../../../../shared/realtime/destinations';
import { startLeaseExpiryNotifications } from '../../leaseExpiryNotification';

beforeEach(() => {
  realtime.connect.mockResolvedValue(undefined);
  realtime.subscribe.mockReset();
  toasts.show.mockReset();
});

describe('startLeaseExpiryNotifications', () => {
  it('개인 큐의 유효한 만료 임박 이벤트를 공통 토스트로 보여 준다', () => {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });

    startLeaseExpiryNotifications();
    expect(realtime.subscribe).toHaveBeenCalledWith(BOOTH_LEASE_EXPIRY_QUEUE, expect.any(Function));
    expect(realtime.connect).toHaveBeenCalledOnce();

    handler?.('{"type":"lease-expiring","leaseId":"11","boothId":"4","endsAt":"2026-09-17T10:00:00Z","remainingSeconds":3599}');
    expect(toasts.show).toHaveBeenCalledWith('내 부스 임대가 약 1시간 뒤 만료됩니다.', 'info');
  });

  it('형식이 다른 이벤트는 알림으로 꾸미지 않고 진단을 남긴다', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });

    startLeaseExpiryNotifications();
    handler?.('{"type":"unexpected"}');

    expect(toasts.show).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledWith(
      '[leaseExpiryNotification] 읽을 수 없는 만료 임박 알림을 버렸습니다.',
      expect.any(Error),
    );
    warn.mockRestore();
  });
});
