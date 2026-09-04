// Temporary Iso Renderer — SVG 아이소메트릭 투영으로 그리는 임시 2.5D 부스 (S15P21A604-405).
// 교체 경계: BoothCanvasViewport 가 넘기는 props 만 소비한다. R3F/Three 로 바꿔도 Shell·Feature 는 그대로다.
// 좌표 규약은 계약(헌법 21조) 그대로 — 원점 = 바닥 중앙, +Z = 부스 정면, y = 높이. 화면 매핑은 이 파일에만 있다.
import { useRef, useState } from 'react';
import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react';
import type { LayoutObject, ObjectType } from '../../../../entities/layout/types';
import { OBJECT_LOCAL_BOUNDS } from '../../../../entities/layout/objectTypes';
import { isAreaOutOfBounds, worldAABB } from '../../../../entities/layout/geometry';
import { clampToBooth, normalizeRotation, snap } from '../../lib/coords';
import type { TransformTool } from '../../model/studioMode';
import './isoRenderer.css';

export interface BoothDecor {
  floorHex: string;
  wallHex: string;
  primaryHex: string;
  signText: string;
  graphic: boolean;
}

interface Props {
  objects: LayoutObject[];
  selectedObjectId: string | null;
  bounds: { width: number; depth: number; height: number };
  zoom: number;
  tool: TransformTool;
  snapOn: boolean;
  decor: BoothDecor;
  onSelect: (objectId: string | null) => void;
  onMove: (objectId: string, x: number, z: number) => void;
  onRotate: (objectId: string, rotationY: number) => void;
}

// ── 아이소메트릭 투영(30°) ──────────────────────────────────────────
// 화면 = ((x - z)·cos30, (x + z)·sin30 - y) · S. +Z(정면)는 화면 왼쪽-아래로 간다.
const S = 70; // px per meter — 확대는 viewBox 로 하고 이 값은 고정이다
const C = Math.cos(Math.PI / 6) * S;
const H = 0.5 * S;
const ROTATE_SNAP_DEG = 15;

type P2 = { x: number; y: number };
const proj = (x: number, y: number, z: number): P2 => ({ x: (x - z) * C, y: (x + z) * H - y * S });
const pts = (list: P2[]) => list.map((q) => q.x.toFixed(1) + ',' + q.y.toFixed(1)).join(' ');
/** 바닥 평면(y=0) 역투영 — 드래그가 화면 이동을 월드 이동으로 되돌릴 때 쓴다 */
const unproj = (sx: number, sy: number) => ({ x: (sx / C + sy / H) / 2, z: (sy / H - sx / C) / 2 });

const OBJECT_FILL: Record<ObjectType, string> = {
  AI_AGENT: '#8fb8ff',
  VIDEO_SCREEN: '#28324a',
  PROJECT_PANEL: '#eef2fa',
  SURVEY_KIOSK: '#cfd6e6',
  RECRUITMENT_BOARD: '#f6f7fb',
  CONSULTATION_DESK: '#f2f4f8',
  LAPTOP: '#dfe4ee',
  LIKE_VOTE: '#ffc4dc',
  FURNITURE: '#e6e9f0',
  DECORATION: '#79c88a',
};
const OBJECT_LABEL: Record<ObjectType, string> = {
  AI_AGENT: 'AI 직원',
  VIDEO_SCREEN: '영상 스크린',
  PROJECT_PANEL: '그래픽 패널',
  SURVEY_KIOSK: '설문 키오스크',
  RECRUITMENT_BOARD: '채용 보드',
  CONSULTATION_DESK: '상담 데스크',
  LAPTOP: '노트북',
  LIKE_VOTE: '좋아요 스탠드',
  FURNITURE: '가구',
  DECORATION: '장식',
};
const FALLBACK_BOX = { min: { x: -0.25, y: 0, z: -0.25 }, max: { x: 0.25, y: 1, z: 0.25 } };

function shade(hex: string, amount: number): string {
  const n = parseInt(hex.slice(1), 16);
  const f = (v: number) => Math.max(0, Math.min(255, Math.round(v * amount)));
  return 'rgb(' + f((n >> 16) & 255) + ',' + f((n >> 8) & 255) + ',' + f(n & 255) + ')';
}

/** rotationY 를 적용한 바닥 사각형 4점(월드) */
function footprintCorners(obj: LayoutObject) {
  const b = OBJECT_LOCAL_BOUNDS[obj.type] ?? FALLBACK_BOX;
  const rad = (obj.rotationY * Math.PI) / 180;
  const cos = Math.cos(rad);
  const sin = Math.sin(rad);
  const local = [
    { x: b.min.x, z: b.min.z },
    { x: b.max.x, z: b.min.z },
    { x: b.max.x, z: b.max.z },
    { x: b.min.x, z: b.max.z },
  ];
  return local.map((q) => ({
    x: obj.position.x + q.x * cos + q.z * sin,
    z: obj.position.z - q.x * sin + q.z * cos,
  }));
}

interface DragState {
  kind: 'move' | 'rotate';
  objectId: string;
  origin: { x: number; z: number };
  grab: { x: number; z: number };
  startRotation: number;
}

export function TemporaryIsoRenderer(p: Props) {
  const svgRef = useRef<SVGSVGElement>(null);
  const [drag, setDrag] = useState<DragState | null>(null);

  const halfW = p.bounds.width / 2;
  const halfD = p.bounds.depth / 2;
  const wallH = p.bounds.height;

  const vbW = 960 / p.zoom;
  const vbH = 690 / p.zoom;
  const centerY = -(wallH * S) / 2 + 20;
  const viewBox = -vbW / 2 + ' ' + (centerY - vbH / 2) + ' ' + vbW + ' ' + vbH;

  function toWorld(e: ReactPointerEvent): { x: number; z: number } | null {
    const svg = svgRef.current;
    const ctm = svg?.getScreenCTM();
    if (!svg || !ctm) return null;
    const pt = svg.createSVGPoint();
    pt.x = e.clientX;
    pt.y = e.clientY;
    const local = pt.matrixTransform(ctm.inverse());
    return unproj(local.x, local.y);
  }

  function beginDrag(e: ReactPointerEvent, obj: LayoutObject, kind: 'move' | 'rotate') {
    e.stopPropagation();
    p.onSelect(obj.objectId);
    const w = toWorld(e);
    if (!w) return;
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
    setDrag({ kind, objectId: obj.objectId, origin: { x: obj.position.x, z: obj.position.z }, grab: w, startRotation: obj.rotationY });
  }

  function onPointerMove(e: ReactPointerEvent) {
    if (!drag) return;
    const w = toWorld(e);
    if (!w) return;
    if (drag.kind === 'move') {
      let x = drag.origin.x + (w.x - drag.grab.x);
      let z = drag.origin.z + (w.z - drag.grab.z);
      if (p.snapOn) {
        x = snap(x);
        z = snap(z);
      }
      const c = clampToBooth(x, z, p.bounds);
      p.onMove(drag.objectId, Number(c.x.toFixed(3)), Number(c.z.toFixed(3)));
    } else {
      // 월드 평면상의 각도로 잰다 — 화면 각도로 재면 아이소 왜곡이 그대로 들어간다
      const a0 = Math.atan2(drag.grab.x - drag.origin.x, drag.grab.z - drag.origin.z);
      const a1 = Math.atan2(w.x - drag.origin.x, w.z - drag.origin.z);
      let deg = drag.startRotation + ((a1 - a0) * 180) / Math.PI;
      if (p.snapOn) deg = Math.round(deg / ROTATE_SNAP_DEG) * ROTATE_SNAP_DEG;
      p.onRotate(drag.objectId, Math.round(normalizeRotation(deg)));
    }
  }

  const endDrag = () => setDrag(null);

  // ── 정적 지오메트리 ──
  const floor = [proj(-halfW, 0, -halfD), proj(halfW, 0, -halfD), proj(halfW, 0, halfD), proj(-halfW, 0, halfD)];
  const gridLines: Array<[P2, P2]> = [];
  for (let i = 1; i < p.bounds.width; i += 1) gridLines.push([proj(-halfW + i, 0, -halfD), proj(-halfW + i, 0, halfD)]);
  for (let i = 1; i < p.bounds.depth; i += 1) gridLines.push([proj(-halfW, 0, -halfD + i), proj(halfW, 0, -halfD + i)]);

  // 뒤쪽 벽(z=-halfD) + 왼쪽 벽(x=-halfW) — 코너형 ㄱ자, 화면 위쪽에서 만난다
  const backWall = [proj(-halfW, wallH, -halfD), proj(halfW, wallH, -halfD), proj(halfW, 0, -halfD), proj(-halfW, 0, -halfD)];
  const leftWall = [proj(-halfW, wallH, -halfD), proj(-halfW, wallH, halfD), proj(-halfW, 0, halfD), proj(-halfW, 0, -halfD)];
  const backBand = [proj(-halfW, wallH * 0.62, -halfD), proj(halfW, wallH * 0.34, -halfD), proj(halfW, 0, -halfD), proj(-halfW, 0, -halfD)];
  const leftBand = [proj(-halfW, wallH * 0.34, -halfD), proj(-halfW, wallH * 0.62, halfD), proj(-halfW, 0, halfD), proj(-halfW, 0, -halfD)];

  const corner = proj(-halfW, wallH, -halfD);
  const frontCorner = proj(-halfW, wallH, halfD);
  // 글자를 벽면에 눕히는 아핀 — u = 벽 가로(m), w = 아래(m). 행렬식이 음수면 글자가 뒤집히므로
  // 왼쪽 벽은 앞쪽 코너에서 -z 방향(화면 오른쪽 위)으로 눕힌다.
  const backSignMatrix = 'matrix(' + C / S + ' ' + H / S + ' 0 1 ' + corner.x + ' ' + corner.y + ')';
  const leftSignMatrix = 'matrix(' + C / S + ' ' + -H / S + ' 0 1 ' + frontCorner.x + ' ' + frontCorner.y + ')';
  const signScale = S; // 벽 로컬 좌표(m)를 픽셀로 되돌린다

  const trussCorners: Array<[number, number]> = [
    [-halfW, -halfD],
    [halfW, -halfD],
    [halfW, halfD],
    [-halfW, halfD],
  ];
  const trussTop = trussCorners.map(([cx, cz]) => proj(cx, wallH, cz));
  const trussPosts = trussCorners.map(([cx, cz]) => [proj(cx, 0, cz), proj(cx, wallH, cz)] as [P2, P2]);
  // 앞쪽 두 변에 격자 — 프레임처럼 읽히게 하는 최소 장치
  const trussWeb: Array<[P2, P2]> = [];
  for (let i = 1; i < p.bounds.width; i += 1) {
    const t = -halfW + i;
    trussWeb.push([proj(t, wallH, halfD), proj(t - 0.5, wallH - 0.28, halfD)]);
  }
  for (let i = 1; i < p.bounds.depth; i += 1) {
    const t = -halfD + i;
    trussWeb.push([proj(halfW, wallH, t), proj(halfW, wallH - 0.28, t - 0.5)]);
  }
  const lamps = [0.25, 0.5, 0.75].map((t) => proj(-halfW + p.bounds.width * t, wallH - 0.12, -halfD + 0.6));

  // 페인터 알고리즘 — (x+z)가 클수록 시청자에 가깝다
  const ordered = [...p.objects].sort((a, b) => a.position.x + a.position.z - (b.position.x + b.position.z));
  const selected = p.objects.find((o) => o.objectId === p.selectedObjectId) ?? null;

  return (
    <svg
      ref={svgRef}
      className="iso-svg"
      viewBox={viewBox}
      preserveAspectRatio="xMidYMid meet"
      style={{ '--iso-floor': p.decor.floorHex, '--iso-wall': p.decor.wallHex, '--iso-wall-side': shade(p.decor.wallHex, 0.86) } as CSSProperties}
      onPointerDown={() => p.onSelect(null)}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
    >
      <defs>
        <linearGradient id="isoFloorSheen" x1="0" y1="0" x2="0.6" y2="1">
          <stop offset="0%" stopColor="#ffffff" stopOpacity="0.22" />
          <stop offset="70%" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
        <radialGradient id="isoLampCone">
          <stop offset="0%" stopColor="#ffe9a8" stopOpacity="0.45" />
          <stop offset="100%" stopColor="#ffe9a8" stopOpacity="0" />
        </radialGradient>
        <marker id="isoArrowX" markerWidth="7" markerHeight="7" refX="5" refY="3.5" orient="auto">
          <path d="M0,0 L7,3.5 L0,7 z" fill="#ff5d5d" />
        </marker>
        <marker id="isoArrowZ" markerWidth="7" markerHeight="7" refX="5" refY="3.5" orient="auto">
          <path d="M0,0 L7,3.5 L0,7 z" fill="#4d8dff" />
        </marker>
      </defs>

      <polygon className="iso-floor-face" points={pts(floor)} />
      <polygon className="iso-floor-sheen" points={pts(floor)} />
      {gridLines.map(([a, b], i) => (
        <line key={i} className="iso-grid" x1={a.x} y1={a.y} x2={b.x} y2={b.y} />
      ))}

      <polygon className="iso-wall-face" points={pts(backWall)} />
      {p.decor.graphic && <polygon className="iso-wall-graphic" points={pts(backBand)} fill={p.decor.primaryHex} opacity={0.85} />}
      <polygon className="iso-wall-face iso-wall-face-side" points={pts(leftWall)} />
      {p.decor.graphic && <polygon className="iso-wall-graphic" points={pts(leftBand)} fill={shade(p.decor.primaryHex, 0.8)} opacity={0.75} />}

      {p.decor.signText !== '' && (
        <g>
          <text className="iso-wall-sign" transform={backSignMatrix} x={0.5 * signScale} y={0.9 * signScale} fontSize={0.5 * signScale}>
            {p.decor.signText}
          </text>
          <text className="iso-wall-sign" transform={leftSignMatrix} x={0.5 * signScale} y={0.9 * signScale} fontSize={0.5 * signScale} opacity={0.7}>
            {p.decor.signText}
          </text>
        </g>
      )}

      {trussPosts.map(([a, b], i) => (
        <line key={'post' + i} className="iso-truss-bar" x1={a.x} y1={a.y} x2={b.x} y2={b.y} />
      ))}
      <polygon className="iso-truss-bar" points={pts(trussTop)} fill="none" />
      {trussWeb.map(([a, b], i) => (
        <line key={'web' + i} className="iso-truss-web" x1={a.x} y1={a.y} x2={b.x} y2={b.y} />
      ))}
      {lamps.map((l, i) => (
        <g key={i}>
          <circle className="iso-lamp-cone" cx={l.x} cy={l.y + 40} r={70} />
          <circle className="iso-lamp-glow" cx={l.x} cy={l.y} r={5} />
        </g>
      ))}

      {ordered.map((obj) => {
        const box = OBJECT_LOCAL_BOUNDS[obj.type] ?? FALLBACK_BOX;
        const h = box.max.y;
        const base = footprintCorners(obj);
        const bottom = base.map((c) => proj(c.x, 0, c.z));
        const top = base.map((c) => proj(c.x, h, c.z));
        const fill = OBJECT_FILL[obj.type] ?? '#e5e9f2';
        const isSel = obj.objectId === p.selectedObjectId;
        const known = OBJECT_LOCAL_BOUNDS[obj.type];
        const oob = known !== undefined && isAreaOutOfBounds(worldAABB(known, obj.rotationY, obj.position), p.bounds);
        const sides = [0, 1, 2, 3]
          .map((i) => {
            const j = (i + 1) % 4;
            return { i, j, depth: (base[i].x + base[i].z + base[j].x + base[j].z) / 2 };
          })
          .sort((a, b) => a.depth - b.depth);
        const label = proj(obj.position.x, h + 0.2, obj.position.z);
        const style = {
          '--iso-obj-top': shade(fill, 1.06),
          '--iso-obj-left': shade(fill, 0.76),
          '--iso-obj-right': shade(fill, 0.9),
        } as CSSProperties;
        return (
          <g
            key={obj.objectId}
            className="iso-object"
            data-selected={isSel}
            data-dragging={drag?.objectId === obj.objectId}
            style={style}
            onPointerDown={(e) => beginDrag(e, obj, p.tool === 'rotate' ? 'rotate' : 'move')}
          >
            <polygon className="iso-obj-shadow" points={pts(bottom)} />
            {sides.map(({ i, j }) => (
              <polygon key={i} className={i % 2 === 0 ? 'iso-obj-left' : 'iso-obj-right'} points={pts([bottom[i], bottom[j], top[j], top[i]])} />
            ))}
            <polygon className="iso-obj-top" points={pts(top)} />
            {isSel && <polygon className={oob ? 'iso-footprint iso-footprint-oob' : 'iso-footprint'} points={pts(bottom)} />}
            {/* 라벨은 선택했을 때만 — 전부 띄우면 부스가 글자로 덮인다 */}
            {isSel && (
              <text className="iso-obj-label" x={label.x} y={label.y}>
                {OBJECT_LABEL[obj.type] ?? obj.type}
              </text>
            )}
          </g>
        );
      })}

      {selected !== null && (() => {
        const box = OBJECT_LOCAL_BOUNDS[selected.type] ?? FALLBACK_BOX;
        const r = Math.max(box.max.x - box.min.x, box.max.z - box.min.z) * 0.75 + 0.45;
        const c0 = proj(selected.position.x, 0, selected.position.z);
        const ring = Array.from({ length: 48 }, (_, i) => {
          const a = (i / 48) * Math.PI * 2;
          return proj(selected.position.x + Math.cos(a) * r, 0, selected.position.z + Math.sin(a) * r);
        });
        const rad = (selected.rotationY * Math.PI) / 180;
        const handle = proj(selected.position.x + Math.sin(rad) * r, 0, selected.position.z + Math.cos(rad) * r);
        const axX = proj(selected.position.x + r + 0.5, 0, selected.position.z);
        const axZ = proj(selected.position.x, 0, selected.position.z + r + 0.5);
        const gx0 = proj(-halfW, 0, selected.position.z);
        const gx1 = proj(halfW, 0, selected.position.z);
        const gz0 = proj(selected.position.x, 0, -halfD);
        const gz1 = proj(selected.position.x, 0, halfD);
        return (
          <g>
            {drag?.kind === 'move' && (
              <g>
                <line className="iso-guide" x1={gx0.x} y1={gx0.y} x2={gx1.x} y2={gx1.y} />
                <line className="iso-guide" x1={gz0.x} y1={gz0.y} x2={gz1.x} y2={gz1.y} />
              </g>
            )}
            <line className="iso-axis-x" x1={c0.x} y1={c0.y} x2={axX.x} y2={axX.y} />
            <line className="iso-axis-z" x1={c0.x} y1={c0.y} x2={axZ.x} y2={axZ.y} />
            <polygon className="iso-ring" points={pts(ring)} />
            {/* 넓은 히트 밴드는 회전 도구일 때만 — 켜 두면 아래에 있는 다른 오브젝트 클릭을 가로챈다 */}
            {p.tool === 'rotate' && (
              <polygon className="iso-ring-hit" points={pts(ring)} onPointerDown={(e) => beginDrag(e, selected, 'rotate')} />
            )}
            <circle className="iso-ring-handle" cx={handle.x} cy={handle.y} r={7} onPointerDown={(e) => beginDrag(e, selected, 'rotate')} />
            <circle className="iso-center" cx={c0.x} cy={c0.y} r={4} />
          </g>
        );
      })()}
    </svg>
  );
}
