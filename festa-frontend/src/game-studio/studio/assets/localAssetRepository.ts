import type { AssetReference } from '../../contracts/gameProject.ts';

export interface StoredAssetResult {
  readonly asset: AssetReference;
  readonly originalName: string;
}

export interface SaveAssetRequest {
  readonly kind: AssetReference['kind'];
  readonly file: File;
  /** Local preview may use this ID. A remote repository must ignore it and return the server-issued ID. */
  readonly suggestedAssetId?: string;
}

export interface GameAssetRepository {
  /** Resolve only after the asset is previewable locally or the remote asset reached READY. */
  save(gameId: number, request: SaveAssetRequest): Promise<StoredAssetResult>;
  resolve(source: string): Promise<string | null>;
}

const DATABASE_NAME = 'festa-game-studio-assets-v1';
const STORE_NAME = 'assets';
const MAX_ASSET_BYTES = 5 * 1024 * 1024;
const ALLOWED_IMAGE_MIME_TYPES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);

export const validateAssetFile = (
  kind: AssetReference['kind'],
  file: Pick<File, 'size' | 'type'>,
): void => {
  if (kind === 'AUDIO') throw new Error('Game Studio v1은 오디오 업로드를 지원하지 않습니다.');
  if (!ALLOWED_IMAGE_MIME_TYPES.has(file.type)) throw new Error('PNG, JPG, GIF 또는 WebP 이미지만 추가할 수 있습니다.');
  if (file.size > MAX_ASSET_BYTES) throw new Error('현재 자산은 파일당 5MB까지 지원합니다.');
};

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
    save: async (gameId, { file, kind, suggestedAssetId }) => {
      validateAssetFile(kind, file);
      const assetId = suggestedAssetId ?? `localAsset_${Date.now().toString(36)}`;
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
