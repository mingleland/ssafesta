// Catalog 소비 훅 + 팔레트용 VM (S15P21A604-167 데이터층 — spec 012 연동).
// 관행 정합: wallet·lease 와 같은 React Query. 구매 성공 시 catalog·wallet-balance invalidate
// (useLeaseSlot 패턴). UI 는 CatalogItemVM 과 mutation 상태만 소비한다 — 표현은 UI Track.
//
// 팔레트 오브젝트 ↔ catalog 품목 매핑(어느 장식이 어느 itemId 인가)은 아직 외부 미확정 —
// 장식 유형 값 자체가 BE 에 없다(types.ts 주석). 이 모듈은 매핑을 발명하지 않고
// assetKey/code 기준 조회만 제공한다.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { catalogApi } from '../../../entities/catalog/api.select';
import { toPurchaseErrorKind } from '../../../entities/catalog/mapper';
import type { PurchaseErrorKind } from '../../../entities/catalog/mapper';
import type { CatalogItemView } from '../../../entities/catalog/types';
import { getSessionSnapshot } from '../../auth/model/session';

export interface CatalogItemVM {
  itemId: number;
  code: string;
  name: string;
  equipSlot: string;
  assetKey: string;
  price: number;
  owned: boolean;
  /** 미보유 = 팔레트에서 잠금 표시 (spec 012 — 보유해야 사용) */
  locked: boolean;
  /** 구매 시도가 의미 있는 상태 — 미보유이고 판매 중 (가격 0 은 서버가 전원 보유 판정이라 여기 안 온다) */
  purchasable: boolean;
}

export function toCatalogItemVM(item: CatalogItemView): CatalogItemVM {
  return {
    itemId: item.itemId,
    code: item.code,
    name: item.name,
    equipSlot: item.equipSlot,
    assetKey: item.assetKey,
    price: item.price,
    owned: item.owned,
    locked: !item.owned,
    purchasable: !item.owned && item.onSale,
  };
}

export const catalogKeys = {
  list: (type: string) => ['catalog-items', type] as const,
};

/** 게스트도 조회 가능(FR-013 — price 0 만 owned) — enabled 게이트 없음 */
export function useCatalogItems(type: string) {
  return useQuery({
    queryKey: catalogKeys.list(type),
    queryFn: async () => (await catalogApi.getCatalog(type)).items.map(toCatalogItemVM),
  });
}

export class GuestPurchaseError extends Error {
  constructor() {
    super('회원 계정만 아이템을 구매할 수 있습니다.');
  }
}

/**
 * 구매 mutation. 게스트는 요청 자체를 만들지 않는다(WalletBadge 의 게이트 관행 — 서버도 403
 * MEMBER_ONLY 로 거부하지만 클라이언트 판단은 통제가 아니다, 헌법 16조).
 * 성공 시: 해당 type 카탈로그(ownership 반영) + wallet-balance invalidate. 전역 invalidate 없음.
 */
export function usePurchaseItem(type: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (itemId: number) => {
      if (getSessionSnapshot().kind !== 'member') throw new GuestPurchaseError();
      return catalogApi.purchaseItem(itemId);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: catalogKeys.list(type) });
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
  });
}

/** mutation error → UI 어휘 (GuestPurchaseError 포함) */
export function purchaseErrorKindOf(error: unknown): PurchaseErrorKind {
  if (error instanceof GuestPurchaseError) return 'member_only';
  return toPurchaseErrorKind(error);
}

/** 팔레트가 오브젝트의 잠금 여부를 물을 때 — 매핑 키는 assetKey 또는 code (외부 매핑 확정 전 조회만 제공) */
export function findCatalogItem(items: CatalogItemVM[], key: { assetKey?: string; code?: string }): CatalogItemVM | undefined {
  if (key.assetKey !== undefined) return items.find((i) => i.assetKey === key.assetKey);
  if (key.code !== undefined) return items.find((i) => i.code === key.code);
  return undefined;
}
