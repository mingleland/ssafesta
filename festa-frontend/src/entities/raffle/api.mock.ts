// 응모권 mock — 대응 BE가 없는 동안 화면이 실제로 동작하도록 채우는 유일한 구현(api.select 참고).
// event-shop mock과 같은 스타일: 잔액 정합은 흉내내지 않는다(dev 전용).
import type { ApiError } from '../../shared/api/client';
import type { PurchaseRecipient } from '../../shared/contracts/purchaseRecipient';
import type { RaffleEntryResult, RafflePrize } from './types';

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

// 가격 확정(2026-09-17, S15P21A604-842 후속) — 말랑이·교보 기프트카드는 즉시교환으로 옮겨졌다
// (entities/eventShop 참고). 응모권엔 치킨 하나만 남고 1장 250→50코인으로 내렸다.
// drawAt을 실제 축제 일정으로 채우지 않는다 — 추첨 시각은 아직 팀이 정하지 않았다(docs/26).
// null로 두면 화면이 "추후 공지"로 정직하게 보여준다.
const raffles: RafflePrize[] = [
  { raffleId: 103, name: '치킨', priceCoin: 50, stock: 21, active: true, closesAt: null, drawAt: null },
];

export async function listRaffles(): Promise<RafflePrize[]> {
  return raffles.filter((r) => r.active);
}

export async function enterRaffle(
  raffleId: number,
  _idempotencyKey: string,
  _recipient?: PurchaseRecipient,
): Promise<RaffleEntryResult> {
  const raffle = raffles.find((r) => r.raffleId === raffleId);
  if (!raffle) throw apiError('RAFFLE_NOT_FOUND', '응모권을 찾을 수 없습니다.');
  if (raffle.stock !== null && raffle.stock <= 0) throw apiError('RAFFLE_OUT_OF_STOCK', '응모권이 모두 소진됐습니다.');
  if (raffle.stock !== null) raffle.stock -= 1;
  return {
    entryId: Date.now(),
    raffleId: raffle.raffleId,
    raffleName: raffle.name,
    coinSpent: raffle.priceCoin,
    enteredAt: new Date().toISOString(),
    drawAt: raffle.drawAt,
  };
}
