// @vitest-environment jsdom
// 내 부스 카드는 그 자체가 Studio 진입점이다 (감사 항목 3).
//
// 예전에는 페이지 위쪽 "내 부스" 배너의 링크 하나가 유일한 진입이라, 목록에서 제 부스 카드를
// 찾아 눌러도 아무 일이 없었다. 여기서 고정하는 것은 ① 내 카드가 Studio 로 가는 링크라는 것과
// ② 남의 카드·임대 가능 카드에는 그 링크가 붙지 않는다는 것이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { SlotView } from '../../../../entities/booth/types';

const MY_BOOTH_ID = 42;

const getSlots = vi.fn();
const getMyBooth = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: { getSlots: () => getSlots(), getMyBooth: () => getMyBooth() },
}));

vi.mock('../../../../entities/wallet/api.select', () => ({
  walletApi: { getWallet: () => Promise.resolve({ balance: 500 }) },
}));

const { SlotListPage } = await import('../../SlotListPage');
const { __resetSessionForTests, markBootstrapped, setMemberSession } = await import(
  '../../../../features/auth/model/session'
);

function slot(over: Partial<SlotView> & Pick<SlotView, 'slotId' | 'slotCode'>): SlotView {
  return {
    floorNo: 11,
    type: 'USER_RENTAL',
    status: 'AVAILABLE',
    boothId: null,
    boothName: null,
    leaseEndsAt: null,
    remainingSeconds: null,
    entryAvailable: false,
    mine: false,
    ...over,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SlotListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * 카드 = 목록 항목. 그 안에서만 링크를 찾아야 페이지 상단 배너 링크와 섞이지 않는다.
 *
 * 슬롯 코드는 화면에 두 번 나온다 — 평면도(S15P21A604-606)와 목록. 같은 데이터를 그림과
 * 목록으로 각각 보여 주는 것이 의도이므로, 조회를 목록 안으로 좁힌다.
 */
function cardOf(slotCode: string): HTMLElement {
  const list = document.querySelector('.slot-grid');
  if (list === null) throw new Error('슬롯 목록이 없다');
  const code = within(list as HTMLElement).getByText(slotCode);
  const card = code.closest('li');
  if (card === null) throw new Error(`카드를 찾지 못했다: ${slotCode}`);
  return card;
}

beforeEach(() => {
  __resetSessionForTests();
  setMemberSession('token', new Date(Date.now() + 60_000).toISOString());
  markBootstrapped();
  getSlots.mockReset();
  getMyBooth.mockReset();
  getMyBooth.mockResolvedValue(null);
});

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

describe('슬롯 목록 — 내 부스 카드로 Studio 진입', () => {
  it('내 부스 카드 전체가 Studio 링크다', async () => {
    getSlots.mockResolvedValue([
      slot({ slotId: 1, slotCode: 'F11-R01', status: 'OCCUPIED', mine: true, boothId: MY_BOOTH_ID, boothName: '내 부스' }),
    ]);

    renderPage();
    await screen.findByText('F11-R01', { selector: '.slot-grid *' });

    const link = within(cardOf('F11-R01')).getByRole('link');
    expect(link.getAttribute('href')).toBe(`/app/studio/${MY_BOOTH_ID}`);
  });

  it('남의 부스 카드에는 Studio 링크가 없다', async () => {
    getSlots.mockResolvedValue([
      slot({ slotId: 2, slotCode: 'F11-R02', status: 'OCCUPIED', mine: false, boothId: 99, boothName: '남의 부스' }),
    ]);

    renderPage();
    await screen.findByText('F11-R02', { selector: '.slot-grid *' });

    expect(within(cardOf('F11-R02')).queryByRole('link')).toBeNull();
  });

  it('임대 가능한 빈 카드는 임대 버튼만 갖는다 — 클릭이 겹치지 않는다', async () => {
    getSlots.mockResolvedValue([slot({ slotId: 3, slotCode: 'F11-R03' })]);

    renderPage();
    await screen.findByText('F11-R03', { selector: '.slot-grid *' });

    const card = cardOf('F11-R03');
    expect(within(card).queryByRole('link')).toBeNull();
    expect(within(card).getByRole('button')).toBeTruthy();
  });
});
