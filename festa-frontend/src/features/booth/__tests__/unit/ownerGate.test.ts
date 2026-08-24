// G-1 Owner 판정 고정 — 훅은 .tsx 테스트 공백(G-4)에 걸리므로 순수 함수 judgeOwner만 검증
import { describe, expect, it } from 'vitest';
import { judgeOwner } from '../../model/useOwnerGate';
import type { MyBooth } from '../../../../entities/booth/types';

function booth(boothId: number): MyBooth {
  return { boothId, name: '내 부스', status: 'ACTIVE', lease: null };
}

describe('judgeOwner', () => {
  it('로딩 중은 loading — 판정을 내리지 않는다', () => {
    expect(judgeOwner(undefined, true, false, 1)).toBe('loading');
  });

  it('네트워크 오류는 error — 차단이 아니라 재시도 안내', () => {
    expect(judgeOwner(undefined, false, true, 1)).toBe('error');
  });

  it('부스 없음(204→null)은 not-owner', () => {
    expect(judgeOwner(null, false, false, 1)).toBe('not-owner');
  });

  it('boothId 불일치는 not-owner', () => {
    expect(judgeOwner(booth(2), false, false, 1)).toBe('not-owner');
  });

  it('boothId 일치만 owner', () => {
    expect(judgeOwner(booth(1), false, false, 1)).toBe('owner');
  });
});
