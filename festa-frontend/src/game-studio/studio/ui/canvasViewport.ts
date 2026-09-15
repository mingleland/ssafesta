import type { GameObject } from '../../contracts/gameProject.ts';

export interface RectLike {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly width: number;
  readonly height: number;
}

export interface GridViewport {
  readonly minX: number;
  readonly minY: number;
  readonly maxX: number;
  readonly maxY: number;
}

const clamp = (value: number, minimum: number, maximum: number): number => (
  Math.max(minimum, Math.min(maximum, value))
);

export const calculateFitZoom = (
  viewportWidth: number,
  viewportHeight: number,
  columns: number,
  rows: number,
  baseCellSize = 32,
): number => {
  if (viewportWidth <= 0 || viewportHeight <= 0 || columns <= 0 || rows <= 0 || baseCellSize <= 0) return 100;
  const horizontal = ((viewportWidth - 56) / (columns * baseCellSize)) * 100;
  const vertical = ((viewportHeight - 56) / (rows * baseCellSize)) * 100;
  // S15P21A604-394 — +/- 버튼·휠과 같은 30~300% 범위로 맞춘다. 예전 10~200 그대로 두면
  // "전체" 맞춤이 30% 밑 값을 만들어낼 수 있어, 그 상태에서 -버튼/휠을 쓰면 즉시 30%로
  // 튀어오르는 불일치가 생긴다.
  return clamp(Math.floor(Math.min(horizontal, vertical) / 5) * 5, 30, 300);
};

export const calculateGridViewport = (
  container: RectLike,
  canvas: RectLike,
  columns: number,
  rows: number,
): GridViewport | null => {
  if (columns <= 0 || rows <= 0 || canvas.width <= 0 || canvas.height <= 0) return null;
  const visibleLeft = Math.max(container.left, canvas.left);
  const visibleTop = Math.max(container.top, canvas.top);
  const visibleRight = Math.min(container.right, canvas.right);
  const visibleBottom = Math.min(container.bottom, canvas.bottom);
  if (visibleLeft >= visibleRight || visibleTop >= visibleBottom) return null;

  return {
    minX: clamp(Math.floor(((visibleLeft - canvas.left) / canvas.width) * columns), 0, columns - 1),
    minY: clamp(Math.floor(((visibleTop - canvas.top) / canvas.height) * rows), 0, rows - 1),
    maxX: clamp(Math.ceil(((visibleRight - canvas.left) / canvas.width) * columns) - 1, 0, columns - 1),
    maxY: clamp(Math.ceil(((visibleBottom - canvas.top) / canvas.height) * rows) - 1, 0, rows - 1),
  };
};

export const expandGridViewport = (
  viewport: GridViewport,
  columns: number,
  rows: number,
  overscanCells: number,
): GridViewport => ({
  minX: clamp(viewport.minX - overscanCells, 0, columns - 1),
  minY: clamp(viewport.minY - overscanCells, 0, rows - 1),
  maxX: clamp(viewport.maxX + overscanCells, 0, columns - 1),
  maxY: clamp(viewport.maxY + overscanCells, 0, rows - 1),
});

export const gridViewportContains = (
  viewport: GridViewport,
  position: { readonly x: number; readonly y: number },
): boolean => (
  position.x >= viewport.minX && position.x <= viewport.maxX
  && position.y >= viewport.minY && position.y <= viewport.maxY
);

export const equalGridViewports = (left: GridViewport | null, right: GridViewport | null): boolean => (
  left === right
  || (left !== null && right !== null
    && left.minX === right.minX && left.minY === right.minY
    && left.maxX === right.maxX && left.maxY === right.maxY)
);

export const objectsInViewport = (
  objects: readonly GameObject[],
  viewport: GridViewport | null,
  alwaysIncludeObjectId: string | null = null,
): readonly GameObject[] => {
  if (viewport === null) return alwaysIncludeObjectId === null
    ? []
    : objects.filter((object) => object.id === alwaysIncludeObjectId);
  return objects.filter((object) => (
    object.id === alwaysIncludeObjectId || gridViewportContains(viewport, object.position)
  ));
};

export const tileIndexesInViewport = (
  viewport: GridViewport | null,
  columns: number,
  tileCount: number,
): readonly number[] => {
  if (viewport === null || columns <= 0 || tileCount <= 0) return [];
  const indexes: number[] = [];
  for (let y = viewport.minY; y <= viewport.maxY; y += 1) {
    for (let x = viewport.minX; x <= viewport.maxX; x += 1) {
      const index = y * columns + x;
      if (index < tileCount) indexes.push(index);
    }
  }
  return indexes;
};
