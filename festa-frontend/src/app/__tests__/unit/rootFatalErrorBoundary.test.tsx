// @vitest-environment jsdom
// 최외곽 경계 — router·provider 가 하나도 없는 자리에서도 그려져야 한다.
// 이 테스트가 provider 를 일부러 하나도 감싸지 않는 것이 요점이다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { RootFatalErrorBoundary } from '../../RootFatalErrorBoundary';

function Thrower(): never {
  throw new Error('최상위 폭발');
}

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('RootFatalErrorBoundary', () => {
  it('정상일 때는 자식을 그대로 통과시킨다', () => {
    render(<RootFatalErrorBoundary><p>정상 화면</p></RootFatalErrorBoundary>);
    expect(screen.getByText('정상 화면')).not.toBeNull();
  });

  it('자식이 던지면 빈 화면 대신 복구 안내를 그린다', () => {
    render(<RootFatalErrorBoundary><Thrower /></RootFatalErrorBoundary>);
    expect(screen.getByText('화면을 표시하지 못했습니다')).not.toBeNull();
    expect(screen.getByRole('button', { name: '새로 불러오기' })).not.toBeNull();
    expect(screen.getByRole('button', { name: '처음 화면으로' })).not.toBeNull();
    expect(document.body.textContent ?? '').not.toContain('최상위 폭발');
  });
});
