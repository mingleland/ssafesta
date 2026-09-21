// @vitest-environment jsdom
// 이벤트 상점 — 즉시구매 실 연동(S15P21A604-842/836) 이후의 shell.
//
// 지키는 것: 상품이 없을 때(PREPARING과 같은 모양) 격자가 살아있고 안내가 얹히는가, 상품이
// 오면 안내만 사라지고 같은 격자에 상품이 차는가(구역 구분 없이 3/3으로), 품절/구매 흐름이
// 실제 API와 맞물리는가, 실물 지급이라 캠퍼스·조 이름·이름을 먼저 받는가(S15P21A604-842 후속).
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
  // jsdom에는 <dialog>가 없다 — 완료 팝업·받는 자 정보 폼이 뜨는지만 보므로 showModal을 no-op으로 채운다
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

// 받는 자 정보 폼을 채우고 확정 버튼을 누른다 — 캠퍼스는 기본값(서울)을 그대로 쓴다.
function fillRecipientForm(actionLabel: '구매' | '응모') {
  fireEvent.change(screen.getByLabelText('조 이름'), { target: { value: 'A101' } });
  fireEvent.change(screen.getByLabelText('이름'), { target: { value: '홍길동' } });
  fireEvent.click(screen.getByRole('button', { name: `${actionLabel} 확정` }));
}

describe('경품이 아직 없다', () => {
  it('빈 격자를 유지한 채 안내를 얹는다', async () => {
    listPrizes.mockResolvedValue([]);
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = renderOverlay();

    await waitFor(() => expect(container.querySelector('.ov-notice')).not.toBeNull());
    expect(container.querySelector('[aria-label="이벤트 상점 상품 목록"]')?.children.length).toBe(0);
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
    { prizeId: 1, name: '마이구미', priceCoin: 400, stock: 32, active: true },
    { prizeId: 2, name: '초코송이', priceCoin: 500, stock: 0, active: true },
  ];

  it('안내가 사라지고 같은 격자에 상품이 찬다 — shell을 다시 만들지 않는다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('마이구미');
    expect(screen.queryByText('경품 상점 준비 중')).toBeNull();
  });

  it('상품이 있어도 설문 참여 버튼이 상시 보인다 — footer 로 옮겼다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue({ kind: 'event', surveyKey: 'SSAFESTA_2026' });

    renderOverlay();

    await screen.findByText('마이구미');
    fireEvent.click(screen.getByRole('button', { name: '설문 참여하기' }));
    expect(openVisitorOverlay).toHaveBeenCalledWith('SURVEY', { kind: 'event', surveyKey: 'SSAFESTA_2026' });
  });

  it('카드가 코인 가격과 재고를 함께 보인다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('마이구미');
    expect(screen.getByText('400 C')).toBeTruthy();
    expect(screen.getByText('재고 32개')).toBeTruthy();
  });

  it('품절 상품은 구매할 수 없다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('초코송이');
    const soldOutCard = screen.getByText('초코송이').closest('.ov-card');
    expect(soldOutCard?.getAttribute('data-disabled')).toBe('');
    const button = soldOutCard?.querySelector('button');
    expect(button?.disabled).toBe(true);
  });

  it('구매를 누르면 받는 자 정보를 먼저 받고, 그다음 idempotency key를 붙여 구매를 보낸다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);
    purchasePrize.mockResolvedValue({
      purchaseId: 1,
      prizeId: 1,
      prizeName: '마이구미',
      quantity: 1,
      coinSpent: 400,
      fulfillment: 'PURCHASED',
      purchasedAt: new Date().toISOString(),
    });

    renderOverlay();
    const buyable = (await screen.findAllByRole('button', { name: '구매' })).find((b) => !(b as HTMLButtonElement).disabled);
    fireEvent.click(buyable!);

    // 폼이 뜬다 — 아직 API는 안 탔다
    await screen.findByRole('button', { name: '구매 확정' });
    expect(purchasePrize).not.toHaveBeenCalled();

    fillRecipientForm('구매');

    await waitFor(() => expect(purchasePrize).toHaveBeenCalledTimes(1));
    const [prizeId, idempotencyKey, quantity, recipient] = purchasePrize.mock.calls[0] as [number, string, number, Record<string, string>];
    expect(prizeId).toBe(1);
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(quantity).toBe(1);
    expect(recipient).toEqual({ campus: '서울', teamName: 'A101', recipientName: '홍길동' });

    await screen.findByText('구매 완료');
    expect(screen.getByText('400 C 사용')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByText('구매 완료')).toBeNull());
  });

  it('실패 후 같은 상품을 다시 구매하면 같은 idempotency key로 재시도한다 — 성공하면 다음 구매는 새 key다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);
    purchasePrize
      .mockRejectedValueOnce({ code: 'INSUFFICIENT_COIN', message: '코인이 부족합니다.', requestId: 'r1', errors: [], warnings: [] })
      .mockResolvedValueOnce({
        purchaseId: 1,
        prizeId: 1,
        prizeName: '마이구미',
        quantity: 1,
        coinSpent: 400,
        fulfillment: 'PURCHASED',
        purchasedAt: new Date().toISOString(),
      });

    renderOverlay();
    const openForm = async () => {
      const buyable = (await screen.findAllByRole('button', { name: '구매' })).find((b) => !(b as HTMLButtonElement).disabled);
      fireEvent.click(buyable!);
      await screen.findByRole('button', { name: '구매 확정' });
      fillRecipientForm('구매');
    };

    await openForm();
    await waitFor(() => expect(purchasePrize).toHaveBeenCalledTimes(1));
    const firstKey = (purchasePrize.mock.calls[0] as [number, string])[1];

    // 실패했다 — 같은 상품을 다시 구매한다(재시도)
    await openForm();
    await waitFor(() => expect(purchasePrize).toHaveBeenCalledTimes(2));
    const secondKey = (purchasePrize.mock.calls[1] as [number, string])[1];
    expect(secondKey).toBe(firstKey);

    await screen.findByText('구매 완료');
    fireEvent.click(screen.getByRole('button', { name: '확인' }));
    await waitFor(() => expect(screen.queryByText('구매 완료')).toBeNull());

    // 성공했으니 다음 구매는 새 key여야 한다
    purchasePrize.mockResolvedValueOnce({
      purchaseId: 2,
      prizeId: 1,
      prizeName: '마이구미',
      quantity: 1,
      coinSpent: 400,
      fulfillment: 'PURCHASED',
      purchasedAt: new Date().toISOString(),
    });
    await openForm();
    await waitFor(() => expect(purchasePrize).toHaveBeenCalledTimes(3));
    const thirdKey = (purchasePrize.mock.calls[2] as [number, string])[1];
    expect(thirdKey).not.toBe(firstKey);
  });

  it('취소하면 폼만 닫히고 API는 안 탄다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();
    const buyable = (await screen.findAllByRole('button', { name: '구매' })).find((b) => !(b as HTMLButtonElement).disabled);
    fireEvent.click(buyable!);

    fireEvent.click(await screen.findByRole('button', { name: '취소' }));

    expect(screen.queryByRole('button', { name: '구매 확정' })).toBeNull();
    expect(purchasePrize).not.toHaveBeenCalled();
  });

  it('말랑이는 사진 아래에 서울캠퍼스 한정 표기가 붙고, 다른 상품엔 없다', async () => {
    listPrizes.mockResolvedValue([
      { prizeId: 4, name: '말랑이', priceCoin: 700, stock: 47, active: true },
      { prizeId: 1, name: '마이구미', priceCoin: 400, stock: 32, active: true },
    ]);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    const mallangiCard = (await screen.findByText('말랑이')).closest('.ov-card');
    expect(mallangiCard?.textContent).toContain('서울캠퍼스 한정');

    const mygummyCard = screen.getByText('마이구미').closest('.ov-card');
    expect(mygummyCard?.textContent).not.toContain('서울캠퍼스 한정');
  });

  it('말랑이는 캠퍼스가 서울로 고정되고 바꿀 수 없다', async () => {
    listPrizes.mockResolvedValue([
      { prizeId: 4, name: '말랑이', priceCoin: 700, stock: 47, active: true },
      ...prizes,
    ]);
    resolveEventSurveyTarget.mockReturnValue(null);
    purchasePrize.mockResolvedValue({
      purchaseId: 1,
      prizeId: 4,
      prizeName: '말랑이',
      quantity: 1,
      coinSpent: 700,
      fulfillment: 'PURCHASED',
      purchasedAt: new Date().toISOString(),
    });

    renderOverlay();
    const mallangiCard = (await screen.findByText('말랑이')).closest('.ov-card')!;
    fireEvent.click(mallangiCard.querySelector('button')!);

    const campusSelect = (await screen.findByLabelText('캠퍼스')) as HTMLSelectElement;
    expect(campusSelect.disabled).toBe(true);
    expect(campusSelect.value).toBe('서울');
    // 서울캠퍼스 한정이라 메신저(MM) 발송이 아니라 직접 가져다 준다
    expect(screen.getByText(/직접 가져다 드립니다\./)).toBeTruthy();

    fireEvent.change(screen.getByLabelText('조 이름'), { target: { value: 'A101' } });
    fireEvent.change(screen.getByLabelText('이름'), { target: { value: '홍길동' } });
    fireEvent.click(screen.getByRole('button', { name: '구매 확정' }));

    await waitFor(() => expect(purchasePrize).toHaveBeenCalledTimes(1));
    const [, , , recipient] = purchasePrize.mock.calls[0] as [number, string, number, Record<string, string>];
    expect(recipient.campus).toBe('서울');
  });

  it('말랑이가 아닌 다른 상품은 캠퍼스를 고를 수 있다', async () => {
    listPrizes.mockResolvedValue(prizes);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();
    const buyable = (await screen.findAllByRole('button', { name: '구매' })).find((b) => !(b as HTMLButtonElement).disabled);
    fireEvent.click(buyable!);

    const campusSelect = (await screen.findByLabelText('캠퍼스')) as HTMLSelectElement;
    expect(campusSelect.disabled).toBe(false);
    expect(screen.getByText(/MM으로 기프티콘을 보내드립니다\./)).toBeTruthy();
  });
});

describe('구역 구분 없이 즉시구매·응모권이 한 격자에 3/3으로 같이 뜬다', () => {
  const prizes = [
    { prizeId: 1, name: '마이구미', priceCoin: 400, stock: 32, active: true },
    { prizeId: 2, name: '초코송이', priceCoin: 500, stock: 15, active: true },
  ];
  const raffles = [{ raffleId: 103, name: '치킨', priceCoin: 50, stock: 21, active: true, drawAt: null }];

  it('"즉시 교환"·"응모권" 같은 구역 제목 없이 한 목록에 다 들어간다', async () => {
    listPrizes.mockResolvedValue(prizes);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = renderOverlay();

    await screen.findByText('치킨');
    expect(screen.queryByText('즉시 교환')).toBeNull();
    expect(screen.queryByText('응모권')).toBeNull();
    const list = container.querySelector('[aria-label="이벤트 상점 상품 목록"]');
    expect(list?.children.length).toBe(3);
    expect(list?.textContent).toContain('마이구미');
    expect(list?.textContent).toContain('초코송이');
    expect(list?.textContent).toContain('치킨');
  });

  it('3열로 고정한다', async () => {
    listPrizes.mockResolvedValue(prizes);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = renderOverlay();

    await screen.findByText('치킨');
    const list = container.querySelector('[aria-label="이벤트 상점 상품 목록"]') as HTMLElement;
    expect(list.style.gridTemplateColumns).toBe('repeat(3, 1fr)');
  });
  it('API 반환 순서와 무관하게 구매 5종 뒤에 치킨을 배치한다', async () => {
    listPrizes.mockResolvedValue([
      { prizeId: 5, name: '교보문고 10000원권', priceCoin: 1000, stock: 3, active: true },
      { prizeId: 4, name: '말랑이', priceCoin: 700, stock: 47, active: true },
      { prizeId: 3, name: '아이스아메리카노', priceCoin: 600, stock: 4, active: true },
      { prizeId: 2, name: '초코송이', priceCoin: 500, stock: 15, active: true },
      { prizeId: 1, name: '마이구미', priceCoin: 400, stock: 32, active: true },
    ]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = renderOverlay();

    await screen.findByText('치킨');
    const cards = container.querySelectorAll('[aria-label="이벤트 상점 상품 목록"] > *');
    expect(Array.from(cards, (card) => card.querySelector('.ov-card-title')?.textContent)).toEqual([
      '마이구미',
      '초코송이',
      '아이스아메리카노',
      '말랑이',
      '교보문고 10000원권',
      '치킨',
    ]);
  });
});

describe('응모권 — 실 계약 전이라 mock으로 동작한다', () => {
  const raffles = [
    { raffleId: 103, name: '치킨', priceCoin: 50, stock: 21, active: true, drawAt: null },
    { raffleId: 104, name: '우산', priceCoin: 50, stock: 0, active: true, drawAt: null },
  ];

  it('추첨 시각을 아직 모르면 지어내지 않고 그렇게 말한다 — 카드 chip은 짧게', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('치킨');
    expect(screen.getAllByText('추후 공지').length).toBeGreaterThan(0);
  });

  it('소진되지 않은 응모권은 응모할 수 있다', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('치킨');
    const openCard = screen.getByText('치킨').closest('.ov-card');
    expect(openCard?.querySelector('button')?.disabled).toBe(false);
  });

  it('소진된 응모권은 응모할 수 없다', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);

    renderOverlay();

    await screen.findByText('우산');
    const soldOutCard = screen.getByText('우산').closest('.ov-card');
    expect(soldOutCard?.getAttribute('data-disabled')).toBe('');
    expect(soldOutCard?.querySelector('button')?.disabled).toBe(true);
  });

  it('응모를 누르면 받는 자 정보를 먼저 받고, 그다음 idempotency key를 붙여 응모를 보낸다', async () => {
    listPrizes.mockResolvedValue([]);
    listRaffles.mockResolvedValue(raffles);
    resolveEventSurveyTarget.mockReturnValue(null);
    enterRaffle.mockResolvedValue({
      entryId: 1,
      raffleId: 103,
      raffleName: '치킨',
      coinSpent: 50,
      enteredAt: new Date().toISOString(),
      drawAt: '2026-09-20T11:00:00+09:00',
    });

    renderOverlay();
    const enterable = (await screen.findAllByRole('button', { name: '응모' })).find((b) => !(b as HTMLButtonElement).disabled);
    fireEvent.click(enterable!);

    await screen.findByRole('button', { name: '응모 확정' });
    expect(enterRaffle).not.toHaveBeenCalled();

    fillRecipientForm('응모');

    await waitFor(() => expect(enterRaffle).toHaveBeenCalledTimes(1));
    const [selected, idempotencyKey, recipient] = enterRaffle.mock.calls[0] as [
      { raffleId: number },
      string,
      Record<string, string>,
    ];
    // raffleId만이 아니라 고른 응모권을 통째로 넘긴다 — 응답에 없는 추첨 시각을 여기서 들고 간다
    expect(selected.raffleId).toBe(103);
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(recipient).toEqual({ campus: '서울', teamName: 'A101', recipientName: '홍길동' });

    await screen.findByText('응모 완료');
    expect(screen.getByText('50 C 사용')).toBeTruthy();
    expect(screen.getByText(/추첨 9월 20일/)).toBeTruthy();
  });
});

describe('응모권은 같은 prizes 목록에서 winnerCount로 갈린다', () => {
  it('winnerCount > 0 인 상품은 즉시교환 카드로 그리지 않는다', async () => {
    listPrizes.mockResolvedValue([
      { prizeId: 1, name: '마이구미', priceCoin: 400, stock: 32, active: true, closesAt: null, winnerCount: 0 },
      { prizeId: 103, name: '치킨', priceCoin: 50, stock: 21, active: true, closesAt: null, winnerCount: 1 },
    ]);
    // 응모권 카드는 entities/raffle(같은 목록의 winnerCount > 0 조각)이 만든다
    listRaffles.mockResolvedValue([
      { raffleId: 103, name: '치킨', priceCoin: 50, stock: 21, active: true, closesAt: null, drawAt: null },
    ]);
    resolveEventSurveyTarget.mockReturnValue(null);

    const { container } = renderOverlay();

    await screen.findByText('치킨');
    const list = container.querySelector('[aria-label="이벤트 상점 상품 목록"]');
    // 치킨이 구매 카드로 한 번 더 그려지면 3장이 된다
    expect(list?.children.length).toBe(2);
    expect(screen.queryByRole('button', { name: '구매' })?.closest('.ov-card')?.textContent).toContain('마이구미');
    expect(screen.getByText('치킨').closest('.ov-card')?.querySelector('button')?.textContent).toBe('응모');
  });
});
