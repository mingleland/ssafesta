// @vitest-environment jsdom
// S15P21A604-186 조건 ① — 강제 오류 fixture에서 해당 게임 영역만 실패하는지 직접 검증한다.
// Boundary 밖(FESTA 나머지 화면을 흉내낸 형제 엘리먼트)은 실제 크래시가 나도 영향을 받지 않아야 한다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { GameRuntimeErrorBoundary } from '../../runtime/ui/GameRuntimeErrorBoundary.tsx';

afterEach(cleanup);

const Boom = (): never => {
  throw new Error('forced crash for isolation test');
};

describe('GameRuntimeErrorBoundary — 격리', () => {
  it('게임 화면이 크래시해도 FESTA 나머지 화면은 그대로 렌더된다', () => {
    // React가 캐치된 에러를 콘솔에 추가로 찍는 걸 막을 뿐, componentDidCatch 자체는 그대로 동작한다.
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <div>
        <span>FESTA 나머지 화면</span>
        <GameRuntimeErrorBoundary onExit={() => undefined} onRetry={() => undefined} resetKey="1">
          <Boom />
        </GameRuntimeErrorBoundary>
      </div>,
    );

    expect(screen.queryByText('FESTA 나머지 화면')).not.toBeNull();
    expect(screen.queryByText('게임 화면을 안전하게 종료했습니다')).not.toBeNull();

    consoleErrorSpy.mockRestore();
  });
});
