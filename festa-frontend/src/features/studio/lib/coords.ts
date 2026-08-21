// 화면 좌표(SVG)와 저장 좌표(부스 미터, +Z=정면)를 잇는 유일한 지점 — 부호 반전은 여기 한 곳에만 있다
// 편집기는 위에서 내려다보는 2D 평면, 저장값은 헌법 21조 좌표계(원점=바닥 중앙, +Z=정면).
// 화면에서 "아래"로 이동은 저장 좌표로 "-Z" 방향이다 (docs/10 좌표 규칙 3항).
// 출처: specs/005-booth-studio-layout/FE/research.md R-03·R-04

import { SNAP_METERS } from '../../../shared/config/studio';

// SVG 화면 y좌표 ↔ 저장 z좌표. x는 그대로(부호 동일), z만 반전한다.
export function worldZToScreenY(z: number): number {
  return -z;
}

export function screenYToWorldZ(y: number): number {
  return -y;
}

export function snap(value: number, step: number = SNAP_METERS): number {
  return Math.round(value / step) * step;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

// 부스 경계 안으로 x·z를 클램프한다 — 상수(width/depth)에서 도출, 숫자 하드코딩 금지.
export function clampToBooth(
  x: number,
  z: number,
  bounds: { width: number; depth: number },
): { x: number; z: number } {
  const halfW = bounds.width / 2;
  const halfD = bounds.depth / 2;
  return { x: clamp(x, -halfW, halfW), z: clamp(z, -halfD, halfD) };
}

// rotationY를 계약 규칙대로 [0,360)에 정규화한다.
export function normalizeRotation(deg: number): number {
  const r = deg % 360;
  return r < 0 ? r + 360 : r;
}
