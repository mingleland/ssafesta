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
//
// campus·teamName·recipientName은 실 BE 계약(EventShopController.PurchaseRequest)에 아직 없다
// (event_purchases 테이블에 대응 컬럼 없음, S15P21A604-842 후속) — 보내도 서버가 조용히 버린다.
// 그래도 요청에 실어 둔다: 계약이 필드를 받게 되는 순간 이 파일 하나만 실 저장으로 바뀐다.
// docs/26에 "구매 받는 자 정보 컬럼 추가" 결정 필요 항목으로 남겨 뒀다.
export interface EventPurchaseResult {
  purchaseId: number;
  prizeId: number;
  prizeName: string;
  quantity: number;
  coinSpent: number;
  fulfillment: string;
  purchasedAt: string;
}
