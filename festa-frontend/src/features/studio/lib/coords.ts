// 화면 좌표(SVG)와 저장 좌표(부스 미터, +Z=정면)를 잇는 유일한 지점 — 부호 반전은 여기 한 곳에만 있다
// 편집기는 위에서 내려다보는 2D 평면, 저장값은 헌법 21조 좌표계(원점=바닥 중앙, +Z=정면).
// 화면에서 "아래"로 이동은 저장 좌표로 "-Z" 방향이다 (docs/10 좌표 규칙 3항).
// 출처: specs/005-booth-studio-layout/FE/research.md R-03·R-04

import { SNAP_METERS } from '../../../shared/config/studio';
import { rotateAABB } from '../../../entities/layout/geometry';
import type { AABB } from '../../../entities/layout/objectTypes';

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
//
// **중심점만 본다.** 로컬 AABB를 아는 자리에서는 clampAreaToBooth를 쓴다 — 이 함수는 크기를
// 모르는 타입(계약에 AABB가 없는 assetCode)의 폴백으로만 남는다.
export function clampToBooth(
  x: number,
  z: number,
  bounds: { width: number; depth: number },
): { x: number; z: number } {
  const halfW = bounds.width / 2;
  const halfD = bounds.depth / 2;
  return { x: clamp(x, -halfW, halfW), z: clamp(z, -halfD, halfD) };
}

// 한 축의 중심 허용 범위 — 회전한 몸체가 벽을 넘지 않는 구간이다.
//
// 오브젝트가 부스보다 크면 lo > hi로 구간이 뒤집힌다. 그때는 부스 중심(0)으로 고정한다.
// **클램프가 검증을 흉내 내지 않는 것이 핵심이다** — 위반 판정의 권위는 isAreaOutOfBounds
// 하나이고, 여기서 억지로 "가장 덜 위반하는 자리"를 계산하면 둘이 다시 갈린다.
function clampAxis(value: number, half: number, min: number, max: number): number {
  const lo = -half - min;
  const hi = half - max;
  if (lo > hi) return 0;
  return clamp(value, lo, hi);
}

/**
 * 회전한 실물 범위째로 부스 안에 넣는다 (S15P21A604-754, GitLab #181).
 *
 * 예전에는 중심점만 클램프해 크기도 회전도 보지 않았다. 그래서 **드래그로 놓을 수 있는 자리가
 * 검증에서는 `AREA_OUT_OF_BOUNDS`로 걸렸다** — 편집기가 허용한 자리에 못 놓는 경험이 된다.
 *
 * 회전 계산을 복제하지 않고 `rotateAABB`를 그대로 쓴다. 로컬 AABB는 대칭이 아니므로
 * (`PROJECT_PANEL`이 `min.x -0.78 / max.x 0.77`) 단일 half-extent로 줄이지 않고 축마다
 * min·max를 따로 반영한다.
 */
export function clampAreaToBooth(
  x: number,
  z: number,
  local: AABB,
  rotationYDeg: number,
  bounds: { width: number; depth: number },
): { x: number; z: number } {
  const rotated = rotateAABB(local, rotationYDeg);
  return {
    x: clampAxis(x, bounds.width / 2, rotated.min.x, rotated.max.x),
    z: clampAxis(z, bounds.depth / 2, rotated.min.z, rotated.max.z),
  };
}

/**
 * 타입의 로컬 AABB를 모르면 중심 클램프로 떨어진다.
 *
 * 계약에 AABB가 없는 assetCode가 들어오면 크기를 알 길이 없다. 그때 0.5 같은 값을 지어내면
 * 그 숫자가 어디서 왔는지 아무도 모르는 채로 배치를 좌우한다 — 모르는 것은 모르는 대로 두고
 * 중심만 막는다. 검증은 그런 타입을 애초에 판정하지 않는다(`known !== undefined`).
 */
export function clampObjectToBooth(
  x: number,
  z: number,
  local: AABB | undefined,
  rotationYDeg: number,
  bounds: { width: number; depth: number },
): { x: number; z: number } {
  if (local === undefined) return clampToBooth(x, z, bounds);
  return clampAreaToBooth(x, z, local, rotationYDeg, bounds);
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
