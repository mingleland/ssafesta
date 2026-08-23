// OAuth 완료·게스트·refresh·logout의 실제 fetch 호출 — client.ts의 공용 api()를 재사용한다
// 출처: specs/001-auth-user/contracts/oauth-completion.md

import { api } from '../../shared/api/client';
import type { OAuthCompleteResponse } from './types';

// FE 의무 1(oauth-completion.md): 완료 호출에 credentials:'include' 명시
export function complete(body?: { nickname: string }): Promise<OAuthCompleteResponse> {
  return api<OAuthCompleteResponse>('/api/v1/auth/oauth/complete', {
    method: 'POST',
    credentials: 'include',
    body: body ? JSON.stringify(body) : undefined,
  });
}

// 게스트·refresh·logout endpoint 경로는 확정 계약 3종(spec 394줄판·oauth-completion.md·nickname-policy.md)에
// 없다(plan.md §미결). 추측 확정 금지(헌법 30조) — 명시 오류로 막아 두고 BE 계약 회수 후 경로만 기입한다.
// TODO(001-BE): 계약 회수 후 경로 기입 (specs/001-auth-user/tasks.md T016, Blocked-on-BE)
export function guestEnter(): Promise<OAuthCompleteResponse> {
  throw new Error(
    'entities/auth/api.ts#guestEnter: endpoint 경로 미확정 — BE 계약 회수 후 구현 (specs/001-auth-user/FE/plan.md §미결)',
  );
}

export function refresh(): Promise<OAuthCompleteResponse> {
  throw new Error(
    'entities/auth/api.ts#refresh: endpoint 경로 미확정 — BE 계약 회수 후 구현 (specs/001-auth-user/FE/plan.md §미결)',
  );
}

export function logout(): Promise<void> {
  throw new Error(
    'entities/auth/api.ts#logout: endpoint 경로 미확정 — BE 계약 회수 후 구현 (specs/001-auth-user/FE/plan.md §미결)',
  );
}
