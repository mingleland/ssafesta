// 401 인터셉트 실 로직(T011) — member는 refresh 1회 성공 시 재시도 허용, 실패 시 세션 클리어.
// guest는 재발급 시도 없이 즉시 클리어(FR-009a). refresh는 동시 호출에도 1회만 나간다(single-flight).
import { beforeEach, describe, expect, it, vi } from 'vitest';

const refreshMock = vi.fn();
vi.mock('../../../../../entities/auth/api.select', () => ({
  authApi: { refresh: (...args: unknown[]) => refreshMock(...args) },
}));

import { handleUnauthorized } from '../../unauthorizedHandler';
import { getSessionSnapshot, setGuestSession, setMemberSession, __resetSessionForTests } from '../../session';

beforeEach(() => {
  refreshMock.mockReset();
  __resetSessionForTests();
});

describe('member', () => {
  it('refresh 성공 시 세션을 갱신하고 true를 반환한다(client.ts가 원요청을 재시도)', async () => {
    setMemberSession('at-old', '2026-01-01T00:00:00.000Z');
    refreshMock.mockResolvedValue({ accessToken: 'at-new', expiresAt: '2026-01-01T01:00:00.000Z' });

    const recovered = await handleUnauthorized();

    expect(recovered).toBe(true);
    expect(getSessionSnapshot()).toEqual({ kind: 'member', expiresAt: '2026-01-01T01:00:00.000Z', notice: null, bootstrapped: false });
  });

  it('refresh 실패 시 세션을 클리어하고 false를 반환한다(FR-020b)', async () => {
    setMemberSession('at-old', '2026-01-01T00:00:00.000Z');
    refreshMock.mockRejectedValue({ code: 'REFRESH_FAILED', message: 'x', errors: [], warnings: [] });

    const recovered = await handleUnauthorized();

    expect(recovered).toBe(false);
    expect(getSessionSnapshot()).toEqual({ kind: 'anonymous', expiresAt: null, notice: 'session-expired', bootstrapped: false });
  });

  it('동시에 여러 번 불려도 refresh는 1회만 나간다(single-flight)', async () => {
    setMemberSession('at-old', '2026-01-01T00:00:00.000Z');
    refreshMock.mockResolvedValue({ accessToken: 'at-new', expiresAt: '2026-01-01T01:00:00.000Z' });

    const [a, b] = await Promise.all([handleUnauthorized(), handleUnauthorized()]);

    expect(a).toBe(true);
    expect(b).toBe(true);
    expect(refreshMock).toHaveBeenCalledTimes(1);
  });
});

// 다중 탭 경합 (S15P21A604-812, GitLab #198). 탭 둘이 같은 refresh_token 으로 동시에 refresh 를
// 부르면 진 쪽이 재사용 감지에 걸려 세션이 통째로 끊겼다. BE 가 30초 유예와 전용 코드를 넣었고,
// 쿠키에는 이미 새 토큰이 있으므로 한 번 더 부르면 성공한다.
describe('REFRESH_TOKEN_ROTATED', () => {
  const rotated = { code: 'REFRESH_TOKEN_ROTATED', message: 'x', status: 401, errors: [], warnings: [] };

  it('회전 경합이면 한 번 더 부르고 성공하면 세션을 유지한다', async () => {
    setMemberSession('at-old', '2026-01-01T00:00:00.000Z');
    refreshMock
      .mockRejectedValueOnce(rotated)
      .mockResolvedValueOnce({ accessToken: 'at-new', expiresAt: '2026-01-01T01:00:00.000Z' });

    const recovered = await handleUnauthorized();

    expect(recovered).toBe(true);
    expect(refreshMock).toHaveBeenCalledTimes(2);
    expect(getSessionSnapshot()).toEqual({ kind: 'member', expiresAt: '2026-01-01T01:00:00.000Z', notice: null, bootstrapped: false });
  });

  it('두 번째도 회전이면 멈춘다 — 유예를 넘겼거나 계보가 정말 끊긴 것이다', async () => {
    setMemberSession('at-old', '2026-01-01T00:00:00.000Z');
    refreshMock.mockRejectedValue(rotated);

    const recovered = await handleUnauthorized();

    expect(recovered).toBe(false);
    expect(refreshMock).toHaveBeenCalledTimes(2);
    expect(getSessionSnapshot()).toEqual({ kind: 'anonymous', expiresAt: null, notice: 'session-expired', bootstrapped: false });
  });

  it('다른 코드는 재시도하지 않는다 — 회전 경합이 아닌 실패까지 두 번 두드리지 않는다', async () => {
    setMemberSession('at-old', '2026-01-01T00:00:00.000Z');
    refreshMock.mockRejectedValue({ code: 'REFRESH_FAILED', message: 'x', status: 401, errors: [], warnings: [] });

    const recovered = await handleUnauthorized();

    expect(recovered).toBe(false);
    expect(refreshMock).toHaveBeenCalledTimes(1);
  });
});

describe('guest', () => {
  it('재발급 시도 없이 즉시 세션을 클리어한다(자동 재발급 금지)', async () => {
    setGuestSession('at-guest', '2026-01-01T00:30:00.000Z');

    const recovered = await handleUnauthorized();

    expect(recovered).toBe(false);
    expect(refreshMock).not.toHaveBeenCalled();
    expect(getSessionSnapshot()).toEqual({ kind: 'anonymous', expiresAt: null, notice: 'guest-reentry-required', bootstrapped: false });
  });
});

describe('anonymous', () => {
  it('refresh를 부르지 않고 false를 반환한다', async () => {
    const recovered = await handleUnauthorized();
    expect(recovered).toBe(false);
    expect(refreshMock).not.toHaveBeenCalled();
  });
});
