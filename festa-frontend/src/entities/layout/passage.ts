// 통행 판정 실시간 경고 — 관람 띠 도달 가능 여부·고립 공간을 서버와 같은 값으로 계산한다
// "서버와 같은 답"이 계약 전제다(#19 ⑤) — 값을 바꾸려면 3파트 합의가 필요하다.
// 출처: specs/005-booth-studio-layout/FE/tasks.md T023, contracts/layout-api.md §10-3

import type { LayoutObject, ValidationDetail } from './types';
import { OBJECT_LOCAL_BOUNDS, OBJECT_TYPE_INFO } from './objectTypes';
import { worldAABB } from './geometry';

const CELL_SIZE = 0.05; // m
const GRID_SIZE = 120; // 6m / 0.05m
const HALF_EXTENT = 3; // |x|,|z| <= 3
// 셀 중심 = -2.975 + 0.05k (k = 0…119)
const CELL_ORIGIN = -HALF_EXTENT + CELL_SIZE / 2;

const AVATAR_ERODE_RADIUS = 0.22; // m — PlayerAvatar 캡슐 반지름 2.2 world unit / 10
const VIEWING_BAND_DEPTH = 0.7; // m — 정면 관람 띠 폭
const FRONT_REACH_THRESHOLD = 0.5; // 관람 띠 도달 가능 비율 50% 미만이면 FRONT_BLOCKED
const ISOLATED_AREA_M2 = 1; // 고립 공간 1㎡ 이상이면 ISOLATED_AREA
const ISOLATED_CELL_COUNT = ISOLATED_AREA_M2 / (CELL_SIZE * CELL_SIZE); // 400셀

function cellCenter(k: number): number {
  return CELL_ORIGIN + CELL_SIZE * k;
}

// 회전 AABB와 셀 중심의 포함 검사 — 경계선상은 점유(보수적).
function isCellInsideAABB(cx: number, cz: number, aabb: { min: { x: number; z: number }; max: { x: number; z: number } }): boolean {
  return cx >= aabb.min.x && cx <= aabb.max.x && cz >= aabb.min.z && cz <= aabb.max.z;
}

interface OccupancyResult {
  occupied: boolean[][]; // [row(z)][col(x)] — 아바타 침식까지 반영된 통행 불가 그리드
  frontBands: Array<{ objectId: string; minX: number; maxX: number; minZ: number; maxZ: number }>;
}

// 오브젝트 배치를 래스터화한다 — 점유 판정 → 유클리드 침식 → 상호작용 파츠의 관람 띠 계산까지 한 번에.
function rasterize(objects: LayoutObject[]): OccupancyResult {
  const rawOccupied: boolean[][] = Array.from({ length: GRID_SIZE }, () => new Array(GRID_SIZE).fill(false));
  const frontBands: OccupancyResult['frontBands'] = [];

  for (const obj of objects) {
    const local = OBJECT_LOCAL_BOUNDS[obj.type];
    if (!local) continue; // 미지 타입은 판정하지 않는다(SC-005, T022와 같은 원칙)

    const world = worldAABB(local, obj.rotationY, obj.position);
    for (let row = 0; row < GRID_SIZE; row++) {
      const cz = cellCenter(row);
      for (let col = 0; col < GRID_SIZE; col++) {
        const cx = cellCenter(col);
        if (isCellInsideAABB(cx, cz, world)) rawOccupied[row][col] = true;
      }
    }

    // 장식(FURNITURE·DECORATION)은 관람 대상이 아니라 관람 띠를 두지 않는다(§10-3).
    const info = OBJECT_TYPE_INFO[obj.type];
    if (info?.category !== 'DECORATIVE') {
      // 정면(+Z)에서 바깥으로 0.7m — 회전 후 AABB의 +z 면 기준으로 띠를 계산한다.
      frontBands.push({
        objectId: obj.objectId,
        minX: world.min.x,
        maxX: world.max.x,
        minZ: world.max.z,
        maxZ: world.max.z + VIEWING_BAND_DEPTH,
      });
    }
  }

  // 유클리드 반경 0.22m 팽창 — 점유 셀 중심에서 반경 안의 모든 셀도 통행 불가로 취급한다.
  const erodeCells = Math.ceil(AVATAR_ERODE_RADIUS / CELL_SIZE);
  const occupied: boolean[][] = Array.from({ length: GRID_SIZE }, () => new Array(GRID_SIZE).fill(false));
  for (let row = 0; row < GRID_SIZE; row++) {
    for (let col = 0; col < GRID_SIZE; col++) {
      if (!rawOccupied[row][col]) continue;
      const cz = cellCenter(row);
      const cx = cellCenter(col);
      for (let dr = -erodeCells; dr <= erodeCells; dr++) {
        const r2 = row + dr;
        if (r2 < 0 || r2 >= GRID_SIZE) continue;
        for (let dc = -erodeCells; dc <= erodeCells; dc++) {
          const c2 = col + dc;
          if (c2 < 0 || c2 >= GRID_SIZE) continue;
          const dx = cellCenter(c2) - cx;
          const dz = cellCenter(r2) - cz;
          if (dx * dx + dz * dz <= AVATAR_ERODE_RADIUS * AVATAR_ERODE_RADIUS) occupied[r2][c2] = true;
        }
      }
    }
  }

  return { occupied, frontBands };
}

// 4방향 flood fill — 시작점은 +z 경계(z=+3) 쪽 비점유 셀 전부(정면만 개방, 좌·우·후면은 벽).
function reachableFromFront(occupied: boolean[][]): boolean[][] {
  const reachable: boolean[][] = Array.from({ length: GRID_SIZE }, () => new Array(GRID_SIZE).fill(false));
  const queue: Array<[number, number]> = [];

  const lastRow = GRID_SIZE - 1; // 가장 큰 z(=+3에 가장 가까운 셀)
  for (let col = 0; col < GRID_SIZE; col++) {
    if (!occupied[lastRow][col] && !reachable[lastRow][col]) {
      reachable[lastRow][col] = true;
      queue.push([lastRow, col]);
    }
  }

  const deltas = [
    [-1, 0],
    [1, 0],
    [0, -1],
    [0, 1],
  ];
  while (queue.length > 0) {
    const [row, col] = queue.shift()!;
    for (const [dr, dc] of deltas) {
      const r2 = row + dr;
      const c2 = col + dc;
      if (r2 < 0 || r2 >= GRID_SIZE || c2 < 0 || c2 >= GRID_SIZE) continue;
      if (occupied[r2][c2] || reachable[r2][c2]) continue;
      reachable[r2][c2] = true;
      queue.push([r2, c2]);
    }
  }

  return reachable;
}

// 배치 전체를 대상으로 §10-3 경고를 계산한다 — 12개 규모라 배치가 바뀔 때마다 재계산으로 충분(계약 명시).
export function passageWarnings(objects: LayoutObject[]): ValidationDetail[] {
  const { occupied, frontBands } = rasterize(objects);
  const reachable = reachableFromFront(occupied);
  const details: ValidationDetail[] = [];

  // FRONT_BLOCKED — 관람 띠 셀 중 도달 가능 비율이 50% 미만인 오브젝트.
  for (const band of frontBands) {
    let total = 0;
    let reached = 0;
    for (let row = 0; row < GRID_SIZE; row++) {
      const cz = cellCenter(row);
      if (cz < band.minZ || cz > band.maxZ) continue;
      for (let col = 0; col < GRID_SIZE; col++) {
        const cx = cellCenter(col);
        if (cx < band.minX || cx > band.maxX) continue;
        total++;
        if (reachable[row][col]) reached++;
      }
    }
    if (total > 0 && reached / total < FRONT_REACH_THRESHOLD) {
      details.push({
        rule: 'FRONT_BLOCKED',
        objectId: band.objectId,
        message: '관람 띠 도달 가능 비율이 50% 미만입니다.',
      });
    }
  }

  // ISOLATED_AREA — 침식 후 비점유인데 flood fill 미도달인 셀이 1㎡(400셀) 이상. 배치 전체 항목이라 objectId 없음.
  let isolatedCells = 0;
  for (let row = 0; row < GRID_SIZE; row++) {
    for (let col = 0; col < GRID_SIZE; col++) {
      if (!occupied[row][col] && !reachable[row][col]) isolatedCells++;
    }
  }
  if (isolatedCells >= ISOLATED_CELL_COUNT) {
    details.push({
      rule: 'ISOLATED_AREA',
      message: '통행할 수 없는 고립 공간이 1㎡ 이상입니다.',
    });
  }

  return details;
}
