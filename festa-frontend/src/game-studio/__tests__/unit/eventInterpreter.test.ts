import { describe, expect, it } from 'vitest';
import { parseGameProject } from '../../contracts/gameProject.ts';
import { dispatchTrigger, startRuntimeSession } from '../../core/runEvent.ts';
import {
  cloneMinimalGameProject,
  minimalGameProject,
} from '../fixtures/minimalGameProject.ts';

describe('condition and action interpreter', () => {
  it('applies matching events in order without mutating the previous state', () => {
    const initial = startRuntimeSession(minimalGameProject);
    const afterPickup = dispatchTrigger(
      minimalGameProject,
      initial,
      { type: 'ON_ENTER', targetId: 'roomKey' },
    );

    expect([...initial.inventory]).toEqual([]);
    expect(initial.objectVisibility.roomKey).toBe(true);
    expect([...afterPickup.inventory]).toEqual(['key']);
    expect(afterPickup.objectVisibility.roomKey).toBe(false);
    expect(afterPickup).not.toBe(initial);
    expect(afterPickup.objectVisibility).not.toBe(initial.objectVisibility);
  });

  it('evaluates each later event against the latest state and stops on a terminal action', () => {
    let state = startRuntimeSession(minimalGameProject);
    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_ENTER', targetId: 'roomKey' },
    );
    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_INTERACT', targetId: 'exitDoor' },
    );

    expect(state.variables.doorOpened).toBe(true);
    expect(state.currentSceneId).toBe('room');
    expect(state.activeDialogueSceneId).toBe('doorHint');
    expect(state.currentDialogueNodeId).toBe('opened');
  });

  it('isolates invalid input to a FAILED runtime session', () => {
    let state = startRuntimeSession(minimalGameProject);
    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_ENTER', targetId: 'roomKey' },
    );
    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_ENTER', targetId: 'roomKey' },
    );

    expect(state.status).toBe('FAILED');
    expect(state.failure?.code).toBe('INPUT_TARGET_HIDDEN');
  });

  it('fails only the runtime session when a dispatch exceeds the 64-action budget', () => {
    const fixture = cloneMinimalGameProject();
    const room = fixture.scenes.find((scene) => scene.type === 'TOP_DOWN');
    if (room?.type !== 'TOP_DOWN') throw new Error('TOP_DOWN fixture scene missing');
    room.events.push(...Array.from({ length: 65 }, (_, index) => ({
      id: `budget${index}`,
      trigger: { type: 'ON_SCENE_START' as const },
      conditions: [],
      actions: [{ type: 'GIVE_ITEM' as const, itemId: 'key' }],
    })));
    const project = parseGameProject(fixture);

    const state = startRuntimeSession(project);

    expect(state.status).toBe('FAILED');
    expect(state.failure?.code).toBe('ACTION_BUDGET_EXCEEDED');
    expect(minimalGameProject.revision).toBe(0);
  });
});
