// OAuth 완료·게스트·refresh·logout의 실제 fetch 호출 — client.ts의 공용 api()를 재사용한다
// 출처: specs/001-auth-user/contracts/oauth-completion.md (complete)
//       origin/develop backend GuestAuthController (guest·refresh·logout — 계약 문서 부재, 구현이 정본)

import { api } from '../../shared/api/client';
import type { OAuthCompleteResponse, TokenResponse } from './types';

// 쿠키가 필요한 요청은 credentials:'include' 를 개별로 명시한다 — complete 는 계약이 그렇게
// 규정했고(oauth-completion.md FE 의무 1), refresh·logout 은 refresh_token 쿠키를 실어야 한다.
// api() 기본값으로 올리지 않는 이유는 쿠키가 필요 없는 나머지 요청까지 함께 실어 나르기 때문이다.

export function complete(body?: { nickname: string }): Promise<OAuthCompleteResponse> {
  return api<OAuthCompleteResponse>('/api/v1/auth/oauth/complete', {
    method: 'POST',
    credentials: 'include',
    body: body ? JSON.stringify(body) : undefined,
  });
}

// 게스트 발급은 쿠키를 주고받지 않는다 — BE 가 Set-Cookie 를 내리지 않는 것을 실측으로 확인했고,
// 그래서 게스트는 새로고침 복원 대상이 아니다(FR-009a 가 코드 수준에서 보장된다).
export function guestEnter(): Promise<TokenResponse> {
  return api<TokenResponse>('/api/v1/auth/guest', { method: 'POST' });
}

// skipAuthRetry: refresh 자신이 401 을 받으면 인터셉트가 다시 refresh 를 부르는 무한 루프가 된다
// (client.ts 의 상한 계약). 이 플래그는 그 경로를 끊는다.
export function refresh(): Promise<TokenResponse> {
  return api<TokenResponse>('/api/v1/auth/refresh', {
    method: 'POST',
    credentials: 'include',
    skipAuthRetry: true,
  });
}

// 204 — api() 가 undefined 로 정규화한다. 서버측 세션 폐기와 refresh_token 쿠키 만료가 함께 일어난다.
export function logout(): Promise<void> {
  return api<void>('/api/v1/auth/logout', {
    method: 'POST',
    credentials: 'include',
  });
}
