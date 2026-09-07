// 실험(정식 티켓 아님) — 씬 단위 게임 흐름을 그래프로 보여주는 화면. 새 그래프 렌더링
// 라이브러리를 들이지 않고, 시작 씬으로부터의 BFS 깊이를 열(column)로 삼아 손수 배치하고
// 간선은 SVG 직선으로 그린다. 노드 크기가 고정이라 실제 DOM 측정 없이 깊이/같은 깊이 내
// 순번만으로 좌표를 계산할 수 있다 — 그래프 자체의 쓸모를 먼저 확인하기 위한 최소 구현.
import type { GameProject } from '../../contracts/gameProject.ts';
import { buildSceneGraph, computeSceneGraphDepths } from '../model/sceneGraph.ts';

interface SceneFlowGraphProps {
  readonly project: GameProject;
  readonly onSelectScene: (sceneId: string) => void;
}

const NODE_WIDTH = 168;
const NODE_HEIGHT = 60;
const COLUMN_GAP = 220;
const ROW_GAP = 84;
const PADDING = 24;

export const SceneFlowGraph = ({ project, onSelectScene }: SceneFlowGraphProps) => {
  const graph = buildSceneGraph(project);
  const depths = computeSceneGraphDepths(graph, project.startSceneId);
  const maxKnownDepth = Math.max(0, ...[...depths.values()].filter((depth) => depth >= 0));
  // 어디서도 도달할 수 없는 노드는 맨 오른쪽 별도 열에 몰아서 "미연결"임을 시각적으로도 드러낸다.
  const unreachableColumn = maxKnownDepth + 1;
  const columnOf = (depth: number) => (depth < 0 ? unreachableColumn : depth);

  const nodesByColumn = new Map<number, string[]>();
  for (const node of graph.nodes) {
    const column = columnOf(depths.get(node.id) ?? -1);
    const list = nodesByColumn.get(column) ?? [];
    list.push(node.id);
    nodesByColumn.set(column, list);
  }

  const positions = new Map<string, { x: number; y: number }>();
  for (const [column, ids] of nodesByColumn) {
    ids.forEach((id, row) => {
      positions.set(id, {
        x: PADDING + column * COLUMN_GAP,
        y: PADDING + row * ROW_GAP,
      });
    });
  }

  const columnCount = unreachableColumn + 1;
  const maxRowCount = Math.max(1, ...[...nodesByColumn.values()].map((ids) => ids.length));
  const width = PADDING * 2 + columnCount * COLUMN_GAP - (COLUMN_GAP - NODE_WIDTH);
  const height = PADDING * 2 + maxRowCount * ROW_GAP - (ROW_GAP - NODE_HEIGHT);

  return (
    <div className="gss-flow-graph" style={{ height, width: Math.max(width, NODE_WIDTH + PADDING * 2) }}>
      <svg className="gss-flow-graph-edges" height={height} width={width}>
        {graph.edges.map((edge, index) => {
          const from = positions.get(edge.from);
          const to = positions.get(edge.to);
          if (from === undefined || to === undefined) return null;
          const x1 = from.x + NODE_WIDTH;
          const y1 = from.y + NODE_HEIGHT / 2;
          const x2 = to.x;
          const y2 = to.y + NODE_HEIGHT / 2;
          return (
            <line
              className={`gss-flow-edge is-${edge.kind.toLowerCase()}`}
              key={`${edge.from}-${edge.to}-${index}`}
              markerEnd="url(#gss-flow-arrow)"
              x1={x1}
              x2={x2}
              y1={y1}
              y2={y2}
            />
          );
        })}
        <defs>
          <marker id="gss-flow-arrow" markerHeight="8" markerWidth="8" orient="auto-start-reverse" refX="7" refY="4">
            <path d="M0,0 L8,4 L0,8 Z" />
          </marker>
        </defs>
      </svg>
      {graph.nodes.map((node) => {
        const position = positions.get(node.id);
        if (position === undefined) return null;
        const depth = depths.get(node.id) ?? -1;
        const className = ['gss-flow-node',
          node.isStart ? 'is-start' : '',
          node.isEndpoint ? 'is-endpoint' : '',
          depth < 0 && !node.isEndpoint ? 'is-unreachable' : '']
          .filter(Boolean).join(' ');
        return (
          <button
            className={className}
            disabled={node.isEndpoint}
            key={node.id}
            onClick={() => onSelectScene(node.id)}
            style={{ left: position.x, top: position.y }}
            type="button"
          >
            <strong>{node.name}</strong>
            <small>{node.typeLabel}</small>
            {node.isStart && <em>START</em>}
            {depth < 0 && !node.isEndpoint && <em className="is-warning">미연결</em>}
          </button>
        );
      })}
      {graph.nodes.length === 0 && <p className="gss-help-card">씬이 없습니다.</p>}
    </div>
  );
};
