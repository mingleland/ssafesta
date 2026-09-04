// 캔버스 뷰포트 — Shell 과 Renderer 사이의 교체 경계 (S15P21A604-405).
// Shell 은 이 컴포넌트만 알고, Renderer 는 TemporaryIsoRenderer → 실제 렌더러로 교체된다.
import type { LayoutObject } from '../../../../entities/layout/types';
import type { TransformTool } from '../../model/studioMode';
import { TransformBar } from '../shell/TransformBar';
import { TemporaryIsoRenderer } from './TemporaryIsoRenderer';
import type { BoothDecor } from './TemporaryIsoRenderer';

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
  return (
    <section className="studio-canvas" aria-label="부스 캔버스">
      {p.hudLeft && <div className="studio-canvas-hud-tl">{p.hudLeft}</div>}
      <TemporaryIsoRenderer
        objects={p.objects}
        selectedObjectId={p.selectedObjectId}
        bounds={p.bounds}
        zoom={p.zoom}
        tool={p.tool}
        snapOn={p.snapOn}
        decor={p.decor}
        onSelect={p.onSelect}
        onMove={p.onMove}
        onRotate={p.onRotate}
      />
      <TransformBar tool={p.tool} snap={p.snapOn} onTool={p.onTool} onSnapToggle={p.onSnapToggle} onFrame={p.onFrame} />
      <div className="studio-canvas-hud-br">{p.bounds.width} × {p.bounds.depth} × {p.bounds.height} m · 임시 렌더러</div>
    </section>
  );
}
