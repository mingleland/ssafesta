import { useEffect, useRef } from 'react';
import type { TileLayer } from '../../contracts/gameProject.ts';
import type { TilesetDefinition } from '../assets/builtinAssetCatalog.ts';

interface TileOverviewCanvasProps {
  readonly columns: number;
  readonly rows: number;
  readonly layer: TileLayer;
  readonly tileset: TilesetDefinition | null;
}

const FALLBACK_TILE_COLORS = [
  '#59544a', '#747064', '#393d42', '#77563b',
  '#3d684b', '#364f6c', '#856d3e', '#5c466d',
] as const;

export const TileOverviewCanvas = ({ columns, rows, layer, tileset }: TileOverviewCanvasProps) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const pixelsPerCell = Math.max(2, Math.min(8, Math.floor(1_000 / Math.max(columns, rows))));

  useEffect(() => {
    const context = canvasRef.current?.getContext('2d');
    if (context === undefined || context === null) return;
    let active = true;

    const draw = (image?: HTMLImageElement) => {
      if (!active) return;
      context.clearRect(0, 0, columns * pixelsPerCell, rows * pixelsPerCell);
      context.imageSmoothingEnabled = false;
      const sourceCellWidth = image === undefined || tileset === null ? 0 : image.naturalWidth / tileset.columns;
      const sourceCellHeight = image === undefined || tileset === null ? 0 : image.naturalHeight / tileset.rows;
      for (let index = 0; index < layer.data.length; index += 1) {
        const tile = layer.data[index] ?? -1;
        if (tile < 0) continue;
        const x = (index % columns) * pixelsPerCell;
        const y = Math.floor(index / columns) * pixelsPerCell;
        if (image !== undefined && tileset !== null) {
          const sourceX = (tile % tileset.columns) * sourceCellWidth;
          const sourceY = (Math.floor(tile / tileset.columns) % tileset.rows) * sourceCellHeight;
          context.drawImage(
            image,
            sourceX,
            sourceY,
            sourceCellWidth,
            sourceCellHeight,
            x,
            y,
            pixelsPerCell,
            pixelsPerCell,
          );
        } else {
          context.fillStyle = FALLBACK_TILE_COLORS[tile % FALLBACK_TILE_COLORS.length] ?? '#59544a';
          context.fillRect(x, y, pixelsPerCell, pixelsPerCell);
        }
      }
    };

    draw();
    if (tileset !== null) {
      const image = new Image();
      image.onload = () => draw(image);
      image.src = tileset.imageUrl;
    }
    return () => { active = false; };
  }, [columns, layer.data, pixelsPerCell, rows, tileset]);

  return (
    <canvas
      aria-hidden="true"
      className="gss-tile-overview"
      height={rows * pixelsPerCell}
      ref={canvasRef}
      width={columns * pixelsPerCell}
    />
  );
};
