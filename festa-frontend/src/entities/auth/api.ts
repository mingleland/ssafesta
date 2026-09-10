// OAuth 완료·게스트·refresh·logout의 실제 fetch 호출 — client.ts의 공용 api()를 재사용한다
// 출처: specs/001-auth-user/contracts/oauth-completion.md (complete)
//       origin/develop backend GuestAuthController (guest·refresh·logout — 계약 문서 부재, 구현이 정본)

import { api } from '../../shared/api/client';
import { authBaseUrl } from '../../shared/config/runtime';
import type { OAuthCompleteResponse, TokenResponse } from './types';

// 쿠키가 필요한 요청은 credentials:'include' 를 개별로 명시한다 — complete 는 계약이 그렇게
// 규정했고(oauth-completion.md FE 의무 1), refresh·logout 은 refresh_token 쿠키를 실어야 한다.
// api() 기본값으로 올리지 않는 이유는 쿠키가 필요 없는 나머지 요청까지 함께 실어 나르기 때문이다.

// **이 파일의 네 호출은 일반 API 와 다른 base 를 쓴다** (S15P21A604-564). `refresh_token` 과
// `oauth_handoff` 는 domain 속성이 없는 host-only 쿠키이고, 그것을 내리는 쪽은 provider 에 등록된
// redirect URI 가 가리키는 호스트다. 그 호스트로 다시 묻지 않으면 쿠키가 실리지 않는다.
// guest 는 쿠키를 주고받지 않지만(BE 가 Set-Cookie 를 내리지 않는다 — 그래서 게스트는 새로고침
// 복원 대상이 아니고 FR-009a 가 코드 수준에서 보장된다) 같은 base 로 둔다: logout 은 게스트도
// 호출하고, 인증 축을 한 호스트로 모아 두면 규칙이 하나로 남는다.
// 게이트웨이가 없는 배포에서는 authBaseUrl() 이 apiBaseUrl() 과 같아 동작이 종전과 같다.

export function complete(body?: { nickname: string }): Promise<OAuthCompleteResponse> {
  return api<OAuthCompleteResponse>('/api/v1/auth/oauth/complete', {
    method: 'POST',
    baseUrl: authBaseUrl(),
    credentials: 'include',
    body: body ? JSON.stringify(body) : undefined,
  });
}

export function guestEnter(): Promise<TokenResponse> {
  return api<TokenResponse>('/api/v1/auth/guest', { method: 'POST', baseUrl: authBaseUrl() });
}

// skipAuthRetry: refresh 자신이 401 을 받으면 인터셉트가 다시 refresh 를 부르는 무한 루프가 된다
// (client.ts 의 상한 계약). 이 플래그는 그 경로를 끊는다.
export function refresh(): Promise<TokenResponse> {
  return api<TokenResponse>('/api/v1/auth/refresh', {
    method: 'POST',
    baseUrl: authBaseUrl(),
    credentials: 'include',
    skipAuthRetry: true,
  });
}

// 204 — api() 가 undefined 로 정규화한다. 서버측 세션 폐기와 refresh_token 쿠키 만료가 함께 일어난다.
export function logout(): Promise<void> {
  return api<void>('/api/v1/auth/logout', {
    method: 'POST',
    baseUrl: authBaseUrl(),
    credentials: 'include',
  });
}
