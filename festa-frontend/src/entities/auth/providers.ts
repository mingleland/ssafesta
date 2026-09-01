// 로그인 provider registry — shared/contracts/auth 계약의 유일한 구현 데이터.
// SSAFY 는 제품 결정상 도입 확정(D-07)이지만 BE enum·OAuth 계약이 아직 없다(S15P21A604-357 예정)
// — availability='not_configured' 로 선언만 하고, BE 계약이 develop 에 도달하면 이 한 줄을
// 'available' 로 바꾸는 것이 활성화의 전부여야 한다. OAuth URL·DTO 를 여기서 발명하지 않는다.
import type { AuthProviderId, AuthProviderVM } from '../../shared/contracts/auth';

export const authProviders: readonly AuthProviderVM[] = [
  // label 은 확정 로그인 reference(login.png)의 버튼 문구를 그대로 따른다 (S15P21A604-379)
  { id: 'google', label: 'Google 로그인', availability: 'available', kind: 'oauth' },
  { id: 'kakao', label: 'Kakao 로그인', availability: 'available', kind: 'oauth' },
  { id: 'ssafy', label: 'SSAFY 로그인', availability: 'not_configured', kind: 'oauth' },
  { id: 'guest', label: '게스트로 둘러보기', availability: 'available', kind: 'guest' },
];

export const oauthProviders: readonly AuthProviderVM[] = authProviders.filter((p) => p.kind === 'oauth');

export const guestProvider: AuthProviderVM = authProviders.find((p) => p.kind === 'guest')!;

/** 실제 OAuth 리다이렉트가 배선된 provider 인가 — mockStartOAuth·BE 경로 둘 다 이 둘뿐이다 */
export function isConfiguredOAuth(id: AuthProviderId): id is 'google' | 'kakao' {
  const provider = authProviders.find((p) => p.id === id);
  return provider?.kind === 'oauth' && provider.availability === 'available';
}
