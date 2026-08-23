import type { AssetReference } from '../../contracts/gameProject.ts';

export interface StoredAssetResult {
  readonly asset: AssetReference;
  readonly originalName: string;
}

export interface GameAssetRepository {
  save(
    gameId: number,
    assetId: string,
    kind: AssetReference['kind'],
    file: File,
  ): Promise<StoredAssetResult>;
  resolve(source: string): Promise<string | null>;
}

const DATABASE_NAME = 'festa-game-studio-assets-v1';
const STORE_NAME = 'assets';
const MAX_ASSET_BYTES = 5 * 1024 * 1024;

const openDatabase = (): Promise<IDBDatabase> => new Promise((resolve, reject) => {
  const request = indexedDB.open(DATABASE_NAME, 1);
  request.onupgradeneeded = () => {
    if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME);
  };
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error ?? new Error('브라우저 자산 저장소를 열지 못했습니다.'));
});

const putBlob = async (source: string, blob: Blob): Promise<void> => {
  const database = await openDatabase();
  await new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).put(blob, source);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error('자산을 저장하지 못했습니다.'));
  });
  database.close();
};

const getBlob = async (source: string): Promise<Blob | null> => {
  const database = await openDatabase();
  const result = await new Promise<Blob | null>((resolve, reject) => {
    const request = database.transaction(STORE_NAME, 'readonly').objectStore(STORE_NAME).get(source);
    request.onsuccess = () => resolve(request.result instanceof Blob ? request.result : null);
    request.onerror = () => reject(request.error ?? new Error('자산을 불러오지 못했습니다.'));
  });
  database.close();
  return result;
};

export const createBrowserAssetRepository = (): GameAssetRepository | null => {
  if (typeof indexedDB === 'undefined') return null;
  return {
    save: async (gameId, assetId, kind, file) => {
      if (kind !== 'AUDIO' && !file.type.startsWith('image/')) throw new Error('PNG, JPG, GIF 또는 WebP 이미지만 추가할 수 있습니다.');
      if (kind === 'AUDIO' && !file.type.startsWith('audio/')) throw new Error('오디오 파일 형식이 아닙니다.');
      if (file.size > MAX_ASSET_BYTES) throw new Error('현재 로컬 자산은 파일당 5MB까지 지원합니다.');
      const source = `asset://local/${gameId}/${assetId}`;
      await putBlob(source, file);
      return { asset: { id: assetId, kind, source }, originalName: file.name };
    },
    resolve: async (source) => {
      if (!source.startsWith('asset://local/')) return null;
      const blob = await getBlob(source);
      return blob === null ? null : URL.createObjectURL(blob);
    },
  };
};
