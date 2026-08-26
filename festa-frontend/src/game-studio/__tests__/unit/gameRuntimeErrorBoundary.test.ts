import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { GameRuntimeErrorBoundary } from '../../runtime/ui/GameRuntimeErrorBoundary.tsx';

describe('Game Runtime error boundary', () => {
  it('renders an isolated Korean recovery surface after a Runtime crash', () => {
    const boundary = new GameRuntimeErrorBoundary({
      children: null,
      onExit: vi.fn(),
      onRetry: vi.fn(),
      resetKey: '123:1',
    });
    boundary.state = { error: new Error('damaged runtime'), resetKey: '123:1' };

    const html = renderToStaticMarkup(boundary.render());
    expect(html).toContain('게임 화면을 안전하게 종료했습니다');
    expect(html).toContain('FESTA 월드와 다른 기능에는 영향을 주지 않습니다');
    expect(html).toContain('다시 불러오기');
    expect(html).toContain('나가기');
  });

  it('clears the isolated error when a new Published snapshot is loaded', () => {
    const current = { error: new Error('boom'), resetKey: '123:1' };
    expect(GameRuntimeErrorBoundary.getDerivedStateFromProps({
      children: null,
      onExit: vi.fn(),
      onRetry: vi.fn(),
      resetKey: '123:2',
    }, current)).toEqual({ error: null, resetKey: '123:2' });
  });
});
