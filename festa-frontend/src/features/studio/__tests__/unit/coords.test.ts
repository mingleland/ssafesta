// 좌표 부호 회귀 방어 — 헌법 21조 "부호 하나는 반드시 틀린다" 영역. 값은 실측(quickstart §3,
// docs/LJH/verify/booth-studio-quickstart.md 2026-08-22)에서 확인된 그대로를 고정한다.
import { describe, expect, it } from 'vitest';
import {
  clamp,
  clampAreaToBooth,
  clampObjectToBooth,
  clampToBooth,
  normalizeRotation,
  screenYToWorldZ,
  snap,
  worldZToScreenY,
} from '../../lib/coords.ts';
import { isAreaOutOfBounds, worldAABB } from '../../../../entities/layout/geometry.ts';

describe('worldZToScreenY / screenYToWorldZ', () => {
  it('아래로 드래그하면 저장 좌표 z는 음수다 (실측: 화면 y+68px → z=-1.5)', () => {
    // 화면에서 "아래"는 z 감소 방향 — worldZToScreenY(z) = -z이므로 z<0일 때 screenY>0(화면 아래)이어야 한다.
    expect(worldZToScreenY(-1.5)).toBeGreaterThan(0);
  });

  it('오른쪽으로 드래그하면 x는 그대로 양수다 (부호 반전 없음, 실측: x=1.75)', () => {
    expect(screenYToWorldZ(-1.5)).toBe(1.5);
  });

  it('서로 역함수다 — 왕복해도 원래 값으로 돌아온다', () => {
    for (const z of [-3, -1.5, 0, 1.5, 3]) {
      expect(screenYToWorldZ(worldZToScreenY(z))).toBe(z);
    }
  });
});

describe('snap', () => {
  it('SNAP_METERS(0.25) 배수로 반올림한다', () => {
    expect(snap(1.1)).toBe(1);
    expect(snap(1.13)).toBe(1.25);
    expect(snap(-0.9)).toBe(-1);
  });

  it('임의 step을 받으면 그 값으로 반올림한다', () => {
    expect(snap(1.1, 0.5)).toBe(1);
    expect(snap(1.3, 0.5)).toBe(1.5);
  });
});

describe('clamp / clampToBooth', () => {
  it('범위 안 값은 그대로 통과한다', () => {
    expect(clamp(0, -3, 3)).toBe(0);
  });

  it('범위 밖 값은 경계로 잘린다', () => {
    expect(clamp(10, -3, 3)).toBe(3);
    expect(clamp(-10, -3, 3)).toBe(-3);
  });

  it('부스 경계(6×6) 밖 앵커를 절반 폭·깊이로 클램프한다', () => {
    const bounds = { width: 6, depth: 6 };
    expect(clampToBooth(10, -10, bounds)).toEqual({ x: 3, z: -3 });
  });
});

// 회전 반영 클램프 (S15P21A604-754, GitLab #181).
//
// 예전에는 중심점만 막아 크기도 회전도 보지 않았다. 그래서 드래그로 놓을 수 있는 자리가
// isAreaOutOfBounds 검증에서는 위반으로 걸렸다 — 편집기가 허용한 자리에 못 놓는 경험이다.
describe('clampAreaToBooth', () => {
  const bounds = { width: 6, depth: 6 };
  // 비대칭 로컬 AABB — 실제 PROJECT_PANEL 이 min.x -0.78 / max.x 0.77 로 비대칭이다
  const asymmetric = { min: { x: -0.8, y: 0, z: -0.2 }, max: { x: 0.6, y: 2, z: 0.2 } };

  it('0°에서 min·max 를 각 축에 따로 반영한다 — 단일 half-extent 가 아니다', () => {
    // 오른쪽 한계는 3 - 0.6 = 2.4, 왼쪽 한계는 -3 + 0.8 = -2.2 로 서로 다르다
    expect(clampAreaToBooth(10, 0, asymmetric, 0, bounds).x).toBeCloseTo(2.4, 9);
    expect(clampAreaToBooth(-10, 0, asymmetric, 0, bounds).x).toBeCloseTo(-2.2, 9);
  });

  it('90° 회전하면 x·z 의 한계가 맞바뀐다', () => {
    const r = clampAreaToBooth(10, 10, asymmetric, 90, bounds);
    // z 범위(±0.2)가 x 쪽으로 온다 — 한계가 3 - 0.2 = 2.8 로 넓어진다
    expect(r.x).toBeCloseTo(2.8, 9);
    // x 범위(-0.8~0.6)가 z 쪽으로 온다
    expect(Math.abs(r.z)).toBeLessThan(3);
  });

  it('45°에서는 대각으로 커진 만큼 한계가 좁아진다', () => {
    const square = { min: { x: -1, y: 0, z: -1 }, max: { x: 1, y: 2, z: 1 } };
    const straight = clampAreaToBooth(10, 0, square, 0, bounds).x;
    const diagonal = clampAreaToBooth(10, 0, square, 45, bounds).x;

    expect(straight).toBeCloseTo(2, 9);
    expect(diagonal).toBeLessThan(straight);
    expect(diagonal).toBeCloseTo(3 - Math.SQRT2, 9);
  });

  it('오브젝트가 부스보다 크면 그 축을 중심으로 고정한다 — 검증 흉내를 내지 않는다', () => {
    const huge = { min: { x: -5, y: 0, z: -0.2 }, max: { x: 5, y: 2, z: 0.2 } };

    const r = clampAreaToBooth(10, 10, huge, 0, bounds);

    expect(r.x).toBe(0);
    // z 는 여전히 들어가므로 정상 클램프된다
    expect(r.z).toBeCloseTo(2.8, 9);
    // 위반 판정의 권위는 계속 검증 쪽이다
    expect(isAreaOutOfBounds(worldAABB(huge, 0, r), { ...bounds, height: 3 })).toBe(true);
  });

  it('클램프 결과는 검증을 통과한다 — 둘이 갈리지 않는 것이 이 함수의 목적이다', () => {
    const booth = { width: 6, depth: 6, height: 3 };
    for (const deg of [0, 30, 45, 90, 137, 180, 270]) {
      const c = clampAreaToBooth(99, -99, asymmetric, deg, bounds);
      expect(isAreaOutOfBounds(worldAABB(asymmetric, deg, c), booth)).toBe(false);
    }
  });
});

describe('clampObjectToBooth', () => {
  const bounds = { width: 6, depth: 6 };

  it('로컬 AABB 를 모르는 타입은 중심 클램프로 떨어진다', () => {
    expect(clampObjectToBooth(10, -10, undefined, 90, bounds)).toEqual({ x: 3, z: -3 });
  });

  it('아는 타입은 몸체째로 막는다', () => {
    const box = { min: { x: -0.5, y: 0, z: -0.5 }, max: { x: 0.5, y: 1, z: 0.5 } };
    expect(clampObjectToBooth(10, 0, box, 0, bounds).x).toBeCloseTo(2.5, 9);
  });
});

describe('normalizeRotation', () => {
  it('[0,360) 범위는 그대로', () => {
    expect(normalizeRotation(90)).toBe(90);
    expect(normalizeRotation(0)).toBe(0);
  });

  it('360 이상·음수는 [0,360)으로 접는다', () => {
    expect(normalizeRotation(450)).toBe(90);
    expect(normalizeRotation(-90)).toBe(270);
    expect(normalizeRotation(360)).toBe(0);
  });
});
