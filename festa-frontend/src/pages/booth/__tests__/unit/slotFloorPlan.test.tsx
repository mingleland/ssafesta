// @vitest-environment jsdom
// 부스 슬롯 평면도 (S15P21A604-606).
//
// 가장 중요한 경계는 **카드 목록이 남아 있는가** 다. 평면도 위 핫스팟만 두면 키보드·스크린리더
// 사용자가 자리를 고를 수 없다. 평면도는 같은 데이터를 그림으로 한 번 더 보여 주는 자리이지
// 목록의 대체가 아니다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { SlotView } from '../../../../entities/booth/types';
import { SLOT_SPOTS } from '../../../../entities/booth/slotLayout';

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

const plan = () => screen.getByLabelText('페스타존 평면도');

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

describe('슬롯 좌표표', () => {
  it('12개 슬롯이 모두 있다 — 씬에서 뽑은 Portal_Ext_01~12', () => {
    expect(Object.keys(SLOT_SPOTS).length).toBe(12);
  });

  it('전부 평면도 안(0~100%)에 있다', () => {
    for (const spot of Object.values(SLOT_SPOTS)) {
      expect(spot.left).toBeGreaterThanOrEqual(0);
      expect(spot.left).toBeLessThanOrEqual(100);
      expect(spot.top).toBeGreaterThanOrEqual(0);
      expect(spot.top).toBeLessThanOrEqual(100);
    }
  });

  it('홀수·짝수가 통로를 사이에 두고 갈린다 — 6열 × 2행 배치', () => {
    for (const [id, spot] of Object.entries(SLOT_SPOTS)) {
      const odd = Number(id) % 2 === 1;
      // 통로는 중앙(42~58%)이고 자리는 그 위아래로 나뉜다
      if (odd) expect(spot.top).toBeLessThan(42);
      else expect(spot.top).toBeGreaterThan(58);
    }
  });

  it('번호가 커질수록 화면 오른쪽이다', () => {
    const lefts = Object.entries(SLOT_SPOTS)
      .sort((a, b) => Number(a[0]) - Number(b[0]))
      .map(([, spot]) => spot.left);
    for (let i = 1; i < lefts.length; i += 1) {
      expect(lefts[i]).toBeGreaterThan(lefts[i - 1]);
    }
  });
});

describe('평면도 렌더', () => {
  it('카드 목록을 대체하지 않는다 — 둘 다 있다', async () => {
    getSlots.mockResolvedValue([slot({ slotId: 1, slotCode: 'F11-R01' })]);
    renderPage();
    await screen.findByLabelText('페스타존 평면도');

    // 목록 항목(li)이 그대로 있어야 키보드·스크린리더 경로가 살아 있다
    const items = document.querySelectorAll('.slot-grid li');
    expect(items.length).toBe(1);
  });

  it('임대 가능한 자리는 평면도에서도 누를 수 있다', async () => {
    getSlots.mockResolvedValue([slot({ slotId: 3, slotCode: 'F11-R03' })]);
    renderPage();
    await screen.findByLabelText('페스타존 평면도');

    expect(within(plan()).getByRole('button', { name: /F11-R03 임대 가능/ })).toBeTruthy();
  });

  it('내 부스는 평면도에서도 Studio 로 간다', async () => {
    getSlots.mockResolvedValue([
      slot({ slotId: 5, slotCode: 'F11-R05', status: 'OCCUPIED', mine: true, boothId: MY_BOOTH_ID }),
    ]);
    renderPage();
    await screen.findByLabelText('페스타존 평면도');

    const link = within(plan()).getByRole('link', { name: /F11-R05/ });
    expect(link.getAttribute('href')).toBe(`/app/studio/${MY_BOOTH_ID}`);
  });

  it('남의 부스는 평면도에서 누를 수 없다', async () => {
    getSlots.mockResolvedValue([
      slot({ slotId: 7, slotCode: 'F11-R07', status: 'OCCUPIED', boothId: 99, boothName: '남의 부스' }),
    ]);
    renderPage();
    await screen.findByLabelText('페스타존 평면도');

    const scope = within(plan());
    expect(scope.queryByRole('button')).toBeNull();
    expect(scope.queryByRole('link')).toBeNull();
  });

  it('좌표표에 없는 slotId 는 평면도에 얹지 않는다 — 모르는 자리를 추측하지 않는다', async () => {
    getSlots.mockResolvedValue([slot({ slotId: 99, slotCode: 'F11-X99' })]);
    renderPage();
    await screen.findByText('F11-X99'); // 목록에는 나온다

    expect(screen.queryByLabelText('페스타존 평면도')).toBeNull();
  });
});
