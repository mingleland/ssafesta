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
  // S15P21A604-561 — asset://{provider}/{gameId}/{assetId} 전체를 넘긴다(assetId만으로는
  // local/remote 어느 저장소를 지울지 구현체가 판단할 근거가 없다). 존재하지 않는 source를
  // 지워도 실패하지 않는다(멱등) — 재시도가 두 번째 호출에서 에러로 보이면 안 된다.
  delete(source: string): Promise<void>;
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

// S15P21A604-561 — IndexedDB의 delete()는 없는 키를 지워도 에러를 던지지 않는다(멱등).
// 그 기본 동작을 그대로 살린다.
const deleteBlob = async (source: string): Promise<void> => {
  const database = await openDatabase();
  await new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, 'readwrite');
    transaction.objectStore(STORE_NAME).delete(source);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error('자산을 삭제하지 못했습니다.'));
  });
  database.close();
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
    delete: async (source) => {
      if (!source.startsWith('asset://local/')) return;
      await deleteBlob(source);
    },
  };
};
