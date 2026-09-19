// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { EventRewardShopOverlay } from '../../ui/EventRewardShopOverlay';
import { ApiError } from '../../../../shared/api/client';

const toasts = vi.hoisted(() => ({ show: vi.fn() }));
vi.mock('../../../../shared/ui/toast/toastStore', () => ({ showToast: toasts.show }));

const eventShopMock = vi.hoisted(() => ({
  listPrizes: vi.fn(),
  purchasePrize: vi.fn(),
}));
vi.mock('../../../../entities/eventShop/api.select', () => ({
  eventShopApi: eventShopMock,
}));

const raffleMock = vi.hoisted(() => ({
  listRaffles: vi.fn(),
  enterRaffle: vi.fn(),
}));
vi.mock('../../../../entities/raffle/api.select', () => ({
  raffleApi: raffleMock,
}));

vi.mock('../../wallet/ui/WalletBadge', () => ({
  WalletBadge: () => <span data-testid="wallet-badge">1000 C</span>,
}));

describe('EventRewardShopOverlay - Raffle and Error Handling (S15P21A604-922)', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    if (!HTMLDialogElement.prototype.showModal) {
      HTMLDialogElement.prototype.showModal = function showModal() {
        this.setAttribute('open', '');
      };
    }
    if (!HTMLDialogElement.prototype.close) {
      HTMLDialogElement.prototype.close = function close() {
        this.removeAttribute('open');
      };
    }
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    toasts.show.mockReset();
    eventShopMock.listPrizes.mockReset();
    eventShopMock.purchasePrize.mockReset();
    raffleMock.listRaffles.mockReset();
    raffleMock.enterRaffle.mockReset();
  });

  function renderComponent() {
    return render(
      <QueryClientProvider client={queryClient}>
        <EventRewardShopOverlay />
      </QueryClientProvider>,
    );
  }

  it('winnerCount > 0 인 경품은 응모형 카드로 렌더링된다', async () => {
    eventShopMock.listPrizes.mockResolvedValue([
      { prizeId: 1, name: '마이구미', priceCoin: 10, stock: 50, active: true, winnerCount: 0 },
    ]);
    raffleMock.listRaffles.mockResolvedValue([
      { raffleId: 6, name: '치킨', priceCoin: 50, stock: 100, active: true, closesAt: '2026-09-23T06:00:00Z', drawAt: null },
    ]);

    renderComponent();

    expect(await screen.findByText('마이구미')).not.toBeNull();
    expect(await screen.findByText('치킨')).not.toBeNull();
    expect(screen.getByRole('button', { name: '응모' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '구매' })).not.toBeNull();
  });

  it('EVENT_PRIZE_ALREADY_ENTERED 에러 수신 시 중복 응모 토스트를 표시한다', async () => {
    eventShopMock.listPrizes.mockResolvedValue([]);
    raffleMock.listRaffles.mockResolvedValue([
      { raffleId: 6, name: '치킨', priceCoin: 50, stock: 100, active: true, closesAt: null, drawAt: null },
    ]);

    const apiErr: ApiError = {
      code: 'EVENT_PRIZE_ALREADY_ENTERED',
      message: '이미 응모하셨습니다.',
      requestId: 'req-1',
      errors: [],
      warnings: [],
    };
    raffleMock.enterRaffle.mockRejectedValue(apiErr);

    renderComponent();

    const enterBtn = await screen.findByRole('button', { name: '응모' });
    fireEvent.click(enterBtn);

    // 폼 입력
    fireEvent.change(screen.getByLabelText(/조 이름/), { target: { value: 'A604' } });
    fireEvent.change(screen.getByLabelText(/^이름/), { target: { value: '홍길동' } });
    fireEvent.click(screen.getByRole('button', { name: '응모 확정' }));

    await waitFor(() => {
      expect(toasts.show).toHaveBeenCalledWith('이미 응모한 경품입니다.', 'error');
    });
  });

  it('EVENT_PRIZE_CLOSED 에러 수신 시 마감 토스트를 표시한다', async () => {
    eventShopMock.listPrizes.mockResolvedValue([]);
    raffleMock.listRaffles.mockResolvedValue([
      { raffleId: 6, name: '치킨', priceCoin: 50, stock: 100, active: true, closesAt: null, drawAt: null },
    ]);

    const apiErr: ApiError = {
      code: 'EVENT_PRIZE_CLOSED',
      message: '응모가 마감됐습니다.',
      requestId: 'req-2',
      errors: [],
      warnings: [],
    };
    raffleMock.enterRaffle.mockRejectedValue(apiErr);

    renderComponent();

    const enterBtns = await screen.findAllByRole('button', { name: '응모' });
    fireEvent.click(enterBtns[0]);
    fireEvent.change(screen.getByLabelText(/조 이름/), { target: { value: 'A604' } });
    fireEvent.change(screen.getByLabelText(/^이름/), { target: { value: '홍길동' } });
    fireEvent.click(screen.getByRole('button', { name: '응모 확정' }));

    await waitFor(() => {
      expect(toasts.show).toHaveBeenCalledWith('응모가 마감되었습니다.', 'error');
    });
  });

  it('EVENT_PRIZE_OUT_OF_STOCK 에러 수신 시 재고 소진 토스트를 표시한다', async () => {
    eventShopMock.listPrizes.mockResolvedValue([]);
    raffleMock.listRaffles.mockResolvedValue([
      { raffleId: 6, name: '치킨', priceCoin: 50, stock: 100, active: true, closesAt: null, drawAt: null },
    ]);

    const apiErr: ApiError = {
      code: 'EVENT_PRIZE_OUT_OF_STOCK',
      message: '재고가 부족합니다.',
      requestId: 'req-3',
      errors: [],
      warnings: [],
    };
    raffleMock.enterRaffle.mockRejectedValue(apiErr);

    renderComponent();

    const enterBtns = await screen.findAllByRole('button', { name: '응모' });
    fireEvent.click(enterBtns[0]);
    fireEvent.change(screen.getByLabelText(/조 이름/), { target: { value: 'A604' } });
    fireEvent.change(screen.getByLabelText(/^이름/), { target: { value: '홍길동' } });
    fireEvent.click(screen.getByRole('button', { name: '응모 확정' }));

    await waitFor(() => {
      expect(toasts.show).toHaveBeenCalledWith('방금 재고가 소진됐습니다.', 'error');
    });
  });
});
