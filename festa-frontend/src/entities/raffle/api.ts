// 응모권 = winnerCount > 0 인 이벤트 상점 경품이다. 전용 BE 엔드포인트는 없고 앞으로도 없다
// (EventShopController 하나가 즉시교환과 응모를 같이 처리한다) — 여기서는 event-shop 계약을
// 화면이 보는 모양(RafflePrize/RaffleEntryResult)으로 바꿔 끼우기만 한다.
import { listPrizes, purchasePrize } from '../eventShop/api';
import type { PurchaseRecipient } from '../../shared/contracts/purchaseRecipient';
import type { RaffleEntryResult, RafflePrize } from './types';

export async function listRaffles(): Promise<RafflePrize[]> {
  const prizes = await listPrizes();
  return prizes
    .filter((prize) => prize.winnerCount > 0)
    .map((prize) => ({
      raffleId: prize.prizeId,
      name: prize.name,
      priceCoin: prize.priceCoin,
      stock: prize.stock,
      active: prize.active,
      closesAt: prize.closesAt,
      // 추첨은 마감 직후 스케줄러가 돌린다(EventPrizeClosingScheduler) — 마감 시각이 곧 추첨 시각이다
      drawAt: prize.closesAt,
    }));
}

// raffleId가 아니라 고른 응모권을 통째로 받는다 — 구매 응답에는 추첨 시각이 없어서, 목록 캐시를
// 다시 뒤지지 않으려면 호출부가 들고 있던 값을 그대로 넘겨주는 편이 맞다.
export async function enterRaffle(
  raffle: RafflePrize,
  idempotencyKey: string,
  recipient?: PurchaseRecipient,
): Promise<RaffleEntryResult> {
  const result = await purchasePrize(raffle.raffleId, idempotencyKey, 1, recipient);
  return {
    entryId: result.purchaseId,
    raffleId: result.prizeId,
    raffleName: result.prizeName,
    coinSpent: result.coinSpent,
    enteredAt: result.purchasedAt,
    drawAt: raffle.drawAt,
  };
}
