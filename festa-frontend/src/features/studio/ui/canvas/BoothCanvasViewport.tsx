// 캔버스 뷰포트 — Shell 과 Renderer 사이의 교체 경계 (S15P21A604-405).
// Shell 은 이 컴포넌트만 알고, Renderer 는 TemporaryIsoRenderer ↔ R3FBoothRenderer 로 바뀐다.
//
// R3F 는 React.lazy 로만 부른다 (S15P21A604-470). static import 하면 three 가 Studio 를 열지 않는
// 사용자의 번들에도 들어간다 — Spike Gate 5·6 이 재는 것이 정확히 그 지점이다.
import { Suspense, lazy } from 'react';
import type { LayoutObject } from '../../../../entities/layout/types';
import type { TransformTool } from '../../model/studioMode';
import { TransformBar } from '../shell/TransformBar';
import { TemporaryIsoRenderer } from './TemporaryIsoRenderer';
import type { BoothDecor } from './canvasTypes';
import { IS_R3F_CANVAS } from './canvasRenderer';
import './boothCanvas.css';

const R3FBoothRenderer = lazy(() => import('./R3FBoothRenderer'));

interface Props {
  objects: LayoutObject[];
  selectedObjectId: string | null;
  bounds: { width: number; depth: number; height: number };
  zoom: number; // 1 = 기본
  tool: TransformTool;
  snapOn: boolean;
  decor: BoothDecor;
  hudLeft?: React.ReactNode;
  onSelect: (objectId: string | null) => void;
  onMove: (objectId: string, x: number, z: number) => void;
  onRotate: (objectId: string, rotationY: number) => void;
  onTool: (tool: TransformTool) => void;
  onSnapToggle: () => void;
  onFrame: () => void;
}

export function BoothCanvasViewport(p: Props) {
  const renderer = {
    objects: p.objects,
    selectedObjectId: p.selectedObjectId,
    bounds: p.bounds,
    zoom: p.zoom,
    tool: p.tool,
    snapOn: p.snapOn,
    decor: p.decor,
    onSelect: p.onSelect,
    onMove: p.onMove,
    onRotate: p.onRotate,
  };
  return (
    <section className="studio-canvas" aria-label="부스 캔버스">
      {p.hudLeft && <div className="studio-canvas-hud-tl">{p.hudLeft}</div>}
      {IS_R3F_CANVAS ? (
        // fallback 은 SVG 렌더러 그대로다 — 청크를 받는 동안 빈 화면을 보이지 않는다
        <Suspense fallback={<TemporaryIsoRenderer {...renderer} />}>
          <R3FBoothRenderer {...renderer} />
        </Suspense>
      ) : (
        <TemporaryIsoRenderer {...renderer} />
      )}
      <TransformBar tool={p.tool} snap={p.snapOn} onTool={p.onTool} onSnapToggle={p.onSnapToggle} onFrame={p.onFrame} />
      <div className="studio-canvas-hud-br">
        {p.bounds.width} × {p.bounds.depth} × {p.bounds.height} m · {IS_R3F_CANVAS ? '3D 미리보기' : '임시 렌더러'}
      </div>
    </section>
  );
}
