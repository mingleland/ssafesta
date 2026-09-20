// 로그인 provider 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01).
// UI Track은 이 타입만 소비한다. OAuth URL·redirect·DTO는 여기 없다 — 그것은 Adapter(entities/auth) 내부다.
// provider 3종(google·kakao·ssafy)이 모두 배선돼 있다 — SSAFY 는 BE -357 도달 후 -495 에서 활성화했다.

export type AuthProviderId = 'google' | 'kakao' | 'guest' | 'ssafy';

export type ProviderAvailability = 'available' | 'not_configured' | 'disabled';

export interface AuthProviderVM {
  id: AuthProviderId;
  label: string;
  availability: ProviderAvailability;
  /** guest는 리다이렉트 없이 세션 발급, oauth는 전체 페이지 이동(LoginPage 관례) */
  kind: 'oauth' | 'guest';
}

export interface AuthCallbackVM {
  phase: 'idle' | 'loading' | 'success' | 'needs_setup' | 'error';
  errorMessage?: string;
}
