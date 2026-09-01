// @vitest-environment jsdom
// Catalog 훅 통합 — mock 어댑터 + React Query 로 조회/구매/invalidate/게스트 차단 검증 (S15P21A604-167)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

vi.mock('../../../../../entities/catalog/api.select', async () => {
  const mockApi = await import('../../../../../entities/catalog/api.mock');
  return { catalogApi: mockApi };
});

import {
  findCatalogItem,
  purchaseErrorKindOf,
  toCatalogItemVM,
  useCatalogItems,
  usePurchaseItem,
} from '../../catalog';
import { __resetCatalogMockForTests } from '../../../../../entities/catalog/api.mock';
import { CATALOG_TYPE_AVATAR_PART } from '../../../../../entities/catalog/types';
import { setGuestSession, setMemberSession, __resetSessionForTests } from '../../../../auth/model/session';

let client: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  __resetCatalogMockForTests();
  __resetSessionForTests();
  setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
});

afterEach(() => {
  client.clear();
});

describe('toCatalogItemVM — 팔레트 판정', () => {
  it('보유 = 잠금 해제, 미보유 = locked, 판매 중 미보유만 purchasable', () => {
    const base = { itemId: 1, code: 'c', name: 'n', equipSlot: 'TOP', assetKey: 'k', price: 100 };
    expect(toCatalogItemVM({ ...base, onSale: true, owned: true })).toMatchObject({ locked: false, purchasable: false });
    expect(toCatalogItemVM({ ...base, onSale: true, owned: false })).toMatchObject({ locked: true, purchasable: true });
    expect(toCatalogItemVM({ ...base, onSale: false, owned: false })).toMatchObject({ locked: true, purchasable: false });
  });
});

describe('useCatalogItems', () => {
  it('목록을 VM 으로 내리고 assetKey/code 조회가 동작한다', async () => {
    const { result } = renderHook(() => useCatalogItems(CATALOG_TYPE_AVATAR_PART), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const items = result.current.data!;
    expect(items).toHaveLength(4);
    expect(findCatalogItem(items, { assetKey: '2118850617' })?.code).toBe('F_Bot.02');
    expect(findCatalogItem(items, { code: 'M_Bot.01' })?.locked).toBe(false);
    expect(findCatalogItem(items, {})).toBeUndefined();
  });
});

describe('usePurchaseItem', () => {
  it('구매 성공 — catalog 재조회로 owned 반영 + wallet-balance invalidate', async () => {
    const invalidated: unknown[] = [];
    const spy = vi.spyOn(client, 'invalidateQueries').mockImplementation(async (f) => {
      invalidated.push((f as { queryKey: unknown }).queryKey);
    });
    const { result } = renderHook(() => usePurchaseItem(CATALOG_TYPE_AVATAR_PART), { wrapper });
    result.current.mutate(2);
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.owned).toBe(true);
    expect(invalidated).toEqual([['catalog-items', CATALOG_TYPE_AVATAR_PART], ['wallet-balance']]);
    spy.mockRestore();
  });

  it('코인 부족은 insufficient_coin 으로 매핑된다', async () => {
    const { result } = renderHook(() => usePurchaseItem(CATALOG_TYPE_AVATAR_PART), { wrapper });
    result.current.mutate(4);
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(purchaseErrorKindOf(result.current.error)).toBe('insufficient_coin');
  });

  it('게스트는 요청 자체를 만들지 않고 member_only 로 거부된다 (FR-010)', async () => {
    setGuestSession('guest-at', new Date(Date.now() + 60_000).toISOString());
    const { result } = renderHook(() => usePurchaseItem(CATALOG_TYPE_AVATAR_PART), { wrapper });
    result.current.mutate(2);
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(purchaseErrorKindOf(result.current.error)).toBe('member_only');
    // 요청이 안 나갔으므로 mock 상태 불변
    const { result: list } = renderHook(() => useCatalogItems(CATALOG_TYPE_AVATAR_PART), { wrapper });
    await waitFor(() => expect(list.current.isSuccess).toBe(true));
    expect(findCatalogItem(list.current.data!, { code: 'F_Bot.02' })?.owned).toBe(false);
  });
});
