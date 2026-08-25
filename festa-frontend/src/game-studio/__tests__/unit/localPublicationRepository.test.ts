import { describe, expect, it } from 'vitest';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { createLocalDraftRepository, type KeyValueStorage } from '../../studio/ports/draftRepository.ts';
import { createLocalPublicationPorts } from '../../studio/ports/localPublicationRepository.ts';

const memoryStorage = (): KeyValueStorage => {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
  };
};

describe('local publication ports', () => {
  it('publishes an immutable snapshot that the public repository can load', async () => {
    const storage = memoryStorage();
    const drafts = createLocalDraftRepository(storage);
    const ports = createLocalPublicationPorts(storage, drafts, () => new Date('2026-08-24T12:00:00.000Z'));
    const project = createStarterProject(901);
    await drafts.save(project);

    const receipt = await ports.publisher.publish(901, project.revision);
    const snapshot = await ports.repository.load(901);

    expect(receipt).toEqual({
      gameId: 901,
      publishedVersion: 1,
      publishedAt: '2026-08-24T12:00:00.000Z',
      warnings: [],
    });
    expect(snapshot.project).toEqual(project);
    expect(snapshot.project).not.toBe(project);

    await ports.publisher.publish(901, project.revision);
    await expect(ports.repository.load(901)).resolves.toMatchObject({ publishedVersion: 2 });
  });

  it('does not expose an unpublished game', async () => {
    const ports = createLocalPublicationPorts(memoryStorage());
    await expect(ports.repository.load(902)).rejects.toMatchObject({
      code: 'NOT_PUBLISHED',
    });
  });

  it('rejects a missing draft and revision mismatch', async () => {
    const storage = memoryStorage();
    const drafts = createLocalDraftRepository(storage);
    const ports = createLocalPublicationPorts(storage, drafts);

    await expect(ports.publisher.publish(903, 0)).rejects.toMatchObject({
      code: 'GAME_DRAFT_NOT_FOUND',
    });

    const project = createStarterProject(903);
    await drafts.save(project);
    await expect(ports.publisher.publish(903, project.revision + 1)).rejects.toMatchObject({
      code: 'GAME_REVISION_CONFLICT',
      currentRevision: project.revision,
    });
  });
});
