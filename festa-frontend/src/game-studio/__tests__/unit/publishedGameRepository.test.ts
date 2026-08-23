import { describe, expect, it } from 'vitest';
import {
  createApiPublishedGameRepository,
  parsePublishedGameSnapshot,
  PublishedGameLoadError,
  type GameApiRequest,
} from '../../runtime/ports/publishedGameRepository.ts';
import { cloneMinimalGameProject } from '../fixtures/minimalGameProject.ts';

const responseFor = (gameId = 123) => {
  const project = cloneMinimalGameProject();
  project.gameId = gameId;
  return {
    gameId,
    publishedVersion: 4,
    schemaVersion: '1.0.0',
    publishedAt: '2026-08-23T10:00:00Z',
    project,
  };
};

describe('Published Game API adapter', () => {
  it('parses and validates the Published envelope with GameProject', () => {
    const parsed = parsePublishedGameSnapshot(responseFor(), 123);
    expect(parsed).toMatchObject({ gameId: 123, publishedVersion: 4, schemaVersion: '1.0.0' });
    expect(parsed.project.gameId).toBe(123);
  });

  it('rejects a response for a different game', () => {
    expect(() => parsePublishedGameSnapshot(responseFor(999), 123)).toThrow(PublishedGameLoadError);
  });

  it('isolates unsupported GameProject major versions', () => {
    const response = responseFor() as Record<string, unknown>;
    response.project = { ...response.project as object, schemaVersion: '2.0.0' };
    expect(() => parsePublishedGameSnapshot(response, 123)).toThrowError(
      expect.objectContaining({ code: 'UNSUPPORTED_SCHEMA' }),
    );
  });

  it('isolates a damaged GameProject without exposing parser internals', () => {
    const response = responseFor() as Record<string, unknown>;
    response.project = { schemaVersion: '1.0.0', gameId: 123 };
    expect(() => parsePublishedGameSnapshot(response, 123)).toThrowError(
      expect.objectContaining({
        code: 'DAMAGED_PROJECT',
        message: '게시된 게임 데이터가 손상되었거나 참조가 올바르지 않습니다.',
      }),
    );
  });

  it('maps public-state API errors to stable player errors', async () => {
    const request: GameApiRequest = async () => {
      throw { code: 'GAME_NOT_PUBLISHED', message: 'raw', errors: [], warnings: [] };
    };
    await expect(createApiPublishedGameRepository(request).load(123)).rejects.toMatchObject({
      code: 'NOT_PUBLISHED',
      retryable: false,
      message: '아직 게시되지 않은 게임입니다.',
    });
  });

  it('keeps network failures retryable', async () => {
    const request: GameApiRequest = async () => { throw new TypeError('fetch failed'); };
    await expect(createApiPublishedGameRepository(request).load(123)).rejects.toMatchObject({
      code: 'NETWORK',
      retryable: true,
    });
  });
});
