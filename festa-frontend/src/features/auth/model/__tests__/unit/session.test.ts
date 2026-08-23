// session store 회귀 방어(T005) — 토큰(client.ts)과 kind가 항상 함께 갱신되는지 확인한다.
import { beforeEach, describe, expect, it } from 'vitest';
import { getAccessToken } from '../../../../../shared/api/client';
import {
  clearSession,
  getSessionSnapshot,
  setGuestSession,
  setMemberSession,
  __resetSessionForTests,
} from '../../session';

beforeEach(() => {
  __resetSessionForTests();
});

describe('setMemberSession', () => {
  it('kind를 member로, AT를 client.ts 메모리에 동시에 설정한다', () => {
    setMemberSession('at-1', '2026-01-01T00:00:00.000Z');
    expect(getSessionSnapshot()).toEqual({ kind: 'member', expiresAt: '2026-01-01T00:00:00.000Z', notice: null });
    expect(getAccessToken()).toBe('at-1');
  });
});

describe('setGuestSession', () => {
  it('kind를 guest로, AT를 client.ts 메모리에 동시에 설정한다', () => {
    setGuestSession('at-guest', '2026-01-01T00:30:00.000Z');
    expect(getSessionSnapshot()).toEqual({ kind: 'guest', expiresAt: '2026-01-01T00:30:00.000Z', notice: null });
    expect(getAccessToken()).toBe('at-guest');
  });
});

describe('clearSession', () => {
  it('kind를 anonymous로 되돌리고 AT를 지운다', () => {
    setMemberSession('at-1', '2026-01-01T00:00:00.000Z');
    clearSession();
    expect(getSessionSnapshot()).toEqual({ kind: 'anonymous', expiresAt: null, notice: null });
    expect(getAccessToken()).toBeNull();
  });

  it('notice를 함께 기록한다(401 인터셉트가 사유를 남기는 경로)', () => {
    setMemberSession('at-1', '2026-01-01T00:00:00.000Z');
    clearSession('session-expired');
    expect(getSessionSnapshot().notice).toBe('session-expired');
  });
});

describe('토큰-kind 정합성', () => {
  it('member → guest → clear로 전이해도 AT와 kind가 항상 짝을 이룬다', () => {
    setMemberSession('at-member', '2026-01-01T00:00:00.000Z');
    expect(getAccessToken()).toBe('at-member');
    expect(getSessionSnapshot().kind).toBe('member');

    setGuestSession('at-guest', '2026-01-01T00:30:00.000Z');
    expect(getAccessToken()).toBe('at-guest');
    expect(getSessionSnapshot().kind).toBe('guest');

    clearSession();
    expect(getAccessToken()).toBeNull();
    expect(getSessionSnapshot().kind).toBe('anonymous');
  });
});
