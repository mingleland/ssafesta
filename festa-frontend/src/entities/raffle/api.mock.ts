// 응모권 mock — 대응 BE가 없는 동안 화면이 실제로 동작하도록 채우는 유일한 구현(api.select 참고).
// event-shop mock과 같은 스타일: 잔액 정합은 흉내내지 않는다(dev 전용).
import type { ApiError } from '../../shared/api/client';
import type { RaffleEntryResult, RafflePrize } from './types';

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

const raffles: RafflePrize[] = [
  { raffleId: 101, name: '말랑이', priceCoin: 250, stock: 47, active: true, closesAt: null },
  { raffleId: 102, name: '교보 기프트카드', priceCoin: 250, stock: 3, active: true, closesAt: null },
  { raffleId: 103, name: '치킨', priceCoin: 250, stock: 21, active: true, closesAt: null },
];

export async function listRaffles(): Promise<RafflePrize[]> {
  return raffles.filter((r) => r.active);
}

export async function enterRaffle(raffleId: number, _idempotencyKey: string): Promise<RaffleEntryResult> {
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
  };
}
