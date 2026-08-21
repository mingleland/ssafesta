// 부스를 위에서 내려다보는 2D SVG 편집 캔버스 — 좌표는 처음부터 미터, 부호 반전은 coords.ts 한 곳뿐
// 드래그 중에는 로컬 상태만 바뀌고 서버 요청이 없다 (docs/10 §17)
import { useRef, useState } from 'react';
import { PX_PER_M } from '../../../shared/config/studio';
import type { LayoutObject } from '../../../entities/layout/types';
import { clampToBooth, screenYToWorldZ, snap, worldZToScreenY } from '../lib/coords';

interface Props {
  objects: LayoutObject[];
  selectedObjectId: string | null;
  bounds: { width: number; depth: number };
  onSelect: (objectId: string | null) => void;
  onMove: (objectId: string, x: number, z: number) => void;
}

const OBJECT_SIZE_M = 0.4; // MVP 표시용 고정 크기 — 실측 bounds는 Polish 단계(§10-1)에서 회전 판정에 사용

export function EditorCanvas({ objects, selectedObjectId, bounds, onSelect, onMove }: Props) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [draggingId, setDraggingId] = useState<string | null>(null);

  const halfW = bounds.width / 2;
  const halfD = bounds.depth / 2;

  function toWorld(clientX: number, clientY: number): { x: number; z: number } {
    const svg = svgRef.current;
    if (!svg) return { x: 0, z: 0 };
    const rect = svg.getBoundingClientRect();
    const screenX = (clientX - rect.left) / PX_PER_M - halfW;
    const screenY = (clientY - rect.top) / PX_PER_M - halfD;
    return { x: screenX, z: screenYToWorldZ(screenY) };
  }

  function handlePointerMove(e: React.PointerEvent) {
    if (!draggingId) return;
    const { x, z } = toWorld(e.clientX, e.clientY);
    const snapped = { x: snap(x), z: snap(z) };
    const clamped = clampToBooth(snapped.x, snapped.z, bounds);
    onMove(draggingId, clamped.x, clamped.z);
  }

  return (
    <svg
      ref={svgRef}
      width={bounds.width * PX_PER_M}
      height={bounds.depth * PX_PER_M}
      viewBox={`${-halfW} ${-halfD} ${bounds.width} ${bounds.depth}`}
      style={{ background: '#f0f0f0', border: '1px solid #999' }}
      onPointerMove={handlePointerMove}
      onPointerUp={() => setDraggingId(null)}
      onPointerLeave={() => setDraggingId(null)}
    >
      {objects.map((obj) => {
        const screenY = worldZToScreenY(obj.position.z);
        return (
          <g
            key={obj.objectId}
            transform={`translate(${obj.position.x} ${screenY}) rotate(${obj.rotationY})`}
            onPointerDown={(e) => {
              e.stopPropagation();
              setDraggingId(obj.objectId);
              onSelect(obj.objectId);
            }}
          >
            <rect
              x={-OBJECT_SIZE_M / 2}
              y={-OBJECT_SIZE_M / 2}
              width={OBJECT_SIZE_M}
              height={OBJECT_SIZE_M}
              fill={obj.objectId === selectedObjectId ? '#4a90d9' : '#ccc'}
              stroke="#333"
              strokeWidth={0.02}
            />
            {/* 정면(+Z, 화면 위쪽) 방향 표시선 */}
            <line x1={0} y1={0} x2={0} y2={-OBJECT_SIZE_M} stroke="#e63946" strokeWidth={0.03} />
          </g>
        );
      })}
    </svg>
  );
}
