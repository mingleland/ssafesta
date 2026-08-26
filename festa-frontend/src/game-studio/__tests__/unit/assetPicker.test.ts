import { describe, expect, it } from 'vitest';
import type { AssetReference } from '../../contracts/gameProject.ts';
import { filterSpriteAssets, spriteAssetGroup } from '../../studio/assets/spriteAssetFilter.ts';

const assets: readonly AssetReference[] = [
  { id: 'player', kind: 'IMAGE', source: 'builtin://sprites/player' },
  { id: 'door', kind: 'IMAGE', source: 'builtin://sprites/door' },
  { id: 'portrait', kind: 'IMAGE', source: 'builtin://portraits/librarian' },
  { id: 'background', kind: 'IMAGE', source: 'builtin://backgrounds/visual-novel-library' },
  { id: 'custom', kind: 'IMAGE', source: 'local://asset/custom' },
  { id: 'tiles', kind: 'TILESET', source: 'builtin://tilesets/library' },
];

describe('beginner sprite asset picker', () => {
  it('groups playable object images without mixing portraits, backgrounds, or tilesets', () => {
    expect(spriteAssetGroup(assets[0]!)).toBe('CHARACTER');
    expect(spriteAssetGroup(assets[1]!)).toBe('OBJECT');
    expect(spriteAssetGroup(assets[2]!)).toBeNull();
    expect(spriteAssetGroup(assets[3]!)).toBeNull();
    expect(spriteAssetGroup(assets[4]!)).toBe('MY');
    expect(spriteAssetGroup(assets[5]!)).toBeNull();
  });

  it('filters by human-readable category and Korean label search', () => {
    expect(filterSpriteAssets(assets, 'ALL', '')).toHaveLength(3);
    expect(filterSpriteAssets(assets, 'OBJECT', '')).toEqual([assets[1]]);
    expect(filterSpriteAssets(assets, 'ALL', '문')).toEqual([assets[1]]);
    expect(filterSpriteAssets(assets, 'MY', '')).toEqual([assets[4]]);
  });
});
