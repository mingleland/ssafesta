// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';

const openPanel = vi.hoisted(() => vi.fn());
vi.mock('../../../world/model/worldScreen', () => ({ openMenuPanelScreen: openPanel }));

const { DailyMissionLauncher } = await import('../../ui/DailyMissionLauncher');
const { __resetSessionForTests, setGuestSession, setMemberSession } = await import('../../../auth/model/session');

beforeEach(() => {
  openPanel.mockReset();
  __resetSessionForTests();
});

afterEach(() => {
  cleanup();
  __resetSessionForTests();
});

describe('일일 미션 런처', () => {
  it('회원이 누르면 기존 미션 패널을 연다', () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    render(<DailyMissionLauncher />);

    fireEvent.click(screen.getByRole('button', { name: '일일 미션' }));
    expect(openPanel).toHaveBeenCalledWith('missions');
  });

  it('게스트와 비로그인에는 보이지 않는다', () => {
    const { rerender } = render(<DailyMissionLauncher />);
    expect(screen.queryByRole('button', { name: '일일 미션' })).toBeNull();

    setGuestSession('at', new Date(Date.now() + 60_000).toISOString());
    rerender(<DailyMissionLauncher />);
    expect(screen.queryByRole('button', { name: '일일 미션' })).toBeNull();
  });
});
