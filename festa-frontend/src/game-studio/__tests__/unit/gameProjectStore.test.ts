import { describe, expect, it, vi } from 'vitest';
import { GameProjectContractError } from '../../contracts/gameProject.ts';
import { createGameProjectStore } from '../../studio/store/gameProjectStore.ts';
import { minimalGameProject } from '../fixtures/minimalGameProject.ts';

describe('GameProject authoring store', () => {
  it('returns a referentially stable snapshot until state changes', () => {
    const store = createGameProjectStore(minimalGameProject);
    expect(store.getState()).toBe(store.getState());
    const before = store.getState();
    store.replace({ ...minimalGameProject, title: 'changed' });
    expect(store.getState()).not.toBe(before);
    expect(store.getState()).toBe(store.getState());
  });

  it('commits valid immutable snapshots and supports undo/redo', () => {
    const store = createGameProjectStore(minimalGameProject);
    const initial = store.getState().project;

    const edited = store.update((project) => ({ ...project, title: '수정된 게임' }));
    expect(edited.project.title).toBe('수정된 게임');
    expect(edited.canUndo).toBe(true);
    expect(Object.isFrozen(edited.project)).toBe(true);
    expect(initial.title).toBe('열쇠와 문');

    const undone = store.undo();
    expect(undone.project.title).toBe('열쇠와 문');
    expect(undone.canRedo).toBe(true);

    const redone = store.redo();
    expect(redone.project.title).toBe('수정된 게임');
  });

  it('clears redo history after branching from an undone snapshot', () => {
    const store = createGameProjectStore(minimalGameProject);
    store.update((project) => ({ ...project, title: '첫 번째' }));
    store.update((project) => ({ ...project, title: '두 번째' }));
    store.undo();

    const branched = store.update((project) => ({ ...project, title: '새 분기' }));

    expect(branched.project.title).toBe('새 분기');
    expect(branched.canRedo).toBe(false);
  });

  it('rejects invalid snapshots without changing history or notifying listeners', () => {
    const store = createGameProjectStore(minimalGameProject);
    const listener = vi.fn();
    store.subscribe(listener);

    expect(() => store.update((project) => ({ ...project, title: '' })))
      .toThrow(GameProjectContractError);
    expect(store.getState().project.title).toBe('열쇠와 문');
    expect(store.getState().canUndo).toBe(false);
    expect(listener).not.toHaveBeenCalled();
  });

  it('notifies subscribers once per committed history transition', () => {
    const store = createGameProjectStore(minimalGameProject);
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    store.update((project) => ({ ...project, title: '수정' }));
    store.undo();
    unsubscribe();
    store.redo();

    expect(listener).toHaveBeenCalledTimes(2);
  });
});
