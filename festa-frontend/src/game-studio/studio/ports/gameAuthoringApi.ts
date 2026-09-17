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
    // 서버 공통 오류 코드는 INTERNAL_ERROR다(#104 BE 확정, 2026-08-25). INTERNAL_SERVER_ERROR로
    // 적혀 있던 동안 이 재시도 분기는 한 번도 타지 않았다 — entities/conversation/stream.types.ts와 같은 이름을 쓴다.
    const retryable = error.code === 'UNKNOWN' || error.code === 'INTERNAL_ERROR';
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

export type GameVisibility = 'PRIVATE' | 'PUBLIC';

export interface GameVisibilitySummary {
  readonly gameId: number;
  readonly visibility: GameVisibility;
}

export interface GameVisibilityPort {
  /** `GET /mine` 목록에서 이 게임을 찾아 현재 공개설정을 읽는다 — 단건 조회 API가 없다. */
  get(gameId: number): Promise<GameVisibilitySummary>;
  set(gameId: number, visibility: GameVisibility): Promise<GameVisibilitySummary>;
}

const gameVisibilityValue = (value: unknown, field: string): GameVisibility => {
  if (value !== 'PRIVATE' && value !== 'PUBLIC') throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', `${field} 값이 올바르지 않습니다.`);
  return value;
};

const parseVisibilitySummary = (input: unknown, expectedGameId: number): GameVisibilitySummary => {
  if (!isRecord(input)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '게임 응답 형식이 올바르지 않습니다.');
  const gameId = positiveInteger(input.gameId, 'gameId');
  if (gameId !== expectedGameId) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '요청한 게임과 응답이 일치하지 않습니다.');
  return { gameId, visibility: gameVisibilityValue(input.visibility, 'visibility') };
};

export const createApiGameVisibilityPort = (
  request: GameApiRequest = api,
): GameVisibilityPort => ({
  get: async (gameId) => {
    try {
      const response = await request<unknown>('/api/v1/games/mine');
      if (!isRecord(response) || !Array.isArray(response.games)) {
        throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '내 게임 목록 응답 형식이 올바르지 않습니다.');
      }
      const match = response.games.find((entry) => isRecord(entry) && entry.gameId === gameId);
      if (match === undefined) throw new GameAuthoringApiError('GAME_NOT_FOUND', '게임을 찾을 수 없습니다.');
      return parseVisibilitySummary(match, gameId);
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
  set: async (gameId, visibility) => {
    try {
      const response = await request<unknown>(`/api/v1/games/${gameId}`, {
        method: 'PATCH',
        body: JSON.stringify({ visibility }),
      });
      return parseVisibilitySummary(response, gameId);
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
});

// S15P21A604-824 — 목록·생성 화면(GamesListPage)이 쓰는 전체 요약. GameVisibilitySummary와
// 필드가 겹치지만(gameId·visibility) 별도 타입으로 둔다 — 저 쪽은 "단건 공개설정"만 있으면 되는
// 자리라 title·publishedVersion·updatedAt·deletedAt까지 실어 나를 이유가 없다.
export interface GameSummary {
  readonly gameId: number;
  readonly title: string;
  readonly visibility: GameVisibility;
  /** 한 번도 게시한 적 없으면 null(BE `Integer publishedVersion`, contracts §내 게임 목록). */
  readonly publishedVersion: number | null;
  readonly updatedAt: string;
  /** 소프트 삭제 시각. null이면 살아 있는 게임이다. */
  readonly deletedAt: string | null;
}

export interface GameLibraryPort {
  /** 삭제한 것까지 포함한 전체 목록 — `deletedAt`으로 가른다(BE `GET /mine` 계약). */
  list(): Promise<readonly GameSummary[]>;
  create(title: string): Promise<GameSummary>;
  remove(gameId: number): Promise<void>;
  restore(gameId: number): Promise<GameSummary>;
}

const nullableInteger = (value: unknown, field: string): number | null => (
  value === null || value === undefined ? null : positiveInteger(value, field)
);

const nullableStringValue = (value: unknown, field: string): string | null => (
  value === null || value === undefined ? null : stringValue(value, field)
);

const parseGameSummary = (input: unknown): GameSummary => {
  if (!isRecord(input)) throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '게임 응답 형식이 올바르지 않습니다.');
  return {
    gameId: positiveInteger(input.gameId, 'gameId'),
    title: stringValue(input.title, 'title'),
    visibility: gameVisibilityValue(input.visibility, 'visibility'),
    publishedVersion: nullableInteger(input.publishedVersion, 'publishedVersion'),
    updatedAt: stringValue(input.updatedAt, 'updatedAt'),
    deletedAt: nullableStringValue(input.deletedAt, 'deletedAt'),
  };
};

export const createApiGameLibraryPort = (
  request: GameApiRequest = api,
): GameLibraryPort => ({
  list: async () => {
    try {
      const response = await request<unknown>('/api/v1/games/mine');
      if (!isRecord(response) || !Array.isArray(response.games)) {
        throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '내 게임 목록 응답 형식이 올바르지 않습니다.');
      }
      return response.games.map(parseGameSummary);
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
  create: async (title) => {
    try {
      const response = await request<unknown>('/api/v1/games', {
        method: 'POST',
        body: JSON.stringify({ title }),
      });
      return parseGameSummary(response);
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
  remove: async (gameId) => {
    try {
      await request<undefined>(`/api/v1/games/${gameId}`, { method: 'DELETE' });
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
  restore: async (gameId) => {
    try {
      const response = await request<unknown>(`/api/v1/games/${gameId}/restore`, { method: 'POST' });
      const summary = parseGameSummary(response);
      if (summary.gameId !== gameId) {
        throw new GameAuthoringApiError('GAME_API_RESPONSE_INVALID', '요청한 게임과 복원 응답이 일치하지 않습니다.');
      }
      return summary;
    } catch (error) {
      throw normalizeGameAuthoringError(error);
    }
  },
});
