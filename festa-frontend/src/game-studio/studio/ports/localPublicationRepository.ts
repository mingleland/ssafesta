import { parseGameProject } from '../../contracts/gameProject.ts';
import {
  parsePublishedGameSnapshot,
  PublishedGameLoadError,
  type PublishedGameRepository,
  type PublishedGameSnapshot,
} from '../../runtime/ports/publishedGameRepository.ts';
import { GameAuthoringApiError, type GamePublishReceipt, type GamePublisher } from './gameAuthoringApi.ts';
import { createLocalDraftRepository, type GameDraftRepository, type KeyValueStorage } from './draftRepository.ts';

const publishedStorageKey = (gameId: number): string => `festa:game-studio:published:v1:${gameId}`;

export interface LocalPublicationPorts {
  readonly publisher: GamePublisher;
  readonly repository: PublishedGameRepository;
}

export const createLocalPublicationPorts = (
  storage: KeyValueStorage,
  draftRepository: GameDraftRepository = createLocalDraftRepository(storage),
  now: () => Date = () => new Date(),
): LocalPublicationPorts => {
  const repository: PublishedGameRepository = {
    load: async (gameId, signal) => {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      const serialized = storage.getItem(publishedStorageKey(gameId));
      if (serialized === null) {
        throw new PublishedGameLoadError('NOT_PUBLISHED', '아직 게시되지 않은 게임입니다.');
      }
      try {
        return parsePublishedGameSnapshot(JSON.parse(serialized), gameId);
      } catch (error) {
        if (error instanceof PublishedGameLoadError) throw error;
        throw new PublishedGameLoadError('DAMAGED_PROJECT', '브라우저 게시본이 손상되었습니다. 다시 게시해 주세요.');
      }
    },
  };

  const publisher: GamePublisher = {
    publish: async (gameId, expectedRevision): Promise<GamePublishReceipt> => {
      const draft = await draftRepository.load(gameId);
      if (draft === null) {
        throw new GameAuthoringApiError('GAME_DRAFT_NOT_FOUND', '저장된 초안이 없습니다. 먼저 게임을 저장해 주세요.');
      }
      const project = parseGameProject(draft);
      if (project.revision !== expectedRevision) {
        throw new GameAuthoringApiError(
          'GAME_REVISION_CONFLICT',
          '저장된 초안이 변경되었습니다. 다시 저장한 뒤 게시해 주세요.',
          { currentRevision: project.revision },
        );
      }

      let publishedVersion = 1;
      const previous = storage.getItem(publishedStorageKey(gameId));
      if (previous !== null) {
        try {
          publishedVersion = parsePublishedGameSnapshot(JSON.parse(previous), gameId).publishedVersion + 1;
        } catch {
          // 손상된 로컬 게시본은 유효한 새 스냅샷으로 교체한다.
        }
      }

      const publishedAt = now().toISOString();
      const snapshot: PublishedGameSnapshot = {
        gameId,
        publishedVersion,
        schemaVersion: project.schemaVersion,
        project: structuredClone(project),
        publishedAt,
      };
      storage.setItem(publishedStorageKey(gameId), JSON.stringify(snapshot));
      return { gameId, publishedVersion, publishedAt, warnings: [] };
    },
  };

  return { publisher, repository };
};

export const createBrowserPublicationPorts = (): LocalPublicationPorts | null => {
  if (typeof window === 'undefined') return null;
  try {
    const storage = window.localStorage;
    storage.getItem('__festa_game_studio_publication_probe__');
    return createLocalPublicationPorts(storage);
  } catch {
    return null;
  }
};
