import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTH_BRIDGE_OBJECT, syncAccessToken } from '../../authBridge';
import { __resetSessionForTests, setGuestSession, setMemberSession, clearSession } from '../../../../features/auth/model/session';
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

describe('syncAccessToken (-91, #60, #128 §2)', () => {
  it('회원 세션이면 Access Token 원본을 AuthBridge.SetAccessToken 으로 밀어 넣는다', () => {
    setMemberSession('at-member', FUTURE());
    const unity = instance();
    expect(syncAccessToken(unity)).toBe('set');
    expect(unity.SendMessage).toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', 'at-member');
  });

  it('게스트도 같은 경로로 Access Token 을 전달한다 — 무토큰이면 world-sessions 가 401 이다 (#128 §2)', () => {
    setGuestSession('at-guest', FUTURE());
    const unity = instance();
    expect(syncAccessToken(unity)).toBe('set');
    expect(unity.SendMessage).toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', 'at-guest');
    expect(unity.SendMessage).not.toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'ClearAccessToken', expect.anything());
  });

  it('비로그인(anonymous, 토큰 없음)은 Clear — 회원도 게스트도 아닌 상태에는 아무 토큰도 넘기지 않는다', () => {
    const unity = instance();
    expect(syncAccessToken(unity)).toBe('cleared');
    expect(unity.SendMessage).toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'ClearAccessToken', '');
    expect(unity.SendMessage).not.toHaveBeenCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', expect.anything());
  });

  it('로그아웃 후에는 Clear — 이전 회원 토큰이 Unity 에 남지 않는다', () => {
    setMemberSession('at-member', FUTURE());
    clearSession();
    const unity = instance();
    expect(syncAccessToken(unity)).toBe('cleared');
  });

  it('refresh 로 토큰이 바뀌면 새 값을 다시 Set 한다 (멱등)', () => {
    setMemberSession('at-1', FUTURE());
    const unity = instance();
    syncAccessToken(unity);
    setMemberSession('at-2', FUTURE());
    syncAccessToken(unity);
    expect(unity.SendMessage).toHaveBeenLastCalledWith(AUTH_BRIDGE_OBJECT, 'SetAccessToken', 'at-2');
  });
});
