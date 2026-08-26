import type { CSSProperties } from 'react';
import type { AssetReference } from '../../contracts/gameProject.ts';
import { findBuiltinStaticImage, type StaticImageDefinition } from './builtinAssetCatalog.ts';

export interface StaticImageVisual {
  readonly imageUrl: string;
  readonly columns: number;
  readonly rows: number;
  readonly frame: number;
}

export const resolveStaticImageVisual = (
  asset: AssetReference | undefined,
  assetUrls: Readonly<Record<string, string>>,
): StaticImageVisual | null => {
  if (asset === undefined || asset.kind !== 'IMAGE') return null;
  const builtIn: StaticImageDefinition | undefined = findBuiltinStaticImage(asset.source);
  if (builtIn !== undefined) return builtIn;
  const imageUrl = assetUrls[asset.id];
  return imageUrl === undefined ? null : { imageUrl, columns: 1, rows: 1, frame: 0 };
};

export const staticImageBackgroundStyle = (
  visual: StaticImageVisual,
): CSSProperties => {
  const column = visual.frame % visual.columns;
  const row = Math.floor(visual.frame / visual.columns);
  const x = visual.columns === 1 ? 0 : (column / (visual.columns - 1)) * 100;
  const y = visual.rows === 1 ? 0 : (row / (visual.rows - 1)) * 100;
  return {
    backgroundImage: `url(${visual.imageUrl})`,
    backgroundPosition: `${x}% ${y}%`,
    backgroundRepeat: 'no-repeat',
    backgroundSize: `${visual.columns * 100}% ${visual.rows * 100}%`,
  };
};
