// 이벤트 상점 real API (S15P21A604-836, GitLab #217 4번). 출처: EventShopController(gitlab back).
import { api } from '../../shared/api/client';
import type { PurchaseRecipient } from '../../shared/contracts/purchaseRecipient';
import type { EventPrize, EventPurchaseResult } from './types';

export async function listPrizes(): Promise<EventPrize[]> {
  const res = await api<{ prizes: EventPrize[] }>('/api/v1/event-shop/prizes');
  return res.prizes;
}

// idempotencyKey는 호출부가 매 구매 시도마다 새로 만든다(crypto.randomUUID()) — 같은 시도를
// 재시도할 때만 같은 값을 다시 보낸다. 가격은 서버가 정하므로 요청에 금액을 넣지 않는다.
//
// recipient(캠퍼스·조 이름·이름)는 계약에 없는 필드다 — 지금 보내도 서버가 모르는 JSON
// 속성이라 조용히 버려진다(EventPurchaseResult 주석 참고). 그래도 실어 보낸다: 계약이
// 받게 되면 이 줄 하나로 실 저장이 시작된다.
export async function purchasePrize(
  prizeId: number,
  idempotencyKey: string,
  quantity = 1,
  recipient?: PurchaseRecipient,
): Promise<EventPurchaseResult> {
  return api<EventPurchaseResult>('/api/v1/event-shop/purchases', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ prizeId, quantity, ...recipient }),
  });
}
