import { describe, expect, it } from 'vitest';
import { parseGameProject, type GameProject } from '../../contracts/gameProject.ts';
import { applyAction, createRuntimeExecutionContext } from '../../core/applyAction.ts';
import { dispatchTrigger, startRuntimeSession } from '../../core/runEvent.ts';
import { createRuntimeSessionState } from '../../core/runtimeState.ts';
import {
  chooseDialogueChoice,
  getActiveDialogue,
  getAvailableDialogueChoices,
} from '../../runtime/dialogue/dialogueRunner.ts';
import {
  cloneMinimalGameProject,
  minimalGameProject,
  type DeepMutable,
} from '../fixtures/minimalGameProject.ts';

const openDoorOverlay = (project: GameProject) => {
  let state = startRuntimeSession(project);
  state = dispatchTrigger(project, state, { type: 'ON_ENTER', targetId: 'roomKey' });
  return dispatchTrigger(project, state, { type: 'ON_INTERACT', targetId: 'exitDoor' });
};

const overlayScene = (project: DeepMutable<GameProject>) => {
  const scene = project.scenes.find(
    (candidate) => candidate.type === 'DIALOGUE' && candidate.presentation === 'OVERLAY',
  );
  if (scene?.type !== 'DIALOGUE') throw new Error('OVERLAY fixture scene missing');
  return scene;
};

describe('dialogue runner', () => {
  it('closes an OVERLAY dialogue and preserves its calling world state', () => {
    const overlayState = openDoorOverlay(minimalGameProject);

    expect(getActiveDialogue(minimalGameProject, overlayState)?.node.id).toBe('opened');
    expect(getAvailableDialogueChoices(minimalGameProject, overlayState).map((choice) => choice.id))
      .toEqual(['continue']);

    const closed = chooseDialogueChoice(minimalGameProject, overlayState, 'continue');

    expect(closed).toMatchObject({
      status: 'PLAYING',
      currentSceneId: 'room',
      activeDialogueSceneId: null,
      currentDialogueNodeId: null,
      variables: { doorOpened: true },
    });
    expect([...closed.inventory]).toEqual(['key']);
  });

  it('transitions to a FULL_SCREEN dialogue and completes the game', () => {
    let state = openDoorOverlay(minimalGameProject);
    state = chooseDialogueChoice(minimalGameProject, state, 'continue');
    state = dispatchTrigger(
      minimalGameProject,
      state,
      { type: 'ON_INTERACT', targetId: 'exitDoor' },
    );

    expect(state).toMatchObject({
      status: 'PLAYING',
      currentSceneId: 'ending',
      activeDialogueSceneId: null,
      currentDialogueNodeId: 'success',
    });

    state = chooseDialogueChoice(minimalGameProject, state, 'finish');
    expect(state.status).toBe('COMPLETED');
    expect(state.currentSceneId).toBe('ending');
  });

  it('moves to nextNodeId inside the same dialogue without changing the world scene', () => {
    const fixture = cloneMinimalGameProject();
    const overlay = overlayScene(fixture);
    const firstChoice = overlay.nodes[0]?.choices[0];
    if (firstChoice === undefined) throw new Error('choice fixture missing');
    firstChoice.actions = [];
    firstChoice.nextNodeId = 'more';
    overlay.nodes.push({
      id: 'more',
      speaker: '안내',
      text: '조금 더 설명합니다.',
      choices: [],
    });
    const project = parseGameProject(fixture);

    const state = chooseDialogueChoice(project, openDoorOverlay(project), 'continue');

    expect(state.currentSceneId).toBe('room');
    expect(state.activeDialogueSceneId).toBe('doorHint');
    expect(state.currentDialogueNodeId).toBe('more');
  });

  it('filters unavailable choices and fails only the runtime session on forced selection', () => {
    const fixture = cloneMinimalGameProject();
    const choice = overlayScene(fixture).nodes[0]?.choices[0];
    if (choice === undefined) throw new Error('choice fixture missing');
    choice.conditions = [{ type: 'HAS_ITEM', itemId: 'key' }];
    const project = parseGameProject(fixture);
    const initial = createRuntimeSessionState(project);
    const overlayState = applyAction(
      project,
      initial,
      { type: 'SHOW_DIALOGUE', sceneId: 'doorHint' },
      createRuntimeExecutionContext(),
    );

    expect(getAvailableDialogueChoices(project, overlayState)).toEqual([]);
    const failed = chooseDialogueChoice(project, overlayState, 'continue');
    expect(failed.status).toBe('FAILED');
    expect(failed.failure?.code).toBe('DIALOGUE_CHOICE_UNAVAILABLE');
    expect(initial.status).toBe('PLAYING');
  });

  it('reports no active dialogue while the player is exploring TOP_DOWN', () => {
    expect(getActiveDialogue(minimalGameProject, startRuntimeSession(minimalGameProject))).toBeNull();
  });
});
