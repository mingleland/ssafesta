// wallet mock의 계약 재현 고정 — 잔액 단일 원천(debitForLease)·부족 봉투·페이지네이션·라벨 폴백(SC-005)
// 출처: specs/003-wallet-coin/contracts/wallet-api.md
import { beforeEach, describe, expect, it } from 'vitest';
import { __resetWalletMockForTests, debitForLease, getTransactions, getWallet } from '../../api.mock';
import { labelForReason } from '../../types';
import { isApiError } from '../../../../shared/api/client';

beforeEach(() => __resetWalletMockForTests());

describe('wallet mock — 잔액', () => {
  it('초기 잔액 200, shape가 계약과 같다', async () => {
    const w = await getWallet();
    expect(w.balance).toBe(200);
    expect(typeof w.userId).toBe('number');
    expect(typeof w.updatedAt).toBe('string');
  });

  it('debitForLease는 차감 후 잔액을 반환하고 getWallet과 일치한다', async () => {
    expect(debitForLease(100)).toBe(100);
    expect((await getWallet()).balance).toBe(100);
  });

  it('부족 시 INSUFFICIENT_COIN 봉투로 거부하고 잔액을 건드리지 않는다', async () => {
    debitForLease(100); // 100 남음
    try {
      debitForLease(150);
      throw new Error('부족 차감이 통과했다');
    } catch (e) {
      expect(isApiError(e) && e.code).toBe('INSUFFICIENT_COIN');
    }
    expect((await getWallet()).balance).toBe(100);
  });
});

describe('wallet mock — 거래 내역 페이지네이션', () => {
  it('기본 20건, 봉투 5필드', async () => {
    const p = await getTransactions(0);
    expect(p.content).toHaveLength(20);
    expect(p.totalElements).toBe(45);
    expect(p.totalPages).toBe(3);
    expect(p.page).toBe(0);
    expect(p.size).toBe(20);
  });

  it('마지막 페이지는 나머지만, 범위 밖 페이지는 빈 content', async () => {
    expect((await getTransactions(2)).content).toHaveLength(5);
    expect((await getTransactions(9)).content).toHaveLength(0);
  });

  it('page 음수·size 범위 밖은 VALIDATION_FAILED', async () => {
    for (const [page, size] of [[-1, 20], [0, 0], [0, 101]] as const) {
      try {
        await getTransactions(page, size);
        throw new Error('통과하면 안 된다');
      } catch (e) {
        expect(isApiError(e) && e.code).toBe('VALIDATION_FAILED');
      }
    }
  });

  it('시드에 미지 reasonType이 존재한다 — 폴백 실측 전제', async () => {
    const all = (await getTransactions(0, 100)).content;
    expect(all.some((t) => t.reasonType === 'FUTURE_UNKNOWN_REASON')).toBe(true);
  });
});

describe('labelForReason — SC-005', () => {
  it('알려진 값은 한글 라벨', () => {
    expect(labelForReason('DAILY_GRANT')).toBe('일일 지급');
    expect(labelForReason('LEASE_PAYMENT')).toBe('부스 임대');
  });

  it('모르는 값은 숨기지 않고 원문 반환', () => {
    expect(labelForReason('FUTURE_UNKNOWN_REASON')).toBe('FUTURE_UNKNOWN_REASON');
  });
});
