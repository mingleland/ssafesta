// @vitest-environment jsdom
// 부스 임대 오버레이의 동선 (S15P21A604-855).
//
// 잠그는 것 — ① 임대 성공이면 이 화면에 남지 않고 관리 화면으로 넘어간다 ② 실패면 그대로 남아 사유를 말한다
// ③ 이미 활성 임대가 있으면 어느 길로 들어왔든 맵을 그리지 않고 관리 화면으로 돌린다 ④ 만료 임대는 막지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { __resetSessionForTests, setMemberSession } from '../../../auth/model/session';
import { __resetGameClientUiForTests, getGameClientUiSnapshot, openBoothRental } from '../../../world/model/gameClientUi';

const getSlots = vi.fn();
const getMyBooth = vi.fn();
const leaseSlot = vi.fn();

vi.mock('../../../../entities/booth/leaseApi.select', () => ({
  leaseApi: {
    getSlots: () => getSlots(),
    getMyBooth: () => getMyBooth(),
    leaseSlot: (id: number) => leaseSlot(id),
  },
}));
vi.mock('../../../../unity/host/boothLayoutBridge', () => ({ notifyBoothSlotChanged: () => true }));
vi.mock('../../../wallet/ui/WalletBadge', () => ({ WalletBadge: () => null }));

const future = () => new Date(Date.now() + 60_000).toISOString();
const past = () => new Date(Date.now() - 60_000).toISOString();
const slots = [
  { slotId: 5, slotCode: 'F11-R05', type: 'USER_RENTAL', status: 'AVAILABLE', mine: false },
  { slotId: 3, slotCode: 'F11-R03', type: 'USER_RENTAL', status: 'OCCUPIED', mine: false },
];

beforeEach(() => {
  // jsdom 에는 <dialog> 가 없다 — 모달이 뜨는지만 보므로 showModal/close 를 no-op 으로 채운다
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', ''); };
    HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open'); };
  }
  getSlots.mockReset().mockResolvedValue(slots);
  getMyBooth.mockReset().mockResolvedValue(null);
  leaseSlot.mockReset();
  __resetGameClientUiForTests();
  setMemberSession('member-token', future());
});
afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

async function renderOverlay() {
  const { BoothRentalOverlay } = await import('../../ui/BoothRentalOverlay');
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  act(() => openBoothRental());
  return render(
    <QueryClientProvider client={client}>
      <BoothRentalOverlay onClose={() => {}} />
    </QueryClientProvider>,
  );
}

async function leaseR05() {
  fireEvent.click(await screen.findByText('F11-R05'));
  fireEvent.click(await screen.findByRole('button', { name: '임대' }));
  fireEvent.click(await screen.findByRole('button', { name: /코인 차감하고 임대/ }));
}

describe('BoothRentalOverlay 동선', () => {
  it('임대 성공이면 임대 화면을 닫고 관리 화면을 연다', async () => {
    leaseSlot.mockResolvedValue({ boothId: 9, leaseId: 1 });
    await renderOverlay();
    await leaseR05();

    await waitFor(() => expect(getGameClientUiSnapshot().managementOverlay).toBe(true));
    expect(getGameClientUiSnapshot().boothRental).toBe(false);
  });

  it('임대 실패면 그 자리에 남아 사유를 말한다', async () => {
    leaseSlot.mockRejectedValue({ code: 'INSUFFICIENT_COIN', message: 'no coin', status: 409, errors: [], warnings: [] });
    await renderOverlay();
    await leaseR05();

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(getGameClientUiSnapshot().boothRental).toBe(true);
    expect(getGameClientUiSnapshot().managementOverlay).toBe(false);
  });

  it('활성 임대가 있으면 맵을 그리지 않고 관리 화면으로 돌린다', async () => {
    getMyBooth.mockResolvedValue({ boothId: 9, name: '내 부스', lease: { slotId: 5, slotCode: 'F11-R05', endsAt: future() } });
    await renderOverlay();

    await waitFor(() => expect(getGameClientUiSnapshot().managementOverlay).toBe(true));
    expect(getGameClientUiSnapshot().boothRental).toBe(false);
    expect(screen.queryByText('F11-R05')).toBeNull();
  });

  it('만료된 임대는 막지 않는다 — 맵이 그려진다', async () => {
    getMyBooth.mockResolvedValue({ boothId: 9, name: '내 부스', lease: { slotId: 5, slotCode: 'F11-R05', endsAt: past() } });
    await renderOverlay();

    expect(await screen.findByText('F11-R05')).toBeTruthy();
    expect(getGameClientUiSnapshot().boothRental).toBe(true);
  });
});
