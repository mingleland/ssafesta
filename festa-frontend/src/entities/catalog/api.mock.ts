// Catalog mock — real 과 같은 시그니처·오류 봉투. 시나리오는 V16 시드 축소판:
// 무료(owned)·유료 미보유·비판매·구매 시 코인 부족 트리거(price 9999).
import type { ApiError } from '../../shared/api/client';
import type { CatalogItemView, CatalogResponse } from './types';
import { CATALOG_TYPE_AVATAR_PART } from './types';

const SEED: CatalogItemView[] = [
  { itemId: 1, code: 'M_Bot.01', name: 'Bot.01', equipSlot: 'BOTTOM', assetKey: '1361764366', price: 0, onSale: true, owned: true },
  { itemId: 2, code: 'F_Bot.02', name: '롤업 숏팬츠', equipSlot: 'BOTTOM', assetKey: '2118850617', price: 100, onSale: true, owned: false },
  { itemId: 3, code: 'F_Bot.03', name: '플레어 스커트', equipSlot: 'BOTTOM', assetKey: '658067854', price: 100, onSale: false, owned: false },
  { itemId: 4, code: 'F_Top.09', name: '한정판 자켓', equipSlot: 'TOP', assetKey: '999000111', price: 9999, onSale: true, owned: false },
];

let items = structuredClone(SEED);

function apiError(code: string, message: string): ApiError {
  return { code, message, errors: [], warnings: [] };
}

export async function getCatalog(type: string): Promise<CatalogResponse> {
  if (type.trim() === '') {
    throw {
      code: 'VALIDATION_FAILED',
      message: '요청 값이 올바르지 않습니다.',
      errors: [{ rule: 'FIELD_INVALID', field: 'type', message: '카탈로그 품목 유형을 입력해 주세요.' }],
      warnings: [],
    } satisfies ApiError;
  }
  // 시드는 전부 AVATAR_PART — 다른 type 은 빈 목록(장식 유형은 BE 미도달)
  return { items: type === CATALOG_TYPE_AVATAR_PART ? items.map((i) => ({ ...i })) : [] };
}

export async function purchaseItem(itemId: number): Promise<CatalogItemView> {
  const item = items.find((i) => i.itemId === itemId);
  if (!item) throw apiError('CATALOG_ITEM_NOT_FOUND', '카탈로그 품목을 찾을 수 없습니다.');
  if (!item.onSale) throw apiError('ITEM_NOT_ON_SALE', '현재 판매 중인 품목이 아닙니다.');
  if (item.owned) throw apiError('ITEM_ALREADY_OWNED', '이미 보유한 품목입니다.');
  if (item.price >= 9999) throw apiError('INSUFFICIENT_COIN', '코인이 부족합니다.');
  item.owned = true;
  return { ...item };
}

export function __resetCatalogMockForTests(): void {
  items = structuredClone(SEED);
}
