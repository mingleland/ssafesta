// 로컬 개발용 이벤트 상점 mock — VITE_USE_MOCK=true일 때 api.ts 대신 사용.
// 지갑 mock과 잔액을 맞추지 않는다(실 서버처럼 원자적 차감을 흉내내지 않음) — dev 전용 눈요기가 목적.
import type { ApiError } from '../../shared/api/client';
import type { EventPrize, EventPurchaseResult } from './types';

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

const prizes: EventPrize[] = [
  { prizeId: 1, name: '마이구미', priceCoin: 450, stock: 32, active: true },
  { prizeId: 2, name: '프링글스', priceCoin: 700, stock: 15, active: true },
  { prizeId: 3, name: '커피', priceCoin: 750, stock: 4, active: true },
];

export async function listPrizes(): Promise<EventPrize[]> {
  return prizes.filter((p) => p.active);
}

export async function purchasePrize(prizeId: number, _idempotencyKey: string, quantity = 1): Promise<EventPurchaseResult> {
  const prize = prizes.find((p) => p.prizeId === prizeId);
  if (!prize) throw apiError('EVENT_PRIZE_NOT_FOUND', '경품을 찾을 수 없습니다.');
  if (prize.stock !== null && prize.stock < quantity) throw apiError('EVENT_PRIZE_OUT_OF_STOCK', '재고가 부족합니다.');
  if (prize.stock !== null) prize.stock -= quantity;
  return {
    purchaseId: Date.now(),
    prizeId: prize.prizeId,
    prizeName: prize.name,
    quantity,
    coinSpent: prize.priceCoin * quantity,
    fulfillment: 'PURCHASED',
    purchasedAt: new Date().toISOString(),
  };
}
