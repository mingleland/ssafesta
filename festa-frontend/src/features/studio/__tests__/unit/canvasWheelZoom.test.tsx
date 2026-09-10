// @vitest-environment jsdom
// 캔버스 안에서만 Ctrl(⌘)+휠을 가져와 편집기 줌으로 쓴다 (S15P21A604-604).
//
// 여기서 고정하는 경계는 두 개다.
//   ① 수식키 없는 휠은 건드리지 않는다 — 편집창 밖의 스크롤·줌은 브라우저 몫이다
//   ② 수식키가 있으면 preventDefault 로 브라우저 페이지 줌을 막는다
//
// ②가 깨지면 Ctrl+휠이 편집기와 페이지를 **동시에** 확대한다. 그게 원래 증상이었다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { ZOOM_MAX, ZOOM_MIN, ZOOM_WHEEL_FACTOR, clampZoom } from '../../../../shared/config/studio';

// R3F 는 jsdom 에서 ResizeObserver 없이 뜨지 않는다. 이 테스트는 뷰포트 껍데기의 이벤트 처리만 본다
vi.mock('../../ui/canvas/canvasRenderer', () => ({
  IS_R3F_CANVAS: false,
  IS_VISUAL_ACCEPTANCE: false,
  VISUAL_ACCEPTANCE_FRAME_MS: 100,
}));

const { BoothCanvasViewport } = await import('../../ui/canvas/BoothCanvasViewport');

const BASE = {
  objects: [],
  selectedObjectId: null,
  bounds: { width: 6, depth: 6, height: 2.72 },
  zoom: 1,
  tool: 'select' as const,
  snapOn: true,
  decor: { floorHex: '#1d4ed8', wallHex: '#ffffff', primaryHex: '#22c55e', signText: '', graphic: false },
  onSelect: () => {},
  onMove: () => {},
  onRotate: () => {},
  onTool: () => {},
  onSnapToggle: () => {},
  onFrame: () => {},
  onZoomChange: () => {},
};

/** 네이티브 wheel 을 직접 만든다 — React 의 onWheel 로는 passive 여부를 볼 수 없다 */
function wheel(target: Element, init: { deltaY: number; ctrlKey?: boolean; metaKey?: boolean }): WheelEvent {
  const event = new WheelEvent('wheel', { ...init, bubbles: true, cancelable: true });
  target.dispatchEvent(event);
  return event;
}

afterEach(cleanup);

describe('캔버스 Ctrl+휠 줌', () => {
  it('수식키 없는 휠은 가로채지 않는다 — 브라우저 기본 동작이 남는다', () => {
    const onZoomChange = vi.fn();
    render(<BoothCanvasViewport {...BASE} onZoomChange={onZoomChange} />);

    const event = wheel(screen.getByLabelText('부스 캔버스'), { deltaY: -100 });

    expect(onZoomChange).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(false);
  });

  it('Ctrl+휠은 가로채고 브라우저 페이지 줌을 막는다', () => {
    const onZoomChange = vi.fn();
    render(<BoothCanvasViewport {...BASE} onZoomChange={onZoomChange} />);

    const event = wheel(screen.getByLabelText('부스 캔버스'), { deltaY: -100, ctrlKey: true });

    expect(event.defaultPrevented).toBe(true);
    expect(onZoomChange).toHaveBeenCalledTimes(1);
    expect(onZoomChange.mock.calls[0][0]).toBeCloseTo(ZOOM_WHEEL_FACTOR, 5);
  });

  it('macOS 의 ⌘ 도 같다', () => {
    const onZoomChange = vi.fn();
    render(<BoothCanvasViewport {...BASE} onZoomChange={onZoomChange} />);

    wheel(screen.getByLabelText('부스 캔버스'), { deltaY: -100, metaKey: true });

    expect(onZoomChange).toHaveBeenCalledTimes(1);
  });

  it('아래로 굴리면 축소된다', () => {
    const onZoomChange = vi.fn();
    render(<BoothCanvasViewport {...BASE} onZoomChange={onZoomChange} />);

    wheel(screen.getByLabelText('부스 캔버스'), { deltaY: 100, ctrlKey: true });

    expect(onZoomChange.mock.calls[0][0]).toBeCloseTo(1 / ZOOM_WHEEL_FACTOR, 5);
  });

  it('같은 프레임에 여러 번 굴려도 누적된다 — prop 갱신을 기다리지 않는다', () => {
    const onZoomChange = vi.fn();
    render(<BoothCanvasViewport {...BASE} onZoomChange={onZoomChange} />);
    const area = screen.getByLabelText('부스 캔버스');

    wheel(area, { deltaY: -100, ctrlKey: true });
    wheel(area, { deltaY: -100, ctrlKey: true });

    // 두 번째 호출이 첫 번째와 같은 값이면 "한 칸에서 멈춘" 그 증상이다
    expect(onZoomChange.mock.calls[1][0]).toBeGreaterThan(onZoomChange.mock.calls[0][0]);
    expect(onZoomChange.mock.calls[1][0]).toBeCloseTo(ZOOM_WHEEL_FACTOR ** 2, 5);
  });

  it('deltaY 0 은 무시한다 — 가로 스크롤만 있는 트랙패드 제스처', () => {
    const onZoomChange = vi.fn();
    render(<BoothCanvasViewport {...BASE} onZoomChange={onZoomChange} />);

    wheel(screen.getByLabelText('부스 캔버스'), { deltaY: 0, ctrlKey: true });

    expect(onZoomChange).not.toHaveBeenCalled();
  });

  it('상한·하한을 넘지 않는다', () => {
    expect(clampZoom(ZOOM_MAX * 10)).toBe(ZOOM_MAX);
    expect(clampZoom(ZOOM_MIN / 10)).toBe(ZOOM_MIN);
    expect(clampZoom(1)).toBe(1);
  });
});
