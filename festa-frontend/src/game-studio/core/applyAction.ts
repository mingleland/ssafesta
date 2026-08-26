import {
  findScene,
  type Action,
  type GameProject,
  type Scalar,
  type VariableType,
} from '../contracts/gameProject.ts';
import type { RuntimeSessionState } from './runtimeState.ts';

export const ACTION_BUDGET_LIMIT = 64;
export const TRANSITION_DEPTH_LIMIT = 8;

export interface RuntimeExecutionContext {
  actionCount: number;
  transitionDepth: number;
}

export class RuntimeExecutionError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'RuntimeExecutionError';
    this.code = code;
  }
}

export const createRuntimeExecutionContext = (): RuntimeExecutionContext => ({
  actionCount: 0,
  transitionDepth: 0,
});

const runtimeFail = (code: string, message: string): never => {
  throw new RuntimeExecutionError(code, message);
};

const scalarMatchesType = (value: Scalar, type: VariableType): boolean => {
  if (type === 'BOOLEAN') return typeof value === 'boolean';
  if (type === 'INTEGER') return Number.isInteger(value);
  return typeof value === 'string';
};

const countAction = (context: RuntimeExecutionContext): void => {
  context.actionCount += 1;
  if (context.actionCount > ACTION_BUDGET_LIMIT) {
    runtimeFail(
      'ACTION_BUDGET_EXCEEDED',
      `action count exceeds ${ACTION_BUDGET_LIMIT}`,
    );
  }
};

const transitionTo = (
  project: GameProject,
  state: RuntimeSessionState,
  sceneId: string,
  context: RuntimeExecutionContext,
): RuntimeSessionState => {
  context.transitionDepth += 1;
  if (context.transitionDepth > TRANSITION_DEPTH_LIMIT) {
    runtimeFail(
      'TRANSITION_DEPTH_EXCEEDED',
      `transition depth exceeds ${TRANSITION_DEPTH_LIMIT}`,
    );
  }

  const target = findScene(project, sceneId);
  if (target === undefined) {
    throw new RuntimeExecutionError('SCENE_REFERENCE_NOT_FOUND', `unknown scene: ${sceneId}`);
  }
  return {
    ...state,
    currentSceneId: target.id,
    activeDialogueSceneId: null,
    currentDialogueNodeId: target.type === 'DIALOGUE' ? target.startNodeId : null,
  };
};

export const isTerminalAction = (action: Action): boolean => (
  action.type === 'SHOW_DIALOGUE'
  || action.type === 'CLOSE_DIALOGUE'
  || action.type === 'GO_TO_SCENE'
  || action.type === 'COMPLETE_GAME'
);

export const applyAction = (
  project: GameProject,
  state: RuntimeSessionState,
  action: Action,
  context: RuntimeExecutionContext,
): RuntimeSessionState => {
  countAction(context);

  switch (action.type) {
    case 'SET_VARIABLE': {
      const definition = project.variables.find((variable) => variable.id === action.variableId);
      if (definition === undefined) {
        throw new RuntimeExecutionError(
          'VARIABLE_REFERENCE_NOT_FOUND',
          `unknown variable: ${action.variableId}`,
        );
      }
      if (!scalarMatchesType(action.value, definition.type)) {
        runtimeFail('VARIABLE_VALUE_TYPE_INVALID', `${action.variableId} assignment type mismatch`);
      }
      return {
        ...state,
        variables: Object.freeze({ ...state.variables, [action.variableId]: action.value }),
      };
    }
    case 'GIVE_ITEM':
      return { ...state, inventory: new Set([...state.inventory, action.itemId]) };
    case 'REMOVE_ITEM': {
      const inventory = new Set(state.inventory);
      inventory.delete(action.itemId);
      return { ...state, inventory };
    }
    case 'SHOW_OBJECT':
    case 'HIDE_OBJECT':
      return {
        ...state,
        objectVisibility: Object.freeze({
          ...state.objectVisibility,
          [action.objectId]: action.type === 'SHOW_OBJECT',
        }),
      };
    case 'SHOW_DIALOGUE': {
      const dialogue = findScene(project, action.sceneId);
      if (dialogue?.type !== 'DIALOGUE') {
        throw new RuntimeExecutionError(
          'DIALOGUE_TARGET_INVALID',
          `not a DIALOGUE scene: ${action.sceneId}`,
        );
      }
      if (dialogue.presentation !== 'OVERLAY') {
        runtimeFail(
          'DIALOGUE_PRESENTATION_INVALID',
          `SHOW_DIALOGUE requires OVERLAY presentation: ${action.sceneId}`,
        );
      }
      return {
        ...state,
        activeDialogueSceneId: dialogue.id,
        currentDialogueNodeId: dialogue.startNodeId,
      };
    }
    case 'CLOSE_DIALOGUE':
      if (state.activeDialogueSceneId === null) {
        runtimeFail('DIALOGUE_CLOSE_CONTEXT_INVALID', 'no active OVERLAY dialogue');
      }
      return {
        ...state,
        activeDialogueSceneId: null,
        currentDialogueNodeId: null,
      };
    case 'GO_TO_SCENE':
      return transitionTo(project, state, action.sceneId, context);
    case 'COMPLETE_GAME':
      return {
        ...state,
        status: 'COMPLETED',
        activeDialogueSceneId: null,
      };
  }
};
