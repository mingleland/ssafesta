// 만료 전 예약 갱신 (S15P21A604-828).
//
// 잠그는 것은 셋이다 — 회원 세션이 서면 만료 전에 갱신을 예약한다 · 게스트와 비로그인은
// 예약하지 않는다 · 이미 지난 만료를 받아도 제자리에서 돌지 않는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const refreshSessionNow = vi.fn();
vi.mock('../../unauthorizedHandler', () => ({
  refreshSessionNow: () => refreshSessionNow(),
  installUnauthorizedHandler: vi.fn(),
  handleUnauthorized: vi.fn(),
}));

import { __resetSessionForTests, clearSession, setGuestSession, setMemberSession } from '../../session';
import { __resetRefreshSchedulerForTests, startRefreshScheduler } from '../../refreshScheduler';

const MINUTE = 60 * 1000;
const future = (ms: number) => new Date(Date.now() + ms).toISOString();

beforeEach(() => {
  vi.useFakeTimers();
  refreshSessionNow.mockReset().mockResolvedValue(true);
  __resetSessionForTests();
  startRefreshScheduler();
});
afterEach(() => {
  __resetRefreshSchedulerForTests();
  __resetSessionForTests();
  vi.useRealTimers();
});

describe('만료 전 예약 갱신 (-828)', () => {
  it('회원 세션이 서면 만료 2분 전에 갱신한다 — 401 을 기다리지 않는다', () => {
    setMemberSession('at-1', future(30 * MINUTE));

    vi.advanceTimersByTime(27 * MINUTE);
    expect(refreshSessionNow).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1 * MINUTE);
    expect(refreshSessionNow).toHaveBeenCalledTimes(1);
  });

  it('갱신될 때마다 다음 만료로 다시 잡는다', () => {
    setMemberSession('at-1', future(30 * MINUTE));
    vi.advanceTimersByTime(28 * MINUTE);
    expect(refreshSessionNow).toHaveBeenCalledTimes(1);

    // 갱신 성공이 새 세션을 쓰면 그 시점부터 다시 센다
    setMemberSession('at-2', future(30 * MINUTE));
    vi.advanceTimersByTime(27 * MINUTE);
    expect(refreshSessionNow).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1 * MINUTE);
    expect(refreshSessionNow).toHaveBeenCalledTimes(2);
  });

  it('게스트는 예약하지 않는다 — 자동 재발급이 아니라 재입장 안내가 동선이다 (FR-009a)', () => {
    setGuestSession('guest-at', future(30 * MINUTE));
    vi.advanceTimersByTime(40 * MINUTE);
    expect(refreshSessionNow).not.toHaveBeenCalled();
  });

  it('세션이 끊기면 예약도 걷는다', () => {
    setMemberSession('at-1', future(30 * MINUTE));
    clearSession('session-expired');
    vi.advanceTimersByTime(40 * MINUTE);
    expect(refreshSessionNow).not.toHaveBeenCalled();
  });

  it('이미 지난 만료를 받아도 제자리에서 돌지 않는다 — 최소 대기를 둔다', () => {
    setMemberSession('at-stale', future(-10 * MINUTE));

    vi.advanceTimersByTime(1000);
    expect(refreshSessionNow).not.toHaveBeenCalled();

    vi.advanceTimersByTime(5000);
    expect(refreshSessionNow).toHaveBeenCalledTimes(1);
  });
});
