// 서버 Asset 저장소 어댑터 — 로컬 IndexedDB 대신 업로드된 stable `asset://game/{gameId}/{assetId}`
// 를 다루는 GameAssetRepository 구현. 계약: specs/019-game-studio/contracts/game-asset-upload.md
import { api, getAccessToken } from '../../../shared/api/client.ts';
import { apiBaseUrl } from '../../../shared/config/runtime.ts';
import type { GameApiRequest } from '../../runtime/ports/publishedGameRepository.ts';
import type { AssetReference } from '../../contracts/gameProject.ts';
import { GameAuthoringApiError, normalizeGameAuthoringError } from '../ports/gameAuthoringApi.ts';
import { validateAssetFile, type GameAssetRepository } from './localAssetRepository.ts';

const STABLE_PREFIX = 'asset://game/';
const LOCAL_PREFIX = 'asset://local/';
// 계약 §2 — assetId 는 stableId 패턴을 만족하는 서버 발급값이다. 클라이언트가 만들지 않는다.
const STABLE_ID = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const CONTENT_TIMEOUT_MS = 15_000;

export interface StableAssetRef {
  readonly gameId: number;
  readonly assetId: string;
}

// `asset://game/{gameId}/{assetId}` 만 받는다. `asset://local/...` 은 arity 가 같아서
// 느슨하게 자르면 로컬 참조가 원격으로 새어 나간다 — authority 를 정확히 맞춘다(계약 §2).
export const parseStableAssetSource = (source: string): StableAssetRef | null => {
  if (!source.startsWith(STABLE_PREFIX)) return null;
  const rest = source.slice(STABLE_PREFIX.length);
  const slash = rest.indexOf('/');
  if (slash < 1) return null;
  const gameId = Number(rest.slice(0, slash));
  const assetId = rest.slice(slash + 1);
  if (!Number.isSafeInteger(gameId) || gameId < 1) return null;
  if (!STABLE_ID.test(assetId)) return null;
  return { gameId, assetId };
};

export const assetContentPath = ({ gameId, assetId }: StableAssetRef): string => (
  `/api/v1/games/${gameId}/assets/${assetId}/content`
);

export type AssetContentFetcher = (path: string) => Promise<Blob>;

// `<img src>` 로 직접 걸지 않고 인증 fetch 로 받아 object URL 을 만든다.
// 계약 §3.4 는 302 redirect 라 문자열 치환만으로 충분하다고 적혀 있지만 그것은 **공개 Published
// 에서만** 참이다 — 같은 endpoint 가 §3 에서 "소유자만 호출할 수 있다"이고, 편집기가 보는 것은
// 아직 공개되지 않은 자기 Draft 의 Asset 이다. AT 는 메모리에 있어(헌법 13조) `<img>` 요청에
// Authorization 헤더가 실리지 않으므로 그 경로는 401 이 된다. 여기서 헤더를 실어 받는다.
// 만료 관리는 여전히 FE 몫이 아니다 — 마운트마다 새로 받고, object URL 정리는
// useResolvedAssetUrls 가 이미 한다.
const defaultFetchContent: AssetContentFetcher = async (path) => {
  const token = getAccessToken();
  const response = await fetch(`${apiBaseUrl()}${path}`, {
    headers: token === null ? undefined : { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(CONTENT_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw new GameAuthoringApiError(
      response.status === 404 ? 'GAME_ASSET_NOT_FOUND' : 'GAME_ASSET_FORBIDDEN',
      '자산을 불러오지 못했습니다.',
      { retryable: response.status >= 500 },
    );
  }
  return response.blob();
};

interface StartGrant {
  readonly assetId: string;
  readonly uploadUrl: string;
  readonly requiredHeaders?: Record<string, string>;
}

const isRecord = (value: unknown): value is Record<string, unknown> => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);

const parseStartGrant = (input: unknown): StartGrant => {
  if (!isRecord(input)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '업로드 시작 응답 형식이 올바르지 않습니다.');
  const { assetId, uploadUrl, requiredHeaders } = input;
  if (typeof assetId !== 'string' || !STABLE_ID.test(assetId)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'assetId 값이 올바르지 않습니다.');
  if (typeof uploadUrl !== 'string' || uploadUrl.length < 1) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'uploadUrl 값이 올바르지 않습니다.');
  return {
    assetId,
    uploadUrl,
    requiredHeaders: isRecord(requiredHeaders)
      ? Object.fromEntries(Object.entries(requiredHeaders).filter((entry): entry is [string, string] => typeof entry[1] === 'string'))
      : undefined,
  };
};

// complete 응답이 source 를 준다(계약 §3.2). FE 가 문자열을 조립하지 않는다 — 조립하면
// 서버가 형식을 바꿨을 때 저장은 성공하고 표시만 깨진다.
const parseCompleted = (input: unknown, expected: StableAssetRef): AssetReference => {
  if (!isRecord(input)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '업로드 완료 응답 형식이 올바르지 않습니다.');
  const { status, source, kind } = input;
  if (status !== 'READY') throw new GameAuthoringApiError('GAME_ASSET_NOT_READY', '자산이 아직 사용할 수 없는 상태입니다.');
  if (typeof source !== 'string') throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'source 값이 올바르지 않습니다.');
  const parsed = parseStableAssetSource(source);
  if (parsed === null || parsed.gameId !== expected.gameId || parsed.assetId !== expected.assetId) {
    throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '업로드한 자산과 응답의 source 가 일치하지 않습니다.');
  }
  if (kind !== 'IMAGE' && kind !== 'TILESET') throw new GameAuthoringApiError('GAME_ASSET_KIND_UNSUPPORTED', '지원하지 않는 자산 종류입니다.');
  return { id: parsed.assetId, kind, source };
};

export type PresignedUploader = (grant: StartGrant, file: File) => Promise<void>;

const defaultUpload: PresignedUploader = async ({ uploadUrl, requiredHeaders }, file) => {
  // presigned PUT 은 우리 서버가 아니다 — api() 를 태우면 base URL·Authorization 이 붙어
  // 서명이 깨진다. 요구된 헤더만 그대로 싣는다(계약 §3.1).
  const response = await fetch(uploadUrl, { method: 'PUT', headers: requiredHeaders, body: file });
  if (!response.ok) throw new GameAuthoringApiError('GAME_ASSET_UPLOAD_FAILED', '자산 업로드에 실패했습니다.', { retryable: true });
};

export interface RemoteAssetRepositoryOptions {
  readonly request?: GameApiRequest;
  readonly upload?: PresignedUploader;
  readonly fetchContent?: AssetContentFetcher;
  /** 기존 프로젝트의 `asset://local/` 참조는 편집 중에만 이쪽으로 넘긴다(-116 오프라인 fallback). */
  readonly local?: Pick<GameAssetRepository, 'resolve'> | null;
}

export const createApiGameAssetRepository = (
  options: RemoteAssetRepositoryOptions = {},
): GameAssetRepository => {
  const request = options.request ?? api;
  const upload = options.upload ?? defaultUpload;
  const fetchContent = options.fetchContent ?? defaultFetchContent;
  const local = options.local ?? null;

  return {
    save: async (gameId, { file, kind }) => {
      // 로컬에서 통과한 파일이 업로드에서 거부되지 않도록 같은 검사를 먼저 돌린다(계약 §5).
      validateAssetFile(kind, file);
      try {
        const grant = parseStartGrant(await request<unknown>(`/api/v1/games/${gameId}/assets`, {
          method: 'POST',
          body: JSON.stringify({ kind, contentType: file.type, byteSize: file.size, fileName: file.name }),
        }));
        await upload(grant, file);
        // complete 는 idempotent 다(계약 §3.2) — 재시도가 중복 Asset 을 만들지 않는다.
        const completed = await request<unknown>(`/api/v1/games/${gameId}/assets/${grant.assetId}/complete`, { method: 'POST' });
        return { asset: parseCompleted(completed, { gameId, assetId: grant.assetId }), originalName: file.name };
      } catch (error) {
        throw normalizeGameAuthoringError(error);
      }
    },
    resolve: async (source) => {
      if (source.startsWith(LOCAL_PREFIX)) return local === null ? null : local.resolve(source);
      const ref = parseStableAssetSource(source);
      if (ref === null) return null;
      const blob = await fetchContent(assetContentPath(ref));
      return URL.createObjectURL(blob);
    },
  };
};
