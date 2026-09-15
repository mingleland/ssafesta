import { api, isApiError } from '../../../shared/api/client.ts';
import type { GameApiRequest } from './publishedGameRepository.ts';

export interface GamePortalRequest {
  readonly boothId: number;
  readonly objectId: string;
  readonly configId: number;
}

/** 부스 포털·오락기가 공통으로 답하는 것 — 오버레이는 이 넷만 읽는다 */
export interface GamePlayableResolution {
  readonly gameId: number | null;
  readonly publishedVersion: number | null;
  readonly playable: boolean;
  readonly unavailableReason: string | null;
}

export interface GamePortalResolution extends GamePlayableResolution {
  readonly configId: number;
  readonly boothId: number;
}

export interface GamePortalRepository {
  resolve(request: GamePortalRequest, signal?: AbortSignal): Promise<GamePortalResolution>;
}

/**
 * 월드 고정물 오락기 — 씬 canonical id 로 푼다 (S15P21A604-712, GitLab #135 · #56 ⓐ).
 *
 * 위 부스 포털과 **다른 경로다.** 그쪽은 임대 부스 안 GAME_PORTAL 을 configId 로 풀고 현재
 * 보류다(-158·-204). 이쪽은 월드 고정물이라 boothId 가 없고 임대·소유권 판정도 없다 —
 * BOOTH_LEASE_EXPIRED 계열이 이 경로에서는 나오지 않는다
 * (specs/019-game-studio/contracts/game-api.md §Arcade Machine Resolution).
 *
 * 파일을 가르지 않는 이유는 오류 정규화·사유 문구가 같기 때문이다. 응답 모양만 다르고
 * 실패를 다루는 방식이 같은 둘을 갈라 두면 문구가 두 곳에서 어긋난다.
 */
export interface ArcadeMachineRequest {
  readonly machineId: string;
}

export interface ArcadeMachineResolution extends GamePlayableResolution {
  readonly machineId: string;
}

export interface ArcadeMachineRepository {
  resolve(request: ArcadeMachineRequest, signal?: AbortSignal): Promise<ArcadeMachineResolution>;
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

/**
 * 오락기 응답 파싱 — 포털과 달리 식별자가 machineId 하나다.
 *
 * 서버가 발급하지 않는 씬 canonical id 라 숫자 검증이 없고, 요청과 같은 값인지만 본다.
 * 나머지 넷(playable·gameId·publishedVersion·unavailableReason)의 규칙은 포털과 같다.
 */
export const parseArcadeMachineResolution = (
  input: unknown,
  expected: ArcadeMachineRequest,
): ArcadeMachineResolution => {
  if (!isRecord(input)) throw new GamePortalLoadError('오락기 응답 형식이 올바르지 않습니다.');
  if (input.machineId !== expected.machineId) {
    throw new GamePortalLoadError('요청한 게임기와 서버 응답의 식별자가 일치하지 않습니다.');
  }
  if (typeof input.playable !== 'boolean') throw new GamePortalLoadError('playable 값이 올바르지 않습니다.');
  const gameId = input.gameId === null || input.gameId === undefined ? null : positiveLong(input.gameId, 'gameId');
  const publishedVersion = input.publishedVersion === null || input.publishedVersion === undefined
    ? null
    : positiveLong(input.publishedVersion, 'publishedVersion');
  // 계약: publishedVersion 은 playable: true 일 때만 값이 있다
  if (input.playable && (gameId === null || publishedVersion === null)) {
    throw new GamePortalLoadError('플레이 가능한 게임기에 공개 게임 정보가 없습니다.');
  }
  if (input.unavailableReason !== null && input.unavailableReason !== undefined && typeof input.unavailableReason !== 'string') {
    throw new GamePortalLoadError('unavailableReason 값이 올바르지 않습니다.');
  }
  return {
    machineId: expected.machineId,
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
  // 오락기 경로 전용 — 등록되지 않은 machineId 는 200 이 아니라 404 + 봉투 code 로 온다.
  // 여기 있으면 normalizePortalError 가 재시도 불가로 판정한다(다시 눌러도 같은 답이다).
  MACHINE_NOT_FOUND: '등록되지 않은 게임기입니다.',
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

export const createApiArcadeMachineRepository = (
  requestApi: GameApiRequest = api,
): ArcadeMachineRepository => ({
  resolve: async (request, signal) => {
    try {
      // cache: 'no-store' — 공개 상태를 매 요청 서버가 판정한다(계약이 Cache-Control: no-store 를
      // 명시한 이유). 캐시된 응답은 방금 비공개로 바꾼 게임을 계속 열어 준다.
      const response = await requestApi<unknown>(
        `/api/v1/arcade-machines/${encodeURIComponent(request.machineId)}`,
        { signal, cache: 'no-store' },
      );
      return parseArcadeMachineResolution(response, request);
    } catch (error) {
      throw normalizePortalError(error);
    }
  },
});
