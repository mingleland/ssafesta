import { describe, expect, it } from 'vitest';
import {
  GameProjectContractError,
  isGameProject,
  parseGameProject,
  type GameProject,
} from '../../contracts/gameProject.ts';
import {
  cloneMinimalGameProject,
  minimalGameProject,
  type DeepMutable,
} from '../fixtures/minimalGameProject.ts';

const expectContractError = (input: unknown, code: string): void => {
  try {
    parseGameProject(input);
    throw new Error(`expected ${code}`);
  } catch (error) {
    expect(error).toBeInstanceOf(GameProjectContractError);
    expect((error as GameProjectContractError).code).toBe(code);
  }
};

const topDownScene = (project: DeepMutable<GameProject>) => {
  const scene = project.scenes.find((candidate) => candidate.type === 'TOP_DOWN');
  if (scene?.type !== 'TOP_DOWN') throw new Error('TOP_DOWN fixture scene missing');
  return scene;
};

const overlayScene = (project: DeepMutable<GameProject>) => {
  const scene = project.scenes.find(
    (candidate) => candidate.type === 'DIALOGUE' && candidate.presentation === 'OVERLAY',
  );
  if (scene?.type !== 'DIALOGUE') throw new Error('OVERLAY fixture scene missing');
  return scene;
};

describe('GameProject v1 contract', () => {
  it('accepts the minimal TOP_DOWN plus DIALOGUE fixture without rewriting it', () => {
    const input = cloneMinimalGameProject();
    const parsed = parseGameProject(input);

    expect(parsed).toBe(input);
    expect(isGameProject(input)).toBe(true);
    expect(parsed).toEqual(minimalGameProject);
  });

  it.each([
    {
      name: 'unsupported schema',
      code: 'GAME_SCHEMA_UNSUPPORTED',
      input: () => ({ ...cloneMinimalGameProject(), schemaVersion: '2.0.0' }),
    },
    {
      name: 'missing start scene',
      code: 'START_SCENE_NOT_FOUND',
      input: () => ({ ...cloneMinimalGameProject(), startSceneId: 'missing' }),
    },
    {
      name: 'duplicate object id',
      code: 'DUPLICATE_OBJECT_ID',
      input: () => {
        const project = cloneMinimalGameProject();
        const scene = topDownScene(project);
        const duplicate = scene.objects[0];
        if (duplicate === undefined) throw new Error('object fixture missing');
        scene.objects.push(structuredClone(duplicate));
        return project;
      },
    },
    {
      name: 'invalid dialogue target',
      code: 'DIALOGUE_TARGET_INVALID',
      input: () => {
        const project = cloneMinimalGameProject();
        const event = topDownScene(project).events.find((candidate) => candidate.id === 'openDoor');
        const action = event?.actions.find((candidate) => candidate.type === 'SHOW_DIALOGUE');
        if (action?.type !== 'SHOW_DIALOGUE') throw new Error('SHOW_DIALOGUE fixture missing');
        action.sceneId = 'room';
        return project;
      },
    },
    {
      name: 'dialogue next with terminal action',
      code: 'DIALOGUE_NEXT_WITH_TERMINAL_ACTION',
      input: () => {
        const project = cloneMinimalGameProject();
        const choice = overlayScene(project).nodes[0]?.choices[0];
        if (choice === undefined) throw new Error('choice fixture missing');
        choice.nextNodeId = 'opened';
        return project;
      },
    },
    {
      name: 'invalid close context',
      code: 'DIALOGUE_CLOSE_CONTEXT_INVALID',
      input: () => {
        const project = cloneMinimalGameProject();
        const event = topDownScene(project).events[0];
        if (event === undefined) throw new Error('event fixture missing');
        event.actions = [{ type: 'CLOSE_DIALOGUE' }];
        return project;
      },
    },
  ])('rejects $name with the shared error code', ({ input, code }) => {
    expectContractError(input(), code);
  });

  it('rejects unknown fields instead of dropping or correcting them', () => {
    expectContractError({ ...cloneMinimalGameProject(), unexpected: true }, 'GAME_PROJECT_INVALID');
  });

  it('exposes a non-throwing type guard', () => {
    expect(isGameProject({ schemaVersion: '1.0.0' })).toBe(false);
  });
});
