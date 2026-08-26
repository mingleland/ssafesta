// 회전 반영 실물 AABB 계산 — 앵커 점이 안이어도 회전한 몸체가 옆으로 걸치는 배치를 잡는다
// 출처: specs/005-booth-studio-layout/FE/tasks.md T022, contracts/layout-api.md §10-1·§10-2

import type { AABB } from './objectTypes';

// 계약서 회전 공식 그대로: x' = x·cosθ + z·sinθ, z' = -x·sinθ + z·cosθ (위에서 볼 때 시계방향 +).
// 90° 단위 스왑이 아니라 네 모서리 각각에 적용한 뒤 다시 AABB를 잡는다 — 원점 기준 로컬 AABB용.
export function rotateAABB(local: AABB, rotationYDeg: number): AABB {
  const rad = (rotationYDeg * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);

  const corners = [
    { x: local.min.x, z: local.min.z },
    { x: local.min.x, z: local.max.z },
    { x: local.max.x, z: local.min.z },
    { x: local.max.x, z: local.max.z },
  ].map((c) => ({ x: c.x * cos + c.z * sin, z: -c.x * sin + c.z * cos }));

  const xs = corners.map((c) => c.x);
  const zs = corners.map((c) => c.z);

  // y는 원점(y축) 회전으로 바뀌지 않는다 — 로컬 min.y/max.y 그대로 옮긴다.
  return {
    min: { x: Math.min(...xs), y: local.min.y, z: Math.min(...zs) },
    max: { x: Math.max(...xs), y: local.max.y, z: Math.max(...zs) },
  };
}

// 회전 AABB를 오브젝트 위치로 옮겨 부스 좌표계(월드) 기준 실물 범위를 낸다.
export function worldAABB(local: AABB, rotationYDeg: number, position: { x: number; z: number }): AABB {
  const rotated = rotateAABB(local, rotationYDeg);
  return {
    min: { x: rotated.min.x + position.x, y: rotated.min.y, z: rotated.min.z + position.z },
    max: { x: rotated.max.x + position.x, y: rotated.max.y, z: rotated.max.z + position.z },
  };
}

// 부동소수점 잡음이 판정을 뒤집지 않도록 계약과 같은 허용오차를 둔다(§10-2 "경계선상은 안이다").
const EPSILON = 1e-9;

export function isAreaOutOfBounds(
  world: AABB,
  booth: { width: number; depth: number; height: number },
): boolean {
  const halfW = booth.width / 2;
  const halfD = booth.depth / 2;
  return (
    world.min.x < -halfW - EPSILON ||
    world.max.x > halfW + EPSILON ||
    world.min.z < -halfD - EPSILON ||
    world.max.z > halfD + EPSILON ||
    world.min.y < -EPSILON ||
    world.max.y > booth.height + EPSILON
  );
}
