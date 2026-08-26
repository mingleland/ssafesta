import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';

export interface RecoverySnapshot {
  readonly savedAt: string;
  readonly project: GameProject;
}

export interface RecoveryStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface GameRecoveryJournal {
  load(gameId: number): RecoverySnapshot | null;
  save(project: GameProject): RecoverySnapshot;
  clear(gameId: number): void;
}

const RECOVERY_VERSION = 1;
const storageKey = (gameId: number): string => `festa:game-studio:recovery:v${RECOVERY_VERSION}:${gameId}`;

const isRecord = (value: unknown): value is Record<string, unknown> => (
  typeof value === 'object' && value !== null && !Array.isArray(value)
);

export const projectsHaveSameContent = (left: GameProject, right: GameProject): boolean => (
  JSON.stringify(left) === JSON.stringify(right)
);

export const shouldOfferRecovery = (
  recovery: RecoverySnapshot,
  persisted: GameProject,
): boolean => (
  recovery.project.gameId === persisted.gameId
  && recovery.project.revision >= persisted.revision
  && !projectsHaveSameContent(recovery.project, persisted)
);

export const createLocalRecoveryJournal = (
  storage: RecoveryStorage,
  now: () => Date = () => new Date(),
): GameRecoveryJournal => ({
  load: (gameId) => {
    const key = storageKey(gameId);
    const serialized = storage.getItem(key);
    if (serialized === null) return null;
    try {
      const envelope: unknown = JSON.parse(serialized);
      if (!isRecord(envelope) || envelope.version !== RECOVERY_VERSION || typeof envelope.savedAt !== 'string') {
        throw new TypeError('invalid recovery envelope');
      }
      const savedAt = new Date(envelope.savedAt);
      if (Number.isNaN(savedAt.getTime())) throw new TypeError('invalid recovery timestamp');
      const project = parseGameProject(envelope.project);
      if (project.gameId !== gameId) throw new TypeError('recovery gameId mismatch');
      return { savedAt: savedAt.toISOString(), project };
    } catch {
      storage.removeItem(key);
      return null;
    }
  },
  save: (project) => {
    const validated = parseGameProject(project);
    const snapshot = { savedAt: now().toISOString(), project: validated };
    storage.setItem(storageKey(validated.gameId), JSON.stringify({
      version: RECOVERY_VERSION,
      ...snapshot,
    }));
    return snapshot;
  },
  clear: (gameId) => storage.removeItem(storageKey(gameId)),
});

export const createBrowserRecoveryJournal = (): GameRecoveryJournal | null => {
  if (typeof window === 'undefined') return null;
  try {
    const storage = window.localStorage;
    storage.getItem('__festa_game_studio_recovery_probe__');
    return createLocalRecoveryJournal(storage);
  } catch {
    return null;
  }
};
