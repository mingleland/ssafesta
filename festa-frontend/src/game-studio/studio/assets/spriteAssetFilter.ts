import type { AssetReference } from '../../contracts/gameProject.ts';
import { assetDisplayLabel, findBuiltinSpriteSheet, findBuiltinStaticImage } from './builtinAssetCatalog.ts';

export type SpriteAssetGroup = 'ALL' | 'CHARACTER' | 'OBJECT' | 'MY';

export const spriteAssetGroup = (asset: AssetReference): Exclude<SpriteAssetGroup, 'ALL'> | null => {
  if (asset.kind !== 'IMAGE') return null;
  if (!asset.source.startsWith('builtin://')) return 'MY';
  if (findBuiltinSpriteSheet(asset.source) !== undefined) return 'CHARACTER';
  const image = findBuiltinStaticImage(asset.source);
  if (image?.category === 'CHARACTER') return 'CHARACTER';
  if (image?.category === 'OBJECT') return 'OBJECT';
  return null;
};

export const filterSpriteAssets = (
  assets: readonly AssetReference[],
  group: SpriteAssetGroup,
  query: string,
): readonly AssetReference[] => {
  const normalizedQuery = query.trim().toLocaleLowerCase('ko-KR');
  return assets.filter((asset) => {
    const assetGroup = spriteAssetGroup(asset);
    if (assetGroup === null || (group !== 'ALL' && assetGroup !== group)) return false;
    if (normalizedQuery === '') return true;
    return assetDisplayLabel(asset).toLocaleLowerCase('ko-KR').includes(normalizedQuery);
  });
};
