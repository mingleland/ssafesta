import { describe, expect, it } from 'vitest';
import type { DialogueScene } from '../../contracts/gameProject.ts';
import { analyzeDialogueFlow } from '../../studio/model/dialogueFlow.ts';

const scene: DialogueScene = {
  id: 'conversation',
  type: 'DIALOGUE',
  name: '분기 대화',
  presentation: 'FULL_SCREEN',
  startNodeId: 'start',
  nodes: [
    {
      id: 'start',
      speaker: '안내',
      text: '어디로 갈까요?',
      choices: [
        { id: 'continue', text: '계속', nextNodeId: 'ending', actions: [] },
        { id: 'finish', text: '완료', actions: [{ type: 'COMPLETE_GAME' }] },
      ],
    },
    {
      id: 'ending',
      speaker: '안내',
      text: '도착했습니다.',
      choices: [{ id: 'exit', text: '나가기', actions: [{ type: 'GO_TO_SCENE', sceneId: 'world' }] }],
    },
    {
      id: 'orphan',
      speaker: '안내',
      text: '연결되지 않았습니다.',
      choices: [],
    },
  ],
};

describe('dialogue flow analysis', () => {
  it('finds reachable nodes, unreachable nodes and incomplete outcomes', () => {
    const result = analyzeDialogueFlow(scene);

    expect(result.reachableCount).toBe(2);
    expect(result.unreachableCount).toBe(1);
    expect(result.incompleteOutcomeCount).toBe(1);
    expect(result.nodes.find((node) => node.nodeId === 'start')?.outcomes).toEqual([
      '대화 · ending',
      '게임 완료',
    ]);
    expect(result.nodes.find((node) => node.nodeId === 'orphan')).toMatchObject({
      reachable: false,
      hasIncompleteOutcome: true,
      outcomes: ['선택지 없음'],
    });
  });
});
