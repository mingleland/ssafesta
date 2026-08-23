import { api, isApiError, type ApiInit } from '../../../shared/api/client.ts';
import {
  GameProjectContractError,
  parseGameProject,
  type GameProject,
} from '../../contracts/gameProject.ts';

export interface PublishedGameSnapshot {
  readonly gameId: number;
  readonly publishedVersion: number;
  readonly schemaVersion: GameProject['schemaVersion'];
  readonly project: GameProject;
  readonly publishedAt?: string;
}

export interface PublishedGameRepository {
  load(gameId: number, signal?: AbortSignal): Promise<PublishedGameSnapshot>;
}

export type GameApiRequest = <T>(path: string, init?: ApiInit) => Promise<T>;

export type PublishedGameLoadErrorCode =
  | 'ABORTED'
  | 'NETWORK'
  | 'NOT_FOUND'
  | 'NOT_PUBLISHED'
  | 'PRIVATE'
  | 'FORBIDDEN'
  | 'UNSUPPORTED_SCHEMA'
  | 'DAMAGED_PROJECT'
  | 'SERVER';

export class PublishedGameLoadError extends Error {
  readonly code: PublishedGameLoadErrorCode;
  readonly retryable: boolean;
  readonly requestId?: string;
  readonly serverCode?: string;

  constructor(
    code: PublishedGameLoadErrorCode,
    message: string,
    options: { readonly retryable?: boolean; readonly requestId?: string; readonly serverCode?: string } = {},
  ) {
    super(message);
    this.name = 'PublishedGameLoadError';
    this.code = code;
    this.retryable = options.retryable ?? false;
    this.requestId = options.requestId;
    this.serverCode = options.serverCode;
  }
}

type UnknownRecord = Record<string, unknown>;

const isRecord = (value: unknown): value is UnknownRecord => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);

const positiveInteger = (value: unknown, field: string): number => {
  if (!Number.isSafeInteger(value) || (value as number) < 1) {
    throw new PublishedGameLoadError('DAMAGED_PROJECT', `${field} 값이 올바르지 않습니다.`);
  }
  return value as number;
};

export const parsePublishedGameSnapshot = (
  input: unknown,
  expectedGameId: number,
): PublishedGameSnapshot => {
  if (!isRecord(input)) {
    throw new PublishedGameLoadError('DAMAGED_PROJECT', '게시된 게임 응답 형식이 올바르지 않습니다.');
  }
  const gameId = positiveInteger(input.gameId, 'gameId');
  if (gameId !== expectedGameId) {
    throw new PublishedGameLoadError('DAMAGED_PROJECT', '요청한 게임과 서버 응답의 게임 ID가 다릅니다.');
  }
  const publishedVersion = positiveInteger(input.publishedVersion, 'publishedVersion');
  let project: GameProject;
  try {
    project = parseGameProject(input.project);
  } catch (error) {
    if (error instanceof GameProjectContractError && error.code === 'GAME_SCHEMA_UNSUPPORTED') {
      throw new PublishedGameLoadError('UNSUPPORTED_SCHEMA', '현재 플레이어가 지원하지 않는 게임 버전입니다.');
    }
    throw new PublishedGameLoadError('DAMAGED_PROJECT', '게시된 게임 데이터가 손상되었거나 참조가 올바르지 않습니다.');
  }
  if (project.gameId !== gameId) {
    throw new PublishedGameLoadError('DAMAGED_PROJECT', 'GameProject의 gameId가 게시 응답과 일치하지 않습니다.');
  }
  if (input.schemaVersion !== project.schemaVersion) {
    throw new PublishedGameLoadError('UNSUPPORTED_SCHEMA', '게시 응답과 GameProject의 schemaVersion이 일치하지 않습니다.');
  }
  if (input.publishedAt !== undefined && typeof input.publishedAt !== 'string') {
    throw new PublishedGameLoadError('DAMAGED_PROJECT', 'publishedAt 값이 올바르지 않습니다.');
  }
  return {
    gameId,
    publishedVersion,
    schemaVersion: project.schemaVersion,
    project,
    ...(typeof input.publishedAt === 'string' ? { publishedAt: input.publishedAt } : {}),
  };
};

const apiErrorCopy: Readonly<Record<string, { code: PublishedGameLoadErrorCode; message: string }>> = {
  GAME_NOT_FOUND: { code: 'NOT_FOUND', message: '게임을 찾을 수 없습니다.' },
  GAME_DELETED: { code: 'NOT_FOUND', message: '삭제된 게임입니다.' },
  GAME_NOT_PUBLISHED: { code: 'NOT_PUBLISHED', message: '아직 게시되지 않은 게임입니다.' },
  GAME_NOT_PUBLIC: { code: 'PRIVATE', message: '현재 비공개 상태인 게임입니다.' },
  GAME_FORBIDDEN: { code: 'FORBIDDEN', message: '이 게임에 접근할 수 없습니다.' },
  GAME_SCHEMA_UNSUPPORTED: { code: 'UNSUPPORTED_SCHEMA', message: '현재 플레이어가 지원하지 않는 게임 버전입니다.' },
  GAME_PROJECT_INVALID: { code: 'DAMAGED_PROJECT', message: '게시된 게임 데이터가 올바르지 않습니다.' },
};

export const normalizePublishedGameLoadError = (error: unknown): PublishedGameLoadError => {
  if (error instanceof PublishedGameLoadError) return error;
  if (error instanceof DOMException && error.name === 'AbortError') {
    return new PublishedGameLoadError('ABORTED', '게임 불러오기가 취소되었습니다.');
  }
  if (isApiError(error)) {
    const known = apiErrorCopy[error.code];
    return new PublishedGameLoadError(
      known?.code ?? 'SERVER',
      known?.message ?? '게임 서버가 요청을 처리하지 못했습니다.',
      {
        retryable: known === undefined,
        requestId: error.requestId,
        serverCode: error.code,
      },
    );
  }
  if (error instanceof TypeError) {
    return new PublishedGameLoadError('NETWORK', '네트워크 연결을 확인한 뒤 다시 시도해 주세요.', { retryable: true });
  }
  return new PublishedGameLoadError('SERVER', '게임을 불러오지 못했습니다.', { retryable: true });
};

export const createApiPublishedGameRepository = (
  request: GameApiRequest = api,
): PublishedGameRepository => ({
  load: async (gameId, signal) => {
    try {
      const response = await request<unknown>(`/api/v1/games/${gameId}/published`, { signal });
      return parsePublishedGameSnapshot(response, gameId);
    } catch (error) {
      throw normalizePublishedGameLoadError(error);
    }
  },
});
