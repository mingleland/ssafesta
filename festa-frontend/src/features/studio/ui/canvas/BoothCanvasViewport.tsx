// 캔버스 뷰포트 — Shell 과 Renderer 사이의 교체 경계 (S15P21A604-405).
// Shell 은 이 컴포넌트만 알고, Renderer 는 TemporaryIsoRenderer ↔ R3FBoothRenderer 로 바뀐다.
//
// R3F 는 React.lazy 로만 부른다 (S15P21A604-470). static import 하면 three 가 Studio 를 열지 않는
// 사용자의 번들에도 들어간다 — Spike Gate 5·6 이 재는 것이 정확히 그 지점이다.
import { Suspense, lazy, useEffect, useRef } from 'react';
import type { LayoutObject } from '../../../../entities/layout/types';
import type { TransformTool } from '../../model/studioMode';
import { TransformBar } from '../shell/TransformBar';
import { TemporaryIsoRenderer } from './TemporaryIsoRenderer';
import type { BoothDecor } from './canvasTypes';
import { IS_R3F_CANVAS } from './canvasRenderer';
import { ZOOM_WHEEL_FACTOR, clampZoom } from '../../../../shared/config/studio';
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
  /** Ctrl(⌘)+휠이 요청하는 새 배율 — 이미 clamp 된 값이다 */
  onZoomChange: (zoom: number) => void;
}

export function BoothCanvasViewport(p: Props) {
  const areaRef = useRef<HTMLElement | null>(null);

  // 같은 프레임 안에서 연속으로 오는 wheel 을 누적하기 위한 자리. zoom prop 은 아직 갱신 전이라
  // 매번 같은 값에서 계산하면 확대가 한 칸에서 멈춘 것처럼 보인다(TopDownCanvas 가 같은 함정을 겪었다).
  const pendingZoom = useRef(p.zoom);
  useEffect(() => {
    pendingZoom.current = p.zoom;
  }, [p.zoom]);

  const onZoomChangeRef = useRef(p.onZoomChange);
  onZoomChangeRef.current = p.onZoomChange;

  /**
   * Ctrl(⌘)+휠을 **캔버스 안에서만** 가져와 편집기 줌으로 쓴다 (S15P21A604-604).
   *
   * React 의 onWheel 은 passive 로 등록돼 그 안에서는 preventDefault 가 듣지 않는다 — 네이티브로
   * `{ passive: false }` 를 직접 붙여야 브라우저 페이지 줌을 막을 수 있다.
   *
   * 수식키가 없는 휠은 건드리지 않는다. 편집창 밖의 스크롤·줌은 그대로 브라우저 몫이다.
   */
  useEffect(() => {
    const area = areaRef.current;
    if (area === null) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      if (event.deltaY === 0) return;
      event.preventDefault();
      const factor = event.deltaY < 0 ? ZOOM_WHEEL_FACTOR : 1 / ZOOM_WHEEL_FACTOR;
      const next = clampZoom(pendingZoom.current * factor);
      if (next === pendingZoom.current) return;
      pendingZoom.current = next;
      onZoomChangeRef.current(next);
    };
    area.addEventListener('wheel', onWheel, { passive: false });
    return () => area.removeEventListener('wheel', onWheel);
  }, []);

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
    <section className="studio-canvas" aria-label="부스 캔버스" ref={areaRef}>
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
