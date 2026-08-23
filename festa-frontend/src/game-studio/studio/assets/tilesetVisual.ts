import type { CSSProperties } from 'react';
import type { AssetReference } from '../../contracts/gameProject.ts';
import { findBuiltinTileset, type TilesetDefinition } from './builtinAssetCatalog.ts';

export const resolveTilesetVisual = (
  asset: AssetReference | undefined,
  resolvedUrls: Readonly<Record<string, string>>,
): TilesetDefinition | null => {
  if (asset === undefined || asset.kind !== 'TILESET') return null;
  const builtin = findBuiltinTileset(asset.source);
  if (builtin !== undefined) return builtin;
  const imageUrl = resolvedUrls[asset.id];
  return imageUrl === undefined ? null : {
    source: asset.source,
    label: asset.id,
    imageUrl,
    columns: 8,
    rows: 8,
  };
};

export const tileBackgroundStyle = (
  tileset: TilesetDefinition,
  tileIndex: number,
): CSSProperties => {
  const column = tileIndex % tileset.columns;
  const row = Math.floor(tileIndex / tileset.columns) % tileset.rows;
  return {
    backgroundImage: `url(${tileset.imageUrl})`,
    backgroundPosition: `${tileset.columns === 1 ? 0 : (column / (tileset.columns - 1)) * 100}% ${tileset.rows === 1 ? 0 : (row / (tileset.rows - 1)) * 100}%`,
    backgroundSize: `${tileset.columns * 100}% ${tileset.rows * 100}%`,
    imageRendering: 'pixelated',
  };
};
