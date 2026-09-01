import { describe, expect, it } from 'vitest';
import { authProviders, oauthProviders, guestProvider, isConfiguredOAuth } from '../../providers';

describe('auth provider registry', () => {
  it('4개 provider 를 선언한다 — google·kakao·ssafy·guest', () => {
    expect(authProviders.map((p) => p.id)).toEqual(['google', 'kakao', 'ssafy', 'guest']);
  });

  it('ssafy 는 not_configured — BE 계약(-357) 전에는 배선되지 않는다', () => {
    const ssafy = authProviders.find((p) => p.id === 'ssafy')!;
    expect(ssafy.availability).toBe('not_configured');
    expect(ssafy.kind).toBe('oauth');
    expect(isConfiguredOAuth('ssafy')).toBe(false);
  });

  it('google·kakao 만 배선된 OAuth 다', () => {
    expect(isConfiguredOAuth('google')).toBe(true);
    expect(isConfiguredOAuth('kakao')).toBe(true);
    expect(isConfiguredOAuth('guest')).toBe(false);
  });

  it('oauthProviders 는 guest 를 제외한다', () => {
    expect(oauthProviders.map((p) => p.id)).toEqual(['google', 'kakao', 'ssafy']);
    expect(guestProvider.id).toBe('guest');
  });
});
