import { describe, expect, it } from 'vitest';
import {
  createApiArcadeMachineRepository,
  createApiGamePortalRepository,
  gamePortalUnavailableMessage,
  parseArcadeMachineResolution,
  parseGamePortalResolution,
  type ArcadeMachineRequest,
  type GamePortalRequest,
} from '../../runtime/ports/gamePortalRepository.ts';
import type { GameApiRequest } from '../../runtime/ports/publishedGameRepository.ts';

const request: GamePortalRequest = { boothId: 7, objectId: 'game-npc-01', configId: 42 };

describe('Game Portal API adapter', () => {
  it('accepts a playable resolution and preserves signed Int32 configId', () => {
    expect(parseGamePortalResolution({
      configId: request.configId,
      boothId: request.boothId,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, request)).toEqual({
      configId: request.configId,
      boothId: request.boothId,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    });
  });

  it('allows a non-playable response without published identifiers', () => {
    const parsed = parseGamePortalResolution({
      configId: request.configId,
      boothId: request.boothId,
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
      configId: request.configId,
      boothId: 8,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, request)).toThrow('식별자가 일치하지 않습니다');
  });

  it('keeps objectId as request-local interaction context instead of requiring it from the binding response', () => {
    expect(parseGamePortalResolution({
      configId: request.configId,
      boothId: request.boothId,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, request)).toEqual({
      configId: request.configId,
      boothId: request.boothId,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    });
  });

  it('uses the agreed flat resolver endpoint', async () => {
    let path = '';
    const apiRequest: GameApiRequest = async <T>(nextPath: string) => {
      path = nextPath;
      return {
        configId: request.configId,
        boothId: request.boothId,
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

// 광장 오락기 — 부스 포털과 같은 파일에 있지만 다른 경로다 (S15P21A604-712, GitLab #135 · #56 ⓐ).
// 식별자가 machineId 하나뿐이고 boothId·임대 판정이 없다는 것이 계약의 요점이라 그것부터 잠근다.
describe('Arcade Machine API adapter', () => {
  const machine: ArcadeMachineRequest = { machineId: 'plaza-arcade-01' };

  it('플레이 가능한 오락기 응답을 읽고 boothId 를 만들어 내지 않는다', () => {
    expect(parseArcadeMachineResolution({
      machineId: machine.machineId,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, machine)).toEqual({
      machineId: machine.machineId,
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    });
  });

  it('공개 상태 3종은 200 + playable:false 로 오고 각기 다른 문구가 된다', () => {
    const cases = [
      ['GAME_DELETED', '삭제된 게임입니다.'],
      ['GAME_NOT_PUBLIC', '현재 비공개 상태인 게임입니다.'],
      ['GAME_NOT_PUBLISHED', '아직 게시되지 않은 게임입니다.'],
    ] as const;
    for (const [reason, copy] of cases) {
      const parsed = parseArcadeMachineResolution({
        machineId: machine.machineId,
        gameId: null,
        publishedVersion: null,
        playable: false,
        unavailableReason: reason,
      }, machine);
      expect(parsed.playable).toBe(false);
      expect(gamePortalUnavailableMessage(parsed.unavailableReason)).toBe(copy);
    }
  });

  it('다른 게임기의 응답을 받아들이지 않는다', () => {
    expect(() => parseArcadeMachineResolution({
      machineId: 'plaza-arcade-02',
      gameId: 123,
      publishedVersion: 5,
      playable: true,
      unavailableReason: null,
    }, machine)).toThrow('식별자가 일치하지 않습니다');
  });

  it('등록되지 않은 machineId 의 404 는 재시도 불가로 읽는다 — 다시 눌러도 같은 답이다', async () => {
    const apiRequest: GameApiRequest = async () => {
      throw { code: 'MACHINE_NOT_FOUND', message: 'not found', errors: [], warnings: [] };
    };
    await expect(createApiArcadeMachineRepository(apiRequest).resolve(machine))
      .rejects.toMatchObject({ message: '등록되지 않은 게임기입니다.', retryable: false });
  });

  it('계약대로 machineId 경로를 부르고 캐시를 끈다 — 공개 상태는 매 요청 서버가 판정한다', async () => {
    let path = '';
    let cache: RequestCache | undefined;
    const apiRequest: GameApiRequest = async <T>(nextPath: string, init?: { cache?: RequestCache }) => {
      path = nextPath;
      cache = init?.cache;
      return {
        machineId: machine.machineId,
        gameId: 123,
        publishedVersion: 5,
        playable: true,
        unavailableReason: null,
      } as T;
    };
    await createApiArcadeMachineRepository(apiRequest).resolve(machine);
    expect(path).toBe('/api/v1/arcade-machines/plaza-arcade-01');
    expect(cache).toBe('no-store');
  });
});
