// 이벤트 상점 real API (S15P21A604-836, GitLab #217 4번). 출처: EventShopController(gitlab back).
import { api } from '../../shared/api/client';
import type { EventPrize, EventPurchaseResult } from './types';

export async function listPrizes(): Promise<EventPrize[]> {
  const res = await api<{ prizes: EventPrize[] }>('/api/v1/event-shop/prizes');
  return res.prizes;
}

// idempotencyKey는 호출부가 매 구매 시도마다 새로 만든다(crypto.randomUUID()) — 같은 시도를
// 재시도할 때만 같은 값을 다시 보낸다. 가격은 서버가 정하므로 요청에 금액을 넣지 않는다.
export async function purchasePrize(
  prizeId: number,
  idempotencyKey: string,
  quantity = 1,
): Promise<EventPurchaseResult> {
  return api<EventPurchaseResult>('/api/v1/event-shop/purchases', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ prizeId, quantity }),
  });
}
