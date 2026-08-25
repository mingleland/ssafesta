import { describe, expect, it } from 'vitest';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import {
  createLocalRecoveryJournal,
  shouldOfferRecovery,
  type RecoveryStorage,
} from '../../studio/ports/localRecoveryJournal.ts';

const createMemoryStorage = (): RecoveryStorage & { readonly values: Map<string, string> } => {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value); },
    removeItem: (key) => { values.delete(key); },
  };
};

describe('Game Studio local recovery journal', () => {
  it('round-trips a validated unsaved project outside the Draft repository', () => {
    const storage = createMemoryStorage();
    const journal = createLocalRecoveryJournal(storage, () => new Date('2026-08-25T01:23:45.000Z'));
    const project = { ...createStarterProject(701), title: '복구할 프로젝트' };

    expect(journal.save(project)).toEqual({ savedAt: '2026-08-25T01:23:45.000Z', project });
    expect(journal.load(701)).toEqual({ savedAt: '2026-08-25T01:23:45.000Z', project });
    expect(journal.load(702)).toBeNull();
  });

  it('offers only a different recovery from the same or newer revision', () => {
    const persisted = createStarterProject(702);
    const changed = { ...persisted, title: '저장되지 않은 제목' };
    const savedAt = '2026-08-25T01:23:45.000Z';

    expect(shouldOfferRecovery({ savedAt, project: changed }, persisted)).toBe(true);
    expect(shouldOfferRecovery({ savedAt, project: persisted }, persisted)).toBe(false);
    expect(shouldOfferRecovery({ savedAt, project: { ...changed, revision: persisted.revision - 1 } }, persisted)).toBe(false);
  });

  it('removes a damaged envelope instead of breaking editor startup', () => {
    const storage = createMemoryStorage();
    storage.values.set('festa:game-studio:recovery:v1:703', '{damaged');
    const journal = createLocalRecoveryJournal(storage);

    expect(journal.load(703)).toBeNull();
    expect(storage.values.size).toBe(0);
  });

  it('clears recovery after an explicit Draft save', () => {
    const storage = createMemoryStorage();
    const journal = createLocalRecoveryJournal(storage);
    journal.save(createStarterProject(704));

    journal.clear(704);

    expect(journal.load(704)).toBeNull();
  });
});
