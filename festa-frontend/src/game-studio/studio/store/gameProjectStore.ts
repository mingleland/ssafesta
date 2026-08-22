import {
  parseGameProject,
  type GameProject,
} from '../../contracts/gameProject.ts';

export interface GameProjectHistoryState {
  readonly project: GameProject;
  readonly canUndo: boolean;
  readonly canRedo: boolean;
}

export interface GameProjectStore {
  getState(): GameProjectHistoryState;
  replace(project: GameProject): GameProjectHistoryState;
  update(updater: (project: GameProject) => GameProject): GameProjectHistoryState;
  undo(): GameProjectHistoryState;
  redo(): GameProjectHistoryState;
  reset(project: GameProject): GameProjectHistoryState;
  subscribe(listener: () => void): () => void;
}

const deepFreeze = <T>(value: T): T => {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const nested of Object.values(value)) deepFreeze(nested);
  return value;
};

const snapshot = (project: GameProject): GameProject => (
  deepFreeze(structuredClone(parseGameProject(project)))
);

const sameProject = (left: GameProject, right: GameProject): boolean => (
  JSON.stringify(left) === JSON.stringify(right)
);

export const createGameProjectStore = (
  initialProject: GameProject,
  historyLimit = 100,
): GameProjectStore => {
  if (!Number.isInteger(historyLimit) || historyLimit < 1) {
    throw new RangeError('historyLimit must be a positive integer');
  }

  let past: GameProject[] = [];
  let present = snapshot(initialProject);
  let future: GameProject[] = [];
  const listeners = new Set<() => void>();

  const state = (): GameProjectHistoryState => ({
    project: present,
    canUndo: past.length > 0,
    canRedo: future.length > 0,
  });

  const emit = (): void => {
    for (const listener of [...listeners]) listener();
  };

  const replace = (nextProject: GameProject): GameProjectHistoryState => {
    const next = snapshot(nextProject);
    if (sameProject(present, next)) return state();
    past = [...past, present].slice(-historyLimit);
    present = next;
    future = [];
    emit();
    return state();
  };

  return {
    getState: state,
    replace,
    update: (updater) => replace(updater(structuredClone(present))),
    undo: () => {
      const previous = past.at(-1);
      if (previous === undefined) return state();
      past = past.slice(0, -1);
      future = [present, ...future].slice(0, historyLimit);
      present = previous;
      emit();
      return state();
    },
    redo: () => {
      const next = future[0];
      if (next === undefined) return state();
      future = future.slice(1);
      past = [...past, present].slice(-historyLimit);
      present = next;
      emit();
      return state();
    },
    reset: (nextProject) => {
      present = snapshot(nextProject);
      past = [];
      future = [];
      emit();
      return state();
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
};
