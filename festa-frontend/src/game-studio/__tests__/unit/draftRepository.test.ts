import { describe, expect, it } from 'vitest';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { createLocalDraftRepository, type KeyValueStorage } from '../../studio/ports/draftRepository.ts';

const createMemoryStorage = (): KeyValueStorage => {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value); },
  };
};

describe('local GameProject repository adapter', () => {
  it('round-trips the same contract that a backend adapter will persist', async () => {
    const repository = createLocalDraftRepository(
      createMemoryStorage(),
      () => new Date('2026-08-22T10:00:00.000Z'),
    );
    const project = createStarterProject(51);
    const receipt = await repository.save(project);

    expect(receipt).toEqual({ savedAt: '2026-08-22T10:00:00.000Z', revision: 0, warnings: [] });
    expect(await repository.load(51)).toEqual(project);
    expect(await repository.load(52)).toBeNull();
  });
});
