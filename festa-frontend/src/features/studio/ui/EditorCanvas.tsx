// 부스를 위에서 내려다보는 2D SVG 편집 캔버스 — 좌표는 처음부터 미터, 부호 반전은 coords.ts 한 곳뿐
// 드래그 중에는 로컬 상태만 바뀌고 서버 요청이 없다 (docs/10 §17)
import { useRef, useState } from 'react';
import { PX_PER_M } from '../../../shared/config/studio';
import type { LayoutObject } from '../../../entities/layout/types';
import { OBJECT_LOCAL_BOUNDS } from '../../../entities/layout/objectTypes';
import { isAreaOutOfBounds, worldAABB } from '../../../entities/layout/geometry';
import { clampToBooth, screenYToWorldZ, snap, worldZToScreenY } from '../lib/coords';

interface Props {
  objects: LayoutObject[];
  selectedObjectId: string | null;
  bounds: { width: number; depth: number; height: number };
  onSelect: (objectId: string | null) => void;
  onMove: (objectId: string, x: number, z: number) => void;
}

// 실물 bounds를 모르는 타입(서버가 신설했지만 로컬 표에 없는 타입, SC-005)의 표시용 대체 크기.
const UNKNOWN_TYPE_SIZE_M = 0.4;

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
      // setPointerCapture로 이후 move/up이 물리적 좌표와 무관하게 이 svg로만 온다 — 없으면 버튼을
      // 캔버스 밖에서 떼도 드래그가 안 풀려 오브젝트가 따라다닌다(T026 눌어붙음). touch-action:none은
      // 터치에서 같은 제스처를 스크롤이 가로채지 않게 한다.
      style={{ background: '#f0f0f0', border: '1px solid #999', touchAction: 'none' }}
      onPointerMove={handlePointerMove}
      onPointerUp={() => setDraggingId(null)}
      onPointerCancel={() => setDraggingId(null)}
    >
      {objects.map((obj) => {
        const screenY = worldZToScreenY(obj.position.z);
        const local = OBJECT_LOCAL_BOUNDS[obj.type];

        // 로컬(x,z)을 이 <g>의 화면 좌표로: x는 그대로, z는 부호 반전(worldZToScreenY와 같은 규칙) —
        // <g>의 rotate()가 이미 §10-1과 같은 방향(위에서 시계방향 +)으로 돌리므로 미리 돌리지 않는다.
        const rect = local
          ? { x: local.min.x, y: -local.max.z, width: local.max.x - local.min.x, height: local.max.z - local.min.z }
          : {
              x: -UNKNOWN_TYPE_SIZE_M / 2,
              y: -UNKNOWN_TYPE_SIZE_M / 2,
              width: UNKNOWN_TYPE_SIZE_M,
              height: UNKNOWN_TYPE_SIZE_M,
            };

        // 실물(회전 반영 AABB)이 부스 영역을 벗어났는지 — 앵커 점은 안이어도 몸체가 걸치는 배치를 드래그 중 바로 보여준다(T022)
        const outOfBounds = local != null && isAreaOutOfBounds(worldAABB(local, obj.rotationY, obj.position), bounds);

        return (
          <g
            key={obj.objectId}
            transform={`translate(${obj.position.x} ${screenY}) rotate(${obj.rotationY})`}
            onPointerDown={(e) => {
              e.stopPropagation();
              svgRef.current?.setPointerCapture(e.pointerId);
              setDraggingId(obj.objectId);
              onSelect(obj.objectId);
            }}
          >
            <rect
              x={rect.x}
              y={rect.y}
              width={rect.width}
              height={rect.height}
              fill={obj.objectId === selectedObjectId ? '#4a90d9' : '#ccc'}
              stroke={outOfBounds ? '#e63946' : '#333'}
              strokeWidth={outOfBounds ? 0.05 : 0.02}
            />
            {/* 정면(+Z, 화면 위쪽) 방향 표시선 — rect 앞쪽 끝을 살짝 지나 눈에 띄게 */}
            <line x1={0} y1={0} x2={0} y2={rect.y - 0.15} stroke="#e63946" strokeWidth={0.03} />
          </g>
        );
      })}
    </svg>
  );
}
