// @vitest-environment jsdom
// 실험(정식 티켓 아님) — 제목 표시줄 드래그(이동)/모서리 핸들 드래그(크기 조절)/닫기 버튼을
// 검증한다. jsdom에는 Pointer Capture API가 없어 canvasMinimapDrag.test.tsx와 동일하게
// no-op으로 보강한다.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { FloatingPanel } from '../../studio/ui/FloatingPanel.tsx';

beforeAll(() => {
  if (typeof HTMLElement.prototype.setPointerCapture !== 'function') {
    HTMLElement.prototype.setPointerCapture = () => undefined;
    HTMLElement.prototype.releasePointerCapture = () => undefined;
    HTMLElement.prototype.hasPointerCapture = () => false;
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const setup = (onClose = vi.fn()) => {
  const { container } = render(
    <FloatingPanel
      initialPosition={{ x: 100, y: 80 }}
      initialSize={{ width: 500, height: 300 }}
      onClose={onClose}
      title="게임 흐름"
    >
      <p>내용</p>
    </FloatingPanel>,
  );
  const panel = container.querySelector('.gss-floating-panel') as HTMLElement;
  const titlebar = container.querySelector('.gss-floating-panel-titlebar') as HTMLElement;
  const resizeHandle = container.querySelector('.gss-floating-panel-resize-handle') as HTMLElement;
  return { container, onClose, panel, resizeHandle, titlebar };
};

describe('FloatingPanel', () => {
  it('제목 표시줄을 드래그하면 그만큼 위치가 이동한다', () => {
    const { panel, titlebar } = setup();
    expect(panel.style.left).toBe('100px');
    expect(panel.style.top).toBe('80px');

    fireEvent.pointerDown(titlebar, { clientX: 200, clientY: 150, pointerId: 1 });
    fireEvent.pointerMove(titlebar, { clientX: 260, clientY: 190, pointerId: 1 });
    fireEvent.pointerUp(titlebar, { clientX: 260, clientY: 190, pointerId: 1 });

    expect(panel.style.left).toBe('160px');
    expect(panel.style.top).toBe('120px');
  });

  it('모서리 핸들을 드래그하면 그만큼 크기가 커진다', () => {
    const { panel, resizeHandle } = setup();
    expect(panel.style.width).toBe('500px');
    expect(panel.style.height).toBe('300px');

    fireEvent.pointerDown(resizeHandle, { clientX: 300, clientY: 200, pointerId: 2 });
    fireEvent.pointerMove(resizeHandle, { clientX: 380, clientY: 260, pointerId: 2 });
    fireEvent.pointerUp(resizeHandle, { clientX: 380, clientY: 260, pointerId: 2 });

    expect(panel.style.width).toBe('580px');
    expect(panel.style.height).toBe('360px');
  });

  it('최소 크기 밑으로는 줄어들지 않는다', () => {
    const { panel, resizeHandle } = setup();

    fireEvent.pointerDown(resizeHandle, { clientX: 600, clientY: 380, pointerId: 3 });
    fireEvent.pointerMove(resizeHandle, { clientX: -5000, clientY: -5000, pointerId: 3 });
    fireEvent.pointerUp(resizeHandle, { clientX: -5000, clientY: -5000, pointerId: 3 });

    expect(panel.style.width).toBe('360px');
    expect(panel.style.height).toBe('240px');
  });

  it('닫기 버튼을 누르면 onClose가 호출된다', () => {
    const { onClose } = setup();
    fireEvent.click(screen.getByRole('button', { name: '게임 흐름 닫기' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
