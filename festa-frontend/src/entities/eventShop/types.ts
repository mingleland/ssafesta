// 이벤트 상점(즉시교환) 계약의 FE측 타입 사본 — BE EventShopController/EventShopService가 정본
// (S15P21A604-836, GitLab #217 4번). 응모권은 여기 없다 — 대응 BE가 아직 없다(S15P21A604-842).

// GET /api/v1/event-shop/prizes 원소. 판매 중단(active=false)은 서버가 목록에서 아예 뺀다.
export interface EventPrize {
  prizeId: number;
  name: string;
  priceCoin: number;
  /** null = 무제한 재고 */
  stock: number | null;
  active: boolean;
}

// POST /api/v1/event-shop/purchases 201 응답
export interface EventPurchaseResult {
  purchaseId: number;
  prizeId: number;
  prizeName: string;
  quantity: number;
  coinSpent: number;
  fulfillment: string;
  purchasedAt: string;
}
