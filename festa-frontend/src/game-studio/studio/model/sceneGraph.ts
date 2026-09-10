// 실험(정식 티켓 아님) — 씬 단위 게임 흐름 그래프. GO_TO_SCENE/SHOW_DIALOGUE 액션을
// 간선으로 삼아 씬↔씬 연결을 뽑아낸다(projectHealth.ts의 reachableSceneIds가 이미 하는
// BFS 탐색과 같은 소스지만, 여기서는 "도달 가능 여부"가 아니라 실제 간선 목록 자체가
// 필요해서 별도로 뽑는다). COMPLETE_GAME 액션이 하나라도 있으면 가상의 "게임 완료" 노드를
// 하나 추가해서 그쪽을 가리키게 한다 — 씬이 아니라 게임의 종료 지점이라 실제 씬 노드와는
// 구분해서 표시할 수 있게 isEndpoint 플래그를 둔다.
import type { Action, GameProject } from '../../contracts/gameProject.ts';
import { describeSceneType } from './authoringRegistry.ts';

export const COMPLETE_GAME_NODE_ID = '__COMPLETE__';

export interface SceneGraphNode {
  readonly id: string;
  readonly name: string;
  readonly typeLabel: string;
  readonly isStart: boolean;
  readonly isEndpoint: boolean;
}

export interface SceneGraphEdge {
  readonly from: string;
  readonly to: string;
  readonly kind: 'GO_TO_SCENE' | 'SHOW_DIALOGUE' | 'COMPLETE_GAME';
}

export interface SceneGraph {
  readonly nodes: readonly SceneGraphNode[];
  readonly edges: readonly SceneGraphEdge[];
}

const sceneActionLists = (scene: GameProject['scenes'][number]): readonly (readonly Action[])[] => (
  scene.type === 'DIALOGUE'
    ? scene.nodes.flatMap((node) => node.choices.map((choice) => choice.actions))
    : scene.events.map((event) => event.actions)
);

export const buildSceneGraph = (project: GameProject): SceneGraph => {
  const nodes: SceneGraphNode[] = project.scenes.map((scene) => ({
    id: scene.id,
    name: scene.name,
    typeLabel: describeSceneType(scene),
    isStart: scene.id === project.startSceneId,
    isEndpoint: false,
  }));

  const edges: SceneGraphEdge[] = [];
  let hasCompletionEdge = false;
  for (const scene of project.scenes) {
    for (const actions of sceneActionLists(scene)) {
      for (const action of actions) {
        if (action.type === 'GO_TO_SCENE' || action.type === 'SHOW_DIALOGUE') {
          edges.push({ from: scene.id, to: action.sceneId, kind: action.type });
        } else if (action.type === 'COMPLETE_GAME') {
          edges.push({ from: scene.id, to: COMPLETE_GAME_NODE_ID, kind: 'COMPLETE_GAME' });
          hasCompletionEdge = true;
        }
      }
    }
  }

  if (hasCompletionEdge) {
    nodes.push({
      id: COMPLETE_GAME_NODE_ID,
      name: '게임 완료',
      typeLabel: '완료',
      isStart: false,
      isEndpoint: true,
    });
  }

  return { nodes, edges };
};

// BFS 깊이 기반 배치 — 시작 씬을 0단으로 두고, 간선을 따라 갈 수 있는 가장 얕은 깊이를
// 각 노드에 배정한다(순환이 있어도 무한루프에 빠지지 않도록 방문 처리). 어디서도 도달할
// 수 없는 노드는 별도의 "미연결" 단(-1)에 모아 그래프 아래쪽에 몰아 보여줄 수 있게 한다.
export const computeSceneGraphDepths = (
  graph: SceneGraph,
  startSceneId: string,
): ReadonlyMap<string, number> => {
  const depths = new Map<string, number>();
  const outgoing = new Map<string, string[]>();
  for (const edge of graph.edges) {
    const list = outgoing.get(edge.from) ?? [];
    list.push(edge.to);
    outgoing.set(edge.from, list);
  }
  const queue: string[] = [];
  if (graph.nodes.some((node) => node.id === startSceneId)) {
    depths.set(startSceneId, 0);
    queue.push(startSceneId);
  }
  while (queue.length > 0) {
    const current = queue.shift();
    if (current === undefined) continue;
    const currentDepth = depths.get(current) ?? 0;
    for (const next of outgoing.get(current) ?? []) {
      if (depths.has(next)) continue;
      depths.set(next, currentDepth + 1);
      queue.push(next);
    }
  }
  for (const node of graph.nodes) {
    if (!depths.has(node.id)) depths.set(node.id, -1);
  }
  return depths;
};
