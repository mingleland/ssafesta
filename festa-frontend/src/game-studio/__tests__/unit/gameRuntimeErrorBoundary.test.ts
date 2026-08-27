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
    boundary.state = { error: new Error('damaged runtime'), resetKey: '123:1', diagnosticId: 'ABC123' };

    const html = renderToStaticMarkup(boundary.render());
    expect(html).toContain('게임 화면을 안전하게 종료했습니다');
    expect(html).toContain('FESTA 월드와 다른 기능에는 영향을 주지 않습니다');
    expect(html).toContain('다시 불러오기');
    expect(html).toContain('나가기');
  });

  it('clears the isolated error when a new Published snapshot is loaded', () => {
    const current = { error: new Error('boom'), resetKey: '123:1', diagnosticId: 'ABC123' };
    expect(GameRuntimeErrorBoundary.getDerivedStateFromProps({
      children: null,
      onExit: vi.fn(),
      onRetry: vi.fn(),
      resetKey: '123:2',
    }, current)).toEqual({ error: null, resetKey: '123:2', diagnosticId: null });
  });

  // S15P21A604-186 조건 ④ — 사용자 화면에는 진단 ID만 보이고, 원본 에러 메시지·스택은 노출되지 않는다.
  it('shows a diagnostic id without leaking the raw error message', () => {
    const boundary = new GameRuntimeErrorBoundary({
      children: null,
      onExit: vi.fn(),
      onRetry: vi.fn(),
      resetKey: '123:1',
    });
    const derived = GameRuntimeErrorBoundary.getDerivedStateFromError(
      new Error('internal secret: /etc/festa/db-credentials'),
    );
    boundary.state = { error: derived.error!, resetKey: '123:1', diagnosticId: derived.diagnosticId! };

    const html = renderToStaticMarkup(boundary.render());
    expect(html).toContain('문의 코드');
    expect(html).not.toContain('internal secret');
    expect(html).not.toContain('/etc/festa');
  });

  // S15P21A604-186 조건 ③ — 같은 원인으로 반복 실패하면 재시도 루프에 가두지 않고 나가기만 남긴다.
  it('hides the retry button after 3 consecutive failures in the same mount', () => {
    const boundary = new GameRuntimeErrorBoundary({
      children: null,
      onExit: vi.fn(),
      onRetry: vi.fn(),
      resetKey: '123:1',
    });
    boundary.state = { error: new Error('boom'), resetKey: '123:1', diagnosticId: 'X1' };

    for (let attempt = 0; attempt < 3; attempt += 1) {
      boundary.componentDidCatch(new Error('boom'), { componentStack: '' });
    }
    const htmlAtThird = renderToStaticMarkup(boundary.render());
    expect(htmlAtThird).toContain('다시 불러오기');

    boundary.componentDidCatch(new Error('boom'), { componentStack: '' });
    const htmlAtFourth = renderToStaticMarkup(boundary.render());
    expect(htmlAtFourth).not.toContain('다시 불러오기');
    expect(htmlAtFourth).toContain('나가기');
    expect(htmlAtFourth).toContain('반복해서');
  });
});
