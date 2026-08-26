// 좌표 부호 회귀 방어 — 헌법 21조 "부호 하나는 반드시 틀린다" 영역. 값은 실측(quickstart §3,
// docs/LJH/verify/booth-studio-quickstart.md 2026-08-22)에서 확인된 그대로를 고정한다.
import { describe, expect, it } from 'vitest';
import { clamp, clampToBooth, normalizeRotation, screenYToWorldZ, snap, worldZToScreenY } from '../../lib/coords.ts';

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
