// 로컬 개발용 이벤트 상점 mock — VITE_USE_MOCK=true일 때 api.ts 대신 사용.
// 지갑 mock과 잔액을 맞추지 않는다(실 서버처럼 원자적 차감을 흉내내지 않음) — dev 전용 눈요기가 목적.
import type { ApiError } from '../../shared/api/client';
import type { PurchaseRecipient } from '../../shared/contracts/purchaseRecipient';
import type { EventPrize, EventPurchaseResult } from './types';

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

// 가격 확정(2026-09-17, S15P21A604-842 후속) — 말랑이·교보 기프트카드는 응모권에서 즉시교환으로
// 옮겨졌다. 실 BE 가격은 admin 콘솔(-832)에서 따로 등록해야 한다 — 여기는 로컬 mock일 뿐이다.
const prizes: EventPrize[] = [
  { prizeId: 1, name: '마이구미', priceCoin: 400, stock: 32, active: true, winnerCount: 0 },
  { prizeId: 2, name: '초코송이', priceCoin: 500, stock: 15, active: true, winnerCount: 0 },
  { prizeId: 3, name: '아이스아메리카노', priceCoin: 600, stock: 4, active: true, winnerCount: 0 },
  { prizeId: 4, name: '말랑이', priceCoin: 700, stock: 47, active: true, winnerCount: 0 },
  { prizeId: 5, name: '교보문고 10000원권', priceCoin: 1000, stock: 3, active: true, winnerCount: 0 },
  { prizeId: 6, name: '치킨', priceCoin: 50, stock: 100, active: true, closesAt: '2026-09-23T06:00:00Z', winnerCount: 1 },
];

export async function listPrizes(): Promise<EventPrize[]> {
  return prizes.filter((p) => p.active);
}

export async function purchasePrize(
  prizeId: number,
  _idempotencyKey: string,
  quantity = 1,
  _recipient?: PurchaseRecipient,
): Promise<EventPurchaseResult> {
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
