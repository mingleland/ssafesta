import { describe, expect, it } from 'vitest';
import { estimateGameProjectJsonBytes, GAME_PROJECT_LIMITS } from '../../contracts/gameProject.ts';
import { moveObject } from '../../studio/model/authoringCommands.ts';
import { createEditorStressProject } from '../../studio/model/createEditorStressProject.ts';
import { createGameProjectStore } from '../../studio/store/gameProjectStore.ts';

describe('Game Studio editor performance budget', () => {
  it('keeps the reproducible maximum fixture inside every current project contract', () => {
    const project = createEditorStressProject(97);
    const scene = project.scenes.find((candidate) => candidate.id === 'library');
    if (scene?.type !== 'TOP_DOWN') throw new Error('stress fixture missing');

    expect(scene.objects).toHaveLength(GAME_PROJECT_LIMITS.maxObjectsPerScene);
    expect(scene.tileLayers[0]?.data).toHaveLength(GAME_PROJECT_LIMITS.maxTileCellsPerLayer);
    expect(estimateGameProjectJsonBytes(project)).toBeLessThan(GAME_PROJECT_LIMITS.maxJsonBytes);
  });

  it('commits a move in a 500-object Scene within the 100ms interaction budget', () => {
    const store = createGameProjectStore(createEditorStressProject(98));
    const durations: number[] = [];
    for (let index = 0; index < 7; index += 1) {
      const startedAt = performance.now();
      store.update((project) => moveObject(project, 'library', 'stressObject1', index % 2, index % 3));
      if (index >= 2) durations.push(performance.now() - startedAt);
    }
    const slowest = Math.max(...durations);
    expect(slowest).toBeLessThan(100);
  });
});
