// Catalog/Purchase 계약 전사 — 구현이 정본 (origin/develop backend inventory/InventoryService.CatalogItemView,
// S15P21A604-378). spec 012 FR-013: 조회는 품목 정보와 호출자 기준 owned 를 한 응답에 반환하고,
// price=0 품목은 회원·게스트 모두 보유로 판정한다. 조회는 토큰 없어도 동작한다(optionalMemberId).

export interface CatalogItemView {
  itemId: number;
  code: string;
  name: string;
  equipSlot: string;
  /** Unity avatar_code 의 i= 슬롯 값 (spec 012 FR-012) — 비모자는 itemId, 모자는 familyId 가 소유 단위 */
  assetKey: string;
  price: number;
  onSale: boolean;
  owned: boolean;
}

export interface CatalogResponse {
  items: CatalogItemView[];
}

// 현재 BE 에 존재하는 유일한 품목 유형 (InventoryService.AVATAR_PART, V16 시드 전부 이 값).
// 부스 장식(-167 팔레트 잠금의 최종 대상) 유형 값은 아직 BE 에 없다 — 여기서 발명하지 않고,
// 그 값이 시드·상수로 도달하면 상수 하나를 추가하는 것이 확장의 전부다.
export const CATALOG_TYPE_AVATAR_PART = 'AVATAR_PART';
