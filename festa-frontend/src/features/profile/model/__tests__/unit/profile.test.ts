// Profile 상태 기계 전이 검증 — userApi 는 mock 어댑터로 대체(unauthorizedHandler.test 패턴)
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/user/api.select', async () => {
  const mockApi = await import('../../../../../entities/user/api.mock');
  return { userApi: mockApi };
});

import {
  __resetProfileForTests,
  beginWithdrawal,
  cancelWithdrawal,
  confirmWithdrawal,
  getProfileSnapshot,
  loadProfile,
  saveAvatar,
  submitNickname,
} from '../../profile';
import { __resetUserMockForTests } from '../../../../../entities/user/api.mock';
import { getSessionSnapshot, setMemberSession, __resetSessionForTests } from '../../../../auth/model/session';

beforeEach(() => {
  __resetProfileForTests();
  __resetUserMockForTests();
  __resetSessionForTests();
});

describe('loadProfile', () => {
  it('idle → loading → ready, 계정 필드가 계약 어휘로 매핑된다', async () => {
    const done = loadProfile();
    expect(getProfileSnapshot().status).toBe('loading');
    await done;
    const s = getProfileSnapshot();
    expect(s.status).toBe('ready');
    expect(s.account).toEqual({
      userId: 1,
      nickname: '페스타참가자',
      providers: ['google'],
      avatarCode: null,
    });
  });
});

describe('submitNickname', () => {
  it('성공 시 success + 닉네임 반영', async () => {
    await loadProfile();
    await submitNickname('새닉네임');
    const s = getProfileSnapshot();
    expect(s.nicknameEdit.phase).toBe('success');
    expect(s.account?.nickname).toBe('새닉네임');
  });

  it('409 중복은 duplicated, 400 정책 위반은 invalid — 기존 닉네임 유지', async () => {
    await loadProfile();
    await submitNickname('taken');
    expect(getProfileSnapshot().nicknameEdit).toEqual({ phase: 'error', errorKind: 'duplicated' });
    await submitNickname('관리자');
    expect(getProfileSnapshot().nicknameEdit).toEqual({ phase: 'error', errorKind: 'invalid' });
    expect(getProfileSnapshot().account?.nickname).toBe('페스타참가자');
  });
});

describe('saveAvatar', () => {
  it('서버 echo 를 상태에 반영한다', async () => {
    await loadProfile();
    await saveAvatar('AVATAR-CODE-1');
    expect(getProfileSnapshot().account?.avatarCode).toBe('AVATAR-CODE-1');
  });
});

describe('withdrawal', () => {
  it('confirming 을 거치지 않으면 confirmWithdrawal 은 no-op — BE WITHDRAWAL_NOT_CONFIRMED 와 같은 방향', async () => {
    await confirmWithdrawal();
    expect(getProfileSnapshot().withdrawal.phase).toBe('idle');
  });

  it('begin → confirm 성공 시 프로필 초기화 + 세션 종료', async () => {
    setMemberSession('at', new Date(Date.now() + 60_000).toISOString());
    await loadProfile();
    beginWithdrawal();
    expect(getProfileSnapshot().withdrawal.phase).toBe('confirming');
    await confirmWithdrawal();
    expect(getProfileSnapshot()).toMatchObject({ status: 'idle', account: null });
    expect(getSessionSnapshot().kind).toBe('anonymous');
  });

  it('cancel 은 idle 로 되돌린다', () => {
    beginWithdrawal();
    cancelWithdrawal();
    expect(getProfileSnapshot().withdrawal.phase).toBe('idle');
  });
});
