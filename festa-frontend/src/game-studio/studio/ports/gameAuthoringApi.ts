import { api, isApiError, type ApiErrorDetail } from '../../../shared/api/client.ts';
import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';
import type { GameApiRequest } from '../../runtime/ports/publishedGameRepository.ts';
import type { DraftSaveReceipt, GameDraftRepository } from './draftRepository.ts';

export interface GamePublishReceipt {
  readonly gameId: number;
  readonly publishedVersion: number;
  readonly publishedAt: string;
  readonly warnings: readonly string[];
}

export interface GamePublisher {
  publish(gameId: number, expectedRevision: number): Promise<GamePublishReceipt>;
}

export class GameAuthoringApiError extends Error {
  readonly code: string;
  readonly currentRevision?: number;
  readonly requestId?: string;
  readonly retryable: boolean;

  constructor(
    code: string,
    message: string,
    options: { readonly currentRevision?: number; readonly requestId?: string; readonly retryable?: boolean } = {},
  ) {
    super(message);
    this.name = 'GameAuthoringApiError';
    this.code = code;
    this.currentRevision = options.currentRevision;
    this.requestId = options.requestId;
    this.retryable = options.retryable ?? false;
  }
}

type UnknownRecord = Record<string, unknown>;
const isRecord = (value: unknown): value is UnknownRecord => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);
const nonNegativeInteger = (value: unknown, field: string): number => {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', `${field} 값이 올바르지 않습니다.`);
  return value as number;
};
const positiveInteger = (value: unknown, field: string): number => {
  const parsed = nonNegativeInteger(value, field);
  if (parsed < 1) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', `${field} 값이 올바르지 않습니다.`);
  return parsed;
};
const stringValue = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.length < 1) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', `${field} 값이 올바르지 않습니다.`);
  return value;
};
const warningMessages = (input: unknown): readonly string[] => {
  if (input === undefined) return [];
  if (!Array.isArray(input)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'warnings 값이 올바르지 않습니다.');
  return input.map((item) => {
    if (!isRecord(item) || typeof item.message !== 'string') throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'warning 항목이 올바르지 않습니다.');
    return item.message;
  });
};

interface ParsedDraftResponse {
  readonly project: GameProject;
  readonly updatedAt: string;
  readonly revision: number;
  readonly warnings: readonly string[];
}

export const parseGameDraftResponse = (input: unknown, expectedGameId: number): ParsedDraftResponse => {
  if (!isRecord(input)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'Draft 응답 형식이 올바르지 않습니다.');
  const gameId = positiveInteger(input.gameId, 'gameId');
  if (gameId !== expectedGameId) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '요청한 게임과 Draft 응답이 일치하지 않습니다.');
  const revision = nonNegativeInteger(input.revision, 'revision');
  const project = parseGameProject(input.project);
  if (project.gameId !== gameId || project.revision !== revision) {
    throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'Draft와 GameProject의 gameId 또는 revision이 일치하지 않습니다.');
  }
  return {
    project,
    revision,
    updatedAt: stringValue(input.updatedAt, 'updatedAt'),
    warnings: warningMessages(input.warnings),
  };
};

const currentRevisionFrom = (errors: readonly ApiErrorDetail[]): number | undefined => {
  const value = errors.find((detail) => detail.rule === 'CURRENT_REVISION')?.message;
  if (value === undefined || !/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
};

export const normalizeGameAuthoringError = (error: unknown): GameAuthoringApiError => {
  if (error instanceof GameAuthoringApiError) return error;
  if (isApiError(error)) {
    if (error.code === 'GAME_REVISION_CONFLICT') {
      return new GameAuthoringApiError(
        error.code,
        '다른 편집 내용이 먼저 저장되었습니다. 서버 초안을 다시 불러온 뒤 변경 내용을 확인해 주세요.',
        { currentRevision: currentRevisionFrom(error.errors), requestId: error.requestId },
      );
    }
    const retryable = error.code === 'UNKNOWN' || error.code === 'INTERNAL_SERVER_ERROR';
    return new GameAuthoringApiError(error.code, error.message || '게임 저장 요청에 실패했습니다.', { requestId: error.requestId, retryable });
  }
  if (error instanceof TypeError) {
    return new GameAuthoringApiError('NETWORK', '네트워크 연결을 확인한 뒤 다시 시도해 주세요.', { retryable: true });
  }
  return new GameAuthoringApiError('UNKNOWN', error instanceof Error ? error.message : '게임 저장 요청에 실패했습니다.');
};

export const createApiGameDraftRepository = (
  request: GameApiRequest = api,
): GameDraftRepository => ({
  load: async (gameId) => {
    try {
      const response = await request<unknown | undefined>(`/api/v1/games/${gameId}/draft`);
      return response === undefined ? null : parseGameDraftResponse(response, gameId).project;
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
  save: async (project): Promise<DraftSaveReceipt> => {
    const validated = parseGameProject(project);
    try {
      const response = await request<unknown>(`/api/v1/games/${validated.gameId}/draft`, {
        method: 'PUT',
        body: JSON.stringify({ expectedRevision: validated.revision, project: validated }),
      });
      const parsed = parseGameDraftResponse(response, validated.gameId);
      return { savedAt: parsed.updatedAt, revision: parsed.revision, warnings: parsed.warnings };
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
});

export const createApiGamePublisher = (
  request: GameApiRequest = api,
): GamePublisher => ({
  publish: async (gameId, expectedRevision) => {
    try {
      const response = await request<unknown>(`/api/v1/games/${gameId}/publish`, {
        method: 'POST',
        body: JSON.stringify({ expectedRevision }),
      });
      if (!isRecord(response)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', 'Publish 응답 형식이 올바르지 않습니다.');
      const responseGameId = positiveInteger(response.gameId, 'gameId');
      if (responseGameId !== gameId) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '요청한 게임과 Publish 응답이 일치하지 않습니다.');
      return {
        gameId,
        publishedVersion: positiveInteger(response.publishedVersion, 'publishedVersion'),
        publishedAt: stringValue(response.publishedAt, 'publishedAt'),
        warnings: warningMessages(response.warnings),
      };
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
});
