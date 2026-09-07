// @vitest-environment jsdom
// S15P21A604-510 — SceneFlowGraph를 직접 마운트해서 배경 드래그(팬)/휠(커서 위치 기준
// 확대·축소)을 검증한다. jsdom에는 Pointer Capture API가 없어 canvasMinimapDrag.test.tsx와
// 동일하게 no-op으로 보강한다. GameStudioShell을 통한 모달 배선(노드 클릭→씬 이동, 토글
// 열고닫기)은 sceneFlowGraph.test.tsx가 이미 검증하므로 여기서는 다루지 않는다.
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { SceneFlowGraph } from '../../studio/ui/SceneFlowGraph.tsx';
import { createStarterProject } from '../../studio/model/createStarterProject.ts';

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

const GAME_ID = 800;

const setup = () => {
  const onSelectScene = vi.fn();
  const project = createStarterProject(GAME_ID);
  const { container } = render(<SceneFlowGraph onSelectScene={onSelectScene} project={project} />);
  const viewport = container.querySelector('.gss-flow-graph-viewport') as HTMLElement;
  const content = container.querySelector('.gss-flow-graph') as HTMLElement;
  return { container, onSelectScene, viewport, content };
};

// transform: "translate(Xpx, Ypx) scale(S)"에서 각 값을 뽑아낸다.
const readTransform = (content: HTMLElement) => {
  const match = /translate\(([-\d.]+)px, ([-\d.]+)px\) scale\(([-\d.]+)\)/.exec(content.style.transform);
  if (match === null) throw new Error(`unexpected transform: ${content.style.transform}`);
  return { x: Number(match[1]), y: Number(match[2]), scale: Number(match[3]) };
};

describe('SceneFlowGraph — 배경 드래그(팬)', () => {
  it('배경을 드래그하면 그 거리만큼 그래프가 이동한다', () => {
    const { viewport, content } = setup();
    expect(readTransform(content)).toEqual({ x: 0, y: 0, scale: 1 });

    fireEvent.pointerDown(viewport, { clientX: 200, clientY: 150, pointerId: 1 });
    fireEvent.pointerMove(viewport, { clientX: 260, clientY: 190, pointerId: 1 });
    fireEvent.pointerUp(viewport, { clientX: 260, clientY: 190, pointerId: 1 });

    expect(readTransform(content)).toEqual({ x: 60, y: 40, scale: 1 });
  });

  it('드래그 중에는 is-panning 클래스가 붙고, 끝나면 사라진다', () => {
    const { viewport } = setup();
    expect(viewport.className).not.toContain('is-panning');

    fireEvent.pointerDown(viewport, { clientX: 100, clientY: 100, pointerId: 2 });
    expect(viewport.className).toContain('is-panning');

    fireEvent.pointerUp(viewport, { clientX: 120, clientY: 100, pointerId: 2 });
    expect(viewport.className).not.toContain('is-panning');
  });

  it('노드 위에서 시작한 드래그는 화면을 이동시키지 않고, 클릭은 정상 동작한다', () => {
    const { container, viewport, content, onSelectScene } = setup();
    const node = container.querySelector('.gss-flow-node') as HTMLElement;

    fireEvent.pointerDown(node, { clientX: 50, clientY: 50, pointerId: 3 });
    fireEvent.pointerMove(viewport, { clientX: 150, clientY: 150, pointerId: 3 });
    fireEvent.pointerUp(node, { clientX: 150, clientY: 150, pointerId: 3 });

    expect(readTransform(content)).toEqual({ x: 0, y: 0, scale: 1 });
    expect(viewport.className).not.toContain('is-panning');

    fireEvent.click(node);
    expect(onSelectScene).toHaveBeenCalledTimes(1);
  });
});

describe('SceneFlowGraph — 휠(커서 위치 기준 확대·축소)', () => {
  // getBoundingClientRect가 jsdom 기본값(전부 0)이면 뷰포트 기준 커서 좌표 계산이 항상
  // 0이 되어 버린다 — 실제 화면 크기처럼 고정된 값으로 대체한다(canvasMinimapDrag.test.tsx와
  // 같은 방식).
  const mockViewportRect = () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600, x: 0, y: 0, toJSON: () => ({}),
    });
  };

  it('휠을 위로 굴리면 커서 위치를 기준점으로 확대된다', () => {
    mockViewportRect();
    const { viewport, content } = setup();

    fireEvent.wheel(viewport, { clientX: 100, clientY: 80, deltaY: -100 });

    const transform = readTransform(content);
    expect(transform.scale).toBeCloseTo(1.1, 5);
    // 커서 아래 지점(100,80)이 확대 후에도 같은 화면 위치에 남으려면 x/y가 이만큼 보정돼야 한다.
    expect(transform.x).toBeCloseTo(-10, 5);
    expect(transform.y).toBeCloseTo(-8, 5);
  });

  it('휠을 아래로 굴리면 축소된다', () => {
    mockViewportRect();
    const { viewport, content } = setup();

    fireEvent.wheel(viewport, { clientX: 100, clientY: 80, deltaY: 100 });

    expect(readTransform(content).scale).toBeCloseTo(1 / 1.1, 5);
  });

  it('최대/최소 배율에서 더 이상 커지거나 작아지지 않는다', () => {
    mockViewportRect();
    const { viewport, content } = setup();

    for (let i = 0; i < 40; i += 1) {
      fireEvent.wheel(viewport, { clientX: 100, clientY: 80, deltaY: -100 });
    }
    expect(readTransform(content).scale).toBeCloseTo(2, 5);

    for (let i = 0; i < 60; i += 1) {
      fireEvent.wheel(viewport, { clientX: 100, clientY: 80, deltaY: 100 });
    }
    expect(readTransform(content).scale).toBeCloseTo(0.5, 5);
  });
});
