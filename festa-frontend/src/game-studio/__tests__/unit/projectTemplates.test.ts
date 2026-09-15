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

  it('uses six structurally distinct playable blueprints instead of title-only variants', () => {
    const profiles = PROJECT_TEMPLATES.map((template) => {
      const project = createProjectFromTemplate(123, template.id);
      const sceneTypes = project.scenes.map((scene) => scene.type).sort().join(',');
      const presets = project.scenes.flatMap((scene) => scene.type === 'DIALOGUE' ? [] : scene.objects.map((object) => object.preset)).sort().join(',');
      const actions = project.scenes.flatMap((scene) => scene.type === 'DIALOGUE'
        ? scene.nodes.flatMap((node) => node.choices.flatMap((choice) => choice.actions.map((action) => action.type)))
        : scene.events.flatMap((event) => event.actions.map((action) => action.type))).sort().join(',');
      return `${sceneTypes}|${presets}|${actions}`;
    });
    expect(new Set(profiles)).toHaveLength(PROJECT_TEMPLATES.length);

    const story = createProjectFromTemplate(123, 'STORY');
    expect(story.scenes.filter((scene) => scene.type === 'DIALOGUE')).toHaveLength(4);
    expect(story.scenes.some((scene) => scene.type === 'DIALOGUE' && scene.nodes.some((node) => (
      node.choices.some((choice) => choice.actions.some((action) => action.type === 'SET_VARIABLE'))
    )))).toBe(true);

    const escape = createProjectFromTemplate(123, 'ESCAPE');
    expect(escape.scenes.filter((scene) => scene.type === 'TOP_DOWN')).toHaveLength(2);
    expect(escape.scenes.some((scene) => scene.type !== 'DIALOGUE' && scene.events.some((event) => (
      event.actions.some((action) => action.type === 'SHOW_OBJECT')
    )))).toBe(true);
  });
});
