// 실험(정식 티켓 아님) — 씬 단위 게임 흐름 그래프 데이터 모델 검증.
import { describe, expect, it } from 'vitest';
import { buildSceneGraph, computeSceneGraphDepths, COMPLETE_GAME_NODE_ID } from '../../studio/model/sceneGraph.ts';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

describe('buildSceneGraph', () => {
  it('starter project(비밀 도서관)의 씬/간선/완료 노드를 정확히 뽑는다', () => {
    const project = createStarterProject(700);
    const graph = buildSceneGraph(project);

    expect(graph.nodes.map((node) => node.id)).toEqual(
      expect.arrayContaining(['library', 'librarianDialogue', 'ending', COMPLETE_GAME_NODE_ID]),
    );
    const startNode = graph.nodes.find((node) => node.id === 'library');
    expect(startNode?.isStart).toBe(true);
    expect(startNode?.typeLabel).toBe('맵-TopDown');

    const completeNode = graph.nodes.find((node) => node.id === COMPLETE_GAME_NODE_ID);
    expect(completeNode?.isEndpoint).toBe(true);
    expect(completeNode?.isStart).toBe(false);

    // library → librarianDialogue(SHOW_DIALOGUE, 사서에게 말 걸기), library → ending
    // (GO_TO_SCENE, 잠긴 문 열기), ending → 완료(COMPLETE_GAME, 선택지 "게임 완료").
    expect(graph.edges).toEqual(expect.arrayContaining([
      { from: 'library', to: 'librarianDialogue', kind: 'SHOW_DIALOGUE' },
      { from: 'library', to: 'ending', kind: 'GO_TO_SCENE' },
      { from: 'ending', to: COMPLETE_GAME_NODE_ID, kind: 'COMPLETE_GAME' },
    ]));
    // librarianDialogue의 "알겠습니다" 선택지는 CLOSE_DIALOGUE(씬 간 이동이 아님)라 간선이 아니다.
    expect(graph.edges.some((edge) => edge.from === 'librarianDialogue')).toBe(false);
  });

  it('COMPLETE_GAME 액션이 하나도 없으면 완료 노드를 만들지 않는다', () => {
    const project = createStarterProject(701);
    const withoutCompletion = {
      ...project,
      scenes: project.scenes.map((scene) => (
        scene.type === 'DIALOGUE'
          ? { ...scene, nodes: scene.nodes.map((node) => ({ ...node, choices: [] })) }
          : { ...scene, events: [] }
      )),
    };
    const graph = buildSceneGraph(withoutCompletion);
    expect(graph.nodes.some((node) => node.id === COMPLETE_GAME_NODE_ID)).toBe(false);
    expect(graph.edges).toEqual([]);
  });
});

describe('computeSceneGraphDepths', () => {
  it('시작 씬을 0단으로 BFS 깊이를 매기고, 도달 불가 노드는 -1로 둔다', () => {
    const project = createStarterProject(702);
    const graph = buildSceneGraph(project);
    const depths = computeSceneGraphDepths(graph, project.startSceneId);

    expect(depths.get('library')).toBe(0);
    expect(depths.get('librarianDialogue')).toBe(1);
    expect(depths.get('ending')).toBe(1);
    expect(depths.get(COMPLETE_GAME_NODE_ID)).toBe(2);
  });

  it('시작 씬에서 어떤 간선으로도 닿지 않는 노드는 -1이다', () => {
    const project = createStarterProject(703);
    const isolatedId = 'isolatedScene';
    const withIsolatedScene = {
      ...project,
      scenes: [
        ...project.scenes,
        {
          id: isolatedId,
          type: 'TOP_DOWN' as const,
          name: '외딴 맵',
          width: 8,
          height: 8,
          tileLayers: [],
          objects: [{ id: 'isolatedSpawnDecoy', preset: 'PLAYER_SPAWN' as const, position: { x: 4, y: 4 }, visible: true, components: [] }],
          events: [],
        },
      ],
    };
    const graph = buildSceneGraph(withIsolatedScene);
    const depths = computeSceneGraphDepths(graph, project.startSceneId);
    expect(depths.get(isolatedId)).toBe(-1);
  });
});
