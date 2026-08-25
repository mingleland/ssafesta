import { describe, expect, it } from 'vitest';
import type { GameObject } from '../../contracts/gameProject.ts';
import {
  calculateGridViewport,
  expandGridViewport,
  objectsInViewport,
  tileIndexesInViewport,
} from '../../studio/ui/canvasViewport.ts';

const rect = (left: number, top: number, width: number, height: number) => ({
  left,
  top,
  right: left + width,
  bottom: top + height,
  width,
  height,
});

describe('Game Studio large-map viewport culling', () => {
  it('converts the visible canvas intersection to grid coordinates', () => {
    const viewport = calculateGridViewport(
      rect(100, 100, 640, 480),
      rect(-220, -140, 3_200, 1_600),
      100,
      50,
    );

    expect(viewport).toEqual({ minX: 10, minY: 7, maxX: 29, maxY: 22 });
    expect(expandGridViewport(viewport!, 100, 50, 2)).toEqual({ minX: 8, minY: 5, maxX: 31, maxY: 24 });
  });

  it('renders only visible objects while retaining an off-screen primary selection', () => {
    const objects: GameObject[] = Array.from({ length: 500 }, (_, index) => ({
      id: `object${index}`,
      preset: 'DECORATION',
      position: { x: index % 100, y: Math.floor(index / 100) },
      visible: true,
      components: [],
    }));

    const visible = objectsInViewport(objects, { minX: 10, minY: 1, maxX: 19, maxY: 2 }, 'object499');

    expect(visible).toHaveLength(21);
    expect(visible.at(-1)?.id).toBe('object499');
  });

  it('enumerates only tiles inside the overscanned viewport', () => {
    expect(tileIndexesInViewport({ minX: 2, minY: 1, maxX: 4, maxY: 2 }, 10, 100)).toEqual([
      12, 13, 14,
      22, 23, 24,
    ]);
    expect(tileIndexesInViewport(null, 10, 100)).toEqual([]);
  });

  it('returns no viewport when the canvas is outside the scroll area', () => {
    expect(calculateGridViewport(rect(0, 0, 300, 200), rect(400, 0, 200, 200), 20, 20)).toBeNull();
  });
});
