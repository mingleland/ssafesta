import { beforeEach, describe, expect, it, vi } from 'vitest';

const realtime = vi.hoisted(() => ({
  connect: vi.fn(),
  subscribe: vi.fn(),
}));
const toasts = vi.hoisted(() => ({ show: vi.fn() }));
const query = vi.hoisted(() => ({ invalidate: vi.fn() }));
const wallet = vi.hoisted(() => ({
  getTransactions: vi.fn(),
  getWallet: vi.fn(),
}));

vi.mock('../../../../../shared/realtime/realtimeClient', () => ({
  connectRealtime: realtime.connect,
  subscribeRealtime: realtime.subscribe,
}));
vi.mock('../../../../../shared/ui/toast/toastStore', () => ({ showToast: toasts.show }));
vi.mock('../../../../../app/providers/queryClient', () => ({
  queryClient: { invalidateQueries: query.invalidate },
}));
vi.mock('../../../../../entities/wallet/api.select', () => ({
  walletApi: {
    getTransactions: wallet.getTransactions,
    getWallet: wallet.getWallet,
  },
}));

import { ALLOWED_SUBSCRIBE_DESTINATIONS, COIN_QUEUE } from '../../../../../shared/realtime/destinations';
import { startCoinGrantNotifications } from '../../coinGrantNotification';

beforeEach(() => {
  realtime.connect.mockResolvedValue(undefined);
  realtime.subscribe.mockReset();
  toasts.show.mockReset();
  query.invalidate.mockReset();
  wallet.getTransactions.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
});

describe('coinGrantNotification', () => {
  it('/user/queue/coin destination이 allowlist에 등록되어 있다', () => {
    expect(COIN_QUEUE).toBe('/user/queue/coin');
    expect(ALLOWED_SUBSCRIBE_DESTINATIONS).toContain(COIN_QUEUE);
  });

  it('구독 등록 시 실시간 연결과 함께 초기 transactions snapshot을 1회 요청한다', () => {
    startCoinGrantNotifications();
    expect(realtime.subscribe).toHaveBeenCalledWith(COIN_QUEUE, expect.any(Function));
    expect(realtime.connect).toHaveBeenCalledOnce();
    expect(wallet.getTransactions).toHaveBeenCalledWith(0);
  });

  it('coin frame 수신 시 Toast를 발생시키고 wallet query를 invalidate/refetch한다', () => {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });

    startCoinGrantNotifications();

    const payload = JSON.stringify({
      type: 'granted',
      entryId: '4821',
      amount: 50,
      balanceAfter: 250,
      reasonType: 'DAILY_GRANT',
      referenceType: null,
      referenceId: null,
      occurredAt: '2026-09-18T04:41:33Z',
    });

    handler?.(payload);

    expect(toasts.show).toHaveBeenCalledWith('일일 지급 +50 코인이 지급되었습니다.', 'success');
    expect(query.invalidate).toHaveBeenCalledWith({ queryKey: ['wallet-balance'] });
    expect(query.invalidate).toHaveBeenCalledWith({ queryKey: ['wallet-transactions'] });
  });

  it('이벤트 amount를 화면 잔액에 직접 가산하지 않고 REST query 갱신을 정본으로 취한다', () => {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });

    startCoinGrantNotifications();

    handler?.(
      JSON.stringify({
        type: 'granted',
        entryId: '9999',
        amount: 100,
        balanceAfter: 350,
        reasonType: 'DAILY_MISSION',
        referenceType: null,
        referenceId: null,
        occurredAt: '2026-09-18T05:00:00Z',
      }),
    );

    // invalidateQueries만 호출하고 클라이언트 내부 수동 state mutation이나 로컬 덧셈을 수행하지 않음
    expect(query.invalidate).toHaveBeenCalledWith({ queryKey: ['wallet-balance'] });
  });
});
