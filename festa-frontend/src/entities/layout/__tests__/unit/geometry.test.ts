// §10-1 회전 AABB 회귀 방어. CONSULTATION_DESK(비대칭 bounds 함정값 — x·z 원점이 중앙이 아님)로
// 90°·180°·270°를 검산하고, 45°는 회전 사각형 AABB의 표준 공식으로 독립 교차검증한다.
import { describe, expect, it } from 'vitest';
import { isAreaOutOfBounds, rotateAABB, worldAABB } from '../../geometry.ts';
import { OBJECT_LOCAL_BOUNDS } from '../../objectTypes.ts';
import { BOOTH_SIZE_FALLBACK } from '../../../../shared/config/studio.ts';

const DESK = OBJECT_LOCAL_BOUNDS.CONSULTATION_DESK; // { min: {-0.93,0,-1.0}, max: {0.93,0.92,0.16} } — x·z 둘 다 비대칭

function closeAABB(actual: ReturnType<typeof rotateAABB>, expected: { min: { x: number; z: number }; max: { x: number; z: number } }) {
  expect(actual.min.x).toBeCloseTo(expected.min.x, 9);
  expect(actual.min.z).toBeCloseTo(expected.min.z, 9);
  expect(actual.max.x).toBeCloseTo(expected.max.x, 9);
  expect(actual.max.z).toBeCloseTo(expected.max.z, 9);
}

describe('rotateAABB — 90° 단위 (비대칭 bounds 함정값)', () => {
  it('0° — 원본 그대로', () => {
    closeAABB(rotateAABB(DESK, 0), { min: { x: -0.93, z: -1.0 }, max: { x: 0.93, z: 0.16 } });
  });

  it('90° — 네 모서리 회전 후 재계산 (90° 스왑이 아니라 실제 함정: x·z 폭이 다르다)', () => {
    closeAABB(rotateAABB(DESK, 90), { min: { x: -1.0, z: -0.93 }, max: { x: 0.16, z: 0.93 } });
  });

  it('180° — 정면·후면이 뒤바뀐다 (z 범위가 -1.0..0.16에서 -0.16..1.0로)', () => {
    closeAABB(rotateAABB(DESK, 180), { min: { x: -0.93, z: -0.16 }, max: { x: 0.93, z: 1.0 } });
  });

  it('270° — 90°의 대칭', () => {
    closeAABB(rotateAABB(DESK, 270), { min: { x: -0.16, z: -0.93 }, max: { x: 1.0, z: 0.93 } });
  });

  it('y는 원점(Y축) 회전으로 바뀌지 않는다', () => {
    for (const deg of [0, 90, 180, 270]) {
      const r = rotateAABB(DESK, deg);
      expect(r.min.y).toBe(DESK.min.y);
      expect(r.max.y).toBe(DESK.max.y);
    }
  });
});

describe('rotateAABB — 45° (표준 공식으로 독립 교차검증)', () => {
  it('회전 사각형 AABB 반폭 = |a·cosθ| + |b·sinθ| (AI_AGENT, 대칭 bounds)', () => {
    const bounds = OBJECT_LOCAL_BOUNDS.AI_AGENT; // -0.31..0.31 x, -0.16..0.16 z — 대칭이라 공식이 단순해진다
    const a = (bounds.max.x - bounds.min.x) / 2;
    const b = (bounds.max.z - bounds.min.z) / 2;
    const rad = (45 * Math.PI) / 180;
    const halfX = Math.abs(a * Math.cos(rad)) + Math.abs(b * Math.sin(rad));
    const halfZ = Math.abs(a * Math.sin(rad)) + Math.abs(b * Math.cos(rad));

    const r = rotateAABB(bounds, 45);
    expect(r.max.x - r.min.x).toBeCloseTo(halfX * 2, 9);
    expect(r.max.z - r.min.z).toBeCloseTo(halfZ * 2, 9);
  });
});

describe('worldAABB / isAreaOutOfBounds (§10-2)', () => {
  it('회전 없이 부스 중앙이면 안전하다', () => {
    const world = worldAABB(DESK, 0, { x: 0, z: 0 });
    expect(isAreaOutOfBounds(world, BOOTH_SIZE_FALLBACK)).toBe(false);
  });

  it('앵커는 안인데 회전한 실물이 걸치면 이탈로 판정한다 (실측: VIDEO_SCREEN x=2.9, rotationY=90)', () => {
    const screen = OBJECT_LOCAL_BOUNDS.VIDEO_SCREEN;
    const world = worldAABB(screen, 90, { x: 2.9, z: 0 });
    expect(isAreaOutOfBounds(world, BOOTH_SIZE_FALLBACK)).toBe(true);
  });

  it('경계선상(오차 1e-9 이내)은 안이다 — 부동소수점 잡음이 판정을 뒤집지 않는다', () => {
    // half=3, DESK가 x -0.93..0.93이라 anchor를 3-0.93=2.07에 두면 world.max.x는 정확히 3.0
    const world = worldAABB(DESK, 0, { x: 2.07, z: 0 });
    expect(world.max.x).toBeCloseTo(3, 9);
    expect(isAreaOutOfBounds(world, BOOTH_SIZE_FALLBACK)).toBe(false);
  });

  it('경계를 확실히 넘으면 이탈이다', () => {
    const world = worldAABB(DESK, 0, { x: 2.5, z: 0 });
    expect(isAreaOutOfBounds(world, BOOTH_SIZE_FALLBACK)).toBe(true);
  });
});
