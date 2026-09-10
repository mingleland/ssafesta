// S15P21A604-570 — 커스텀 자산이 "내 자산 · assetId"라는 내용을 알 수 없는 라벨로만
// 표시되고, 역할별 select에서 빌트인과 구분 없이 섞여 있던 문제(Notion QA #53)를 고친다.
import { describe, expect, it } from 'vitest';
import type { AssetReference } from '../../contracts/gameProject.ts';
import { assetDisplayLabel, isCustomAsset, partitionAssetsByRole } from '../../studio/assets/builtinAssetCatalog.ts';

describe('assetDisplayLabel(S15P21A604-570)', () => {
  it('label이 있는 커스텀 자산은 원본 파일명을 보여준다', () => {
    const asset: AssetReference = { id: 'image1', kind: 'IMAGE', source: 'local://asset/1', label: 'staple-icon.png' };
    expect(assetDisplayLabel(asset)).toBe('내 자산 · staple-icon.png');
  });

  it('label이 없는 커스텀 자산(소급 적용 전)은 기존 폴백을 그대로 쓴다', () => {
    const asset: AssetReference = { id: 'image2', kind: 'IMAGE', source: 'local://asset/2' };
    expect(assetDisplayLabel(asset)).toBe('내 자산 · image2');
  });

  it('빌트인 자산은 label이 있어도 카탈로그 라벨이 우선한다', () => {
    const asset: AssetReference = { id: 'builtin_x', kind: 'IMAGE', source: 'builtin://backgrounds/visual-novel-library', label: '아무거나.png' };
    expect(assetDisplayLabel(asset)).not.toBe('내 자산 · 아무거나.png');
  });
});

describe('isCustomAsset(S15P21A604-570)', () => {
  it('builtin:// 소스는 커스텀이 아니다', () => {
    expect(isCustomAsset({ id: 'a', kind: 'IMAGE', source: 'builtin://sprites/player' })).toBe(false);
  });

  it('그 외 소스는 커스텀이다', () => {
    expect(isCustomAsset({ id: 'a', kind: 'IMAGE', source: 'local://asset/1' })).toBe(true);
  });
});

describe('partitionAssetsByRole(S15P21A604-570)', () => {
  const assets: readonly AssetReference[] = [
    { id: 'bg1', kind: 'IMAGE', source: 'builtin://backgrounds/visual-novel-library' },
    { id: 'portrait1', kind: 'IMAGE', source: 'builtin://portraits/librarian' },
    { id: 'custom1', kind: 'IMAGE', source: 'local://asset/custom1', label: 'my-bg.png' },
    { id: 'tiles', kind: 'TILESET', source: 'builtin://tilesets/library' },
  ];

  it('역할이 맞는 빌트인은 builtin 그룹에, 역할과 무관하게 커스텀은 custom 그룹에 들어간다', () => {
    const { builtin, custom } = partitionAssetsByRole(assets, 'BACKGROUND');
    expect(builtin.map((asset) => asset.id)).toEqual(['bg1']);
    expect(custom.map((asset) => asset.id)).toEqual(['custom1']);
  });

  it('역할이 다른 빌트인·TILESET은 어느 그룹에도 들어가지 않는다', () => {
    const { builtin, custom } = partitionAssetsByRole(assets, 'PORTRAIT');
    expect(builtin.map((asset) => asset.id)).toEqual(['portrait1']);
    expect(custom.map((asset) => asset.id)).toEqual(['custom1']);
  });
});
