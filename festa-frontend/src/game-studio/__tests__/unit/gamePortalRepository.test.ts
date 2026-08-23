import { describe, expect, it } from 'vitest';
import {
  createApiGamePortalRepository,
  gamePortalUnavailableMessage,
  parseGamePortalResolution,
  type GamePortalRequest,
} from '../../runtime/ports/gamePortalRepository.ts';
import type { GameApiRequest } from '../../runtime/ports/publishedGameRepository.ts';

const request: GamePortalRequest = { boothId: 7, objectId: 'game-npc-01', configId: 42 };

describe('Game Portal API adapter', () => {
  it('accepts a playable resolution and preserves signed Int32 configId', () => {
    expect(parseGamePortalResolution({
      ...request,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, request)).toEqual({
      ...request,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    });
  });

  it('allows a non-playable response without published identifiers', () => {
    const parsed = parseGamePortalResolution({
      ...request,
      gameId: null,
      publishedVersion: null,
      playable: false,
      unavailableReason: 'GAME_NOT_PUBLIC',
    }, request);
    expect(parsed.gameId).toBeNull();
    expect(gamePortalUnavailableMessage(parsed.unavailableReason)).toBe('현재 비공개 상태인 게임입니다.');
  });

  it('rejects mismatched binding identifiers', () => {
    expect(() => parseGamePortalResolution({
      ...request,
      boothId: 8,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, request)).toThrow('식별자가 일치하지 않습니다');
  });

  it('uses the agreed flat resolver endpoint', async () => {
    let path = '';
    const apiRequest: GameApiRequest = async <T>(nextPath: string) => {
      path = nextPath;
      return {
        ...request,
        gameId: 123,
        publishedVersion: 5,
        playable: true,
        unavailableReason: null,
      } as T;
    };
    await createApiGamePortalRepository(apiRequest).resolve(request);
    expect(path).toBe('/api/v1/game-portals/42');
  });
});
