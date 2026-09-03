// @vitest-environment jsdom
// S15P21A604-359 — 미니맵은 원래 onClick 한 번으로만 이동했다(pointerdown~pointerup 사이 추적이
// 없었다). 여기서는 "드래그 중 실시간 추적"과 "클릭만 해도 기존과 동일" 두 요구사항이 서로를
// 깨지 않는지, 그리고 Scene 경계 밖으로 드래그해도 clamp되어 오류 없이 동작하는지를 검증한다.
// jsdom은 setPointerCapture 계열을 구현하지 않으므로 여기서 no-op으로 보강한다.
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { CanvasMinimap } from '../../studio/ui/CanvasMinimap.tsx';
import { cloneMinimalGameProject } from '../fixtures/minimalGameProject.ts';

// 컴포넌트 내부 상수(WIDTH=184/HEIGHT=116/PADDING=8/LABEL_HEIGHT=18)와 fixture의 4×4 Scene으로
// mapRect가 만드는 사각형은 [51,133]×[8,90]이다 — 아래 기대값은 이 계산에서 나왔다.
const SCENE = cloneMinimalGameProject().scenes[0];

beforeAll(() => {
  // jsdom에는 Pointer Capture API가 없다 — 실제 브라우저 동작(포인터가 요소 밖으로 나가도
  // 계속 이벤트를 받는 것)은 테스트 대상이 아니므로 no-op으로 대체해 TypeError만 막는다.
  if (typeof HTMLElement.prototype.setPointerCapture !== 'function') {
    HTMLElement.prototype.setPointerCapture = () => undefined;
    HTMLElement.prototype.releasePointerCapture = () => undefined;
    HTMLElement.prototype.hasPointerCapture = () => false;
  }
});

beforeEach(() => {
  // getBoundingClientRect를 캔버스 실제 크기(184×116)와 동일하게 고정해 clientX/Y를
  // 캔버스 좌표로 그대로 쓸 수 있게 한다 — jsdom 기본값은 전부 0이라 나눗셈이 NaN이 된다.
  vi.spyOn(HTMLButtonElement.prototype, 'getBoundingClientRect').mockReturnValue({
    left: 0, top: 0, width: 184, height: 116, right: 184, bottom: 116, x: 0, y: 0, toJSON: () => ({}),
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const setup = () => {
  const onNavigate = vi.fn();
  render(
    <CanvasMinimap
      hiddenObjectIds={new Set()}
      onNavigate={onNavigate}
      scene={SCENE}
      selectedObjectId={null}
      viewport={null}
    />,
  );
  return { button: screen.getByRole('button'), onNavigate };
};

describe('CanvasMinimap — 드래그 이동', () => {
  it('pointerdown~pointermove 동안 마우스 위치를 실시간으로 따라간다', () => {
    const { button, onNavigate } = setup();

    fireEvent.pointerDown(button, { clientX: 50, clientY: 50, pointerId: 1 });
    fireEvent.pointerMove(button, { clientX: 100, clientY: 60, pointerId: 1 });
    fireEvent.pointerMove(button, { clientX: 120, clientY: 70, pointerId: 1 });
    fireEvent.pointerUp(button, { clientX: 120, clientY: 70, pointerId: 1 });

    // pointerdown 1회 + pointermove 2회 = 3번 — pointerup 자체는 추가로 이동시키지 않는다.
    expect(onNavigate).toHaveBeenCalledTimes(3);
    const positions = onNavigate.mock.calls.map(([position]) => position);
    expect(positions[0]).toEqual({ x: 0, y: expect.closeTo(2.0488, 3) });
    expect(positions[1]).toEqual({ x: expect.closeTo(2.3902, 3), y: expect.closeTo(2.5366, 3) });
    // clientX가 100→120으로 계속 늘었으니 grid x도 계속 늘어야 "실시간으로 따라간다".
    expect(positions[2].x).toBeGreaterThan(positions[1].x);
  });

  it('드래그 없이 pointerdown~pointerup만 해도(=클릭) 기존과 동일하게 한 번만 이동한다', () => {
    const { button, onNavigate } = setup();

    fireEvent.pointerDown(button, { clientX: 70, clientY: 40, pointerId: 2 });
    fireEvent.pointerUp(button, { clientX: 70, clientY: 40, pointerId: 2 });

    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onNavigate.mock.calls[0][0]).toEqual({
      x: expect.closeTo(0.9268, 3),
      y: expect.closeTo(1.561, 3),
    });
  });

  it('Scene 경계 밖으로 드래그해도 좌표가 [0, width-1]/[0, height-1]로 clamp된다', () => {
    const { button, onNavigate } = setup();

    fireEvent.pointerDown(button, { clientX: 5000, clientY: 5000, pointerId: 3 });
    fireEvent.pointerMove(button, { clientX: -5000, clientY: -5000, pointerId: 3 });
    fireEvent.pointerUp(button, { clientX: -5000, clientY: -5000, pointerId: 3 });

    expect(onNavigate).toHaveBeenCalledTimes(2);
    for (const [position] of onNavigate.mock.calls) {
      expect(Number.isFinite(position.x)).toBe(true);
      expect(Number.isFinite(position.y)).toBe(true);
      expect(position.x).toBeGreaterThanOrEqual(0);
      expect(position.x).toBeLessThanOrEqual(SCENE.width - 1);
      expect(position.y).toBeGreaterThanOrEqual(0);
      expect(position.y).toBeLessThanOrEqual(SCENE.height - 1);
    }
    // 큰 양수 clientX/Y → 우하단 clamp, 큰 음수 → 좌상단 clamp로 서로 반대쪽이어야 한다.
    expect(onNavigate.mock.calls[0][0]).toEqual({ x: SCENE.width - 1, y: SCENE.height - 1 });
    expect(onNavigate.mock.calls[1][0]).toEqual({ x: 0, y: 0 });
  });
});
