import { describe, expect, it } from 'vitest';
import { dispatchTrigger, startRuntimeSession } from '../../core/runEvent.ts';
import { chooseDialogueChoice } from '../../runtime/dialogue/dialogueRunner.ts';
import { minimalGameProject } from '../fixtures/minimalGameProject.ts';

describe('minimal Game Studio runtime flow', () => {
  it('runs key → door → overlay → full-screen dialogue → complete without Unity', () => {
    let state = startRuntimeSession(minimalGameProject);
    expect(state.currentSceneId).toBe('room');

    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_ENTER', targetId: 'roomKey' },
    );
    expect([...state.inventory]).toEqual(['key']);
    expect(state.objectVisibility.roomKey).toBe(false);

    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_INTERACT', targetId: 'exitDoor' },
    );
    expect(state.activeDialogueSceneId).toBe('doorHint');
    expect(state.variables.doorOpened).toBe(true);

    state = chooseDialogueChoice(minimalGameProject, state, 'continue');
    expect(state.activeDialogueSceneId).toBeNull();
    expect(state.currentSceneId).toBe('room');

    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_INTERACT', targetId: 'exitDoor' },
    );
    expect(state.currentSceneId).toBe('ending');
    expect(state.currentDialogueNodeId).toBe('success');

    state = chooseDialogueChoice(minimalGameProject, state, 'finish');
    expect(state.status).toBe('COMPLETED');
    expect(state.failure).toBeNull();
  });
});
