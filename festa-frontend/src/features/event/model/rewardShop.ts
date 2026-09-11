// 이벤트 경품 상점 상태 (S15P21A604-599).
//
// 이 화면의 정체는 처음부터 **이벤트 코인으로 한정수량 경품을 교환하는 상점**이다. 지금 상품이
// 없다고 "준비 중 안내창" 을 따로 만들면 상품이 들어올 때 화면을 다시 만들게 된다. 그래서 최종
// 상점 구조를 먼저 세우고 지금 상태를 `PREPARING` 으로 표현한다 — 바뀌는 것은 `items` 뿐이다.
//
// 화폐는 wallet Coin 이다. `entities/wallet` 은 단일 `balance` 이고 거래 사유에 `PURCHASE`(아이템 구매)가
// 이미 있다 — "이벤트 코인" 이 별도 화폐라는 근거가 저장소 어디에도 없으므로 새 화폐를 발명하지 않는다.
// 별도 화폐가 확정되면 그때 이 모듈에 화폐 축을 더한다.

export type RewardShopPhase =
  /** 경품이 아직 등록되지 않았다. shell·grid 는 그대로 두고 안내를 얹는다 */
  | 'PREPARING'
  /** 교환 가능한 경품이 있다 */
  | 'READY';

export interface RewardItem {
  id: string;
  name: string;
  /** 없으면 카드가 기본 아이콘을 그린다 */
  imageUrl?: string;
  /** 교환에 필요한 코인 */
  priceCoin: number;
  /** 남은 수량. 0 이면 교환 버튼이 비활성이다 */
  remaining: number;
  /** 최초 수량 — "3/20 남음" 처럼 보여 줄 때 쓴다 */
  total: number;
}

export interface RewardShopState {
  phase: RewardShopPhase;
  items: RewardItem[];
}

/**
 * 현재 상점 상태.
 *
 * 서버 계약이 아직 없다 — 경품 품목 유형이 BE 카탈로그에 없고(`catalog_items` 는 전부 `AVATAR_PART`),
 * 이벤트 경품 endpoint 도 없다. 계약이 오면 **이 함수 하나가 조회로 바뀐다.** 화면·카드·그리드는
 * 그대로다. 그것이 이 분리의 목적이다.
 */
export function getRewardShopState(): RewardShopState {
  return { phase: 'PREPARING', items: [] };
}

/** 카드 하단 chip 문구 — 수량 표기를 한 곳에서 정한다 */
export function remainingLabel(item: RewardItem): string {
  return item.remaining === 0 ? '품절' : `${item.remaining}/${item.total} 남음`;
}
