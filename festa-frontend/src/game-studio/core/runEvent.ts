import {
  findScene,
  type GameProject,
  type Trigger,
} from '../contracts/gameProject.ts';
import {
  applyAction,
  createRuntimeExecutionContext,
  isTerminalAction,
  RuntimeExecutionError,
  type RuntimeExecutionContext,
} from './applyAction.ts';
import { evaluateConditions } from './evaluateCondition.ts';
import {
  createRuntimeSessionState,
  failRuntimeSession,
  type RuntimeSessionState,
} from './runtimeState.ts';

const triggerMatches = (candidate: Trigger, dispatched: Trigger): boolean => {
  if (candidate.type !== dispatched.type) return false;
  if (candidate.type === 'ON_SCENE_START' || dispatched.type === 'ON_SCENE_START') return true;
  return candidate.targetId === dispatched.targetId;
};
const dispatchTriggerInternal = (
  project: GameProject,
  initialState: RuntimeSessionState,
  trigger: Trigger,
  context: RuntimeExecutionContext,
): RuntimeSessionState => {
  if (initialState.status !== 'PLAYING') {
    throw new RuntimeExecutionError(
      'INPUT_NOT_ALLOWED',
      `runtime status is ${initialState.status}`,
    );
  }

  const scene = findScene(project, initialState.currentSceneId);
  if (scene?.type !== 'TOP_DOWN') {
    throw new RuntimeExecutionError(
      'INPUT_NOT_ALLOWED',
      `${trigger.type} requires a TOP_DOWN scene`,
    );
  }

  if (
    trigger.type !== 'ON_SCENE_START'
    && initialState.objectVisibility[trigger.targetId] === false
  ) {
    throw new RuntimeExecutionError(
      'INPUT_TARGET_HIDDEN',
      `target is hidden: ${trigger.targetId}`,
    );
  }

  const matchingEvents = scene.events.filter((event) => triggerMatches(event.trigger, trigger));
  let state = initialState;

  for (const event of matchingEvents) {
    if (!evaluateConditions(state, event.conditions)) continue;

    for (const action of event.actions) {
      state = applyAction(project, state, action, context);
      if (action.type === 'GO_TO_SCENE') {
        const target = findScene(project, state.currentSceneId);
        if (target?.type === 'TOP_DOWN') {
          state = dispatchTriggerInternal(
            project,
            state,
            { type: 'ON_SCENE_START' },
            context,
          );
        }
      }
      if (isTerminalAction(action)) return state;
    }
  }

  return state;
};

const isolateRuntimeFailure = (
  state: RuntimeSessionState,
  error: unknown,
): RuntimeSessionState => {
  if (error instanceof RuntimeExecutionError) {
    return failRuntimeSession(state, { code: error.code, message: error.message });
  }
  throw error;
};

export const dispatchTrigger = (
  project: GameProject,
  state: RuntimeSessionState,
  trigger: Trigger,
  context: RuntimeExecutionContext = createRuntimeExecutionContext(),
): RuntimeSessionState => {
  try {
    return dispatchTriggerInternal(project, state, trigger, context);
  } catch (error) {
    return isolateRuntimeFailure(state, error);
  }
};

export const startRuntimeSession = (project: GameProject): RuntimeSessionState => {
  const state = createRuntimeSessionState(project);
  const scene = findScene(project, state.currentSceneId);
  if (scene?.type !== 'TOP_DOWN') return state;

  try {
    return dispatchTriggerInternal(
      project,
      state,
      { type: 'ON_SCENE_START' },
      createRuntimeExecutionContext(),
    );
  } catch (error) {
    return isolateRuntimeFailure(state, error);
  }
};
