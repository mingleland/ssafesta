// lease mock의 계약 재현 고정 — 목록 shape·임대 차감·FR-018 무차감 재요청·409 전종·
// C-02 읽기 시 만료 판정·getMyBooth null 정규화
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetLeaseMockForTests, getMyBooth, getSlots, leaseSlot } from '../../leaseApi.mock';
import { __resetWalletMockForTests, getWallet } from '../../../wallet/api.mock';
import { isApiError } from '../../../../shared/api/client';

beforeEach(() => {
  __resetLeaseMockForTests();
  __resetWalletMockForTests();
});

async function rejection(fn: () => Promise<unknown>): Promise<unknown> {
  try {
    await fn();
  } catch (e) {
    return e;
  }
  throw new Error('거부돼야 하는 요청이 통과했다');
}

describe('getSlots — 목록', () => {
  it('시드 전 슬롯이 계약 shape로 온다 (개수는 length 기준 — 하드코딩 금지)', async () => {
    const slots = await getSlots();
    expect(slots.length).toBeGreaterThan(0);
    for (const s of slots) {
      expect(typeof s.slotId).toBe('number');
      expect(s.slotCode).toMatch(/^F11-/);
      expect(s.floorNo).toBe(11);
      expect(['AVAILABLE', 'OCCUPIED']).toContain(s.status);
      expect(typeof s.mine).toBe('boolean');
    }
  });

  it('초기 상태는 전부 AVAILABLE·mine false', async () => {
    const slots = await getSlots();
    expect(slots.every((s) => s.status === 'AVAILABLE' && !s.mine)).toBe(true);
  });
});

describe('leaseSlot — 임대·오류', () => {
  it('임대 성공 시 100 차감, 목록에 OCCUPIED·mine 반영', async () => {
    const r = await leaseSlot(1);
    expect(r.chargedCoin).toBe(100);
    expect(r.balanceAfter).toBe(100);
    expect((await getWallet()).balance).toBe(100);
    const slot = (await getSlots()).find((s) => s.slotId === 1);
    expect(slot?.status).toBe('OCCUPIED');
    expect(slot?.mine).toBe(true);
  });

  it('FR-018 — 같은 슬롯 재요청은 무차감으로 같은 leaseId 반환', async () => {
    const first = await leaseSlot(1);
    const again = await leaseSlot(1);
    expect(again.leaseId).toBe(first.leaseId);
    expect((await getWallet()).balance).toBe(100); // 두 번째 차감 없음
    expect(again.balanceAfter).toBe(100);
  });

  it('활성 임대 보유 중 다른 슬롯 → ACTIVE_LEASE_LIMIT', async () => {
    await leaseSlot(1);
    const e = await rejection(() => leaseSlot(2));
    expect(isApiError(e) && e.code).toBe('ACTIVE_LEASE_LIMIT');
  });

  it('ADMIN 슬롯 → BOOTH_SLOT_NOT_RENTABLE', async () => {
    const e = await rejection(() => leaseSlot(905));
    expect(isApiError(e) && e.code).toBe('BOOTH_SLOT_NOT_RENTABLE');
  });

  it('없는 슬롯 → BOOTH_SLOT_NOT_FOUND', async () => {
    const e = await rejection(() => leaseSlot(12345));
    expect(isApiError(e) && e.code).toBe('BOOTH_SLOT_NOT_FOUND');
  });

  it('sentinel R06 → INSUFFICIENT_COIN, 차감 없음', async () => {
    const e = await rejection(() => leaseSlot(6));
    expect(isApiError(e) && e.code).toBe('INSUFFICIENT_COIN');
    expect((await getWallet()).balance).toBe(200);
  });

  it('durationDays !== 1 → FIELD_INVALID 봉투', async () => {
    const e = await rejection(() => leaseSlot(1, 3));
    expect(isApiError(e) && e.code).toBe('VALIDATION_FAILED');
    expect(isApiError(e) && e.errors[0]?.rule).toBe('FIELD_INVALID');
    expect(isApiError(e) && e.errors[0]?.field).toBe('durationDays');
  });
});

describe('getMyBooth · C-02 읽기 시 만료 판정', () => {
  it('임대 없으면 null (204 정규화와 동일 시그니처)', async () => {
    expect(await getMyBooth()).toBeNull();
  });

  it('임대 후에는 부스·lease가 온다', async () => {
    const r = await leaseSlot(2);
    const booth = await getMyBooth();
    expect(booth?.boothId).toBe(r.boothId);
    expect(booth?.lease?.slotId).toBe(2);
    expect(booth?.lease?.slotCode).toBe('F11-R02');
  });

  it('sentinel R07(90초 임대)의 endsAt 경과 후 getSlots가 AVAILABLE로 되돌린다 — C-02 읽기 시 판정', async () => {
    vi.useFakeTimers();
    try {
      await leaseSlot(7);
      expect((await getSlots()).find((s) => s.slotId === 7)?.status).toBe('OCCUPIED');
      vi.advanceTimersByTime(91_000); // 90초 임대 경과 — Date.now()가 endsAt을 넘는다
      expect((await getSlots()).every((s) => s.status === 'AVAILABLE')).toBe(true);
      expect(await getMyBooth()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

afterEach(() => vi.useRealTimers());
