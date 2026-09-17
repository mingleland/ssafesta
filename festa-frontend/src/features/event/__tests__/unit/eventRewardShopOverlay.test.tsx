// @vitest-environment jsdom
// 이벤트 상점 — 즉시교환 실 연동(S15P21A604-842/836) 이후의 shell.
//
// 지키는 것: 상품이 없을 때(PREPARING과 같은 모양) 격자가 살아있고 안내가 얹히는가, 상품이
// 오면 안내만 사라지고 같은 격자에 상품이 차는가, 품절/구매 흐름이 실제 API와 맞물리는가.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const openVisitorOverlay = vi.fn();
vi.mock('../../../world/model/worldScreen', () => ({ openVisitorOverlay }));
// wallet 배지는 별건이다 — 여기서는 상점 구조만 본다
vi.mock('../../../wallet/ui/WalletBadge', () => ({ WalletBadge: () => <span>보유 코인</span> }));

const listPrizes = vi.fn();
const purchasePrize = vi.fn();
vi.mock('../../../../entities/eventShop/api.select', () => ({
  eventShopApi: {
    listPrizes: (...args: unknown[]) => listPrizes(...args),
    purchasePrize: (...args: unknown[]) => purchasePrize(...args),
  },
}));

const listRaffles = vi.fn();
const enterRaffle = vi.fn();
vi.mock('../../../../entities/raffle/api.select', () => ({
  raffleApi: {
    listRaffles: (...args: unknown[]) => listRaffles(...args),
    enterRaffle: (...args: unknown[]) => enterRaffle(...args),
  },
}));

const resolveEventSurveyTarget = vi.fn();
vi.mock('../../model/surveyEntry', () => ({ resolveEventSurveyTarget: () => resolveEventSurveyTarget() }));

const { EventRewardShopOverlay } = await import('../../ui/EventRewardShopOverlay');

// 응모권 흐름을 안 보는 테스트는 rafflesQuery가 매달리지 않게 빈 목록으로 기본값을 준다
beforeEach(() => {
  listRaffles.mockResolvedValue([]);
  // jsdom에는 <dialog>가 없다 — 완료 팝업이 뜨는지만 보므로 showModal을 no-op으로 채운다
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal() {
      this.setAttribute('open', '');
    };
  }
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderOverlay() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EventRewardShopOverlay />
    </QueryClientProvider>,
  );
}

describe('경품이 아직 없다', () => {
  it('빈 격자를 유지한 채 안내를 얹는다', async () => {
    listPrizes.mockResolvedValue([]);
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = renderOverlay();

    await waitFor(() => expect(container.querySelector('.ov-notice')).not.toBeNull());
    expect(container.querySelector('[aria-label="즉시 교환 경품 목록"]')?.children.length).toBe(0);
  });

  it('CTA가 기존 Survey 오버레이로 간다 — 이벤트 설문용 새 OverlayType을 만들지 않는다', async () => {
    listPrizes.mockResolvedValue([]);
    resolveEventSurveyTarget.mockReturnValue({ kind: 'event', surveyKey: 'SSAFESTA_2026' });

    renderOverlay();
    fireEvent.click(await screen.findByRole('button', { name: '설문 참여하기' }));

    expect(openVisitorOverlay).toHaveBeenCalledWith('SURVEY', { kind: 'event', surveyKey: 'SSAFESTA_2026' });
  });
});

describe('경품이 들어온 뒤', () => {
  const prizes = [
    { prizeId: 1, name: '마이구미', priceCoin: 450, stock: 32, active: true },
    { prizeId: 2, name: '프링글스', priceCoin: 700, stock: 0, active: true },
  ];

  it('안내가 사라지고 같은 격자에 상품이 찬다 — shell을 다시 만들지 않는다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <EventRewardShopOverlay />
      </QueryClientProvider>,
    );

    await screen.findByText('마이구미');
    expect(screen.queryByText('경품 상점 준비 중')).toBeNull();
  });

  it('카드가 코인 가격과 재고를 함께 보인다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('마이구미');
    expect(screen.getByText('450 C')).toBeTruthy();
    expect(screen.getByText('재고 32개')).toBeTruthy();
  });

  it('품절 상품은 교환할 수 없다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('프링글스');
    const soldOutCard = screen.getByText('프링글스').closest('.ov-card');
    expect(soldOutCard?.getAttribute('data-disabled')).toBe('');
    const button = soldOutCard?.querySelector('button');
    expect(button?.disabled).toBe(true);
  });

  it('교환을 누르면 idempotency key를 붙여 구매를 보내고 목록·지갑을 다시 읽는다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);
    purchasePrize.mockResolvedValue({
      purchaseId: 1,
      prizeId: 1,
      prizeName: '마이구미',
      quantity: 1,
      coinSpent: 450,
      fulfillment: 'PURCHASED',
      purchasedAt: new Date().toISOString(),
    });

    renderOverlay();
    const buyable = (await screen.findAllByRole('button', { name: '교환' })).find((b) => !(b as HTMLButtonElement).disabled);
    fireEvent.click(buyable!);

    await waitFor(() => expect(purchasePrize).toHaveBeenCalledTimes(1));
    const [prizeId, idempotencyKey] = purchasePrize.mock.calls[0] as [number, string];
    expect(prizeId).toBe(1);
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);

    await screen.findByText('교환 완료');
    expect(screen.getByText('450 C 사용')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByText('교환 완료')).toBeNull());
  });
});

describe('응모권 — 실 계약 전이라 mock으로 동작한다', () => {
  const raffles = [
    { raffleId: 101, name: '말랑이', priceCoin: 250, stock: 47, active: true },
    { raffleId: 102, name: '교보 기프트카드', priceCoin: 250, stock: 0, active: true },
  ];

  it('소진되지 않은 응모권은 응모할 수 있다', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('말랑이');
    const openCard = screen.getByText('말랑이').closest('.ov-card');
    expect(openCard?.querySelector('button')?.disabled).toBe(false);
  });

  it('소진된 응모권은 응모할 수 없다', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('교보 기프트카드');
    const soldOutCard = screen.getByText('교보 기프트카드').closest('.ov-card');
    expect(soldOutCard?.getAttribute('data-disabled')).toBe('');
    expect(soldOutCard?.querySelector('button')?.disabled).toBe(true);
  });

  it('응모를 누르면 idempotency key를 붙여 응모를 보내고 목록·지갑을 다시 읽는다', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);
    enterRaffle.mockResolvedValue({
      entryId: 1,
      raffleId: 101,
      raffleName: '말랑이',
      coinSpent: 250,
      enteredAt: new Date().toISOString(),
    });

    renderOverlay();
    const enterable = (await screen.findAllByRole('button', { name: '응모' })).find((b) => !(b as HTMLButtonElement).disabled);
    fireEvent.click(enterable!);

    await waitFor(() => expect(enterRaffle).toHaveBeenCalledTimes(1));
    const [raffleId, idempotencyKey] = enterRaffle.mock.calls[0] as [number, string];
    expect(raffleId).toBe(101);
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);

    await screen.findByText('응모 완료');
    expect(screen.getByText('250 C 사용')).toBeTruthy();
  });
});
