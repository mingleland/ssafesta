// 응모권 실 API 배선 — BE는 별도 엔드포인트가 아니라 event-shop 엔드포인트를 공용으로 쓴다 (S15P21A604-922).
// winnerCount > 0 인 경품이 응모형이고, 구매와 동일하게 POST /api/v1/event-shop/purchases 로 응모한다.
import { eventShopApi } from '../eventShop/api.select';
import type { PurchaseRecipient } from '../../shared/contracts/purchaseRecipient';
import type { RaffleEntryResult, RafflePrize } from './types';

export async function listRaffles(): Promise<RafflePrize[]> {
  const prizes = await eventShopApi.listPrizes();
  return prizes
    .filter((p) => p.winnerCount > 0)
    .map((p) => ({
      raffleId: p.prizeId,
      name: p.name,
      priceCoin: p.priceCoin,
      stock: p.stock,
      active: p.active,
      closesAt: p.closesAt,
      drawAt: p.closesAt ?? null,
    }));
}

export async function enterRaffle(
  raffleId: number,
  idempotencyKey: string,
  recipient?: PurchaseRecipient,
): Promise<RaffleEntryResult> {
  const res = await eventShopApi.purchasePrize(raffleId, idempotencyKey, 1, recipient);
  return {
    entryId: res.purchaseId,
    raffleId: res.prizeId,
    raffleName: res.prizeName,
    coinSpent: res.coinSpent,
    enteredAt: res.purchasedAt,
    drawAt: null,
  };
}
