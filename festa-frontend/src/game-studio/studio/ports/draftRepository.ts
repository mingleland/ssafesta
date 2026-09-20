import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';

export interface DraftSaveReceipt {
  readonly savedAt: string;
  readonly revision: number;
  readonly warnings?: readonly string[];
}

export interface GameDraftRepository {
  load(gameId: number): Promise<GameProject | null>;
  save(project: GameProject): Promise<DraftSaveReceipt>;
}

export interface KeyValueStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

const storageKey = (gameId: number): string => `festa:game-studio:draft:v1:${gameId}`;

export const createLocalDraftRepository = (
  storage: KeyValueStorage,
  now: () => Date = () => new Date(),
): GameDraftRepository => ({
  load: async (gameId) => {
    const serialized = storage.getItem(storageKey(gameId));
    return serialized === null ? null : parseGameProject(JSON.parse(serialized));
  },
  save: async (project) => {
    const validated = parseGameProject(project);
    storage.setItem(storageKey(project.gameId), JSON.stringify(validated));
    return { savedAt: now().toISOString(), revision: validated.revision, warnings: [] };
  },
});

export const createBrowserDraftRepository = (): GameDraftRepository | null => {
  if (typeof window === 'undefined') return null;
  try {
    const storage = window.localStorage;
    storage.getItem('__festa_game_studio_probe__');
    return createLocalDraftRepository(storage);
  } catch {
    return null;
  }
};
