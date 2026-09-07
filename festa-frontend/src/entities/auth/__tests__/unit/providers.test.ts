import { describe, expect, it } from 'vitest';
import { authProviders, oauthProviders, guestProvider, isConfiguredOAuth } from '../../providers';

describe('auth provider registry', () => {
  it('4개 provider 를 선언한다 — google·kakao·ssafy·guest', () => {
    expect(authProviders.map((p) => p.id)).toEqual(['google', 'kakao', 'ssafy', 'guest']);
  });

  it('ssafy 는 available — BE 계약(-357)이 develop 에 도달해 -495 에서 배선했다', () => {
    const ssafy = authProviders.find((p) => p.id === 'ssafy')!;
    expect(ssafy.availability).toBe('available');
    expect(ssafy.kind).toBe('oauth');
    expect(isConfiguredOAuth('ssafy')).toBe(true);
  });

  it('OAuth 3종이 배선돼 있고 guest 는 OAuth 가 아니다', () => {
    expect(isConfiguredOAuth('google')).toBe(true);
    expect(isConfiguredOAuth('kakao')).toBe(true);
    expect(isConfiguredOAuth('guest')).toBe(false);
  });

  it('배선 판정은 availability 하나로 갈린다 — 목록에 있다고 배선된 것이 아니다', () => {
    // 회귀 방어: 다시 not_configured 로 내려도 버튼만 죽고 startOAuth 는 살아 있는 상태를 막는다.
    expect(oauthProviders.every((p) => isConfiguredOAuth(p.id) === (p.availability === 'available'))).toBe(true);
  });

  it('oauthProviders 는 guest 를 제외한다', () => {
    expect(oauthProviders.map((p) => p.id)).toEqual(['google', 'kakao', 'ssafy']);
    expect(guestProvider.id).toBe('guest');
  });
});
