import { describe, expect, it } from 'vitest';
import type { GameApiRequest } from '../../runtime/ports/publishedGameRepository.ts';
import {
  createApiGameDraftRepository,
  createApiGamePublisher,
} from '../../studio/ports/gameAuthoringApi.ts';
import { cloneMinimalGameProject } from '../fixtures/minimalGameProject.ts';

describe('Game authoring API adapter', () => {
  it('normalizes a 204 Draft response to null', async () => {
    const request: GameApiRequest = async <T>() => undefined as T;
    await expect(createApiGameDraftRepository(request).load(123)).resolves.toBeNull();
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
});
