// 경품 넣고 빼기 — mock 어댑터의 등록·수정 검증이 BE validatePrizeFields 와 같은 표를 지키는지.
// FE 가 통과시킨 입력을 BE 가 400 으로 막으면 운영자에게는 "눌렀는데 실패" 로만 보인다.
import { beforeEach, describe, expect, it } from 'vitest';
import { adminApi, __resetAdminMockForTests } from '../../../../entities/admin/api.mock';
import { prizeKindLabel } from '../../../../entities/admin/types';

beforeEach(() => __resetAdminMockForTests());

describe('경품 종류 표기', () => {
  it('winnerCount 0 이면 즉시 구매, 1 이상이면 응모형', () => {
    expect(prizeKindLabel(0)).toBe('즉시 구매');
    expect(prizeKindLabel(3)).toBe('응모형');
  });
});

describe('경품 등록', () => {
  it('즉시 구매는 재고 무제한(null)으로 넣을 수 있다', async () => {
    const p = await adminApi.createPrize({ name: '뱃지', priceCoin: 10, stock: null, closesAt: null, winnerCount: 0 });
    expect(p.winnerCount).toBe(0);
    expect(p.stock).toBeNull();
    expect(p.active).toBe(true);
  });

  it('응모형은 응모권 수 없이는 등록되지 않는다', async () => {
    await expect(adminApi.createPrize({ name: '추첨', priceCoin: 10, stock: null, closesAt: null, winnerCount: 1 }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('당첨자 수는 응모권 수보다 많을 수 없다', async () => {
    await expect(adminApi.createPrize({ name: '추첨', priceCoin: 10, stock: 2, closesAt: null, winnerCount: 3 }))
      .rejects.toMatchObject({ status: 400 });
  });
});

describe('경품 빼기 = 판매 종료(active=false)', () => {
  it('active 를 뒤집어도 경품은 목록에 남는다 — 하드 삭제가 아니다', async () => {
    const [first] = await adminApi.listPrizes();
    const stopped = await adminApi.updatePrize(first.prizeId, {
      name: first.name, priceCoin: first.priceCoin, stock: first.stock, closesAt: first.closesAt, winnerCount: first.winnerCount, active: false,
    });
    expect(stopped.active).toBe(false);
    const after = await adminApi.listPrizes();
    expect(after.some((p) => p.prizeId === first.prizeId)).toBe(true);
  });

  it('없는 경품 수정은 404', async () => {
    await expect(adminApi.updatePrize(9999, { name: 'x', priceCoin: 1, stock: null, closesAt: null, winnerCount: 0, active: true }))
      .rejects.toMatchObject({ status: 404 });
  });
});
