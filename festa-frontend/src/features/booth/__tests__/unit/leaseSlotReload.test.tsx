// @vitest-environment jsdom
// 임대 성공 → 상주 Unity 에 슬롯 재조회 알림 (S15P21A604-644).
//
// 임대하면 BE 가 그 슬롯의 게시본을 만든다(6번 임대 직후 layouts/published 200 실측). Unity 는
// 진입 시 그 슬롯을 404 로 캐시하고 있으므로 알리지 않으면 임대한 부스에 들어갈 수 없다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

const leaseSlot = vi.fn();
vi.mock('../../../../entities/booth/leaseApi.select', () => ({ leaseApi: { leaseSlot: (id: number) => leaseSlot(id) } }));
const notify = vi.fn<(slotId: number) => boolean>(() => true);
vi.mock('../../../../unity/host/boothLayoutBridge', () => ({ notifyBoothSlotChanged: (id: number) => notify(id) }));

const { useLeaseSlot } = await import('../../model/useLeaseSlot');

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => { leaseSlot.mockReset(); notify.mockClear(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('임대 성공', () => {
  it('그 슬롯 번호로 재조회를 1회 알린다', async () => {
    leaseSlot.mockResolvedValue({ boothId: 2, leaseId: 3 });
    const { result } = renderHook(() => useLeaseSlot(), { wrapper: wrapper() });
    result.current.mutate(6);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith(6);
  });

  it('API 가 실패하면 알리지 않는다', async () => {
    leaseSlot.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useLeaseSlot(), { wrapper: wrapper() });
    result.current.mutate(6);
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(notify).not.toHaveBeenCalled();
  });

  it('Unity 가 없어도(알림이 false 를 돌려줘도) 임대 결과는 그대로 성공이다', async () => {
    notify.mockImplementation(() => false);
    leaseSlot.mockResolvedValue({ boothId: 2, leaseId: 3 });
    const { result } = renderHook(() => useLeaseSlot(), { wrapper: wrapper() });
    result.current.mutate(6);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
