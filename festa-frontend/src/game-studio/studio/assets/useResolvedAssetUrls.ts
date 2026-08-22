import { useEffect, useState } from 'react';
import type { AssetReference } from '../../contracts/gameProject.ts';
import type { GameAssetRepository } from './localAssetRepository.ts';

export const useResolvedAssetUrls = (
  assets: readonly AssetReference[],
  repository: GameAssetRepository | null,
): Readonly<Record<string, string>> => {
  const [urls, setUrls] = useState<Readonly<Record<string, string>>>({});

  useEffect(() => {
    let active = true;
    const createdUrls: string[] = [];
    if (repository === null) {
      setUrls({});
      return () => { active = false; };
    }
    Promise.all(assets.map(async (asset) => {
      const url = await repository.resolve(asset.source);
      if (url !== null) createdUrls.push(url);
      return [asset.id, url] as const;
    })).then((entries) => {
      if (active) setUrls(Object.fromEntries(entries.filter((entry): entry is readonly [string, string] => entry[1] !== null)));
    }).catch(() => { if (active) setUrls({}); });
    return () => {
      active = false;
      createdUrls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [assets, repository]);

  return urls;
};
