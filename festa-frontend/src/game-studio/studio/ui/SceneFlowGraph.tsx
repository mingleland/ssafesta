// 실험(정식 티켓 아님) — 씬 단위 게임 흐름을 그래프로 보여주는 화면. 새 그래프 렌더링
// 라이브러리를 들이지 않고, 시작 씬으로부터의 BFS 깊이를 열(column)로 삼아 손수 배치하고
// 간선은 SVG 직선으로 그린다. 노드 크기가 고정이라 실제 DOM 측정 없이 깊이/같은 깊이 내
// 순번만으로 좌표를 계산할 수 있다 — 그래프 자체의 쓸모를 먼저 확인하기 위한 최소 구현.
//
// S15P21A604-510 — 배경 드래그(팬)/휠(커서 위치 기준 확대·축소) 지원. FloatingPanel의
// pointerdown~pointerup + setPointerCapture 드래그 패턴을 그대로 재사용한다. 노드
// (.gss-flow-node) 위에서 시작된 pointerdown은 팬으로 잡지 않는다 — 안 그러면 노드를
// 클릭하려는 손짓이 화면 이동으로 오인되어 클릭이 씹힌다.
import { useRef, useState, type PointerEvent as ReactPointerEvent, type WheelEvent as ReactWheelEvent } from 'react';
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

const MIN_SCALE = 0.5;
const MAX_SCALE = 2;
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

export const SceneFlowGraph = ({ project, onSelectScene }: SceneFlowGraphProps) => {
  const [view, setView] = useState({ x: 0, y: 0, scale: 1 });
  const [isPanning, setIsPanning] = useState(false);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{ startX: number; startY: number; startViewX: number; startViewY: number } | null>(null);

  const startPan = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.target instanceof Element && event.target.closest('.gss-flow-node') !== null) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { startX: event.clientX, startY: event.clientY, startViewX: view.x, startViewY: view.y };
    setIsPanning(true);
  };
  const handlePanMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (drag === null) return;
    setView((current) => ({
      ...current,
      x: drag.startViewX + (event.clientX - drag.startX),
      y: drag.startViewY + (event.clientY - drag.startY),
    }));
  };
  const stopPan = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (dragRef.current === null) return;
    dragRef.current = null;
    setIsPanning(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };

  // 커서 위치 아래의 "콘텐츠 좌표"가 확대·축소 뒤에도 화면상 같은 자리에 남도록 view.x/y를
  // 함께 보정한다 — 그냥 scale만 바꾸면 항상 (0,0) 기준으로 커지고 작아져서 커서가 가리키던
  // 지점이 화면 밖으로 튀어 나간다.
  const handleWheel = (event: ReactWheelEvent<HTMLDivElement>) => {
    const viewport = viewportRef.current;
    if (viewport === null) return;
    event.preventDefault();
    const rect = viewport.getBoundingClientRect();
    const cursorX = event.clientX - rect.left;
    const cursorY = event.clientY - rect.top;
    setView((current) => {
      const factor = event.deltaY < 0 ? 1.1 : 1 / 1.1;
      const nextScale = clamp(current.scale * factor, MIN_SCALE, MAX_SCALE);
      const contentX = (cursorX - current.x) / current.scale;
      const contentY = (cursorY - current.y) / current.scale;
      return { scale: nextScale, x: cursorX - contentX * nextScale, y: cursorY - contentY * nextScale };
    });
  };

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
    <div
      className={`gss-flow-graph-viewport${isPanning ? ' is-panning' : ''}`}
      onPointerDown={startPan}
      onPointerMove={handlePanMove}
      onPointerUp={stopPan}
      onPointerCancel={stopPan}
      onWheel={handleWheel}
      ref={viewportRef}
    >
      <div
        className="gss-flow-graph"
        style={{
          height,
          transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})`,
          transformOrigin: '0 0',
          width: Math.max(width, NODE_WIDTH + PADDING * 2),
        }}
      >
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
    </div>
  );
};
