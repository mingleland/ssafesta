import { describe, expect, it } from 'vitest';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';
import { analyzeProjectHealth } from '../../studio/model/projectHealth.ts';

describe('project health analysis', () => {
  it('reports the complete starter journey as ready', () => {
    const checks = analyzeProjectHealth(createStarterProject(401));

    expect(checks).toHaveLength(6);
    expect(checks.every((check) => check.status === 'PASS')).toBe(true);
  });

  it('surfaces broken interactions, dialogue branches, scene links and assets without changing the contract', () => {
    const project = createStarterProject(402);
    const library = project.scenes.find((scene) => scene.id === 'library');
    const dialogue = project.scenes.find((scene) => scene.id === 'librarianDialogue');
    if (library?.type !== 'TOP_DOWN' || dialogue?.type !== 'DIALOGUE') throw new Error('starter fixture missing');

    const checks = analyzeProjectHealth({
      ...project,
      assets: project.assets.map((asset) => asset.id === 'npcImage'
        ? { ...asset, source: 'asset://local/test-npc' }
        : asset),
      scenes: project.scenes.map((scene) => {
        if (scene.id === library.id) {
          return { ...library, events: library.events.filter((event) => event.id !== 'talkToLibrarian') };
        }
        if (scene.id === dialogue.id) {
          return {
            ...dialogue,
            nodes: [
              ...dialogue.nodes,
              { id: 'orphan', speaker: '사서', text: '숨은 대사', choices: [] },
            ],
          };
        }
        return scene;
      }),
    });

    expect(checks.find((check) => check.id === 'INTERACTION')?.status).toBe('WARNING');
    expect(checks.find((check) => check.id === 'DIALOGUE')?.status).toBe('WARNING');
    expect(checks.find((check) => check.id === 'SCENE_FLOW')?.status).toBe('WARNING');
    expect(checks.find((check) => check.id === 'ASSET')?.status).toBe('BLOCKER');
  });
});
