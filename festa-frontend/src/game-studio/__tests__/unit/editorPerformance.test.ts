import { describe, expect, it } from 'vitest';
import { parseGameProject, type GameObject } from '../../contracts/gameProject.ts';
import { moveObject } from '../../studio/model/authoringCommands.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { createGameProjectStore } from '../../studio/store/gameProjectStore.ts';

const createMaximumObjectProject = () => {
  const base = createStarterProject(97);
  const library = base.scenes.find((scene) => scene.id === 'library');
  if (library?.type !== 'TOP_DOWN') throw new Error('library fixture missing');
  const additions: GameObject[] = Array.from({ length: 500 - library.objects.length }, (_, index) => ({
    id: `perfObject${index + 1}`,
    preset: 'DECORATION',
    position: { x: index % library.width, y: Math.floor(index / library.width) % library.height },
    visible: true,
    components: [],
  }));
  return parseGameProject({
    ...base,
    scenes: base.scenes.map((scene) => scene.id === library.id ? { ...library, objects: [...library.objects, ...additions] } : scene),
  });
};

describe('Game Studio editor performance budget', () => {
  it('commits a move in a 500-object Scene within the 100ms interaction budget', () => {
    const store = createGameProjectStore(createMaximumObjectProject());
    const durations: number[] = [];
    for (let index = 0; index < 7; index += 1) {
      const startedAt = performance.now();
      store.update((project) => moveObject(project, 'library', 'perfObject1', index % 2, index % 3));
      if (index >= 2) durations.push(performance.now() - startedAt);
    }
    const slowest = Math.max(...durations);
    expect(slowest).toBeLessThan(100);
  });
});
