import type { Condition } from '../contracts/gameProject.ts';
import type { RuntimeSessionState } from './runtimeState.ts';

export const evaluateCondition = (
  state: RuntimeSessionState,
  condition: Condition,
): boolean => {
  if (condition.type === 'HAS_ITEM') return state.inventory.has(condition.itemId);
  return state.variables[condition.variableId] === condition.value;
};

export const evaluateConditions = (
  state: RuntimeSessionState,
  conditions: readonly Condition[] = [],
): boolean => conditions.every((condition) => evaluateCondition(state, condition));
