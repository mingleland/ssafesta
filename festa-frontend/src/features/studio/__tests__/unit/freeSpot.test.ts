// 새 오브젝트 배치 규칙 회귀 방어 — 같은 지점에 쌓이면 선택도 드래그도 못 하게 된다(S15P21A604-405 실측).
import { describe, expect, it } from 'vitest';
import { findFreeSpot } from '../../lib/coords';
import { SNAP_METERS } from '../../../../shared/config/studio';

const BOUNDS = { width: 6, depth: 6 };

describe('findFreeSpot', () => {
  it('빈 부스에서는 중앙을 준다', () => {
    expect(findFreeSpot([], BOUNDS)).toEqual({ x: 0, z: 0 });
  });

  it('중앙이 차 있으면 다른 자리를 준다 — 최소 간격을 지킨다', () => {
    const spot = findFreeSpot([{ x: 0, z: 0 }], BOUNDS);
    expect(Math.hypot(spot.x, spot.z)).toBeGreaterThanOrEqual(0.9);
  });

  it('연속 추가해도 서로 겹치지 않는다', () => {
    const placed: Array<{ x: number; z: number }> = [];
    for (let i = 0; i < 8; i += 1) placed.push(findFreeSpot(placed, BOUNDS));
    for (let i = 0; i < placed.length; i += 1) {
      for (let j = i + 1; j < placed.length; j += 1) {
        expect(Math.hypot(placed[i].x - placed[j].x, placed[i].z - placed[j].z)).toBeGreaterThanOrEqual(0.9);
      }
    }
  });

  it('부스 경계 안이고 스냅 격자 위다', () => {
    const placed: Array<{ x: number; z: number }> = [];
    for (let i = 0; i < 8; i += 1) placed.push(findFreeSpot(placed, BOUNDS));
    for (const s of placed) {
      expect(Math.abs(s.x)).toBeLessThanOrEqual(BOUNDS.width / 2);
      expect(Math.abs(s.z)).toBeLessThanOrEqual(BOUNDS.depth / 2);
      expect(Math.abs(Math.round(s.x / SNAP_METERS) * SNAP_METERS - s.x)).toBeLessThan(1e-9);
      expect(Math.abs(Math.round(s.z / SNAP_METERS) * SNAP_METERS - s.z)).toBeLessThan(1e-9);
    }
  });
});
