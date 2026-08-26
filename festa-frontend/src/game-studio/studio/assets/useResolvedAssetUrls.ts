import { useEffect, useState } from 'react';
import type { AssetReference } from '../../contracts/gameProject.ts';
import type { GameAssetRepository } from './localAssetRepository.ts';

export type GameAssetResolver = Pick<GameAssetRepository, 'resolve'>;

const sameUrls = (
  left: Readonly<Record<string, string>>,
  right: Readonly<Record<string, string>>,
): boolean => {
  const leftEntries = Object.entries(left);
  const rightEntries = Object.entries(right);
  return leftEntries.length === rightEntries.length
    && leftEntries.every(([key, value]) => right[key] === value);
};

export const useResolvedAssetUrls = (
  assets: readonly AssetReference[],
  repository: GameAssetResolver | null,
): Readonly<Record<string, string>> => {
  const [urls, setUrls] = useState<Readonly<Record<string, string>>>({});

  useEffect(() => {
    let active = true;
    const createdUrls: string[] = [];
    if (repository === null) {
      setUrls((current) => Object.keys(current).length === 0 ? current : {});
      return () => { active = false; };
    }
    Promise.all(assets.map(async (asset) => {
      const url = await repository.resolve(asset.source);
      if (url !== null) createdUrls.push(url);
      return [asset.id, url] as const;
    })).then((entries) => {
      if (active) {
        const next = Object.fromEntries(entries.filter((entry): entry is readonly [string, string] => entry[1] !== null));
        setUrls((current) => sameUrls(current, next) ? current : next);
      }
    }).catch(() => { if (active) setUrls((current) => Object.keys(current).length === 0 ? current : {}); });
    return () => {
      active = false;
      createdUrls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [assets, repository]);

  return urls;
};
