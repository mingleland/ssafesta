import { api, isApiError } from '../../../shared/api/client.ts';
import type { GameApiRequest } from './publishedGameRepository.ts';

export interface GamePortalRequest {
  readonly boothId: number;
  readonly objectId: string;
  readonly configId: number;
}

export interface GamePortalResolution {
  readonly configId: number;
  readonly boothId: number;
  readonly gameId: number | null;
  readonly publishedVersion: number | null;
  readonly playable: boolean;
  readonly unavailableReason: string | null;
}

export interface GamePortalRepository {
  resolve(request: GamePortalRequest, signal?: AbortSignal): Promise<GamePortalResolution>;
}

export class GamePortalLoadError extends Error {
  readonly retryable: boolean;
  readonly requestId?: string;
  readonly serverCode?: string;

  constructor(
    message: string,
    options: { readonly retryable?: boolean; readonly requestId?: string; readonly serverCode?: string } = {},
  ) {
    super(message);
    this.name = 'GamePortalLoadError';
    this.retryable = options.retryable ?? false;
    this.requestId = options.requestId;
    this.serverCode = options.serverCode;
  }
}

type UnknownRecord = Record<string, unknown>;
const isRecord = (value: unknown): value is UnknownRecord => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);
const signedInt32 = (value: unknown, field: string): number => {
  if (!Number.isInteger(value) || (value as number) < 1 || (value as number) > 2_147_483_647) {
    throw new GamePortalLoadError(`${field} 값이 올바르지 않습니다.`);
  }
  return value as number;
};
const positiveLong = (value: unknown, field: string): number => {
  if (!Number.isSafeInteger(value) || (value as number) < 1) throw new GamePortalLoadError(`${field} 값이 올바르지 않습니다.`);
  return value as number;
};

export const parseGamePortalResolution = (
  input: unknown,
  expected: GamePortalRequest,
): GamePortalResolution => {
  if (!isRecord(input)) throw new GamePortalLoadError('게임 포털 응답 형식이 올바르지 않습니다.');
  const configId = signedInt32(input.configId, 'configId');
  const boothId = positiveLong(input.boothId, 'boothId');
  if (configId !== expected.configId || boothId !== expected.boothId) {
    throw new GamePortalLoadError('요청한 포털과 서버 응답의 식별자가 일치하지 않습니다.');
  }
  if (typeof input.playable !== 'boolean') throw new GamePortalLoadError('playable 값이 올바르지 않습니다.');
  const gameId = input.gameId === null || input.gameId === undefined ? null : positiveLong(input.gameId, 'gameId');
  const publishedVersion = input.publishedVersion === null || input.publishedVersion === undefined
    ? null
    : positiveLong(input.publishedVersion, 'publishedVersion');
  if (input.playable && (gameId === null || publishedVersion === null)) {
    throw new GamePortalLoadError('플레이 가능한 포털에 공개 게임 정보가 없습니다.');
  }
  if (input.unavailableReason !== null && input.unavailableReason !== undefined && typeof input.unavailableReason !== 'string') {
    throw new GamePortalLoadError('unavailableReason 값이 올바르지 않습니다.');
  }
  return {
    configId,
    boothId,
    gameId,
    publishedVersion,
    playable: input.playable,
    unavailableReason: typeof input.unavailableReason === 'string' ? input.unavailableReason : null,
  };
};

const unavailableCopy: Readonly<Record<string, string>> = {
  GAME_NOT_PUBLISHED: '아직 게시되지 않은 게임입니다.',
  GAME_NOT_PUBLIC: '현재 비공개 상태인 게임입니다.',
  GAME_DELETED: '삭제된 게임입니다.',
  BOOTH_LEASE_EXPIRED: '이 부스의 이용 기간이 만료되었습니다.',
  CONFIG_NOT_FOUND: '게임 포털 연결을 찾을 수 없습니다.',
};

export const gamePortalUnavailableMessage = (reason: string | null): string => (
  reason === null ? '현재 이 게임을 실행할 수 없습니다.' : unavailableCopy[reason] ?? '현재 이 게임을 실행할 수 없습니다.'
);

const normalizePortalError = (error: unknown): GamePortalLoadError => {
  if (error instanceof GamePortalLoadError) return error;
  if (error instanceof DOMException && error.name === 'AbortError') return new GamePortalLoadError('포털 조회가 취소되었습니다.');
  if (isApiError(error)) {
    return new GamePortalLoadError(
      unavailableCopy[error.code] ?? '게임 포털을 불러오지 못했습니다.',
      { retryable: !Object.hasOwn(unavailableCopy, error.code), requestId: error.requestId, serverCode: error.code },
    );
  }
  if (error instanceof TypeError) return new GamePortalLoadError('네트워크 연결을 확인한 뒤 다시 시도해 주세요.', { retryable: true });
  return new GamePortalLoadError('게임 포털을 불러오지 못했습니다.', { retryable: true });
};

export const createApiGamePortalRepository = (
  requestApi: GameApiRequest = api,
): GamePortalRepository => ({
  resolve: async (request, signal) => {
    try {
      const response = await requestApi<unknown>(`/api/v1/game-portals/${request.configId}`, { signal });
      return parseGamePortalResolution(response, request);
    } catch (error) {
      throw normalizePortalError(error);
    }
  },
});
