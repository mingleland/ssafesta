// Catalog real API — 출처: origin/develop backend inventory/CatalogController.java (S15P21A604-378).
// 조회는 토큰 선택(게스트도 200 — owned 는 price=0 만 true), 구매는 MEMBER 전용(403 MEMBER_ONLY).
import { api } from '../../shared/api/client';
import type { CatalogItemView, CatalogResponse } from './types';

export function getCatalog(type: string): Promise<CatalogResponse> {
  return api<CatalogResponse>(`/api/v1/catalog/items?type=${encodeURIComponent(type)}`);
}

// 201 — 응답은 구매 직후의 품목(owned=true). 오류: 404 CATALOG_ITEM_NOT_FOUND ·
// 409 ITEM_NOT_ON_SALE / ITEM_ALREADY_OWNED / INSUFFICIENT_COIN · 403 MEMBER_ONLY
export function purchaseItem(itemId: number): Promise<CatalogItemView> {
  return api<CatalogItemView>(`/api/v1/catalog/items/${itemId}/purchases`, { method: 'POST' });
}
