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
import {
  holdCoinGrantToasts,
  releaseCoinGrantToasts,
  startCoinGrantNotifications,
} from '../../coinGrantNotification';

beforeEach(() => {
  // 붙들기는 모듈 상태다 — 앞 테스트가 쌓아 둔 채 끝나면 여기서 풀린다. mock 을 지우기 **전에**
  // 비워야 그 잔여 토스트가 다음 테스트의 호출로 세어지지 않는다.
  releaseCoinGrantToasts();
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
    // start 는 입장 전까지 붙든다 — 이 테스트가 보는 것은 붙들기가 아니라 토스트 내용이다.
    releaseCoinGrantToasts();

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
      // BE 는 createdAt desc, id desc 로 준다(CoinLedgerEntryRepository) — 실제 순서로 고정한다.
      content: [
        { id: 13, entryType: 'CHARGE', amount: 50, balanceAfter: 250, reasonType: 'DAILY_GRANT', referenceType: null, referenceId: null, createdAt: justNow },
        { id: 12, entryType: 'CHARGE', amount: 200, balanceAfter: 200, reasonType: 'INITIAL_GRANT', referenceType: null, referenceId: null, createdAt: justNow },
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });

    startCoinGrantNotifications();
    await Promise.resolve();
    await Promise.resolve();

    // catch-up 은 입장 전에 끝나 큐에 쌓인다 — 입장 순간 이 순서 그대로 나가는지를 본다.
    releaseCoinGrantToasts();

    // 최신순 그대로 나간다 — 붙들기는 순서를 바꾸지 않는다.
    expect(toasts.show).toHaveBeenNthCalledWith(1, '일일 지급 +50 코인이 지급되었습니다.', 'success');
    expect(toasts.show).toHaveBeenNthCalledWith(2, '가입 지급 +200 코인이 지급되었습니다.', 'success');
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

// 로딩 판 위에서 떴다 사라지는 것을 막는다 — WorldPage 가 입장 전까지 붙들고, 입장 순간에 푼다.
describe('입장 전 코인 토스트 붙들기', () => {
  function receive(reasonType: string, amount: number) {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });
    startCoinGrantNotifications();
    handler?.(
      JSON.stringify({
        type: 'granted',
        entryId: '777',
        amount,
        balanceAfter: 1000,
        reasonType,
        referenceType: null,
        referenceId: null,
        occurredAt: new Date().toISOString(),
      }),
    );
  }

  it('붙들고 있는 동안에는 토스트를 내지 않고 잔액·내역은 그대로 갱신한다', () => {
    holdCoinGrantToasts();
    receive('DAILY_GRANT', 50);

    expect(toasts.show).not.toHaveBeenCalled();
    expect(query.invalidate).toHaveBeenCalledWith({ queryKey: ['wallet-balance'] });
  });

  it('구독을 시작하는 순간부터 붙든다 — 월드 라우트보다 로그인 처리가 먼저다', () => {
    // hold 를 부르지 않는다. start 자체가 켜야 catch-up 이 로딩 판 위로 새지 않는다.
    receive('DAILY_GRANT', 50);

    expect(toasts.show).not.toHaveBeenCalled();

    releaseCoinGrantToasts();
    expect(toasts.show).toHaveBeenCalledWith('일일 지급 +50 코인이 지급되었습니다.', 'success');
  });

  it('풀면 쌓인 것을 순서대로 낸다 — 미루는 것이지 버리는 것이 아니다', () => {
    holdCoinGrantToasts();
    receive('INITIAL_GRANT', 200);
    receive('DAILY_GRANT', 50);
    expect(toasts.show).not.toHaveBeenCalled();

    releaseCoinGrantToasts();

    expect(toasts.show).toHaveBeenNthCalledWith(1, '가입 지급 +200 코인이 지급되었습니다.', 'success');
    expect(toasts.show).toHaveBeenNthCalledWith(2, '일일 지급 +50 코인이 지급되었습니다.', 'success');
  });

  it('입장 뒤에 온 지급은 그 자리에서 뜬다 — 붙들기는 입장 전까지다', () => {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });
    startCoinGrantNotifications();
    releaseCoinGrantToasts(); // 월드 입장

    handler?.(
      JSON.stringify({
        type: 'granted',
        entryId: '778',
        amount: 15,
        balanceAfter: 1015,
        reasonType: 'DAILY_MISSION',
        referenceType: null,
        referenceId: null,
        occurredAt: new Date().toISOString(),
      }),
    );

    expect(toasts.show).toHaveBeenCalledWith('일일 미션 보상 +15 코인이 지급되었습니다.', 'success');
  });

  it('붙들어도 무음 사유는 쌓이지 않는다 — 풀 때 슬롯 당첨이 쏟아지지 않게', () => {
    holdCoinGrantToasts();
    receive('SLOT_PAYOUT', 300);

    releaseCoinGrantToasts();

    expect(toasts.show).not.toHaveBeenCalled();
  });

  it('세션이 끝나면 대기분을 버린다 — 다음 로그인에 남의 토스트가 튀지 않게', () => {
    let handler: ((body: string) => void) | undefined;
    realtime.subscribe.mockImplementation((_destination: string, next: (body: string) => void) => {
      handler = next;
      return vi.fn();
    });
    const stop = startCoinGrantNotifications();
    handler?.(
      JSON.stringify({
        type: 'granted',
        entryId: '779',
        amount: 200,
        balanceAfter: 200,
        reasonType: 'INITIAL_GRANT',
        referenceType: null,
        referenceId: null,
        occurredAt: new Date().toISOString(),
      }),
    );

    stop(); // 로그아웃
    releaseCoinGrantToasts(); // 다음 사용자가 월드에 입장

    expect(toasts.show).not.toHaveBeenCalled();
  });
});
