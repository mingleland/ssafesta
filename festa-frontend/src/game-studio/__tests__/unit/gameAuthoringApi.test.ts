import { describe, expect, it } from 'vitest';
import type { GameApiRequest } from '../../runtime/ports/publishedGameRepository.ts';
import {
  createApiGameDraftRepository,
  createApiGameLibraryPort,
  createApiGamePublisher,
  createApiGameVisibilityPort,
  normalizeGameAuthoringError,
} from '../../studio/ports/gameAuthoringApi.ts';
import { cloneMinimalGameProject } from '../fixtures/minimalGameProject.ts';

describe('Game authoring API adapter', () => {
  it('normalizes a 204 Draft response to null', async () => {
    const request: GameApiRequest = async <T>() => undefined as T;
    await expect(createApiGameDraftRepository(request).load(123)).resolves.toBeNull();
  });

  // 서버 공통 코드는 INTERNAL_ERROR다(#104 BE 확정). INTERNAL_SERVER_ERROR로 적혀 있던 동안
  // 재시도 분기가 죽어 있었으므로, 이름이 다시 어긋나면 이 테스트가 잡는다.
  it('marks the server internal error code as retryable', () => {
    const envelope = (code: string) => ({ code, message: '서버 오류', errors: [], warnings: [] });

    expect(normalizeGameAuthoringError(envelope('INTERNAL_ERROR')).retryable).toBe(true);
    expect(normalizeGameAuthoringError(envelope('UNKNOWN')).retryable).toBe(true);
    expect(normalizeGameAuthoringError(envelope('GAME_FORBIDDEN')).retryable).toBe(false);
  });

  it('sends expectedRevision and returns the server revision', async () => {
    const draft = cloneMinimalGameProject();
    draft.gameId = 123;
    let body: unknown;
    const request: GameApiRequest = async <T>(
      _path: string,
      init?: Parameters<GameApiRequest>[1],
    ): Promise<T> => {
      body = JSON.parse(String(init?.body));
      const project = { ...draft, revision: 1 };
      return {
        gameId: 123,
        revision: 1,
        project,
        updatedAt: '2026-08-23T10:00:00Z',
        warnings: [],
      } as T;
    };
    const receipt = await createApiGameDraftRepository(request).save(draft);
    expect(body).toMatchObject({ expectedRevision: 0, project: { gameId: 123, revision: 0 } });
    expect(receipt).toEqual({ savedAt: '2026-08-23T10:00:00Z', revision: 1, warnings: [] });
  });

  it('extracts CURRENT_REVISION from the shared five-field error envelope', async () => {
    const request: GameApiRequest = async () => {
      throw {
        code: 'GAME_REVISION_CONFLICT',
        message: 'conflict',
        requestId: 'req-1',
        errors: [{ rule: 'CURRENT_REVISION', message: '8' }],
        warnings: [],
      };
    };
    const draft = cloneMinimalGameProject();
    await expect(createApiGameDraftRepository(request).save(draft)).rejects.toMatchObject({
      code: 'GAME_REVISION_CONFLICT',
      currentRevision: 8,
      requestId: 'req-1',
    });
  });

  it('publishes the exact revision that was saved', async () => {
    let body: unknown;
    const request: GameApiRequest = async <T>(
      _path: string,
      init?: Parameters<GameApiRequest>[1],
    ): Promise<T> => {
      body = JSON.parse(String(init?.body));
      return {
        gameId: 123,
        publishedVersion: 4,
        publishedAt: '2026-08-23T10:02:00Z',
        warnings: [{ rule: 'GAME_NOTICE', message: '확인' }],
      } as T;
    };
    const result = await createApiGamePublisher(request).publish(123, 8);
    expect(body).toEqual({ expectedRevision: 8 });
    expect(result).toMatchObject({ gameId: 123, publishedVersion: 4, warnings: ['확인'] });
  });

  // S15P21A604-701 — 단건 조회 API가 없어 /mine 목록에서 찾는다. 여러 게임 중 정확히
  // 요청한 gameId를 골라내는지가 핵심 회귀 지점이다.
  it('finds the matching game in /mine and reads its visibility', async () => {
    const request: GameApiRequest = async <T>(): Promise<T> => ({
      games: [
        { gameId: 1, title: 'A', visibility: 'PRIVATE', publishedVersion: null, updatedAt: 't', deletedAt: null },
        { gameId: 701, title: 'B', visibility: 'PUBLIC', publishedVersion: 2, updatedAt: 't', deletedAt: null },
      ],
    } as T);
    await expect(createApiGameVisibilityPort(request).get(701)).resolves.toEqual({ gameId: 701, visibility: 'PUBLIC' });
  });

  it('rejects with GAME_NOT_FOUND when the game is missing from /mine', async () => {
    const request: GameApiRequest = async <T>(): Promise<T> => ({ games: [] } as T);
    await expect(createApiGameVisibilityPort(request).get(701)).rejects.toMatchObject({ code: 'GAME_NOT_FOUND' });
  });

  it('sends the requested visibility via PATCH and returns the server value', async () => {
    let path: string | undefined;
    let body: unknown;
    const request: GameApiRequest = async <T>(
      requestedPath: string,
      init?: Parameters<GameApiRequest>[1],
    ): Promise<T> => {
      path = requestedPath;
      body = JSON.parse(String(init?.body));
      return { gameId: 701, title: 'B', visibility: 'PUBLIC', publishedVersion: 2, updatedAt: 't', deletedAt: null } as T;
    };
    const result = await createApiGameVisibilityPort(request).set(701, 'PUBLIC');
    expect(path).toBe('/api/v1/games/701');
    expect(body).toEqual({ visibility: 'PUBLIC' });
    expect(result).toEqual({ gameId: 701, visibility: 'PUBLIC' });
  });

  // S15P21A604-824 — 목록·생성·삭제·복원. 게임 개수는 2건이면 파싱을 검증하기 충분하다
  // (상한 자체는 서버 값이라 여기서 N개를 실제로 만들 필요가 없다 — 409 응답만 흉내 내면 된다).
  it('parses the full mine list including a soft-deleted game', async () => {
    const request: GameApiRequest = async <T>(): Promise<T> => ({
      games: [
        { gameId: 1, title: '살아있는 게임', visibility: 'PRIVATE', publishedVersion: null, updatedAt: 't1', deletedAt: null },
        { gameId: 2, title: '삭제된 게임', visibility: 'PUBLIC', publishedVersion: 3, updatedAt: 't2', deletedAt: 't3' },
      ],
    } as T);
    await expect(createApiGameLibraryPort(request).list()).resolves.toEqual([
      { gameId: 1, title: '살아있는 게임', visibility: 'PRIVATE', publishedVersion: null, updatedAt: 't1', deletedAt: null },
      { gameId: 2, title: '삭제된 게임', visibility: 'PUBLIC', publishedVersion: 3, updatedAt: 't2', deletedAt: 't3' },
    ]);
  });

  it('creates a game with the given title and returns its summary', async () => {
    let path: string | undefined;
    let body: unknown;
    const request: GameApiRequest = async <T>(
      requestedPath: string,
      init?: Parameters<GameApiRequest>[1],
    ): Promise<T> => {
      path = requestedPath;
      body = JSON.parse(String(init?.body));
      return { gameId: 9, title: '새 게임', visibility: 'PRIVATE', publishedVersion: null, updatedAt: 't', deletedAt: null } as T;
    };
    const result = await createApiGameLibraryPort(request).create('새 게임');
    expect(path).toBe('/api/v1/games');
    expect(body).toEqual({ title: '새 게임' });
    expect(result.gameId).toBe(9);
  });

  it('propagates GAME_LIMIT_EXCEEDED with the server message intact', async () => {
    const request: GameApiRequest = async () => {
      throw { code: 'GAME_LIMIT_EXCEEDED', message: '게임은 최대 10개까지 만들 수 있습니다.', errors: [], warnings: [] };
    };
    await expect(createApiGameLibraryPort(request).create('넘침')).rejects.toMatchObject({
      code: 'GAME_LIMIT_EXCEEDED',
      message: '게임은 최대 10개까지 만들 수 있습니다.',
    });
  });

  it('sends DELETE for remove and resolves with no value', async () => {
    let path: string | undefined;
    let method: string | undefined;
    const request: GameApiRequest = async <T>(requestedPath: string, init?: Parameters<GameApiRequest>[1]): Promise<T> => {
      path = requestedPath;
      method = init?.method;
      return undefined as T;
    };
    await expect(createApiGameLibraryPort(request).remove(9)).resolves.toBeUndefined();
    expect(path).toBe('/api/v1/games/9');
    expect(method).toBe('DELETE');
  });

  it('restores a game and rejects if the response names a different gameId', async () => {
    const request: GameApiRequest = async <T>(): Promise<T> => (
      { gameId: 99, title: 'X', visibility: 'PRIVATE', publishedVersion: null, updatedAt: 't', deletedAt: null } as T
    );
    await expect(createApiGameLibraryPort(request).restore(9)).rejects.toMatchObject({ code: 'GAME_API_RESPONSE_INVALID' });
  });
});
