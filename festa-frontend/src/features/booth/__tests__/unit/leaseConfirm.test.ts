// 확인 모달의 잔액 판정 고정 — 부족 표시가 요청 차단으로 번지지 않는 것이 이 파일의 요지다(T-24)
import { describe, expect, it } from 'vitest';
import { judgeAffordability } from '../../model/leaseConfirm';
import { LEASE_COIN_COST } from '../../../../entities/booth/types';

describe('judgeAffordability', () => {
  it('잔액을 모르면 unknown — 부족으로 단정하지 않는다', () => {
    expect(judgeAffordability(undefined, LEASE_COIN_COST)).toBe('unknown');
  });

  it('임대료와 같으면 sufficient — 경계값은 충분한 쪽이다', () => {
    expect(judgeAffordability(LEASE_COIN_COST, LEASE_COIN_COST)).toBe('sufficient');
  });

  it('임대료보다 1 적으면 insufficient', () => {
    expect(judgeAffordability(LEASE_COIN_COST - 1, LEASE_COIN_COST)).toBe('insufficient');
  });

  it('0도 insufficient — 그래도 판정일 뿐 요청을 막는 신호가 아니다', () => {
    expect(judgeAffordability(0, LEASE_COIN_COST)).toBe('insufficient');
  });
});
