// 통행 판정 실시간 경고 — 관람 띠 도달 가능 여부·고립 공간을 서버와 같은 값으로 계산한다
// "서버와 같은 답"이 계약 전제다(#19 ⑤) — 값을 바꾸려면 3파트 합의가 필요하다.
// 출처: specs/005-booth-studio-layout/FE/tasks.md T023, contracts/layout-api.md §10-3
//
// 격자는 **부스 footprint 에서 유도한다** (S15P21A604-785, GitLab #181). 예전에는 6m 정사각형
// (120×120 · |x|,|z| ≤ 3)을 상수로 박아 뒀는데, footprint 가 9.4 × 6 으로 확정되면서 x 와 z 의
// 셀 수도 원점도 갈라졌다(188 × 120, x 첫 셀 중심 −4.675). 상수로 두면 서버가 넓힌 자리를
// FE 가 계속 벽으로 보고, 사용자는 편집기가 허용한 자리에서 게시에 실패한다.

import type { LayoutObject, ValidationDetail } from './types';
import type { AABB } from './objectTypes';
import { OBJECT_LOCAL_BOUNDS, OBJECT_TYPE_INFO } from './objectTypes';
import { worldAABB } from './geometry';

const CELL_SIZE = 0.05; // m

const AVATAR_ERODE_RADIUS = 0.22; // m — PlayerAvatar 캡슐 반지름 2.2 world unit / 10
const VIEWING_BAND_DEPTH = 0.7; // m — 정면 관람 띠 폭
const FRONT_REACH_THRESHOLD = 0.5; // 관람 띠 도달 가능 비율 50% 미만이면 FRONT_BLOCKED
const ISOLATED_AREA_M2 = 1; // 고립 공간 1㎡ 이상이면 ISOLATED_AREA
const ISOLATED_CELL_COUNT = ISOLATED_AREA_M2 / (CELL_SIZE * CELL_SIZE); // 400셀 — 셀 크기가 그대로라 값도 그대로

export interface BoothFootprint {
  width: number;
  depth: number;
}

/**
 * 오브젝트의 로컬 AABB 를 어디서 가져오는가.
 *
 * 기본은 타입 표다. 호출부가 runtime manifest 해석기를 넘기면 assetCode 단위 실측값을 쓴다
 * (`features/studio/model/useBoothAssets` 의 `resolveLocalBounds`). `entities` 가 `features` 를
 * 모르게 두려고 값이 아니라 **함수**를 받는다.
 */
export type LocalBoundsFn = (object: { type: LayoutObject['type']; assetCode?: string }) => AABB | undefined;

const typeLocalBounds: LocalBoundsFn = (object) => OBJECT_LOCAL_BOUNDS[object.type];

interface Grid {
  cols: number; // x 방향 셀 수
  rows: number; // z 방향 셀 수
  originX: number; // 0번 열의 셀 중심
  originZ: number; // 0번 행의 셀 중심
}

function gridOf(bounds: BoothFootprint): Grid {
  return {
    cols: Math.round(bounds.width / CELL_SIZE),
    rows: Math.round(bounds.depth / CELL_SIZE),
    originX: -bounds.width / 2 + CELL_SIZE / 2,
    originZ: -bounds.depth / 2 + CELL_SIZE / 2,
  };
}

const cellX = (g: Grid, col: number): number => g.originX + CELL_SIZE * col;
const cellZ = (g: Grid, row: number): number => g.originZ + CELL_SIZE * row;

// 회전 AABB와 셀 중심의 포함 검사 — 경계선상은 점유(보수적).
function isCellInsideAABB(cx: number, cz: number, aabb: { min: { x: number; z: number }; max: { x: number; z: number } }): boolean {
  return cx >= aabb.min.x && cx <= aabb.max.x && cz >= aabb.min.z && cz <= aabb.max.z;
}

interface OccupancyResult {
  occupied: boolean[][]; // [row(z)][col(x)] — 아바타 침식까지 반영된 통행 불가 그리드
  frontBands: Array<{ objectId: string; minX: number; maxX: number; minZ: number; maxZ: number }>;
}

function emptyGrid(g: Grid): boolean[][] {
  return Array.from({ length: g.rows }, () => new Array<boolean>(g.cols).fill(false));
}

// 오브젝트 배치를 래스터화한다 — 점유 판정 → 유클리드 침식 → 상호작용 파츠의 관람 띠 계산까지 한 번에.
function rasterize(objects: LayoutObject[], g: Grid, localBounds: LocalBoundsFn): OccupancyResult {
  const rawOccupied = emptyGrid(g);
  const frontBands: OccupancyResult['frontBands'] = [];

  for (const obj of objects) {
    const local = localBounds(obj);
    if (!local) continue; // 미지 타입은 판정하지 않는다(SC-005, T022와 같은 원칙)

    const world = worldAABB(local, obj.rotationY, obj.position);
    for (let row = 0; row < g.rows; row++) {
      const cz = cellZ(g, row);
      for (let col = 0; col < g.cols; col++) {
        const cx = cellX(g, col);
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
  const occupied = emptyGrid(g);
  for (let row = 0; row < g.rows; row++) {
    for (let col = 0; col < g.cols; col++) {
      if (!rawOccupied[row][col]) continue;
      const cz = cellZ(g, row);
      const cx = cellX(g, col);
      for (let dr = -erodeCells; dr <= erodeCells; dr++) {
        const r2 = row + dr;
        if (r2 < 0 || r2 >= g.rows) continue;
        for (let dc = -erodeCells; dc <= erodeCells; dc++) {
          const c2 = col + dc;
          if (c2 < 0 || c2 >= g.cols) continue;
          const dx = cellX(g, c2) - cx;
          const dz = cellZ(g, r2) - cz;
          if (dx * dx + dz * dz <= AVATAR_ERODE_RADIUS * AVATAR_ERODE_RADIUS) occupied[r2][c2] = true;
        }
      }
    }
  }

  return { occupied, frontBands };
}

// 4방향 flood fill — 시작점은 +z 경계 쪽 비점유 셀 전부(정면만 개방, 좌·우·후면은 벽).
//
// 큐는 `shift()` 가 아니라 head 인덱스로 전진한다. `shift()` 는 원소마다 배열 전체를 당겨 O(n),
// 큐 전체로는 O(n²) 이다. 격자가 120×120(14,400셀)에서 188×120(22,560셀)으로 1.57배 커지면서
// 그 비용이 드래그 한 번마다 돌아온다.
function reachableFromFront(occupied: boolean[][], g: Grid): boolean[][] {
  const reachable = emptyGrid(g);
  const queue: Array<[number, number]> = [];
  let head = 0;

  const lastRow = g.rows - 1; // 가장 큰 z(=정면 경계에 가장 가까운 셀)
  for (let col = 0; col < g.cols; col++) {
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
  while (head < queue.length) {
    const [row, col] = queue[head];
    head++;
    for (const [dr, dc] of deltas) {
      const r2 = row + dr;
      const c2 = col + dc;
      if (r2 < 0 || r2 >= g.rows || c2 < 0 || c2 >= g.cols) continue;
      if (occupied[r2][c2] || reachable[r2][c2]) continue;
      reachable[r2][c2] = true;
      queue.push([r2, c2]);
    }
  }

  return reachable;
}

// 배치 전체를 대상으로 §10-3 경고를 계산한다 — 12개 규모라 배치가 바뀔 때마다 재계산으로 충분(계약 명시).
export function passageWarnings(
  objects: LayoutObject[],
  bounds: BoothFootprint,
  localBounds: LocalBoundsFn = typeLocalBounds,
): ValidationDetail[] {
  const g = gridOf(bounds);
  const { occupied, frontBands } = rasterize(objects, g, localBounds);
  const reachable = reachableFromFront(occupied, g);
  const details: ValidationDetail[] = [];

  // FRONT_BLOCKED — 관람 띠 셀 중 도달 가능 비율이 50% 미만인 오브젝트.
  for (const band of frontBands) {
    let total = 0;
    let reached = 0;
    for (let row = 0; row < g.rows; row++) {
      const cz = cellZ(g, row);
      if (cz < band.minZ || cz > band.maxZ) continue;
      for (let col = 0; col < g.cols; col++) {
        const cx = cellX(g, col);
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
  for (let row = 0; row < g.rows; row++) {
    for (let col = 0; col < g.cols; col++) {
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

