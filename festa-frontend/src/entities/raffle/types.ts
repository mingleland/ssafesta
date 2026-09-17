// 응모권 계약의 FE측 타입 — 정본이 아직 없다(S15P21A604-836에 추첨 개념 자체가 없음).
// event-shop(EventPrize/EventPurchaseResult)과 같은 모양으로 맞춘 추정치다 — 실 계약이 오면
// 이 파일과 api.ts만 계약대로 고치면 된다(S15P21A604-842 후속).
export interface RafflePrize {
  raffleId: number;
  name: string;
  priceCoin: number;
  /** null = 무제한 */
  stock: number | null;
  active: boolean;
  /** 응모 마감 시각(ISO-8601). 없으면 상시 응모 */
  closesAt?: string | null;
}

export interface RaffleEntryResult {
  entryId: number;
  raffleId: number;
  raffleName: string;
  coinSpent: number;
  enteredAt: string;
}
