import type { DialogueChoice, DialogueScene } from '../../contracts/gameProject.ts';

export interface DialogueNodeFlow {
  readonly nodeId: string;
  readonly reachable: boolean;
  readonly hasIncompleteOutcome: boolean;
  readonly outcomes: readonly string[];
}

export interface DialogueFlowAnalysis {
  readonly nodes: readonly DialogueNodeFlow[];
  readonly reachableCount: number;
  readonly unreachableCount: number;
  readonly incompleteOutcomeCount: number;
}

const choiceTargetNodeId = (choice: DialogueChoice): string | null => choice.nextNodeId ?? null;

const choiceOutcomeLabel = (choice: DialogueChoice): string => {
  if (choice.nextNodeId !== undefined) return `대화 · ${choice.nextNodeId}`;
  const terminal = choice.actions.at(-1);
  if (terminal?.type === 'CLOSE_DIALOGUE') return '게임 화면으로 복귀';
  if (terminal?.type === 'COMPLETE_GAME') return '게임 완료';
  if (terminal?.type === 'GO_TO_SCENE') return `Scene · ${terminal.sceneId}`;
  return '결과 미설정';
};

const hasIncompleteOutcome = (choice: DialogueChoice): boolean => (
  choice.nextNodeId === undefined
  && !choice.actions.some((action) => (
    action.type === 'CLOSE_DIALOGUE'
    || action.type === 'COMPLETE_GAME'
    || action.type === 'GO_TO_SCENE'
  ))
);

export const analyzeDialogueFlow = (scene: DialogueScene): DialogueFlowAnalysis => {
  const nodeIds = new Set(scene.nodes.map((node) => node.id));
  const reachable = new Set<string>();
  const queue = nodeIds.has(scene.startNodeId) ? [scene.startNodeId] : [];

  while (queue.length > 0) {
    const nodeId = queue.shift();
    if (nodeId === undefined || reachable.has(nodeId)) continue;
    reachable.add(nodeId);
    const node = scene.nodes.find((candidate) => candidate.id === nodeId);
    if (node === undefined) continue;
    for (const choice of node.choices) {
      const targetNodeId = choiceTargetNodeId(choice);
      if (targetNodeId !== null && nodeIds.has(targetNodeId) && !reachable.has(targetNodeId)) {
        queue.push(targetNodeId);
      }
    }
  }

  const nodes = scene.nodes.map((node): DialogueNodeFlow => ({
    nodeId: node.id,
    reachable: reachable.has(node.id),
    hasIncompleteOutcome: node.choices.length === 0 || node.choices.some(hasIncompleteOutcome),
    outcomes: node.choices.length === 0 ? ['선택지 없음'] : node.choices.map(choiceOutcomeLabel),
  }));

  return {
    nodes,
    reachableCount: nodes.filter((node) => node.reachable).length,
    unreachableCount: nodes.filter((node) => !node.reachable).length,
    incompleteOutcomeCount: nodes.filter((node) => node.hasIncompleteOutcome).length,
  };
};
