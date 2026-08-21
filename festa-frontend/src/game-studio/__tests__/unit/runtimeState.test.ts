import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import { createRuntimeSessionState } from '../../core/runtimeState.ts';
import {
  cloneMinimalGameProject,
  minimalGameProject,
} from '../fixtures/minimalGameProject.ts';

describe('RuntimeSessionState', () => {
  it('initializes shared variables, inventory, and object visibility from GameProject', () => {
    const state = createRuntimeSessionState(minimalGameProject);

    expect(state).toMatchObject({
      status: 'PLAYING',
      currentSceneId: 'room',
      activeDialogueSceneId: null,
      currentDialogueNodeId: null,
      variables: { doorOpened: false },
      objectVisibility: {
        playerSpawn: true,
        roomKey: true,
        exitDoor: true,
      },
      failure: null,
    });
    expect([...state.inventory]).toEqual([]);
    expect(Object.isFrozen(state.variables)).toBe(true);
    expect(Object.isFrozen(state.objectVisibility)).toBe(true);
  });

  it('starts a FULL_SCREEN DIALOGUE at its declared start node', () => {
    const fixture = cloneMinimalGameProject();
    fixture.startSceneId = 'ending';
    const project = parseGameProject(fixture);

    expect(createRuntimeSessionState(project)).toMatchObject({
      currentSceneId: 'ending',
      activeDialogueSceneId: null,
      currentDialogueNodeId: 'success',
    });
  });
});
