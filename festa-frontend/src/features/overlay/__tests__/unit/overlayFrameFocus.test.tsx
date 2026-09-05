// @vitest-environment jsdom
// OverlayFrame focus 소유권 (S15P21A604-428, G-8-1) — 닫은 뒤 focus 를 돌려주지 않으면 body 에 남고
// Unity 가 키 입력을 못 받는다. 세 경로(이전 요소 / canvas / canvas 없음)를 고정한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { OverlayFrame } from '../../ui/OverlayFrame';

afterEach(cleanup);

const renderFrame = () => render(
  <OverlayFrame title="테스트" onClose={vi.fn()}>
    <p>본문</p>
  </OverlayFrame>,
);

describe('OverlayFrame focus 소유권 (-428)', () => {
  it('열리면 프레임이 focus 를 가져간다', () => {
    renderFrame();
    expect((document.activeElement as HTMLElement).getAttribute('role')).toBe('dialog');
  });

  it('닫히면 열기 직전에 focus 를 갖고 있던 요소로 돌려준다 — 월드에서는 그것이 canvas 다', () => {
    const canvas = document.createElement('canvas');
    canvas.id = 'unity-canvas';
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    canvas.focus();
    expect(document.activeElement).toBe(canvas);

    const view = renderFrame();
    expect(document.activeElement).not.toBe(canvas);
    view.unmount();

    expect(document.activeElement).toBe(canvas);
    canvas.remove();
  });

  it('이전 요소가 body 면(dev 트리거 등) canvas 로 돌린다', () => {
    const canvas = document.createElement('canvas');
    canvas.tabIndex = -1;
    document.body.appendChild(canvas);
    expect(document.activeElement).toBe(document.body);

    const view = renderFrame();
    view.unmount();

    expect(document.activeElement).toBe(canvas);
    canvas.remove();
  });

  it('canvas 가 없는 화면에서는 아무것도 하지 않는다 — 예외 없이 지나간다', () => {
    const view = renderFrame();
    expect(() => view.unmount()).not.toThrow();
  });
});
