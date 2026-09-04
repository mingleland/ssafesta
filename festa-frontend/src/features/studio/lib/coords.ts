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

// 새 오브젝트를 놓을 빈 자리 — 같은 지점에 쌓이면 선택도 드래그도 불가능해진다.
// 부스 중앙에서 바깥으로 나선형으로 훑어 이미 놓인 것과 최소 간격이 확보되는 첫 칸을 고른다.
export function findFreeSpot(
  taken: ReadonlyArray<{ x: number; z: number }>,
  bounds: { width: number; depth: number },
  minGap: number = 0.9,
): { x: number; z: number } {
  const step = 0.75;
  const halfW = bounds.width / 2 - 0.4;
  const halfD = bounds.depth / 2 - 0.4;
  const free = (x: number, z: number) => taken.every((t) => Math.hypot(t.x - x, t.z - z) >= minGap);
  for (let ring = 0; ring <= Math.ceil(Math.max(halfW, halfD) / step); ring += 1) {
    for (let ix = -ring; ix <= ring; ix += 1) {
      for (let iz = -ring; iz <= ring; iz += 1) {
        if (ring > 0 && Math.abs(ix) !== ring && Math.abs(iz) !== ring) continue;
        const x = clamp(ix * step, -halfW, halfW);
        const z = clamp(iz * step, -halfD, halfD);
        // +0 을 더해 -0 을 없앤다 — 계약 JSON 에 -0 이 들어가면 서버·Unity 쪽 비교가 흔들린다
        if (free(x, z)) return { x: snap(x) + 0, z: snap(z) + 0 };
      }
    }
  }
  return { x: 0, z: 0 };
}
