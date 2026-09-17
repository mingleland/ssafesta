// @vitest-environment jsdom
// 이벤트 상점 — 즉시교환 실 연동(S15P21A604-842/836) 이후의 shell.
//
// 지키는 것: 상품이 없을 때(PREPARING과 같은 모양) 격자가 살아있고 안내가 얹히는가, 상품이
// 오면 안내만 사라지고 같은 격자에 상품이 차는가, 품절/구매 흐름이 실제 API와 맞물리는가.
import { afterEach, describe, expect, it, vi } from 'vitest';
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

const resolveEventSurveyTarget = vi.fn();
vi.mock('../../model/surveyEntry', () => ({ resolveEventSurveyTarget: () => resolveEventSurveyTarget() }));

const { EventRewardShopOverlay } = await import('../../ui/EventRewardShopOverlay');

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
  });
});

describe('응모권 — 대응 BE가 없다', () => {
  it('버튼이 항상 비활성이다', async () => {
    listPrizes.mockResolvedValue([]);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    const raffleButtons = await screen.findAllByRole('button', { name: '응모' });
    expect(raffleButtons.length).toBeGreaterThan(0);
    for (const button of raffleButtons) expect((button as HTMLButtonElement).disabled).toBe(true);
  });
});
