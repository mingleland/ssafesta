import {
  findScene,
  type DialogueChoice,
  type DialogueNode,
  type DialogueScene,
  type GameProject,
} from '../../contracts/gameProject.ts';
import {
  applyAction,
  createRuntimeExecutionContext,
  isTerminalAction,
  RuntimeExecutionError,
  type RuntimeExecutionContext,
} from '../../core/applyAction.ts';
import { evaluateConditions } from '../../core/evaluateCondition.ts';
import { dispatchTrigger } from '../../core/runEvent.ts';
import {
  failRuntimeSession,
  type RuntimeSessionState,
} from '../../core/runtimeState.ts';

export interface ActiveDialogue {
  readonly scene: DialogueScene;
  readonly node: DialogueNode;
}

const resolveActiveDialogue = (
  project: GameProject,
  state: RuntimeSessionState,
): ActiveDialogue => {
  const sceneId = state.activeDialogueSceneId ?? state.currentSceneId;
  const scene = findScene(project, sceneId);
  if (scene?.type !== 'DIALOGUE') {
    throw new RuntimeExecutionError('INPUT_NOT_ALLOWED', 'dialogue input requires a DIALOGUE scene');
  }
  const node = scene.nodes.find((candidate) => candidate.id === state.currentDialogueNodeId);
  if (node === undefined) {
    throw new RuntimeExecutionError(
      'DIALOGUE_NODE_NOT_FOUND',
      `unknown dialogue node: ${String(state.currentDialogueNodeId)}`,
    );
  }
  return { scene, node };
};
export const getActiveDialogue = (
  project: GameProject,
  state: RuntimeSessionState,
): ActiveDialogue | null => {
  try {
    return resolveActiveDialogue(project, state);
  } catch (error) {
    if (error instanceof RuntimeExecutionError) return null;
    throw error;
  }
};

export const getAvailableDialogueChoices = (
  project: GameProject,
  state: RuntimeSessionState,
): readonly DialogueChoice[] => {
  const active = resolveActiveDialogue(project, state);
  return active.node.choices.filter((choice) => evaluateConditions(state, choice.conditions));
};

const failChoice = (
  state: RuntimeSessionState,
  error: RuntimeExecutionError,
): RuntimeSessionState => failRuntimeSession(state, {
  code: error.code,
  message: error.message,
});

export const chooseDialogueChoice = (
  project: GameProject,
  initialState: RuntimeSessionState,
  choiceId: string,
  context: RuntimeExecutionContext = createRuntimeExecutionContext(),
): RuntimeSessionState => {
  try {
    if (initialState.status !== 'PLAYING') {
      throw new RuntimeExecutionError(
        'INPUT_NOT_ALLOWED',
        `runtime status is ${initialState.status}`,
      );
    }

    const active = resolveActiveDialogue(project, initialState);
    const choice = active.node.choices.find((candidate) => candidate.id === choiceId);
    if (choice === undefined) {
      throw new RuntimeExecutionError(
        'DIALOGUE_CHOICE_NOT_FOUND',
        `unknown choice: ${choiceId}`,
      );
    }
    if (!evaluateConditions(initialState, choice.conditions)) {
      throw new RuntimeExecutionError(
        'DIALOGUE_CHOICE_UNAVAILABLE',
        `choice conditions failed: ${choiceId}`,
      );
    }

    let state = initialState;
    for (const action of choice.actions) {
      state = applyAction(project, state, action, context);
      if (action.type === 'GO_TO_SCENE') {
        const target = findScene(project, state.currentSceneId);
        if (target !== undefined && target.type !== 'DIALOGUE') {
          state = dispatchTrigger(
            project,
            state,
            { type: 'ON_SCENE_START' },
            context,
          );
        }
      }
      if (isTerminalAction(action)) return state;
    }

    if (choice.nextNodeId !== undefined) {
      return { ...state, currentDialogueNodeId: choice.nextNodeId };
    }
    return state;
  } catch (error) {
    if (error instanceof RuntimeExecutionError) return failChoice(initialState, error);
    throw error;
  }
};
