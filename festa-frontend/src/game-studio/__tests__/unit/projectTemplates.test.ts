import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import { createProjectFromTemplate, PROJECT_TEMPLATES } from '../../studio/model/projectTemplates.ts';

describe('Game Studio project templates', () => {
  it.each(PROJECT_TEMPLATES)('creates a valid, immediately playable $title project', (template) => {
    const project = createProjectFromTemplate(123, template.id);
    expect(parseGameProject(project)).toBe(project);
    expect(project.title.length).toBeGreaterThan(0);
    expect(template.previewUrl).toMatch(/\.webp$/);
    expect(template.estimatedMinutes).toBeGreaterThanOrEqual(10);
    const start = project.scenes.find((scene) => scene.id === project.startSceneId);
    expect(start).toBeDefined();
    if (!start) throw new Error('template start scene must exist');
    expect(start.type).toBe(template.runtimeMode);
    if (start.type !== 'DIALOGUE') {
      expect(start.objects.some((object) => object.preset === 'PLAYER_SPAWN')).toBe(true);
      expect(start.events.length).toBeGreaterThan(0);
    }
  });

  it('ships six distinct creation flows without adding backend-specific document shapes', () => {
    expect(PROJECT_TEMPLATES).toHaveLength(6);
    expect(new Set(PROJECT_TEMPLATES.map((template) => template.runtimeMode))).toEqual(new Set(['TOP_DOWN', 'PLATFORMER']));
  });
});
