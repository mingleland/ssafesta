// 로그인 provider registry — shared/contracts/auth 계약의 유일한 구현 데이터.
// SSAFY 는 BE 계약(S15P21A604-357)이 develop 에 도달해 2026-09-07 에 활성화했다 — -366 이 남긴
// 해제 경로대로 availability 한 줄만 바꿨고 OAuth URL·DTO 는 여전히 여기 없다(Adapter 소관).
// 세 provider 모두 같은 경로를 쓴다: GET /api/v1/auth/oauth/{id} → 동의 → /auth/callback.
import type { AuthProviderId, AuthProviderVM } from '../../shared/contracts/auth';

export const authProviders: readonly AuthProviderVM[] = [
  // label 은 확정 로그인 reference(login.png)의 버튼 문구를 그대로 따른다 (S15P21A604-379)
  { id: 'google', label: 'Google 로그인', availability: 'available', kind: 'oauth' },
  { id: 'kakao', label: 'Kakao 로그인', availability: 'available', kind: 'oauth' },
  { id: 'ssafy', label: 'SSAFY 로그인', availability: 'available', kind: 'oauth' },
  { id: 'guest', label: '게스트로 둘러보기', availability: 'available', kind: 'guest' },
];

export const oauthProviders: readonly AuthProviderVM[] = authProviders.filter((p) => p.kind === 'oauth');

export const guestProvider: AuthProviderVM = authProviders.find((p) => p.kind === 'guest')!;

/** 실제 OAuth 리다이렉트가 배선된 provider 인가 — mockStartOAuth·BE 경로 둘 다 이 셋이다 */
export function isConfiguredOAuth(id: AuthProviderId): id is 'google' | 'kakao' | 'ssafy' {
  const provider = authProviders.find((p) => p.id === id);
  return provider?.kind === 'oauth' && provider.availability === 'available';
}
