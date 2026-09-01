import { beforeEach, describe, expect, it } from 'vitest';
import { getCatalog, purchaseItem, __resetCatalogMockForTests } from '../../api.mock';
import { toPurchaseErrorKind } from '../../mapper';
import { CATALOG_TYPE_AVATAR_PART } from '../../types';
import type { ApiError } from '../../../../shared/api/client';

function apiError(code: string): ApiError {
  return { code, message: '', errors: [], warnings: [] };
}

beforeEach(() => {
  __resetCatalogMockForTests();
});

describe('catalog mock — BE 계약 동형', () => {
  it('type 필수 — 빈 값은 VALIDATION_FAILED 봉투(FIELD_INVALID rule)', async () => {
    await expect(getCatalog('')).rejects.toMatchObject({
      code: 'VALIDATION_FAILED',
      errors: [{ rule: 'FIELD_INVALID', field: 'type' }],
    });
  });

  it('AVATAR_PART 조회 — price 0 은 owned(FR-013), 시드 외 type 은 빈 목록', async () => {
    const { items } = await getCatalog(CATALOG_TYPE_AVATAR_PART);
    expect(items).toHaveLength(4);
    expect(items.find((i) => i.price === 0)?.owned).toBe(true);
    expect((await getCatalog('UNKNOWN_TYPE')).items).toEqual([]);
  });

  it('구매 성공 — owned 전환, 재구매는 ITEM_ALREADY_OWNED', async () => {
    const bought = await purchaseItem(2);
    expect(bought.owned).toBe(true);
    expect((await getCatalog(CATALOG_TYPE_AVATAR_PART)).items.find((i) => i.itemId === 2)?.owned).toBe(true);
    await expect(purchaseItem(2)).rejects.toMatchObject({ code: 'ITEM_ALREADY_OWNED' });
  });

  it('비판매·미존재·코인 부족 오류 코드', async () => {
    await expect(purchaseItem(3)).rejects.toMatchObject({ code: 'ITEM_NOT_ON_SALE' });
    await expect(purchaseItem(999)).rejects.toMatchObject({ code: 'CATALOG_ITEM_NOT_FOUND' });
    await expect(purchaseItem(4)).rejects.toMatchObject({ code: 'INSUFFICIENT_COIN' });
  });
});

describe('toPurchaseErrorKind — ErrorCode.java 실물 매핑', () => {
  it.each([
    ['INSUFFICIENT_COIN', 'insufficient_coin'],
    ['ITEM_ALREADY_OWNED', 'already_owned'],
    ['ITEM_NOT_ON_SALE', 'not_on_sale'],
    ['CATALOG_ITEM_NOT_FOUND', 'not_found'],
    ['MEMBER_ONLY', 'member_only'],
    ['UNAUTHORIZED', 'member_only'],
    ['UNKNOWN', 'network'],
  ])('%s → %s', (code, kind) => {
    expect(toPurchaseErrorKind(apiError(code))).toBe(kind);
  });

  it('비봉투는 network', () => {
    expect(toPurchaseErrorKind(new TypeError('fetch failed'))).toBe('network');
  });
});
