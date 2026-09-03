import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTH_BRIDGE_OBJECT, syncAccessToken } from '../../authBridge';
import { __resetSessionForTests, getSessionSnapshot, setGuestSession, setMemberSession, clearSession } from '../../../../features/auth/model/session';
import type { UnityInstance } from '../../types';

const FUTURE = () => new Date(Date.now() + 60_000).toISOString();

function instance() {
  return { SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: vi.fn(async () => {}) } as unknown as UnityInstance & {
    SendMessage: ReturnType<typeof vi.fn>;
  };
}

beforeEach(() => {
  __resetSessionForTests();
});

describe('syncAccessToken (-91, #60)', () => {
  it('회원 세션이면 Access Token 원본을 AuthBridge.SetAccessToken 으로 밀어 넣는다', () => {
    setMemberSession('at-member', FUTURE());
    const unity = instance();
    expect(syncAccessToken(unity, getSessionSnapshot())).toBe('set');
    expect(unity.SendMessage).toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', 'at-member');
  });

  it('게스트는 토큰을 노출하지 않는다 — ClearAccessToken (열람 전용)', () => {
    setGuestSession('at-guest', FUTURE());
    const unity = instance();
    expect(syncAccessToken(unity, getSessionSnapshot())).toBe('cleared');
    expect(unity.SendMessage).toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'ClearAccessToken', '');
    expect(unity.SendMessage).not.toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', expect.anything());
  });

  it('로그아웃(anonymous) 도 Clear — 이전 회원 토큰이 Unity 에 남지 않는다', () => {
    setMemberSession('at-member', FUTURE());
    clearSession();
    const unity = instance();
    expect(syncAccessToken(unity, getSessionSnapshot())).toBe('cleared');
  });

  it('refresh 로 토큰이 바뀌면 새 값을 다시 Set 한다 (멱등)', () => {
    setMemberSession('at-1', FUTURE());
    const unity = instance();
    syncAccessToken(unity, getSessionSnapshot());
    setMemberSession('at-2', FUTURE());
    syncAccessToken(unity, getSessionSnapshot());
    expect(unity.SendMessage).toHaveBeenLastCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', 'at-2');
  });
});
