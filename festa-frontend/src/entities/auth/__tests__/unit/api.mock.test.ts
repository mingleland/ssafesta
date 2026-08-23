// api.mock.ts 시맨틱 회귀 방어(T003·T014) — plan.md §Mock 전략의 시나리오 전부.
import { beforeEach, describe, expect, it } from 'vitest';
import { isApiError } from '../../../../shared/api/client';
import {
  complete,
  guestEnter,
  mockStartOAuth,
  refresh,
  __resetAuthMockForTests,
  __triggerOtherBrowserLoginForTests,
} from '../../api.mock';

beforeEach(() => {
  __resetAuthMockForTests();
});

describe('신규 회원 흐름', () => {
  it('handoff 없이 직진입하면 handoff 누락 오류를 던진다(400 상당)', async () => {
    await expect(complete()).rejects.toMatchObject({ code: 'OAUTH_HANDOFF_MISSING' });
  });

  it('최초 로그인은 NICKNAME_REQUIRED, handoff는 소비되지 않는다(FR-021c)', async () => {
    mockStartOAuth('google');
    const res = await complete();
    expect(res).toEqual({ status: 'NICKNAME_REQUIRED', accessToken: null, expiresAt: null });

    // 재호출해도 여전히 NICKNAME_REQUIRED — handoff가 소비되지 않았다는 증거
    const res2 = await complete();
    expect(res2.status).toBe('NICKNAME_REQUIRED');
  });

  it('금칙 케이스 제출은 일반 안내 오류만 던지고 handoff는 보존한다(재제출 가능)', async () => {
    mockStartOAuth('google');
    await expect(complete({ nickname: 'admin' })).rejects.toMatchObject({ code: 'NICKNAME_REJECTED' });
    // 재제출 — 여전히 가능해야 한다
    const res = await complete({ nickname: '정상닉네임' });
    expect(res.status).toBe('AUTHENTICATED');
  });

  it('정규화 우회(기호·공백 삽입)도 대표 케이스와 동일하게 차단한다', async () => {
    mockStartOAuth('google');
    await expect(complete({ nickname: 'a-d-m-i-n' })).rejects.toMatchObject({ code: 'NICKNAME_REJECTED' });
    mockStartOAuth('kakao');
    await expect(complete({ nickname: '관 리 자' })).rejects.toMatchObject({ code: 'NICKNAME_REJECTED' });
  });

  it('유효한 닉네임 제출 시 가입 기록 후 AUTHENTICATED, handoff 소비 → 재호출은 410 상당', async () => {
    mockStartOAuth('google');
    const res = await complete({ nickname: '테스트유저' });
    expect(res.status).toBe('AUTHENTICATED');
    if (res.status !== 'AUTHENTICATED') throw new Error('unreachable');
    expect(typeof res.accessToken).toBe('string');
    expect(typeof res.expiresAt).toBe('string');

    await expect(complete()).rejects.toMatchObject({ code: 'OAUTH_HANDOFF_EXPIRED' });
  });
});

describe('기존 회원 재로그인', () => {
  it('같은 provider로 다시 로그인하면 닉네임 폼 없이 즉시 AUTHENTICATED', async () => {
    mockStartOAuth('google');
    await complete({ nickname: '테스트유저' }); // 최초 가입

    mockStartOAuth('google'); // 재로그인 — 새 handoff
    const res = await complete();
    expect(res.status).toBe('AUTHENTICATED');
  });
});

describe('게스트 입장(FR-009a)', () => {
  it('fake AT 발급 + expiresAt이 약 30분 뒤다', async () => {
    const before = Date.now();
    const res = await guestEnter();
    expect(res.status).toBe('AUTHENTICATED');
    if (res.status !== 'AUTHENTICATED') throw new Error('unreachable');
    const expiresAtMs = new Date(res.expiresAt).getTime();
    expect(expiresAtMs - before).toBeGreaterThan(29 * 60_000);
    expect(expiresAtMs - before).toBeLessThan(31 * 60_000);
  });
});

describe('refresh — 다른 브라우저 로그인 트리거(US3 AS4)', () => {
  it('로그인 이력이 없으면 실패한다', async () => {
    await expect(refresh()).rejects.toMatchObject({ code: 'REFRESH_FAILED' });
  });

  it('member 로그인 후에는 성공한다', async () => {
    mockStartOAuth('google');
    await complete({ nickname: '테스트유저' });
    const res = await refresh();
    expect(res.status).toBe('AUTHENTICATED');
  });

  it('게스트는 refresh 대상이 아니다', async () => {
    await guestEnter();
    await expect(refresh()).rejects.toMatchObject({ code: 'REFRESH_FAILED' });
  });

  it('다른 브라우저 로그인 트리거 후에는 refresh가 실패한다', async () => {
    mockStartOAuth('google');
    await complete({ nickname: '테스트유저' });
    __triggerOtherBrowserLoginForTests();
    await expect(refresh()).rejects.toMatchObject({ code: 'REFRESH_FAILED' });
  });
});

describe('isApiError 봉투 형태(client.ts와 동일)', () => {
  it('mock이 던지는 오류가 isApiError를 통과한다', async () => {
    try {
      await complete();
      throw new Error('should have thrown');
    } catch (err) {
      expect(isApiError(err)).toBe(true);
    }
  });
});
