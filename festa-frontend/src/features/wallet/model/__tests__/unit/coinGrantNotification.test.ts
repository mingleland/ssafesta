// @vitest-environment jsdom
// 캐치업 워터마크가 localStorage를 쓴다(S15P21A604-953) — 기본 node 환경엔 window가 없다.
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
  window.localStorage.clear();
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

  it('구독 전에 발행된 가입·일일 지급을 catch-up REST 응답에서 찾아 토스트를 대신 띄운다 (S15P21A604-953)', async () => {
    const justNow = new Date().toISOString();
    wallet.getTransactions.mockResolvedValue({
      content: [
        { id: 12, entryType: 'CHARGE', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: justNow },
        { id: 13, entryType: 'CHARGE', amount: 50, balanceAfter: 250, reasonType: 'DAILY_GRANT', referenceType: null, referenceId: null, createdAt: justNow },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });

    startCoinGrantNotifications();
    await Promise.resolve();
    await Promise.resolve();

    expect(toasts.show).toHaveBeenCalledWith('가입 지급 +200 코인이 지급되었습니다.', 'success');
    expect(toasts.show).toHaveBeenCalledWith('일일 지급 +50 코인이 지급되었습니다.', 'success');
  });

  it('이미 알린 거래는 재접속 catch-up에서 다시 토스트를 띄우지 않는다 (S15P21A604-953)', async () => {
    window.localStorage.setItem('festa.wallet.lastAnnouncedGrantId', '13');
    const justNow = new Date().toISOString();
    wallet.getTransactions.mockResolvedValue({
      content: [
        { id: 12, entryType: 'CHARGE', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: justNow },
        { id: 13, entryType: 'CHARGE', amount: 50, balanceAfter: 250, reasonType: 'DAILY_GRANT', referenceType: null, referenceId: null, createdAt: justNow },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });

    startCoinGrantNotifications();
    await Promise.resolve();
    await Promise.resolve();

    expect(toasts.show).not.toHaveBeenCalled();
  });

  it('워터마크 없는 첫 방문(새 브라우저)에서도 오래된 지급은 알리지 않고 워터마크만 올린다 (S15P21A604-953 리뷰)', async () => {
    // 매일 접속하는 오래된 계정 — 최근 20건 중 며칠 치 DAILY_GRANT 가 전부 과거다.
    const daysAgo = (n: number) => new Date(Date.now() - n * 24 * 60 * 60 * 1000).toISOString();
    wallet.getTransactions.mockResolvedValue({
      content: [
        { id: 40, entryType: 'CHARGE', amount: 50, balanceAfter: 500, reasonType: 'DAILY_GRANT', referenceType: null, referenceId: null, createdAt: daysAgo(0.1) },
        { id: 39, entryType: 'CHARGE', amount: 50, balanceAfter: 450, reasonType: 'DAILY_GRANT', referenceType: null, referenceId: null, createdAt: daysAgo(1) },
        { id: 1, entryType: 'CHARGE', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: daysAgo(30) },
      ],
      page: 0,
      size: 20,
      totalElements: 3,
      totalPages: 1,
    });

    startCoinGrantNotifications();
    await Promise.resolve();
    await Promise.resolve();

    // 워터마크가 없던 세션이라도 며칠 치 "일일 지급" 토스트가 한꺼번에 쏟아지지 않는다.
    expect(toasts.show).not.toHaveBeenCalled();
    expect(window.localStorage.getItem('festa.wallet.lastAnnouncedGrantId')).toBe('40');
  });

  it('SLOT_PAYOUT은 실시간 수신에서도 토스트를 띄우지 않는다 (S15P21A604-949 회귀)', () => {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });

    startCoinGrantNotifications();

    handler?.(
      JSON.stringify({
        type: 'granted',
        entryId: '5555',
        amount: 300,
        balanceAfter: 800,
        reasonType: 'SLOT_PAYOUT',
        referenceType: null,
        referenceId: null,
        occurredAt: new Date().toISOString(),
      }),
    );

    expect(toasts.show).not.toHaveBeenCalled();
    // 잔액·내역 갱신은 건너뛰지 않는다 — 감추는 것은 알림뿐이다.
    expect(query.invalidate).toHaveBeenCalledWith({ queryKey: ['wallet-balance'] });
  });
});
